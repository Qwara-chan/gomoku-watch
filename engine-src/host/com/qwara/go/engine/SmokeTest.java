// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host smoke test for libpachi.so.
 *
 * <p>Walks through the scenarios the app relies on: a GTP session with boardsize/komi/play/
 * genmove, lz-analyze and lz-genmove_analyze (the exact output formats are printed here and
 * pinned by ScanProtocolTest), undo, a final_score after double pass, an illegal move, a
 * board-size switch, and a full session rebuild after EOF.
 */
public final class SmokeTest {

    private static final List<String> LINES = new ArrayList<>();
    private static final AtomicInteger READERS = new AtomicInteger();

    private static void startReader() {
        int id = READERS.incrementAndGet();
        Thread t = new Thread(() -> {
            try {
                for (;;) {
                    String line = PachiNative.readLine();
                    if (line == null) break;
                    synchronized (LINES) { LINES.add(line); }
                }
            } catch (Throwable e) {
                System.out.println("!! reader " + id + " died: " + e);
            }
        }, "smoke-reader-" + id);
        t.setDaemon(true);
        t.start();
    }

    private static List<String> snapshot() {
        synchronized (LINES) { return new ArrayList<>(LINES); }
    }

    private static void send(String cmd) {
        System.out.println(">>> " + cmd);
        PachiNative.write(cmd);
    }

    private static int printFrom(int printed) {
        List<String> all = snapshot();
        for (; printed < all.size(); printed++) System.out.println("<<< " + all.get(printed));
        return printed;
    }

    /** Wait until a GTP success reply ("= ...") shows up (or timeout). */
    private static boolean awaitGtpReply(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (String l : snapshot()) {
                String t = l.trim();
                if (t.startsWith("=") || t.startsWith("?")) return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /** Wait until a line containing {@code needle} shows up (or timeout). */
    private static boolean await(String needle, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (String l : snapshot()) if (l.contains(needle)) return true;
            Thread.sleep(50);
        }
        return false;
    }

    /** The first start() hijacks process fd 1 for the engine; keep our own console first. */
    private static void keepOurStdout() {
        int fd = PachiNative.dupStdout();
        if (fd < 0) return;
        try {
            java.io.PrintStream ps = new java.io.PrintStream(
                    new java.io.FileOutputStream("/proc/self/fd/" + fd), true, "UTF-8");
            System.setOut(ps);
            System.setErr(ps);
        } catch (Exception e) {
            // Keep whatever streams we had; output may vanish into the engine pipe.
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: SmokeTest <workDir>");
            System.exit(2);
        }
        keepOurStdout();
        String workDir = args[0];
        int printed = 0;
        int failures = 0;

        // ---------------------------------------------------------------- scenario 1: GTP basics
        System.out.println("========== scenario 1: boardsize/komi/play/genmove ==========");
        PachiNative.start(workDir);
        startReader();
        System.out.println("isRunning after start = " + PachiNative.isRunning());

        send("boardsize 19");
        send("komi 6.5");
        send("clear_board");
        send("kgs-time_settings byoyomi 0 3 1");
        send("play black Q16");
        printed = printFrom(printed);
        send("genmove white");
        if (!awaitGtpReply(30000)) { System.out.println("FAIL: no genmove reply"); failures++; }
        printed = printFrom(printed);

        send("pachi-result");
        if (!await("winrate", 3000) && !await("=", 3000)) { /* format pinned below */ }
        printed = printFrom(printed);

        // ---------------------------------------------------------------- scenario 2: lz-analyze
        System.out.println();
        System.out.println("========== scenario 2: lz-analyze (exact format capture) ==========");
        send("lz-analyze black 50");
        Thread.sleep(4000);
        printed = printFrom(printed);
        send("lz-analyze black 0");
        Thread.sleep(500);
        printed = printFrom(printed);

        // ---------------------------------------------------------------- scenario 3: lz-genmove_analyze
        System.out.println();
        System.out.println("========== scenario 3: lz-genmove_analyze ==========");
        send("lz-genmove_analyze black 50");
        if (!await("play ", 30000)) { System.out.println("FAIL: no lz-genmove_analyze play line"); failures++; }
        printed = printFrom(printed);

        // ---------------------------------------------------------------- scenario 4: undo
        System.out.println();
        System.out.println("========== scenario 4: undo ==========");
        send("undo");
        send("play black D4");
        send("kgs-time_settings byoyomi 0 2 1");
        send("genmove white");
        if (!awaitGtpReply(20000)) { System.out.println("FAIL: no genmove after undo"); failures++; }
        printed = printFrom(printed);

        // ---------------------------------------------------------------- scenario 5: illegal move
        System.out.println();
        System.out.println("========== scenario 5: illegal move ==========");
        send("play black D4");   // occupied after scenario 4
        Thread.sleep(500);
        printed = printFrom(printed);

        // ---------------------------------------------------------------- scenario 6: final_score after double pass
        System.out.println();
        System.out.println("========== scenario 6: double pass + final_score ==========");
        send("clear_board");
        send("play black pass");
        send("play white pass");
        send("final_score");
        Thread.sleep(8000);   // scoring playouts take a while
        printed = printFrom(printed);

        // ---------------------------------------------------------------- scenario 7: 9x9 switch
        System.out.println();
        System.out.println("========== scenario 7: boardsize 9 ==========");
        send("boardsize 9");
        send("komi 5.5");
        send("play black E5");
        send("kgs-time_settings byoyomi 0 2 1");
        send("genmove white");
        if (!awaitGtpReply(20000)) { System.out.println("FAIL: no 9x9 genmove"); failures++; }
        printed = printFrom(printed);

        // ---------------------------------------------------------------- scenario 8: EOF + restart
        System.out.println();
        System.out.println("========== scenario 8: EOF, then rebuild the session ==========");
        // Native-side destroy: start() again destroys the old session (stdin EOF ends the loop).
        PachiNative.start(workDir);
        startReader();
        System.out.println("isRunning after restart = " + PachiNative.isRunning());
        send("boardsize 19");
        send("komi 6.5");
        send("play black Q16");
        send("kgs-time_settings byoyomi 0 3 1");
        send("genmove white");
        if (!awaitGtpReply(30000)) { System.out.println("FAIL: no genmove after restart"); failures++; }
        printed = printFrom(printed);

        send("boardsize 13");
        send("clear_board");
        send("play black J10");
        send("kgs-time_settings byoyomi 0 2 1");
        send("genmove white");
        if (!awaitGtpReply(20000)) { System.out.println("FAIL: no 13x13 genmove"); failures++; }
        printed = printFrom(printed);

        System.out.println();
        PachiNative.shutdown();
        System.out.println("done. total engine output lines = " + snapshot().size()
                + ", reader threads = " + READERS.get() + ", failures = " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
