// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.gomoku.engine;

import java.lang.management.ManagementFactory;

/** Isolates whether repeated sessions leak JVM threads (native attach without detach). */
public final class LeakProbe {

    private static void startReader() {
        Thread t = new Thread(() -> {
            try {
                while (RapfiNative.readLine() != null) {
                }
            } catch (Throwable ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
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

    private static long rssKb() throws Exception {
        try (java.io.BufferedReader r =
                     new java.io.BufferedReader(new java.io.FileReader("/proc/self/status"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("VmRSS:")) {
                    return Long.parseLong(l.replaceAll("[^0-9]", ""));
                }
            }
        }
        return -1;
    }

    private static int liveThreads() {
        return ManagementFactory.getThreadMXBean().getThreadCount();
    }

    public static void main(String[] args) throws Exception {
        String dir = args[0];
        int rounds = Integer.parseInt(args[1]);
        for (int i = 0; i < rounds; i++) {
            boot(dir);
            send("TURN 7,7");
            long d = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < d && RapfiNative.isRunning()) {
                // wait for the timed move to finish
                Thread.sleep(50);
                if (!RapfiNative.isRunning()) {
                    break;
                }
            }
            send("END");
            d = System.currentTimeMillis() + 5000;
            while (RapfiNative.isRunning() && System.currentTimeMillis() < d) {
                Thread.sleep(20);
            }
            System.gc();
            Thread.sleep(200);
            System.out.printf("round %2d: rss=%7d kB  liveThreads=%d%n",
                    i + 1, rssKb(), liveThreads());
        }
        System.exit(0);
    }
}
