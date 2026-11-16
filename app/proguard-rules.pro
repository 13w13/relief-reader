# R8 rules for the release build (and the "minified" build that tests it).
# Most libraries ship their own rules (AndroidX, Room, WorkManager, OkHttp, Coil,
# kotlinx.serialization). Only what they leave out is here.

# Stack traces stay readable in bug reports: file names and line numbers are kept.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

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
