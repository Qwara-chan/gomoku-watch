package com.qwara.gomoku.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Probes the race the app can hit when it does not wait for its reader thread to exit before
 * starting the next session: the old reader is still blocked in readLine() when start() runs.
 *
 * Checks that no move line is lost or double-reported across the handover.
 */
public final class RaceTest {

    private static final List<String> LINES = new ArrayList<>();

    private static void startReader() {
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
            } catch (Throwable ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static List<String> snapshot() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    private static void send(String c) {
        RapfiNative.write(c);
    }

    private static void boot(String dir) {
        RapfiNative.start(dir);
        startReader();
        send("YXSHOWINFO");
        send("INFO SHOW_DETAIL 0");
        send("INFO THREAD_NUM 1");
        send("INFO RULE 0");
        send("INFO TIMEOUT_TURN 800");
        send("INFO TIMEOUT_MATCH 100000000");
        send("INFO TIME_LEFT 100000000");
        send("START 15");
    }

    /** Count move lines at or after `from`. */
    private static int movesSince(int from) {
        List<String> all = snapshot();
        int n = 0;
        for (int i = from; i < all.size(); i++) {
            if (all.get(i).trim().matches("^-?\\d+,-?\\d+$")) {
                n++;
            }
        }
        return n;
    }

    private static int waitMoves(int from, int want, long timeoutMs) throws InterruptedException {
        long d = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < d) {
            if (movesSince(from) >= want) {
                return movesSince(from);
            }
            Thread.sleep(20);
        }
        return movesSince(from);
    }

    public static void main(String[] args) throws Exception {
        String dir = args[0];
        System.out.println("=== race: END then start() WITHOUT waiting for the reader to exit ===");

        for (int round = 1; round <= 5; round++) {
            boot(dir);
            int m0 = snapshot().size();
            send("TURN 7,7");
            int got = waitMoves(m0, 1, 15000);
            if (got < 1) {
                System.out.println("round " + round + ": !! no move on the first session");
            }

            // END and immediately restart. isRunning() may still be true, and the previous
            // reader is likely still parked inside readLine().
            send("END");
            boot(dir);
            int m1 = snapshot().size();
            send("TURN 7,7");
            int got2 = waitMoves(m1, 1, 15000);

            send("END");
            long d = System.currentTimeMillis() + 4000;
            while (RapfiNative.isRunning() && System.currentTimeMillis() < d) {
                Thread.sleep(20);
            }
            System.out.println("round " + round + ": first=" + got + " second=" + got2
                    + "  lines=" + snapshot().size());
        }

        // Duplicate detection: each session should produce exactly one final move line.
        List<String> all = snapshot();
        long moveLines = all.stream().filter(l -> l.trim().matches("^-?\\d+,-?\\d+$")).count();
        System.out.println("total move lines = " + moveLines + " (expected 10 for 10 TURN searches)");
        System.out.println("done.");
        System.exit(0);
    }
}
