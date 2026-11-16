# R8 rules for the release build (and the "minified" build that tests it).
# Most libraries ship their own rules (AndroidX, Room, WorkManager, OkHttp, Coil,
# kotlinx.serialization). Only what they leave out is here.

# Stack traces: line numbers are kept, source file names become "SourceFile", and classes and
# methods are renamed. The mapping.txt of each build turns a trace back (retrace): CI keeps it as an
# artifact of the minified build, and release.yml attaches it to each release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# WorkManager stores scheduled work by the worker's class name. A worker renamed by R8 could not be
# found after an update from a version built without R8 (0.6.x): SyncWorker and DigestWorker do
# not schedule themselves again. Their names and constructors stay; CI checks the mapping.
-keep class io.relais.app.work.** extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Exception names are shown on screen as a diagnosis (e::class.simpleName: "Network error:
# SocketTimeoutException", a failed organization search): renamed, they would read "a".
-keepnames class * extends java.lang.Throwable

# Navigation routes are @Serializable objects and classes, found by their serializers.
-keep @kotlinx.serialization.Serializable class io.relais.** { *; }

# PdfBox-Android reads fonts, encodings and filters by name at run time: kept whole.
-keep class com.tom_roush.** { *; }
# JPEG 2000 support is an optional add-on that Re.L does not include, and Bouncy Castle is
# left out on purpose (app/build.gradle.kts: only for PDFs encrypted with a certificate).
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

# Optional dependencies of Ktor and jsoup that are not on Android or not used.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn com.google.re2j.**
