# JNI native 方法按名绑定，禁止混淆
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.qwara.gomoku.engine.RapfiNative { *; }

# Compose
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}
-dontwarn org.jetbrains.annotations.**
