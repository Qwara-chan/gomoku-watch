# gomoku-watch（围棋分支 go-pachi）

圆屏 Wear OS 围棋应用：双人对战 / 摆谱、Pachi 引擎局面分析、人机对弈。
纯 Kotlin + Jetpack Compose for Wear OS，无 GMS 依赖，国内手表可直接安装使用。
9 / 13 / 19 路棋盘可选，中国规则数子法，贴目可调。

## 功能

- **双人对战 / 摆谱**：同表轮流落子（中国规则：提子、打劫、停一手）；菜单内可开启编辑模式，任意摆放黑白子摆出题面（编辑模式只摆放子，不判胜负）；支持悔棋/重做。
- **人机对战**：Pachi 引擎（蒙特卡洛树搜索）执黑或执白，每步用时 1–10 秒可调（也是棋力主杠杆；实际思考时间略短于名义值——pachi 对读秒保留约 15% 安全余量）；支持悔棋（引擎同步回退）；引擎思考期间状态胶囊下方显示实时读数（`胜率 · 首选点`），棋盘上同步标出 A/B/C 候选点；引擎停一手、认输都会正确处理（双停一手终局数子）。
- **局面分析**：Pachi 实时分析（lz-analyze），实时显示胜率条、最佳着法、模拟数、多点分析候选点（1/3/5 路，棋盘上以 A/B/C 徽章标注并列出各自胜率）与主变例着法序列；可“采纳走法”、表冠滚动悔棋/重做、翻转棋盘、从对局一键转入分析。
- **评估曲线与复盘**：对局中专用的「菜单 → 评估曲线」或分析页点顶部评估胶囊都能展开曲线面板，不离开棋局、不动局面；「全谱分析」逐手评估整局（每手等到引擎第一次上报就停，实测约 1 秒/手，可随时取消，分析期间棋盘跟随进度走子），得到黑方胜率走势；拖动曲线即可跳到任意手数的局面复盘（含当时的提子状态），点「回到当前」或直接点棋盘返回实战局面。
- **对局与分析的往返**：棋盘一直保留在内存里，导航不会清空它。从对局内「菜单 → 局面分析」或主菜单「局面分析」进入分析后，返回键都会**回到对局继续下**（局面、行棋方、人机模式全部保留）；棋局未结束时主菜单会多出「继续对局（第 N 手）」入口。
- **棋盘显示**：手数序号、最后一步光环、多点分析候选点徽章，均可在设置页按需开关；星位按路数自动调整（19 路 3/9/15 线等）。
- **提示**：任意局面一键查询引擎推荐点（绿色脉冲标记）——引擎按「分析路数」分析约 2 秒后自动停止，棋盘上同时给出 A/B/C 候选点徽章。
- **设置**：棋盘路数（9/13/19，改动会清盘）、贴目（0.5–9.5）、引擎每步用时、分析路数、显示项开关、音效、震动。
- **终局数子**：双方连续停一手即终局，先以本地中国规则数子立即显示结果，再用引擎 `final_score` 校正；引擎认为局面未定时回退本地结果。

## 操作

- **点按**棋盘交叉点落子；落子点带最近交叉点吸附与距离容差（放大后容差自动放宽）。
- **表冠旋转**：缩放棋盘（替代双指捏合）——向后放大至局部精准落子，向前缩小可将整盘收进圆屏（四个角也完整可见）。
- **双击已有棋子**：在同一交叉点上快速点两次即在该点放大/缩小（单手替代捏合）；空点上的连续两次点按按两次落子处理，点两处不同的棋子不会触发缩放。
- **单指拖拽**：平移视角（松手即停）。最小倍率下拖入会随距离渐进放大；放大后可把任意交叉点（含四个角）拖到屏幕中心，圆屏切角不再导致角部落点不可达；拖出棋盘范围时显示木色桌底而不是黑底。棋盘四角附近不响应拖拽，避免误触。
- **复位**：视图缩放/平移后，棋盘右缘出现圆形复位钮，一键回到全局视图。
- **悬浮控件自动隐藏**：顶部状态胶囊与底部圆形按钮静置约 3 秒后淡出，点按屏幕、旋转表冠或落子即唤出；引擎思考与消息提示期间保持显示。
- 底部按钮（纯图标圆形，沿圆屏下边缘弧线吸附）：悔棋 / 重做 / **停一手** / 提示（对局）或 开始·停止分析（分析）/ 菜单。
- 对局中看曲线：菜单 → 评估曲线（面板覆盖在棋盘上，背景会挡住误触，不会误落子）。
- 复盘浏览中，直接点棋盘也会回到实战局面（不会落子）。
- 实体返回键或滑动手势返回主菜单（对局中需确认）。

## 架构

```
app/src/main/java/com/qwara/go/
├── game/       GoBoard（围棋规则：提子/打劫/停一手/数子，纯 Kotlin 无 Android 依赖）
├── engine/     PachiEngine（GTP 协议编排）、PachiNative（JNI 桥）、EngineInstaller（数据文件安装）
├── data/       SettingsRepository
├── ui/         Wear Compose 界面（board 自绘 Canvas 棋盘、screens、theme）
└── MainViewModel.kt  状态与引擎编排
```

