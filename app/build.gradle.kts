// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 本地发布签名：keystore 属性放在 ~/.gomoku-watch/keystore.properties（不进仓库）；
// 文件不存在时 release 输出未签名包，不影响构建。
val releaseKeystoreProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".gomoku-watch/keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

// 版本号：默认值供本地构建用（设置页「关于」显示的就是 versionName）；发布路径上由 CI 从
// 发布 tag 推导后经 -PappVersionName/-PappVersionCode 覆盖，见 .github/workflows/build.yml。
// 不这么做的话 tag 与包内版本会各说各话，且 versionCode 永不增长。
val appVersionName = providers.gradleProperty("appVersionName").orNull ?: "1.0.0"
val appVersionCode = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull() ?: 1

android {
    namespace = "com.qwara.gomoku"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.qwara.gomoku"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseKeystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = File(releaseKeystoreProps.getProperty("storeFile"))
                storePassword = releaseKeystoreProps.getProperty("storePassword")
                keyAlias = releaseKeystoreProps.getProperty("keyAlias")
                keyPassword = releaseKeystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystoreProps.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    // 引擎数据文件已按 AGENTS.md 配方用 llvm-strip --strip-debug 处理过；
    // 禁止 AGP 用各机器版本不一的 strip 再削一层，保证 APK 内与提交字节一致
    // （CI 的优化产物校验按字节数核对此文件）
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libpachi.so"
        }
    }
    // 按产物细分 ABI：arm64-v8a / armeabi-v7a / x86_64 各出一个独立 APK，
    // 另出 universal 全量包。CI 会把四件套一起发布到 Release
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    sourceSets {
        getByName("main") {
            assets.srcDir("src/main/assets")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
    testImplementation("junit:junit:4.13.2")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
