// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.gomoku.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host smoke test for librapfi.so.
 *
 * <p>Walks through the three scenarios the delivery requires: a free-style game move, a renju
 * analysis session driven by YXNBEST/STOP, and a full session rebuild after END.
 *
 * <p>The reader thread is started <em>after</em> start(), matching the app: readLine() returns
 * null once there is no live session, which is how the reader learns to stop.
 */
public final class SmokeTest {

    /** Every engine line seen so far, in order. */
    private static final List<String> LINES = new ArrayList<>();

    private static final AtomicInteger READERS = new AtomicInteger();

    /** Reads engine output on a background thread, the same way the app's reader thread does. */
    private static void startReader() {
        int id = READERS.incrementAndGet();
        Thread t = new Thread(() -> {
            try {
                for (;;) {
                    String line = RapfiNative.readLine();
                    if (line == null) {
                        break;
                    }
                    synchronized (LINES) {
                        LINES.add(line);
                    }
                }
            } catch (Throwable e) {
                System.out.println("!! reader " + id + " died: " + e);
            }
        }, "smoke-reader-" + id);
        t.setDaemon(true);
        t.start();
    }

    private static int lineCount() {
        synchronized (LINES) {
            return LINES.size();
        }
    }

    private static List<String> snapshot() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    private static void send(String cmd) {
        System.out.println(">>> " + cmd);
        RapfiNative.write(cmd);
    }

    /** Print every line not shown yet, keeping a cursor the caller advances. */
    private static int printFrom(int printed) {
        List<String> all = snapshot();
        for (; printed < all.size(); printed++) {
            System.out.println("<<< " + all.get(printed));
        }
        return printed;
    }

    /** The final move line is a bare "x,y" or a list of such pairs. */
    private static boolean isMoveLine(String s) {
        return s.matches("^-?\\d+,-?\\d+( -?\\d+,-?\\d+)*$");
    }

    /**
     * Pump output for a while, printing as lines arrive, and report whether a final move line
     * showed up. Returns {newPrintedCursor, sawMoveLine}.
     */
    private static Object[] pump(int printed, long timeoutMs, boolean stopAtMoveLine)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean sawMove = false;
        while (System.currentTimeMillis() < deadline) {
            printed = printFrom(printed);
            List<String> all = snapshot();
            if (!all.isEmpty() && isMoveLine(all.get(all.size() - 1).trim())) {
                sawMove = true;
                if (stopAtMoveLine) {
                    return new Object[] {printed, Boolean.TRUE};
                }
            }
            if (sawMove && stopAtMoveLine) {
                break;
            }
            Thread.sleep(50);
        }
        return new Object[] {printed, sawMove};
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: SmokeTest <workDir>");
            System.exit(2);
        }
        String workDir = args[0];
        int printed = 0;

        // ---------------------------------------------------------------- scenario 1
        System.out.println("========== scenario 1: freestyle (RULE 0) TURN ==========");
        RapfiNative.start(workDir);
        startReader();
        System.out.println("isRunning after start = " + RapfiNative.isRunning());
        printed = printFrom(printed);

        send("YXSHOWINFO");
        send("INFO SHOW_DETAIL 2");
        send("INFO THREAD_NUM 1");
        send("INFO RULE 0");
        send("START 15");
        send("INFO TIMEOUT_MATCH 100000000");
        send("INFO TIMEOUT_TURN 2000");
        send("INFO TIME_LEFT 100000000");
        send("TURN 7,7");
        Object[] r1 = pump(printed, 30000, true);
        printed = (int) r1[0];
        System.out.println("scenario 1: move line received = " + r1[1]);

        // ---------------------------------------------------------------- scenario 2
        System.out.println();
        System.out.println("========== scenario 2: renju (RULE 4) BOARD + YXNBEST 3 + STOP ==========");
        // A time limit would end the analysis on its own; 0 keeps it running until STOP.
        send("INFO TIMEOUT_TURN 0");
        send("INFO RULE 4");
        send("START 15");
        send("BOARD");
        send("7,7,1");
        send("8,8,2");
        send("7,8,1");
        send("DONE");
        send("YXNBEST 3");
        Thread.sleep(4000);
        printed = printFrom(printed);

        int beforeStop = lineCount();
        send("STOP");
        Object[] r2 = pump(printed, 8000, true);
        printed = (int) r2[0];
        System.out.println("scenario 2: stopped, " + (lineCount() - beforeStop)
                + " lines emitted from STOP onward");

        // ---------------------------------------------------------------- scenario 3
        System.out.println();
        System.out.println("========== scenario 3: END, then rebuild the session ==========");
        send("END");
        long deadline = System.currentTimeMillis() + 8000;
        while (RapfiNative.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        System.out.println("isRunning after END = " + RapfiNative.isRunning());
        Thread.sleep(1000);
        printed = printFrom(printed);

        System.out.println("---- calling start() again (the reader from scenario 1 has exited) ----");
        RapfiNative.start(workDir);
        startReader();
        System.out.println("isRunning after restart = " + RapfiNative.isRunning());

        send("YXSHOWINFO");
        send("INFO SHOW_DETAIL 2");
        send("INFO THREAD_NUM 1");
        send("INFO RULE 0");
        send("INFO TIMEOUT_MATCH 100000000");
        send("INFO TIMEOUT_TURN 2000");
        send("INFO TIME_LEFT 100000000");
        send("START 15");
        send("TURN 7,7");
        Object[] r3 = pump(printed, 30000, true);
        printed = (int) r3[0];
        System.out.println("scenario 3: move line received after restart = " + r3[1]);

        // Confirm NNUE really loaded rather than the engine silently degrading to classical eval.
        List<String> all = snapshot();
        long weightLoads = all.stream().filter(l -> l.contains("weight loaded")).count();
        System.out.println("'weight loaded' lines seen = " + weightLoads);

        send("END");
        Thread.sleep(500);
        System.out.println();
        System.out.println("done. total engine output lines = " + all.size()
                + ", reader threads = " + READERS.get());
        System.exit(0);
    }
}
