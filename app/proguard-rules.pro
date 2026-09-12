# Keep NativeBridge class and native methods for JNI
-keep class com.pro.injector.NativeBridge { *; }
-keepclassmembers class * {
    native <methods>;
}
