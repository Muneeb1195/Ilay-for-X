# Ilay for X — R8 / ProGuard Release Rules

# Preserve source file names and line numbers for readable crash reports
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# Keep Moshi @JsonClass domain models and compile-time KSP-generated JsonAdapters
-keep class com.example.domain.model.** { *; }
-keepclassmembers class * extends com.squareup.moshi.JsonAdapter {
    public <init>(com.squareup.moshi.Moshi);
    public <init>(com.squareup.moshi.Moshi, java.lang.reflect.Type[]);
}
-keep class com.example.domain.model.*JsonAdapter { *; }

# Keep WorkManager CoroutineWorker / ListenableWorker subclasses instantiated via reflection
-keep class com.example.data.worker.** extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Keep Room database implementations and entities
-keep class * extends androidx.room.RoomDatabase
-keep class com.example.data.local.**_Impl { *; }
-keep class com.example.data.local.*Entity { *; }

# Silence benign warnings from optional JVM/OkHttp/Moshi annotations on Android
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.bouncycastle.**
