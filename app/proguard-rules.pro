# XML-RPC
-keep class noline.zhouzhou.data.model.** { *; }

# SSH 终端：SSHJ 的算法工厂和 BouncyCastle 的 Provider 全靠反射按名字找，
# 混淆或者裁掉之后握手会莫名其妙失败（连不上、没有可用算法），这里整块保住。
-keep class net.schmizz.sshj.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn net.i2p.crypto.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**

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
