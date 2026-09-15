// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.gomoku.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Edge cases around session lifecycle that the app can actually hit.
 *
 * <p>Notably: END is often issued while a search is still running, and start() may be called
 * again before the previous loop has fully wound down. Both must leave the engine usable.
 */
public final class EdgeTest {

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

    private static int count() {
        synchronized (LINES) {
            return LINES.size();
        }
    }

    private static void send(String cmd) {
        RapfiNative.write(cmd);
    }

    private static boolean sawMoveSince(int from, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<String> all;
        while (System.currentTimeMillis() < deadline) {
            synchronized (LINES) {
                all = new ArrayList<>(LINES);
            }
            for (int i = from; i < all.size(); i++) {
                if (all.get(i).trim().matches("^-?\\d+,-?\\d+( -?\\d+,-?\\d+)*$")) {
                    return true;
                }
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static void printNew(int from) {
        List<String> all;
        synchronized (LINES) {
            all = new ArrayList<>(LINES);
        }
        for (int i = from; i < all.size(); i++) {
            System.out.println("    <<< " + all.get(i));
        }
    }

    private static long residentKb() throws Exception {
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(
                "/proc/self/status"));
        String l;
        while ((l = r.readLine()) != null) {
            if (l.startsWith("VmRSS:")) {
                r.close();
                return Long.parseLong(l.replaceAll("[^0-9]", ""));
            }
        }
        r.close();
        return -1;
    }

    private static void boot(String workDir) {
        RapfiNative.start(workDir);
        startReader();
        send("YXSHOWINFO");
        send("INFO SHOW_DETAIL 0");
        send("INFO THREAD_NUM 1");
        send("INFO RULE 0");
        send("INFO TIMEOUT_MATCH 100000000");
        send("INFO TIMEOUT_TURN 1500");
        send("INFO TIME_LEFT 100000000");
        send("START 15");
    }

    public static void main(String[] args) throws Exception {
        String workDir = args[0];

        System.out.println("========== edge 1: write() before any session ==========");
        RapfiNative.write("START 15");
        System.out.println("write() before start() did not crash; isRunning = "
                + RapfiNative.isRunning());

        System.out.println();
        System.out.println("========== edge 2: END while a search is running, then restart ==========");
        boot(workDir);
        send("TURN 7,7");
        Thread.sleep(400);  // let the search get going
        int mark = count();
        send("END");        // END during an active analysis search
        long deadline = System.currentTimeMillis() + 10000;
        while (RapfiNative.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        System.out.println("isRunning after mid-search END = " + RapfiNative.isRunning());
        printNew(mark);

        System.out.println("---- restart after a mid-search END ----");
        boot(workDir);
        int mark2 = count();
        send("TURN 7,7");
        boolean got = sawMoveSince(mark2, 20000);
        System.out.println("move received after restart from mid-search END = " + got);
        printNew(mark2);
        send("END");
        Thread.sleep(300);

        System.out.println();
        System.out.println("========== edge 3: start() without END (start over a live session) ==========");
        int mark3 = count();
        boot(workDir);  // previous session ended above, but this repeats the rapid case
        Thread.sleep(200);
        boot(workDir);  // restart immediately, old loop may still be finishing
        int mark4 = count();
        send("TURN 7,7");
        boolean got2 = sawMoveSince(mark4, 20000);
        System.out.println("move received after back-to-back start() = " + got2);
        printNew(mark4);
        send("END");
        Thread.sleep(300);

        System.out.println();
        System.out.println("========== edge 4: 6 back-to-back sessions, watch RSS ==========");
        long rss0 = residentKb();
        System.out.println("RSS before = " + rss0 + " kB");
        for (int i = 0; i < 6; i++) {
            boot(workDir);
            int m = count();
            send("TURN 7,7");
            boolean ok = sawMoveSince(m, 20000);
            send("END");
            long d = System.currentTimeMillis() + 5000;
            while (RapfiNative.isRunning() && System.currentTimeMillis() < d) {
                Thread.sleep(20);
            }
            System.out.println("  session " + (i + 1) + ": move=" + ok
                    + " rss=" + residentKb() + " kB");
        }
        long rss1 = residentKb();
        System.out.println("RSS after 6 sessions = " + rss1 + " kB, delta = " + (rss1 - rss0) + " kB");

        System.out.println();
        System.out.println("========== edge 5: bad workDir ==========");
        try {
            RapfiNative.start("/nonexistent/definitely/not/here");
            System.out.println("start(bad dir) returned without throwing; isRunning = "
                    + RapfiNative.isRunning());
        } catch (Throwable t) {
            System.out.println("start(bad dir) threw " + t);
        }
        RapfiNative.write("END");
        Thread.sleep(300);
        System.out.println("done.");
        System.exit(0);
    }
}
