# gomoku-watch

圆屏 Wear OS 专业五子棋应用：双人对战 / 摆谱、Rapfi 引擎局面分析、人机对弈。
纯 Kotlin + Jetpack Compose for Wear OS，无 GMS 依赖，国内手表可直接安装使用。

## 功能

- **双人对战 / 摆谱**：同表轮流落子；菜单内可开启编辑模式，任意摆放黑白子摆出题面；支持悔棋/重做、连珠禁手校验（三三/四四/长连，本地判定，禁手点红叉提示）。
- **人机对战**：Rapfi 引擎（NNUE 评估）执黑或执白，每步用时可在 1–10 秒调节；支持悔棋（引擎同步回退）。
- **局面分析**：Rapfi 无限深度分析，实时显示胜率条、深度、节点数、nps、前 3 路变化与主变例着法序列；可“采纳走法”、表冠滚动悔棋/重做、翻转棋盘、从对局一键转入分析。
- **提示**：任意局面一键查询引擎推荐点（绿色脉冲标记）。
- **设置**：规则（无禁手/有禁手连珠）、引擎每步用时、音效、震动。

## 操作

- **点按**棋盘交叉点落子；落子点带最近交叉点吸附与距离容差。
- **表冠旋转**：缩放棋盘（Wear 标准旋转输入，替代双指捏合）——向前放大至局部精准落子，向后缩小至全局纵览棋形。
- **单指拖拽**：平移视角（松手即停）；棋盘四角附近不响应拖拽，避免误触。
- **复位**：视图缩放/平移后，棋盘右缘出现圆形复位钮，一键回到全局视图。
- **悬浮控件自动隐藏**：顶部状态胶囊与底部圆形按钮静置约 3 秒后淡出，点按屏幕、旋转表冠或落子即唤出；引擎思考与消息提示期间保持显示。
- 底部按钮（纯图标圆形，沿圆屏下边缘弧线吸附）：悔棋 / 重做 / 提示（对局）或 开始·停止分析（分析）/ 菜单。
- 实体返回键或滑动手势返回主菜单（对局中需确认）。

## 架构

```
app/src/main/java/com/qwara/gomoku/
├── game/       Board（棋盘/胜负）、RenjuRules（连珠禁手）
├── engine/     RapfiEngine（协议编排）、RapfiNative（JNI 桥）、EngineInstaller（权重安装）
├── data/       SettingsRepository
├── ui/         Wear Compose 界面（board 自绘 Canvas 棋盘、screens、theme）
└── MainViewModel.kt  状态与引擎编排
```

引擎编译为 JNI 共享库 `librapfi.so`（arm64-v8a / armeabi-v7a / x86_64），在 App 进程内
以独立线程运行 Piskvork/Yixin 协议循环，标准输入输出经 streambuf 重定向对接 Kotlin 层。
NNUE 权重与配置打包在 `app/src/main/assets/engine/common/`，首次使用时解压到私有目录
（Android SELinux 禁止 exec 应用数据目录下的独立可执行文件，因此采用进程内 JNI 方案）。
Rapfi 源码：`github.com/dhbloo/rapfi`（GPLv3）；引擎版本 0.43.02。

## 构建

```bash
./gradlew :app:assembleDebug      # 调试包
./gradlew :app:assembleRelease    # 发布包（已配置 R8 压缩）
./gradlew :app:testDebugUnitTest  # 规则单元测试
```

需要 JDK 17+ 与 Android SDK（compileSdk 37）。Gradle 发行包已通过
`mirrors.cloud.tencent.com` 镜像加速；如 Gradle 依赖下载缓慢，可在
`settings.gradle.kts` 的 repositories 头部追加阿里云镜像：

```kotlin
maven("https://maven.aliyun.com/repository/google")
maven("https://maven.aliyun.com/repository/central")
```

发布签名（可选）：把 keystore 属性写入 `~/.gomoku-watch/keystore.properties`
（`storeFile/storePassword/keyAlias/keyPassword` 四个键），`assembleRelease`
即输出已签名 APK；没有该文件时输出未签名包，不影响构建。

GitHub 访问不畅时，可用 `https://gh-proxy.com/https://github.com/...` 代理克隆
Rapfi 及其权重仓库（`dhbloo/rapfi-networks`）。

## 引擎再构建（可选）

`Rapfi-src/` 内含 JNI 封装（`Rapfi/jni/rapfi_jni.cpp`）与构建说明
（`Rapfi/jni/README.md`）。重新编译三 ABI 后，把 `librapfi.so` 放到
`app/src/main/jniLibs/<abi>/` 即可。
