# JNA and the native tray call into Java classes by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class dev.nucleusframework.** { *; }
-dontwarn com.sun.jna.**
-dontwarn dev.nucleusframework.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class app.woom.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
-keep class app.woom.**$$serializer { *; }
# OkHttp / Coil optional dependencies
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn android.**
-dontwarn coil3.**
-dontwarn kotlinx.coroutines.**
-dontnote **
-dontwarn okhttp3.internal.graal.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**
# Coil finds its network fetcher through ServiceLoader.
-keep class coil3.** { *; }