引擎编译为 JNI 共享库 `libpachi.so`（arm64-v8a / armeabi-v7a / x86_64），在 App 进程内
以独立线程运行 GTP 协议循环，标准输入输出经 dup2 管道重定向对接 Kotlin 层
（Android SELinux 禁止 exec 应用数据目录下的独立可执行文件，因此采用进程内 JNI 方案）。
模式库与开局库打包在 `app/src/main/assets/engine/common/`，首次使用时解压到私有目录作引擎工作目录；
缺失时引擎自动降级为纯蒙特卡洛。
Pachi 源码：`github.com/pasky/pachi`（GPL-2.0-or-later）；引擎版本 12.84。

围棋规则在本地 `GoBoard` 完成：无气提子、自杀禁着、单劫禁着（与上一手全局同形判禁）、
双方连续停一手终局、中国规则数子（子空皆地 + 贴目）。悔棋/复盘采用清空重放，
提子状态与哈希历史自然重建。

## 构建

```bash
./gradlew :app:assembleDebug      # 调试包
./gradlew :app:assembleRelease    # 发布包（已配置 R8 压缩）
./gradlew :app:testDebugUnitTest  # 规则/几何单元测试

# 想按某个发布版本号出包（CI 就是这么做的）：
./gradlew :app:assembleRelease -PappVersionName=1.2.3 -PappVersionCode=10203
```

需要 JDK 17+ 与 Android SDK（compileSdk 37）。SDK 路径请写入 `local.properties`
（`sdk.dir=/path/to/Android/Sdk`）或设置环境变量 `ANDROID_HOME`，否则 Gradle 会报
“SDK location not found”。Gradle 发行包已通过
`mirrors.cloud.tencent.com` 镜像加速。

发布签名（可选）：把 keystore 属性写入 `~/.gomoku-watch/keystore.properties`
（`storeFile/storePassword/keyAlias/keyPassword` 四个键），`assembleRelease`
即输出已签名 APK；没有该文件时输出未签名包，不影响构建。

GitHub 访问不畅时，可用 `https://gh-proxy.com/https://github.com/...` 代理克隆
Pachi 仓库。

发布包体积：单 ABI 约 2.4MB / universal 约 3.3MB（含模式库与开局库；不再有 NNUE 权重的 30MB，debug 包约 15MB 仅供真机调试）。

## 自动构建（GitHub Actions）

`.github/workflows/build.yml` 在 push 到 `main`、PR、手动触发（workflow_dispatch）以及打 `v*` 标签时运行：
跑单元测试 → 构建 debug 与 release 两个 APK → 作为构建产物（Artifacts）上传。发布三条路径：

- **打 `v*` 标签**推送：自动创建正式 GitHub Release 并把 APK 附上去（发布说明自动生成）；
- **手动触发并填 `tag`**（Actions → build → Run workflow，输入形如 `v1.0.1`）：在本次提交上建标签、
  发正式 Release，效果与推标签一致，不用先打标签；同名 Release 已存在时改为替换其中的 APK；
- **手动触发且留空 `tag`**：只发布滚动预发布 tag `ci`（每次整体替换）。

**版本号由 tag 决定**：发布时 CI 把 tag 换算成应用版本号传给 Gradle（`v1.2.3` → versionName `1.2.3`、
versionCode `10203`，minor/patch 需 ≤ 99）。发布路径上 CI 用 `aapt2` 把版本从 APK 清单里读回来校验。

CI 还做两项防退化校验：release 包必须合并应用自有的 baseline profile 规则（≥1000 条）；
两个 ABI 的 `libpachi.so` 字节数必须与仓库提交一致（防止用未验证的引擎构建覆盖）。

## 引擎再构建（可选）

JNI 封装（零上游补丁）、桌面校验器与两个 ABI 的构建脚本都在仓库内的 `engine-src/`
（本地克隆 `pachi-src/` 未入库），构建步骤与已实测锁定的协议格式见 `engine-src/README.md`。
重新编译各 ABI 后，把 `libpachi.so` 放到 `app/src/main/jniLibs/<abi>/` 即可；
产物体积若有变化，记得同步更新 CI 防退化校验里的预期字节数。

## 许可与致谢

本应用以 **GPL-3.0** 发布（全文见 `LICENSE`），© 2026 Qwara-chan。打包的 Pachi 引擎为
GPL-2.0-or-later（与 GPL-3.0 兼容），其全文与第三方声明在 `app/src/main/assets/licenses/`。

- **Pachi**（围棋引擎）：<https://github.com/pasky/pachi>，GPL-2.0-or-later，作者 Petr "Pasky" Baudiš
  （见其 `CREDITS`）。本仓库打包的是它的 JNI 共享库产物（纯蒙特卡洛构建），
  **对应源码与构建配方在 `engine-src/`**，上游基线提交 `617986f4`。
- **Pachi 数据文件**（`app/src/main/assets/engine/common/`）：`patterns_mm.*` 随 pachi 源码仓库分发；
  `opening.dat` 来自 pachi 官方发布（GPL-2.0-or-later）。
- **AndroidX / Jetpack Compose for Wear OS / Material 图标**：Apache-2.0；**Gradle wrapper**：Apache-2.0。

APK 内也附了一份 GPL-3.0/GPL-2.0 全文与第三方声明；
应用内「设置 → 关于」直接列出本仓库与引擎仓库地址。
