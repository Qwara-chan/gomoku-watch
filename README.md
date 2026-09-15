# gomoku-watch

圆屏 Wear OS 专业五子棋应用：双人对战 / 摆谱、Rapfi 引擎局面分析、人机对弈。
纯 Kotlin + Jetpack Compose for Wear OS，无 GMS 依赖，国内手表可直接安装使用。

## 功能

- **双人对战 / 摆谱**：同表轮流落子；菜单内可开启编辑模式，任意摆放黑白子摆出题面（编辑模式只摆放子，不判胜负、不拦禁手，便于研究禁手形状）；支持悔棋/重做、连珠禁手校验（三三/四四/长连，本地判定，禁手点按类型标记）。
- **人机对战**：Rapfi 引擎（NNUE 评估）执黑或执白，每步用时可在 1–10 秒调节，棋力可分 5 档（入门/初级/中级/高级/最强，低档降低搜索深度并有意走出次优着法）；支持悔棋（引擎同步回退）；引擎思考期间状态胶囊下方显示实时读数（`深度 · 胜率/杀 · 首选点`），棋盘上同步标出 A/B/C 候选点。
- **局面分析**：Rapfi 无限深度分析，实时显示胜率条、最佳着法、深度/选择深度、节点数、nps、用时、多点分析候选点（1/3/5 路，棋盘上以 A/B/C 徽章标注并列出各自胜率）与主变例着法序列；可“采纳走法”、表冠滚动悔棋/重做、翻转棋盘、从对局一键转入分析。
- **评估曲线与复盘**：对局中专用的「菜单 → 评估曲线」或分析页点顶部评估胶囊都能展开曲线面板，不离开棋局、不动局面；「全谱分析」逐手评估整局（约 0.6 秒/手，可随时取消，分析期间棋盘跟随进度走子），得到黑方胜率走势；拖动曲线即可跳到任意手数的局面复盘，点「回到当前」或直接点棋盘返回实战局面。
- **对局与分析的往返**：棋盘一直保留在内存里，导航不会清空它。从对局内「菜单 → 局面分析」或主菜单「局面分析」进入分析后，返回键都会**回到对局继续下**（局面、行棋方、人机模式全部保留）；棋局未结束时主菜单会多出「继续对局（第 N 手）」入口。人机对战中做过扫描也会自动把引擎内部盘面同步回实战局面。
- **棋盘显示**：手数序号、最后一步光环、胜利连线（终局遮罩减淡并可「查看棋盘」）、连珠禁手点（三三/四四/长连分色字形）、多点分析候选点徽章，均可在设置页按需开关。
- **提示**：任意局面一键查询引擎推荐点（绿色脉冲标记），并按「分析路数」一并给出候选点（棋盘上 A/B/C 徽章）。
- **设置**：规则（无禁手/有禁手连珠）、引擎每步用时、引擎棋力、分析路数（对局应着与提示同样按此路数搜索，1 路时棋力最强）、显示项开关、音效、震动。

## 操作

- **点按**棋盘交叉点落子；落子点带最近交叉点吸附与距离容差。
- **表冠旋转**：缩放棋盘（替代双指捏合）——向前放大至局部精准落子，向后缩到最小可将 15x15 整盘收进圆屏（四个角也完整可见）。
- **双击已有棋子**：在该点放大/缩小（单手替代捏合）；空点上的连续两次点按按两次落子处理，不会触发缩放。
- **单指拖拽**：平移视角（松手即停）。最小倍率下拖入会随距离渐进放大；放大后可把任意交叉点（含四个角）拖到屏幕中心，圆屏切角不再导致角部落点不可达；拖出棋盘范围时显示木色桌底而不是黑底。棋盘四角附近不响应拖拽，避免误触。
- **复位**：视图缩放/平移后，棋盘右缘出现圆形复位钮，一键回到全局视图。
- **悬浮控件自动隐藏**：顶部状态胶囊与底部圆形按钮静置约 3 秒后淡出，点按屏幕、旋转表冠或落子即唤出；引擎思考与消息提示期间保持显示。
- 底部按钮（纯图标圆形，沿圆屏下边缘弧线吸附）：悔棋 / 重做 / 提示（对局）或 开始·停止分析（分析）/ 菜单。
- 对局中看曲线：菜单 → 评估曲线（面板覆盖在棋盘上，背景会挡住误触，不会误落子）；面板内 ⟳ 开始或取消全谱分析，拖动曲线复盘，收起即回到对局。
- 分析页顶部评估胶囊：点按展开/收起评估曲线面板；面板内 ⟳ 开始或取消全谱分析，拖动曲线复盘，→ 回到当前局面。
- 退出分析：返回键/左上返回钮 → 若内存里还有一盘棋就回到对局继续下，否则回主菜单；对局中返回键会先确认是否离开。
- 主菜单：棋局未结束时顶部有「继续对局」（副标题显示第几手），随时回到那盘棋接着下。
- 复盘浏览中，直接点棋盘也会回到实战局面（不会落子）。
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

