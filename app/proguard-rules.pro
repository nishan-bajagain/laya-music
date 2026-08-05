### Rules for NewPipeExtractor
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.*
-dontwarn javax.script.*
-dontwarn jdk.dynalink.**
-dontwarn com.google.re2j.Matcher
-dontwarn com.google.re2j.Pattern

### kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ca.ilianokokoro.umihi.music.**$$serializer { *; }
-keepclassmembers class ca.ilianokokoro.umihi.music.** {
    *** Companion;
}
-keepclasseswithmembers class ca.ilianokokoro.umihi.music.** {
    kotlinx.serialization.KSerializer serializer(...);
}

### kotlinx.coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

### OkHttp (used by UmihiHttpClient / Coil / NewPipe)
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

### Coil image loading
-dontwarn coil3.**

### WorkManager
-keep class androidx.work.** { *; }
-keepclassmembers class * extends androidx.work.Worker { *; }
-keepclassmembers class * extends androidx.work.CoroutineWorker { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

### Room
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.**

### Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

### Custom Activity on Crash
-keep class cat.ereza.customactivityoncrash.** { *; }

### Palette
-keep class androidx.palette.** { *; }

### Kotlin UUID (experimental API)
-dontwarn kotlin.uuid.**
-keep class kotlin.uuid.** { *; }

### Kotlin reflect / metadata (belt-and-suspenders for serialization)
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keepattributes Signature, Exceptions

### General — suppress known-safe missing-class warnings from JDK stubs
-dontwarn java.lang.management.**
-dontwarn sun.misc.Signal
