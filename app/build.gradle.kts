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

android {
    namespace = "com.qwara.gomoku"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.qwara.gomoku"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
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
    // 引擎可执行文件禁止压缩，确保可直接提取执行
    androidResources {
        noCompress += listOf("bin")
    }
    packaging {
        jniLibs {
            // librapfi.so 已按 AGENTS.md 配方用 llvm-strip --strip-debug 处理过；
            // 禁止 AGP 用各机器版本不一的 strip 再削一层，保证 APK 内与提交字节一致
            // （CI 的优化产物校验按字节数核对此文件）
            keepDebugSymbols += "**/librapfi.so"
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
