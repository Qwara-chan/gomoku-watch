// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine;

/**
 * Desktop-JVM copy of the Android bridge object, used to smoke-test libpachi.so on the host.
 *
 * <p>The app module supplies the real class with the same package and class name; only the four
 * native methods below matter, and they must stay in sync with the JNI bridge in
 * {@code engine-src/pachi_jni.cpp}.
 */
public final class PachiNative {
    static {
        System.loadLibrary("pachi");
    }

    private PachiNative() {}

    /** Start (or restart) the GTP protocol loop, resolving data files from {@code workDir}. */
    public static native void start(String workDir);

    /** Queue one GTP command line for the engine. */
    public static native void write(String line);

    /** Block until one engine output line is available; null once the session ended and drained. */
    public static native String readLine();

    /** Whether the GTP loop thread is still alive. */
    public static native boolean isRunning();

    /** Destroy the current session and join the engine thread. */
    public static native void shutdown();

    /**
     * Duplicate of the process stdout fd, taken before the first {@link #start} hijacks fd 1
     * for the engine. Host-side tests repoint System.out at it via /proc/self/fd/&lt;n&gt;.
     */
    public static native int dupStdout();
}
