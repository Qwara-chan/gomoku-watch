# 引擎源码（对应 `librapfi.so`）

App 打包的 `app/src/main/jniLibs/<abi>/librapfi.so` 是 Rapfi 引擎的 JNI 共享库构建产物。
Rapfi 以 **GPL-3.0** 发布，因此本目录把「二进制对应的源码」一并放在仓库里：

| 路径 | 内容 |
|---|---|
| `rapfi_jni.cpp` | JNI 封装：把引擎的 piskvork 协议循环跑在 App 进程内（Android SELinux 不允许 execve 应用私有目录下的独立可执行文件，所以必须做成进程内共享库）。它是本项目的自有代码，`RapfiNative.kt` 按名字绑定其中的 `Java_..._RapfiNative_write/readLine/...` 入口 |
| `patches/0001-cmake-jni-target-and-armv7-neon.patch` | 给上游 CMake 加 `BUILD_JNI`（额外产出 JNI 共享库）与 `USE_NEON_ARMV7_COMPAT`（32 位 ARM 用纯 `armv7-a+neon` 旗标，避免三元组变成 `thumbv8a` 而与预编译的 `liblz4.a` 冲突） |
| `patches/0002-vec-armv7-neon-compat.patch` | `eval/simd/vec.h`：为 32 位 ARM 补齐 AArch64 专有的 NEON 内在函数（`vuzp1q/vuzp2q`、`vmull_high_*`、`vqmovn_high_*`、`vpaddq_*`、`vpadalq_*`、`vaddvq_*`），并给 `vfmaq_f32` 回退加 `__ARM_FEATURE_FMA` 守卫。上游只有 AArch64 版本的 NEON 内核，不打这个补丁 v7a 就退回纯标量（原版预编译包里零 NEON 指令） |
| `host/` | 桌面 JVM 校验器：`RapfiNative` 的同契约副本 + 若干驱动（`SmokeTest` 冒烟、`AppFlowTest` 复刻 App 流程、`HintFlowTest` 复刻提示/应着流程、`ScanProtocolTest` 全谱扫描、`EdgeTest`/`RaceTest`/`LeakProbe`）。不含 Android 依赖 |

## 上游版本

- 仓库：<https://github.com/dhbloo/rapfi>（GPL-3.0，作者见其 `AUTHORS`）
- 基线提交：`3c94c2a976f24a0dd1c5517623e9ab6fffe66bd7`（引擎自报版本 0.43.x）
- 两个补丁都是相对该提交的 `git diff`，在克隆根目录 `git apply -p1` 即可

```bash
git clone https://github.com/dhbloo/rapfi && cd rapfi
git checkout 3c94c2a976f24a0dd1c5517623e9ab6fffe66bd7
git apply -p1 /path/to/gomoku-watch/engine-src/patches/0001-cmake-jni-target-and-armv7-neon.patch
git apply -p1 /path/to/gomoku-watch/engine-src/patches/0002-vec-armv7-neon-compat.patch
cp /path/to/gomoku-watch/engine-src/rapfi_jni.cpp Rapfi/jni/rapfi_jni.cpp
```

NNUE 权重不在本目录：它们来自 <https://github.com/dhbloo/rapfi-networks>（**CC0-1.0**，可自由分发），
放在 `app/src/main/assets/engine/common/`。

## 构建

`Rapfi/` 是 CMake 源目录。宿主机构建（配合 `host/` 校验器用）：

```bash
cmake -S Rapfi -B build/jni-host -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_JNI=ON -DNO_COMMAND_MODULES=ON \
  -DJNI_INCLUDE_DIR="$(dirname "$(find /usr/lib/jvm -name jni.h | head -1)")"
cmake --build build/jni-host
```

Android（以 NDK r30 为例；`JNI_INCLUDE_DIR` 在 `ANDROID` 下默认取 NDK sysroot）：

```bash
NDK=~/Android/Sdk/ndk/30.0.16248370
# arm64-v8a
cmake -S Rapfi -B build/jni-arm64 -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
  -DBUILD_JNI=ON -DNO_COMMAND_MODULES=ON -DUSE_NEON=ON -DUSE_NEON_DOTPROD=OFF
cmake --build build/jni-arm64

# armeabi-v7a（本仓库的手表 OWW251 就是这一档：monaco/W4100，32 位用户空间）
cmake -S Rapfi -B build/jni-v7a -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
  -DBUILD_JNI=ON -DNO_COMMAND_MODULES=ON -DUSE_NEON=ON -DUSE_NEON_ARMV7_COMPAT=ON \
  -DCMAKE_CXX_FLAGS_RELEASE="-O3 -DNDEBUG -march=armv7-a -mfpu=neon-vfpv4 -mfloat-abi=softfp"
cmake --build build/jni-v7a
```

产物 `librapfi.so` 拷到 `app/src/main/jniLibs/<abi>/`。**剥符号只能用
`llvm-strip --strip-debug`**：`--strip-all` 会连 JNI 入口一起剥掉，运行期按名字绑定就会失败。

仓库里两个二进制的预期字节数（CI 的「防退化校验」会断言，见 `.github/workflows/build.yml`）：
arm64-v8a = `2487912`，armeabi-v7a = `1853696`。换成自己编的版本时记得同步更新那两个数字。

发布用的两个库还带 PGO + ThinLTO（`-fvisibility=hidden -fno-semantic-interposition`、`-Wl,--lto-O3`）；
完整的 PGO 配方（instrumented 构建 → 跑 `host/` 里的驱动采集 profile → `llvm-profdata merge`
→ 带 `-fprofile-instr-use` 重编）记在仓库根的 `AGENTS.md`。

## 校验器

```bash
javac -d /tmp/classes engine-src/host/com/qwara/gomoku/engine/*.java
java -Djava.library.path=build/jni-host -cp /tmp/classes \
  com.qwara.gomoku.engine.HintFlowTest /path/to/gomoku-watch/app/src/main/assets/engine/common
```

`workDir` 指到含权重与 `config.toml` 的目录（即 `app/src/main/assets/engine/common`）。

## 许可

本目录下的文件（`rapfi_jni.cpp`、`host/`、两个补丁）属于本项目，按 **GPL-3.0** 发布，
与 Rapfi 保持一致；Rapfi 本身的版权归其作者所有（见上游 `AUTHORS`）。