禁手判定在本地 `RenjuRules` 完成（未用引擎的 `YXSHOWFORBID`），按定义而非形状模式实现：
“四” = 再落一子恰好成五（含跳四 XX_XX / X_XXX / XXX_X），“活三” = 再落一子能成活四，
并排除“补子后变成长连”的假活四。已知边界：若某个“三”唯一能成活四的补子点本身是黑棋
禁手点，本实现仍会计入活三（引擎内部会递归排除这类伪三）；出现频率极低，如需完全对齐
可改为查询引擎的 `YXSHOWFORBID`。

## 构建

```bash
./gradlew :app:assembleDebug      # 调试包
./gradlew :app:assembleRelease    # 发布包（已配置 R8 压缩）
./gradlew :app:testDebugUnitTest  # 规则单元测试
```

需要 JDK 17+ 与 Android SDK（compileSdk 37）。SDK 路径请写入 `local.properties`
（`sdk.dir=/path/to/Android/Sdk`）或设置环境变量 `ANDROID_HOME`，否则 Gradle 会报
“SDK location not found”。Gradle 发行包已通过
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

发布包体积：debug 约 48MB、release（R8 压缩后）约 36MB。

## 自动构建（GitHub Actions）

`.github/workflows/build.yml` 在 push 到 `main`、PR、手动触发（workflow_dispatch）以及打 `v*` 标签时运行：
跑单元测试 → 构建 debug 与 release 两个 APK → 作为构建产物（Artifacts）上传。发布三条路径：

- **打 `v*` 标签**推送：自动创建正式 GitHub Release 并把 APK 附上去（发布说明自动生成）；
- **手动触发并填 `tag`**（Actions → build → Run workflow，输入形如 `v1.0.1`）：在本次提交上建标签、
  发正式 Release，效果与推标签一致，不用先打标签；同名 Release 已存在时改为替换其中的 APK；
- **手动触发且留空 `tag`**：只发布滚动预发布 tag `ci`（每次整体替换），适合装了看最新开发版。

**可选签名**：在仓库 Secrets 里配置下面 4 项后，CI 出的 release 包会用你的 keystore 签名（可直接安装）：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 ~/.gomoku-watch/gomoku-watch.jks` 的输出 |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEYSTORE_KEY_ALIAS` | key 别名 |
| `KEYSTORE_KEY_PASSWORD` | key 口令 |

未配置时 release 输出 `app-release-unsigned.apk`（构建仍通过，但装不了），debug 包始终可直接安装。
CI 用 JDK 21 与 `platforms;android-37.0`（新版版本号，Android 17）；仓库里的 wrapper 指向腾讯镜像，
CI 步骤里会临时改成官方源。

## 引擎再构建（可选）

JNI 封装、两处上游补丁与桌面校验器都在仓库内的 `engine-src/`（本地克隆 `Rapfi-src/` 未入库），
构建步骤见 `engine-src/README.md`。重新编译各 ABI 后，把 `librapfi.so` 放到
`app/src/main/jniLibs/<abi>/` 即可；产物体积若有变化，记得同步更新 CI 防退化校验里的预期字节数。

## 许可与致谢

本应用以 **GPL-3.0** 发布（全文见 `LICENSE`）。之所以是 GPL-3.0：打包的 Rapfi 引擎本身是
GPL-3.0，链接它的分发必须采用同一许可。

- **Rapfi**（五子棋/连珠引擎）：<https://github.com/dhbloo/rapfi>，GPL-3.0，作者见其 `AUTHORS`。
  本仓库打包的是它的 JNI 共享库产物，**对应源码在 `engine-src/`**（JNI 封装 + 两处补丁 + 构建说明），
  上游基线提交 `3c94c2a9`。
- **NNUE 权重**（`app/src/main/assets/engine/common/*.bin*`）：来自
  <https://github.com/dhbloo/rapfi-networks>，CC0-1.0，可自由分发。
- **AndroidX / Jetpack Compose for Wear OS / Material 图标**：Apache-2.0；**Gradle wrapper**：Apache-2.0。

APK 内也附了一份 GPL-3.0 全文与第三方声明（`app/src/main/assets/licenses/`）；
应用内「设置 → 关于」直接列出本仓库与引擎仓库地址。
