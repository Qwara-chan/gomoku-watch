#!/bin/bash
# SPDX-FileCopyrightText: 2026 Qwara-chan
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Cross-build libpachi.so for the Android ABIs shipped in the APK
# (arm64-v8a / armeabi-v7a / x86_64), from the pachi sources in pachi-src/.
#
# Pachi's Makefile builds in-tree, so each ABI gets its own private copy of the
# sources under build-android/<abi>/. Only debug symbols are stripped from the
# result — never use --strip-all, it would drop the JNI entry points that
# PachiNative binds to by name.
#
# Prerequisites: Android NDK (r25+; developed against r30), make.
# Usage:
#   NDK=~/Android/Sdk/ndk/30.0.16248370 ./build-android.sh
# Output:
#   build-android/<abi>/libpachi.so   (copy into app/src/main/jniLibs/<abi>/)

set -euo pipefail
cd "$(dirname "$0")"

NDK="${NDK:-$HOME/Android/Sdk/ndk/$(ls "$HOME/Android/Sdk/ndk" | sort -V | tail -1)}"
SRC=pachi-src
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[ -d "$TOOLCHAIN" ] || { echo "NDK toolchain not found at $TOOLCHAIN"; exit 1; }

build_abi() {
    local abi="$1" clang="$2"
    local dir="build-android/$abi"
    echo "=== $abi ==="
    rm -rf "$dir"
    mkdir -p "$dir"
    cp -r "$SRC" "$dir/src"
    # The clone tree may carry host build artifacts with newer timestamps; drop them so the
    # cross build actually recompiles everything for the target ABI.
    find "$dir/src" -name '*.o' -delete
    find "$dir/src" -name 'lib.a' -delete
    rm -f "$dir/src"/pachi "$dir/src"/build.h "$dir/src"/build.h.git
    local cc="$TOOLCHAIN/bin/$clang"
    # The `pachi` binary target also builds the root objects; LIBS overrides drop -lrt
    # (bionic has no librt). The binary itself is not shipped, only the objects.
    ( cd "$dir/src" && \
      make -j"$(nproc)" CC="$cc" DCNN=0 JOSEKIFIX=0 GENERIC=1 XCFLAGS="-fPIC -Oz" \
           LIBS="-lm -ldl" build.h pachi )
    # JNI bridge (jni.h comes from the host JDK, not the NDK sysroot)
    local jni_dir
    jni_dir="$(dirname "$(find /usr/lib/jvm -name jni.h -print -quit)")"
    "$cc" -std=gnu99 -Oz -fPIC \
        -I "$dir/src" -I "$jni_dir" -I "$jni_dir/linux" \
        -c pachi_jni.c -o "$dir/pachi_jni.o"
    # Link
    "$cc" -shared -o "$dir/libpachi.so" \
        "$dir/src"/*.o \
        "$dir/src"/engines/lib.a "$dir/src"/joseki/lib.a "$dir/src"/pattern/lib.a \
        "$dir/src"/playout/lib.a "$dir/src"/tactics/lib.a "$dir/src"/t-predict/lib.a \
        "$dir/src"/t-unit/lib.a "$dir/src"/uct/lib.a "$dir/src"/uct/policy/lib.a \
        "$dir/pachi_jni.o" \
        -lm -ldl -pthread
    "$TOOLCHAIN/bin/llvm-strip" --strip-debug "$dir/libpachi.so"
    ls -la "$dir/libpachi.so"
}

build_abi arm64-v8a     aarch64-linux-android24-clang
build_abi armeabi-v7a   armv7a-linux-androideabi24-clang
build_abi x86_64        x86_64-linux-android24-clang

echo "all ABIs built under build-android/"
