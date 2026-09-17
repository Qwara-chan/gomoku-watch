// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Minimal stepping repro: send one command, print every line with a timestamp. */
public final class Repro {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Repro <workDir>");
            System.exit(2);
        }
        int fd = PachiNative.dupStdout();
        java.io.PrintStream ps = new java.io.PrintStream(
                new java.io.FileOutputStream("/proc/self/fd/" + fd), true, "UTF-8");
        System.setOut(ps);
        System.setErr(ps);

        final long t0 = System.currentTimeMillis();
        final List<String> lines = new CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            for (;;) {
                String l = PachiNative.readLine();
                if (l == null) break;
                lines.add(l);
                System.out.println(String.format("%6d <<< %s", System.currentTimeMillis() - t0, l));
            }
        }, "repro-reader");
        reader.setDaemon(true);

        PachiNative.start(args[0]);
        reader.start();
        long lastMark = t0;

        String phase = args.length > 1 ? args[1] : "a";
        java.util.List<String> script = new java.util.ArrayList<>(java.util.List.of(
            "boardsize 19", "komi 6.5", "clear_board",
            "play black Q16", "kgs-time_settings byoyomi 0 2 1", "genmove white"));
        if (phase.compareTo("b") >= 0) {
            script.add("lz-analyze black 50");
            script.add("__sleep3000");
            script.add("lz-analyze black 0");
        }
        if (phase.compareTo("c") >= 0) {
            script.add("lz-genmove_analyze black 50");
        }
        if (phase.compareTo("d") >= 0) {
            script.add("pachi-result");
            script.add("undo");
            script.add("play black D4");
            script.add("genmove white");
            script.add("play black D4");   // illegal: occupied
        }
        script.addAll(java.util.List.of(
            "clear_board",
            "play black pass", "play white pass",
            "final_score",
            "echo mark-after-score",
            "boardsize 9", "komi 5.5", "play black E5",
            "kgs-time_settings byoyomi 0 2 1", "genmove white",
            "echo mark-end"));
        for (String cmd : script) {
            if (cmd.startsWith("__sleep")) {
                Thread.sleep(Long.parseLong(cmd.substring(7)));
                continue;
            }
            System.out.println(String.format("%6d >>> %s", System.currentTimeMillis() - t0, cmd));
            PachiNative.write(cmd);
            // Wait for this command's reply (any new GTP reply line) before the next step.
            int before = countReplies(lines);
            long deadline = System.currentTimeMillis() + 25000;
            while (countReplies(lines) <= before && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            if (countReplies(lines) <= before) {
                System.out.println(String.format("%6d !!! NO REPLY for: %s", System.currentTimeMillis() - t0, cmd));
                System.out.println("=== hanging; attach jstack/gdb now ===");
                Thread.sleep(60000);
                System.exit(3);
            }
        }
        System.out.println("all commands answered");
        PachiNative.shutdown();
        long t = System.currentTimeMillis();
        while (System.currentTimeMillis() - t < 5000) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            if (!PachiNative.isRunning()) break;
        }
        System.out.println("shutdown returned, exiting");
        System.exit(0);
    }

    private static int countReplies(List<String> lines) {
        int n = 0;
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith("=") || t.startsWith("?")) n++;
        }
        return n;
    }
}
