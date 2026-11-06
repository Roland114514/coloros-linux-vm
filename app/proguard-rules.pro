# JNI 桥接方法与原生接口保留
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.oppo.coloros.linux.core.NativeDisplayBridge { *; }
-keep class com.oppo.coloros.linux.bridge.** { *; }
-keep class com.oppo.coloros.linux.service.** { *; }
-keep class com.oppo.coloros.linux.ui.** { *; }
-keep class com.oppo.coloros.linux.provider.** { *; }
-keep class com.oppo.coloros.linux.display.** { *; }

# Kotlin 反射与协程保留
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn java.lang.invoke.**
