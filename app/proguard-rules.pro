# Keep kotlinx.serialization models & generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.self.phonetodo.model.**$$serializer { *; }
-keepclassmembers class com.self.phonetodo.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.self.phonetodo.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.self.phonetodo.data.GoogleOAuthTokens** { *; }

# Keep Glance widget & BroadcastReceivers
-keep class com.self.phonetodo.widget.** { *; }
-keep class com.self.phonetodo.reminder.** { *; }
-keep class com.self.phonetodo.service.** { *; }

# Suppress harmless OkHttp optional platform warnings
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
