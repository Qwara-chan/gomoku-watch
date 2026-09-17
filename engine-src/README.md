# 引擎源码（对应 `libpachi.so`）

App 打包的 `app/src/main/jniLibs/<abi>/libpachi.so` 是 **Pachi** 围棋引擎（GTP 协议）的 JNI 共享库构建产物。
Pachi 以 **GPL-2.0-or-later** 发布（与仓库的 GPL-3.0 兼容），因此本目录把「二进制对应的源码」一并放在仓库里：

| 路径 | 内容 |
|---|---|
| `pachi_jni.c` | JNI 封装：把 pachi 的 GTP 协议循环跑在 App 进程内（Android SELinux 不允许 execve 应用私有目录下的独立可执行文件，所以必须做成进程内共享库）。它是本项目的自有代码，`PachiNative.kt` 按名字绑定其中的 `Java_..._PachiNative_start/write/readLine/isRunning/shutdown/dupStdout` 入口 |
| `build-host.sh` | 桌面 x86-64 `libpachi.so` 构建配方（配合 `host/` 校验器使用） |
| `build-android.sh` | Android 三 ABI（arm64-v8a / armeabi-v7a / x86_64）交叉编译配方 |
| `host/` | 桌面 JVM 校验器：`PachiNative` 的同契约副本 + 驱动（`SmokeTest` 冒烟 8 场景、`Repro` 单步调试）。不含 Android 依赖 |

## 上游版本

- 仓库：<https://github.com/pasky/pachi>（GPL-2.0-or-later，作者 Petr "Pasky" Baudiš，见其 `CREDITS`）
- 基线提交：`617986f4570d56174cf3759392e02d7af9f58fa6`
- **零补丁**：JNI 桥完全外置（`pachi_jni.c` 只调用 pachi 公开头文件 API），上游树不做任何修改

```bash
git clone https://github.com/pasky/pachi engine-src/pachi-src
cd engine-src/pachi-src && git checkout 617986f4570d56174cf3759392e02d7af9f58fa6
```

GitHub 连不上时可以用加速镜像：`https://gh-proxy.com/https://github.com/pasky/pachi.git`。

## 构建形态

纯蒙特卡洛构建（无 DCNN/Caffe/Boost、无 KataGo josekifix）：

```
make DCNN=0 JOSEKIFIX=0 GENERIC=1 XCFLAGS="-fPIC"
```

- `DCNN=0 JOSEKIFIX=0`：默认 Makefile 会拉 Caffe + KataGo，手表跑不动也不需要；
  注意 JOSEKIFIX 默认值是 1，不显式关掉会触发 katago 构建。
- `GENERIC=1`：不带 `-march=native`（交叉编译必需）。
- JNI 桥侧固定 `threads=1,tree_size=16,max_tree_size=64,max_mem=96`（MiB）——
  单搜索线程，树内存 16MB 起步、64MB 封顶、含临时树全局 96MB，按手表内存预算调。
- joseki 模块在桥里显式 `disable_joseki()`：无 DCNN 的 UCT 引擎在 `joseki19.gtp`
  缺失时会直接 `die()`，禁用最省事（该库只对 DCNN 构建有意义）。

### 宿主机构建（先跑校验器，再编 Android）

```bash
cd engine-src
./build-host.sh        # 产物 build-host/libpachi.so
javac -d /tmp/classes host/com/qwara/go/engine/*.java
java -Djava.library.path=build-host -cp /tmp/classes \
  com.qwara.go.engine.SmokeTest "$(pwd)/pachi-src"
```

`workDir` 指到 pachi 源码树（`patterns_mm.*`/`joseki19.gtp` 在库里，引擎按工作目录查找；
`opening.dat` 开局库在 GitHub release，另见下）。

### Android（以 NDK r30 为例）

```bash
cd engine-src
NDK=~/Android/Sdk/ndk/30.0.16248370 ./build-android.sh
# 产物 build-android/<abi>/libpachi.so，拷到 app/src/main/jniLibs/<abi>/
```

