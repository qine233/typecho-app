# XML-RPC
-keep class com.typecho.app.data.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.typecho.app.**$$serializer { *; }
-keepclassmembers class com.typecho.app.** { *** Companion; }
-keepclasseswithmembers class com.typecho.app.** { kotlinx.serialization.KSerializer serializer(...); }
