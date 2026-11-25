# Umefor Library ProGuard / R8 Keep Rules

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.lightknight.omniread.**$$serializer { *; }
-keepclassmembers class io.lightknight.omniread.** {
    *** Companion;
}

# Room & SQLite
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# QuickJS JNI
-keep class com.dokar.quickjs.** { *; }
-keepclassmembers class com.dokar.quickjs.** { *; }

# youtubedl-android & FFmpeg
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
-dontwarn com.yausername.**

# PdfBox Android & Junrar
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.bouncycastle.**
-dontwarn com.github.junrar.**

# OkHttp / Jsoup / Google Play Services
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-keep class com.google.android.gms.** { *; }
