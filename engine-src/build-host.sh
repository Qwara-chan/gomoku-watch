#!/bin/bash
# SPDX-FileCopyrightText: 2026 Qwara-chan
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Build the host (desktop x86-64) libpachi.so used by the host harness in engine-src/host/.
#
# Prerequisites: gcc, make, a JDK (for jni.h). Pachi sources are expected in pachi-src/
# (git clone of https://github.com/pasky/pachi, see README.md).
#
# Usage: ./build-host.sh            (from engine-src/)
# Output: build-host/libpachi.so

set -euo pipefail
cd "$(dirname "$0")"

SRC=pachi-src
OUT=build-host
JNI_DIR="$(dirname "$(find /usr/lib/jvm -name jni.h -print -quit)")"

[ -d "$SRC" ] || { echo "pachi-src/ missing — see README.md"; exit 1; }

# 1. Build all Pachi objects (pure Monte-Carlo build: no DCNN/Caffe/Boost, no KataGo josekifix;
#    GENERIC avoids -march=native so the recipe also works for cross builds).
make -C "$SRC" -j"$(nproc)" DCNN=0 JOSEKIFIX=0 GENERIC=1 XCFLAGS="-fPIC" build.h
make -C "$SRC" -j"$(nproc)" DCNN=0 JOSEKIFIX=0 GENERIC=1 XCFLAGS="-fPIC" pachi

# 2. Compile the JNI bridge against Pachi headers + JNI.
gcc -std=gnu99 -O2 -fPIC \
    -I "$SRC" -I "$JNI_DIR" -I "$JNI_DIR/linux" \
    -c pachi_jni.c -o "$OUT/pachi_jni.o"

# 3. Link the shared library exactly like the `pachi` binary links, plus the bridge object.
gcc -shared -o "$OUT/libpachi.so" \
    "$SRC"/*.o \
    "$SRC"/engines/lib.a "$SRC"/joseki/lib.a "$SRC"/pattern/lib.a \
    "$SRC"/playout/lib.a "$SRC"/tactics/lib.a "$SRC"/t-predict/lib.a \
    "$SRC"/t-unit/lib.a "$SRC"/uct/lib.a "$SRC"/uct/policy/lib.a \
    "$OUT/pachi_jni.o" \
    -lm -ldl -lrt -pthread

echo "built $OUT/libpachi.so"
