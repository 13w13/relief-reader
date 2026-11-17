// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

// The ReliefWeb appname: from the RELIEFWEB_APPNAME environment variable (a GitHub
// Actions secret in CI), else from local.properties. Never committed, never printed.
// Empty is allowed: the app then reads the RSS feed of headlines. Debug builds only: the release
// build type sets it empty (v0.3.1).
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val reliefwebAppname: String = providers.environmentVariable("RELIEFWEB_APPNAME").orNull
    ?.takeIf { it.isNotBlank() }
    ?: localProperties.getProperty("RELIEFWEB_APPNAME", "")

// Links of Settings > About (T19): the public repository (13w13/relief-reader, founder's
// decision of 13/11), its issues, and the feedback form, empty until the founder creates it.
// An environment variable or local.properties can override each; an empty link hides its entry.
fun buildLink(name: String, default: String = ""): String = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
    ?: localProperties.getProperty(name, default)

fun String.asJavaString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// "A new version is available" (founder, 15/11): once a day the app asks GitHub for the latest
// release. On by default. The Gradle property updateCheck=false or the REL_UPDATE_CHECK=false
// environment variable removes it: then no request, no notice and no switch in Settings. The
// F-Droid recipe does not use it (16/11): F-Droid checks that its build matches the signed
// GitHub APK byte for byte, so both must be built the same way.
val updateCheck: Boolean = (providers.gradleProperty("updateCheck").orNull
    ?: providers.environmentVariable("REL_UPDATE_CHECK").orNull
    ?: "true").trim().lowercase() != "false"

// Release key: decoded by release.yml from the RELEASE_* secrets when they exist.
// Without them, release builds are left unsigned (F-Droid signs its own).
val releaseKeystore: String? = providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "io.relais.app"
    compileSdk = 36

    defaultConfig {
        // Re.L (8/11): a new applicationId, so it installs as a new app; the namespace (R, Kotlin
        // packages) stays io.relais.app for now.
        applicationId = "org.relreader.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "0.7.0"
        // The day this version is published, written by hand with versionName (yyyy-MM-dd; see
        // RELEASING.md). Never the build time: the build stays reproducible for F-Droid. 60 days
        // later, Today suggests checking for a newer one (UpdateCheck.Aging). ci.yml checks it is
        // a date; release.yml also checks it is within 7 days of the tag.
        val releaseDate = "2026-11-17"
        buildConfigField("String", "RELEASE_DATE", "\"$releaseDate\"")
        buildConfigField("String", "RELIEFWEB_APPNAME", reliefwebAppname.trim().asJavaString())
        buildConfigField("String", "SOURCE_URL", buildLink("REL_SOURCE_URL", "https://github.com/13w13/relief-reader").trim().asJavaString())
        buildConfigField("String", "FEEDBACK_URL", buildLink("REL_FEEDBACK_URL").trim().asJavaString())
        buildConfigField("boolean", "UPDATE_CHECK", updateCheck.toString())
        buildConfigField("String", "ISSUES_URL", buildLink("REL_ISSUES_URL", "https://github.com/13w13/relief-reader/issues").trim().asJavaString())
        // Instrumented tests: the PDF text benchmark (pdf-bench.yml).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A shared debug key, committed on purpose (standard Android debug passwords,
        // not a secret): successive CI builds then install over each other.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            // R8 removes the code and resources the app never uses: the APK was 19 MB without it
            // (F-Droid code quality report, 16/11). The rules for the libraries that need them
            // are in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Since v0.3.1 no release APK carries an appname, whatever the environment: each
            // reader enters their own. Only debug builds (the journeys) read RELIEFWEB_APPNAME.
            buildConfigField("String", "RELIEFWEB_APPNAME", "\"\"")
            // Without the release key, an unsigned APK: F-Droid builds Re.L and signs it with its
            // own key. release.yml requires the key, so no published APK is ever unsigned.
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        // The release build, R8 included, signed with the shared debug key and installed beside
        // Re.L (org.relreader.app.minified): a code R8 removed by mistake crashes only in the
        // release APK, which the tests do not run. CI builds it on every run to try on a phone.
        create("minified") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".minified"
            versionNameSuffix = "-minified"
            matchingFallbacks += listOf("release")
            // A test build: no daily request to GitHub for its "-minified" version. Its appname is
            // empty, as in release (initWith); CI checks both in the generated BuildConfig.
            buildConfigField("boolean", "UPDATE_CHECK", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // No encrypted list of dependencies in the APK: F-Droid's scanner refuses it, and the
    // libraries are listed in THIRD_PARTY_NOTICES anyway.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // The Room schemas as assets of the debug build, for MigrationTestHelper in the
    // Robolectric tests (recordRoborazziDebug runs them on the debug variant).
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES",
                "/META-INF/versions/9/previous-compilation-data.bin",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.generateKotlin", "true")
    // One JSON file per schema version, committed: the migration tests read them and
    // CI fails when the generated schema differs from the committed one.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.ktor.client.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Text of the PDFs, read on the phone. Bouncy Castle is only needed for PDFs
    // encrypted with a certificate; leaving it out saves about 8 MB.
    implementation(libs.pdfbox.android) {
        exclude(group = "org.bouncycastle")
    }

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
