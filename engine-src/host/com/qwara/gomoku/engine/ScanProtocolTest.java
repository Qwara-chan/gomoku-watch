package com.qwara.gomoku.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Exercises the protocol sequences added for the eval curve / strength handicap features:
 *
 *  - full-game scan: for each ply, YXNBEST 1 with a finite TIMEOUT_TURN must emit INFO WINRATE
 *    (+ NUMPV/SELDEPTH) and end by itself with a final move line, which is what RapfiEngine.scanAnalyze
 *    waits for;
 *  - INFO STRENGTH before TURN/BEGIN must be accepted without ERROR/UNKNOWN, and must still emit
 *    parseable PV blocks;
 *  - INFO STRENGTH 100 before analysis must restore full-strength output.
 */
public final class ScanProtocolTest {

    private static final List<String> LINES = new ArrayList<>();
    private static int failures = 0;

    private static void startReader() {
        Thread t = new Thread(() -> {
            try {
                for (;;) {
                    String line = RapfiNative.readLine();
                    if (line == null) {
                        break;
                    }
                    synchronized (LINES) {
                        LINES.add(line.trim());
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

    private static int mark() {
        return snapshot().size();
    }

    private static void send(String cmd) {
        RapfiNative.write(cmd);
    }

    private static List<String> since(int mark) {
        List<String> all = snapshot();
        return new ArrayList<>(all.subList(Math.min(mark, all.size()), all.size()));
    }

    /** Wait for a final move line; returns it or null on timeout. */
    private static String awaitMove(int from, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<String> all = snapshot();
            for (int i = Math.min(from, all.size()); i < all.size(); i++) {
                String s = all.get(i).trim();
                if (s.matches("^-?\\d+,-?\\d+( -?\\d+,-?\\d+)*$")) {
                    return s;
                }
            }
            Thread.sleep(30);
        }
        return null;
    }

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static boolean hasError(List<String> lines) {
        for (String s : lines) {
            if (s.startsWith("ERROR") || s.startsWith("UNKNOWN")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPrefix(List<String> lines, String prefix) {
        for (String s : lines) {
            if (s.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void printSince(int mark) {
        for (String s : since(mark)) {
            System.out.println("      <<< " + s);
        }
    }

    /** Highest INFO DEPTH seen in [lines], or -1 when the search printed none. */
    private static int maxDepth(List<String> lines) {
        int best = -1;
        for (String s : lines) {
            if (s.startsWith("INFO DEPTH ")) {
                try {
                    best = Math.max(best, Integer.parseInt(s.substring("INFO DEPTH ".length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return best;
    }

    private static void boot(String rule) throws InterruptedException {
        send("YXSHOWINFO");
        send("INFO THREAD_NUM 1");
        send("INFO PONDERING 0");
        send("INFO SHOW_DETAIL 2");
        send("INFO RULE " + rule);
        send("INFO TIMEOUT_MATCH 100000000");
        send("INFO TIMEOUT_TURN 600");
        send("INFO TIME_LEFT 100000000");
        send("INFO HASH_SIZE 16384");
        send("START 15");
        Thread.sleep(1500);
    }

    public static void main(String[] args) throws Exception {
        String workDir = args[0];
        RapfiNative.start(workDir);
        startReader();
        boot("0");

        // Opening used by the scan: 6 plies, alternating colors starting with black.
        int[][] game = {
                {7, 7, 1}, {8, 8, 2}, {7, 8, 1}, {6, 6, 2}, {8, 7, 1}, {6, 8, 2},
        };

        // ---- empty board: answered from the opening book without any INFO block ----
        // This is why RapfiEngine/MainViewModel start the full-game scan at ply 1 (the empty-board
        // "evaluation" does not exist), asserted here so the reason stays visible.
        System.out.println("========== empty board (book move) ==========");
        int m0 = mark();
        send("INFO TIMEOUT_TURN 600");
        send("INFO STRENGTH 100");
        send("YXBOARD");
        send("DONE");
        send("YXNBEST 1");
        String bookMove = awaitMove(m0, 15000);
        check(bookMove != null, "empty board answered with a move");
        check(!hasPrefix(since(m0), "INFO PV "), "empty-board answer carries no PV (scan starts at ply 1)");

        // ---- full-game scan: one finite YXNBEST 1 per ply ----
        System.out.println("========== full scan (YXNBEST 1, TIMEOUT_TURN 600) ==========");
        for (int k = 1; k <= game.length; k++) {
            int m = mark();
            send("INFO TIMEOUT_TURN 600");
            // What RapfiEngine.sendBoardCommands sends before every board sync.
            send("INFO STRENGTH 100");
            send("YXBOARD");
            for (int i = 0; i < k; i++) {
                send(game[i][0] + "," + game[i][1] + "," + game[i][2]);
            }
            send("DONE");
            send("YXNBEST 1");
            String move = awaitMove(m, 15000);
            List<String> lines = since(m);
            check(move != null, "ply " + k + ": finite YXNBEST 1 ends by itself with a move line");
            check(hasPrefix(lines, "INFO WINRATE "), "ply " + k + ": INFO WINRATE emitted (curve value)");
            check(hasPrefix(lines, "INFO PV "), "ply " + k + ": INFO PV block emitted");
            check(hasPrefix(lines, "INFO NUMPV "), "ply " + k + ": INFO NUMPV emitted (new parse key)");
            check(!hasError(lines), "ply " + k + ": no ERROR/UNKNOWN");
            System.out.println("      ply " + k + " -> " + move);
        }

        // ---- strength handicap: same TURN sequence the app uses for AI moves ----
        // SkillMovePicker.pickDepth() caps the search depth by level: 4 (level 0), 8 (30),
        // 14 (85); at 100 the picker is disabled entirely. Depth in the INFO blocks is the
        // observable proof that INFO STRENGTH takes effect mid-session.
        System.out.println();
        System.out.println("========== INFO STRENGTH <level> + TURN (weak AI move) ==========");
        for (int level : new int[] {0, 30, 85, 100}) {
            // The engine's board is the 6-ply game above with black to move; TURN plays the
            // side to move and the engine answers, so the probe adds two stones. Undo both
            // (like MainViewModel.undo() in AI mode) to restart each level from the same ply.
            int m = mark();
            send("INFO TIMEOUT_TURN 600");
            send("INFO STRENGTH " + level);
            send("TURN 9,9");
            String move = awaitMove(m, 15000);
            List<String> lines = since(m);
            check(move != null, "strength " + level + ": TURN answered with a move");
            check(!hasError(lines), "strength " + level + ": INFO STRENGTH accepted (no ERROR/UNKNOWN)");
            check(hasPrefix(lines, "INFO PV "), "strength " + level + ": PV blocks still emitted");
            int maxDepth = maxDepth(lines);
            System.out.println("      strength " + level + " -> " + move + " (max depth " + maxDepth + ")");
            if (level == 0) {
                check(maxDepth <= 4, "strength 0 caps depth at 4 (got " + maxDepth + ")");
            } else if (level == 30) {
                check(maxDepth <= 8, "strength 30 caps depth at 8 (got " + maxDepth + ")");
            } else if (level == 85) {
                check(maxDepth <= 14, "strength 85 caps depth at 14 (got " + maxDepth + ")");
            } else {
                check(maxDepth > 14, "strength 100 runs deeper than the capped levels (got " + maxDepth + ")");
            }
            send("TAKEBACK 0,0");
            send("TAKEBACK 0,0");
            Thread.sleep(400);
        }

        // ---- analysis after a weak session: STRENGTH 100 must be restored by sendBoardCommands ----
        System.out.println();
        System.out.println("========== analysis after weak session (STRENGTH 100 + YXNBEST 3) ==========");
        int m = mark();
        send("INFO TIMEOUT_TURN 0");
        send("INFO STRENGTH 100");
        send("YXBOARD");
        for (int[] mv : game) {
            send(mv[0] + "," + mv[1] + "," + mv[2]);
        }
        send("DONE");
        send("YXNBEST 3");
        Thread.sleep(4000);
        List<String> lines = since(m);
        boolean depthReached = false;
        for (String s : lines) {
            if (s.startsWith("INFO DEPTH ") && Integer.parseInt(s.substring(11)) >= 6) {
                depthReached = true;
            }
        }
        check(depthReached, "full-strength analysis reaches depth >= 6 within 4s");
        check(lines.stream().filter(s -> s.startsWith("INFO PV ")).count() > 3, "multi-PV blocks stream");
        // The app sets TIMEOUT_MATCH (so timeLimit=true) and the engine's isAnalysisMode() is
        // !timeLimit, i.e. a YXNBEST search is NOT analysis mode: once it proves a mate it stops
        // by itself and prints the final move (numIterationAfterMate depth iterations later).
        // The app must tolerate that (final move line => phase IDLE), so STOP is only required
        // when the position is still being searched.
        boolean endedEarly = false;
        for (String s : lines) {
            if (s.matches("^-?\\d+,-?\\d+( -?\\d+,-?\\d+)*$")) {
                endedEarly = true;
            }
        }
        System.out.println("      -- analysis stream tail (" + lines.size() + " lines) --");
        printSince(Math.max(m, m + lines.size() - 12));
        if (endedEarly) {
            String lastEval = "";
            for (String s : lines) {
                if (s.startsWith("INFO EVAL ")) {
                    lastEval = s.substring("INFO EVAL ".length());
                }
            }
            check(lastEval.matches("[+-]M\\d+"), "self-terminated analysis proves a mate (" + lastEval + ")");
            send("STOP"); // no-op on an idle engine; keeps the harness tidy
        } else {
            m = mark();
            send("STOP");
            check(awaitMove(m, 15000) != null, "STOP returns the final move line");
        }

        send("END");
        Thread.sleep(500);
        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