- pachi 的 Makefile 在源码树内产出 `.o`，所以每个 ABI 复制一份私有树再编；
  复制后会删除 `*.o`/`lib.a`/`pachi`/`build.h*` 防止把宿主产物当年新目标跳过编译。
- bionic 没有 `librt`：链接期用 `LIBS="-lm -ldl"` 覆盖（脚本已处理）。
- **剥符号只能用 `llvm-strip --strip-debug`**：`--strip-all` 会连 JNI 入口一起剥掉，
  运行期按名字绑定就会失败。
- 产物字节数（CI 防退化校验按此断言）：arm64-v8a = `467584`，armeabi-v7a = `436916`，
  x86_64 = `442936`。换成自己编的版本时同步更新 `.github/workflows/build.yml` 里的数字。

## 数据文件

`app/src/main/assets/engine/common/`：

| 文件 | 来源 | 作用 |
|---|---|---|
| `patterns_mm.gamma` / `patterns_mm.spat` | pachi 源码仓库内置（~1MB） | 走子模式库，指导 UCT 前中盘选点；缺失时引擎告警并降级纯蒙特卡洛 |
| `opening.dat` | <https://github.com/pasky/pachi/releases/download/pachi-10.00-satsugen/opening.dat.zip>（1.7MB） | 开局库，开局更强更多变；`pachi_jni.c` 检测到才启用 |

`EngineInstaller` 把它们解压到 `filesDir/engine` 作会话 workDir（pachi 按 cwd 查找数据文件）。
`joseki19.gtp` **不需要**（joseki 已禁用）。

## GTP 协议要点（App 依赖的行为，全部经 host 校验器实测）

- 会话：`boardsize <n>` / `komi <k>` / `clear_board`；改局面只能 `clear_board` + `play` 重放
  （无整盘设置命令；App 每次应着都整盘重摆，引擎盘面不会漂移）。
- 每步时限：`kgs-time_settings byoyomi 0 <sec> 1`（0 主时 + sec 秒 1 期读秒）。
- 应着：`genmove <color>`（应答 `= D16`，引擎已自落该子）；要看流式候选用
  `lz-genmove_analyze <color> <freq百分秒>`。
- 分析：`lz-analyze <color> <freq>`，停止发 `lz-analyze <color> 0`（或任意其它命令也会停）。
- **实测输出行格式**（`lz-analyze` / `lz-genmove_analyze`，stdout）：
  `info move <coord> visits <n> winrate <千分率> prior <n> order <k> pv <coords...>`
  —— **一行可含多段** `info move`（空格分隔）；winrate 是行棋方视角千分值
  （4849 = 48.49%）。`lz-genmove_analyze` 收尾再吐一行 `play <coord>` 作为应答着。
- `pachi-result` → `= <color> <coord> <playouts> <winrate> <dynkomi>`（genmove 后查询）。
- `undo` 会自动重建引擎局面（等价 TAKEBACK）；`final_score` 在引擎认为局面未定
  （如空盘双 pass）时回 `? too early to pass`——App 端要兜底本地数子。
- `play <color> pass` 在开局前若干手会被 `? too early to pass` 拒绝（fuseki 保护）。
- **绝不发送 `quit`**（其处理器 exit(0) 会杀掉整个 App 进程）；结束会话 =
  关闭 stdin（EOF 使 GTP 循环自然退出，引擎清理后线程结束，见 `shutdown()`）。
- **stdio 重定向的坑**（血泪）：引擎 printf 走 fd 1/2，它们是管道写端的 dup2 副本；
  会话销毁必须先恢复进程 stdio 再 join demux 线程，否则 demux 永远等不到 EOF。

## 许可

本目录下的文件（`pachi_jni.c`、`build-*.sh`、`host/`）属于本项目，按 **GPL-3.0** 发布；
Pachi 本身的版权归其作者所有（见上游 `CREDITS`），以 GPL-2.0-or-later 授权。
