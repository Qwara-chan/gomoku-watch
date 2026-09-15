package com.qwara.gomoku.engine;

/**
 * Desktop-JVM copy of the Android bridge object, used to smoke-test librapfi.so on the host.
 *
 * <p>The app module supplies the real class with the same package and class name; only the four
 * native methods below matter, and they must stay in sync with the JNI bridge in
 * {@code Rapfi/jni/rapfi_jni.cpp}.
 */
public final class RapfiNative {
    static {
        System.loadLibrary("rapfi");
    }

    private RapfiNative() {}

    /** Start (or restart) the protocol loop, resolving config and weights from {@code workDir}. */
    public static native void start(String workDir);

    /** Queue one command line for the engine. */
    public static native void write(String line);

    /** Block until one engine output line is available; null once the session ended and drained. */
    public static native String readLine();

    /** Whether the protocol loop thread is still alive. */
    public static native boolean isRunning();
}
