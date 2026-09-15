# SPDX-FileCopyrightText: 2026 Qwara-chan
# SPDX-License-Identifier: GPL-3.0-or-later

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

# baseline-prof.txt 的通配规则在 R8 改名之后才按源码名展开，应用类混淆后无法匹配。
# 应用自有类保留原名（仅类/成员名，不阻止 R8 压缩与优化），换取 AOT profile 全覆盖
-keepnames class com.qwara.gomoku.** { *; }
