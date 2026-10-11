# XML-RPC
-keep class noline.zhouzhou.data.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class noline.zhouzhou.**$$serializer { *; }
-keepclassmembers class noline.zhouzhou.** { *** Companion; }
-keepclasseswithmembers class noline.zhouzhou.** { kotlinx.serialization.KSerializer serializer(...); }
