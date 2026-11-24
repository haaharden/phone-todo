# Keep kotlinx.serialization models, enums, & generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.self.phonetodo.model.** { *; }

# Keep Room / WorkManager / Glance reflective constructors (critical for AGP 9 R8 full mode!)
-keep class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.work.impl.WorkDatabase_Impl {
    <init>(...);
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger {
    public <init>(...);
}
-keep class * extends androidx.startup.Initializer {
    public <init>(...);
}

# Keep Glance widget, BroadcastReceivers, and Services
-keep class com.self.phonetodo.widget.** { *; }
-keep class com.self.phonetodo.reminder.** { *; }
-keep class com.self.phonetodo.service.** { *; }
-keep class androidx.glance.** { *; }

# Suppress harmless OkHttp optional platform warnings
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
