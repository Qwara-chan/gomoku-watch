// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.gomoku.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Reproduces "在人机对战中点击提示后引擎会超时" at the protocol level.
 *
 * <p>The engine applies the result of <em>every</em> search to its own board
 * ({@code sendActionAndUpdateBoard} -> {@code board->move}), including the YXNBEST search behind the
 * app's hint. So after a hint the engine's board carries one stone the app does not have; the next
 * {@code TURN} on that recommended point is then rejected by {@code parseLegalCoord}, and
 * {@code turn()} returns without searching and without printing a final move line - only
 * {@code ERROR Coord is not valid or empty.}, which the app logs and ignores. The app's
 * {@code awaitingMoves} never completes, so its 20 s timeout reports "引擎出错：引擎超时".
 *
 * <p>Case A replays the app's current sequence (TURN with no re-sync) and expects the drop.
 * Case B replays the fix (YXBOARD of the position before the move, then TURN) and expects a reply.
 * Case C checks the same re-sync on an empty board (the human's first move as Black).
 */
public final class HintFlowTest {

    private static final List<String> LINES = new ArrayList<>();
    private static boolean failed = false;

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

    private static void send(String cmd) {
        System.out.println(">>> " + cmd);
        RapfiNative.write(cmd);
    }

    /** YXBOARD + entries + DONE: exactly RapfiEngine.sendBoard. */
    private static void sendBoard(String... entries) {
        send("YXBOARD");
        for (String e : entries) {
            send(e);
        }
        send("DONE");
    }

    /** Session boot, exactly like RapfiEngine.startNewGame. */
    private static void boot(String workDir) throws InterruptedException {
        RapfiNative.start(workDir);
        startReader();
        send("YXSHOWINFO");
        send("INFO THREAD_NUM 1");
        send("INFO PONDERING 0");
        send("INFO SHOW_DETAIL 2");
        send("INFO RULE 0");
        send("INFO TIMEOUT_MATCH 100000000");
        send("INFO TIMEOUT_TURN 3000");
        send("INFO TIME_LEFT 100000000");
        send("INFO HASH_SIZE 16384");
        send("START 15");
        Thread.sleep(800);
    }

    private static boolean isMoveLine(String s) {
        return s.matches("^-?\\d+,-?\\d+( -?\\d+,-?\\d+)*$");
    }

    /** First move line at or after {@code from}, or null on timeout. */
    private static String awaitMove(int from, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<String> all = snapshot();
            for (int i = from; i < all.size(); i++) {
                String s = all.get(i).trim();
                if (isMoveLine(s)) {
                    System.out.println("      (move line after " + (System.currentTimeMillis() - start)
                            + " ms)");
                    return s;
                }
            }
            Thread.sleep(30);
        }
        return null;
    }

    /** The first coordinate of a move line - the single stone the app would place. */
    private static String firstCoord(String moveLine) {
        int space = moveLine.indexOf(' ');
        return space < 0 ? moveLine : moveLine.substring(0, space);
    }

    /** True when the engine reported the command as rejected at or after {@code from}. */
    private static boolean hasError(int from) {
        List<String> all = snapshot();
        for (int i = from; i < all.size(); i++) {
            if (all.get(i).trim().startsWith("ERROR")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Waits for whichever comes first: a final move line or an ERROR line.
     *
     * @return the move line, or null when the command was rejected / nothing came back
     */
    private static String awaitMoveOrError(int from, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String mv = awaitMove(from, 1);
            if (mv != null) {
                return mv;
            }
            if (hasError(from)) {
                return null;
            }
            Thread.sleep(30);
        }
        return null;
    }

    private static void printNew(int from) {
        List<String> all = snapshot();
        for (int i = from; i < all.size(); i++) {
            System.out.println("<<< " + all.get(i));
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) {
            failed = true;
        }
    }

    public static void main(String[] args) throws Exception {
        String workDir = args[0];

        System.out.println("========== session boot ==========");
        boot(workDir);

        // ---- engine plays first (Black), like engineMoveFirst ----
        System.out.println();
        System.out.println("========== BEGIN (engine is Black) ==========");
        int mark = snapshot().size();
        send("BEGIN");
        String engFirst = awaitMove(mark, 30000);
        printNew(mark);
        System.out.println("engine first move = " + engFirst);
        if (engFirst == null) {
            System.out.println("cannot continue without the engine's first move");
            System.exit(1);
        }

        // ---- human replies 8,8 as White: the fixed playUserMove (re-sync the position before it) ----
        System.out.println();
        System.out.println("========== human answer: YXBOARD + TURN 8,8 ==========");
        mark = snapshot().size();
        send("INFO TIMEOUT_TURN 3000");
        send("INFO STRENGTH 100");
        sendBoard(engFirst + ",1");
        send("TURN 8,8");
        String engReply = awaitMove(mark, 30000);
        printNew(mark);
        System.out.println("engine reply = " + engReply);
        if (engReply == null) {
            System.out.println("cannot continue without the engine's reply");
            System.exit(1);
        }

        // The app's position after these three moves; the engine's own board holds the same
        // three stones because it applies its own search results.
        String[] appPosition = {engFirst + ",1", "8,8,2", engReply + ",1"};

        // ---- the hint: YXBOARD + TIMEOUT_TURN 2000 + YXNBEST 1, exactly MainViewModel.hint ----
        System.out.println();
        System.out.println("========== hint (YXNBEST 1, TIMEOUT_TURN 2000) ==========");
        mark = snapshot().size();
        send("INFO TIMEOUT_TURN 2000");
        send("INFO STRENGTH 100");
        sendBoard(appPosition);
        send("YXNBEST 1");
        String hint = awaitMove(mark, 30000);
        printNew(mark);
        System.out.println("hint move = " + hint);
        if (hint == null) {
            System.out.println("hint search produced no move line, cannot continue");
            System.exit(1);
        }
        String hintPoint = firstCoord(hint);
        System.out.println("hinted point the user would play (bestMoves.first) = " + hintPoint);

        // ---- Case A: the current app behaviour - TURN straight at the hinted point ----
        System.out.println();
        System.out.println("========== case A: TURN " + hintPoint + " with no re-sync (current app) ==========");
        mark = snapshot().size();
        send("INFO TIMEOUT_TURN 3000");
        send("INFO STRENGTH 100");
        send("TURN " + hintPoint);
        String caseA = awaitMoveOrError(mark, 6000);
        printNew(mark);
        System.out.println("case A reply = " + caseA);
        check("case A reproduces the drop: no move line, ERROR seen", caseA == null && hasError(mark));

        // ---- Case B: the fix - re-sync the position *before* the move, then TURN ----
        System.out.println();
        System.out.println("========== case B: YXBOARD + TURN " + hintPoint + " (fixed) ==========");
        mark = snapshot().size();
        sendBoard(appPosition);
        send("INFO TIMEOUT_TURN 3000");
        send("INFO STRENGTH 100");
        send("TURN " + hintPoint);
        String caseB = awaitMove(mark, 30000);
        printNew(mark);
        System.out.println("case B reply = " + caseB);
        check("case B answers after the re-sync", caseB != null && !hasError(mark));

        // ---- Case C: same re-sync on an empty board (human's first move as Black) ----
        System.out.println();
        System.out.println("========== case C: empty YXBOARD + TURN 7,7 (fresh session) ==========");
        send("END");
        Thread.sleep(800);
        boot(workDir);
        mark = snapshot().size();
        send("INFO TIMEOUT_TURN 3000");
        send("INFO STRENGTH 100");
        sendBoard();
        send("TURN 7,7");
        String caseC = awaitMove(mark, 30000);
        printNew(mark);
        System.out.println("case C reply = " + caseC);
        check("case C answers on a synced empty board", caseC != null && !hasError(mark));

        // ---- Case D: the stopAndAwait barrier - an in-flight hint search is stopped, then the
        //      re-synced TURN goes through (this is the fixed playUserMove when the user moves
        //      while a hint/analysis search is still running) ----
        System.out.println();
        System.out.println("========== case D: YXNBEST stopped by STOP, then YXBOARD + TURN ==========");
        String[] posD = {"7,7,1", caseC + ",2"};
        mark = snapshot().size();
        send("INFO TIMEOUT_TURN 30000"); // long limit: this search must not end on its own
        send("INFO STRENGTH 100");
        sendBoard(posD);
        send("YXNBEST 1");
        Thread.sleep(500);
        send("STOP");
        String stopped = awaitMove(mark, 15000);
        System.out.println("move line after STOP = " + stopped);
        check("case D: STOP brings the engine back to idle with a move line", stopped != null);

        if (stopped != null) {
            mark = snapshot().size();
            sendBoard(posD);
            send("INFO TIMEOUT_TURN 3000");
            send("INFO STRENGTH 100");
            send("TURN " + firstCoord(stopped));
            String caseD = awaitMove(mark, 30000);
            printNew(mark);
            System.out.println("case D reply = " + caseD);
            check("case D answers after stop + re-sync", caseD != null && !hasError(mark));
        }

        // ---- Case E: the multi-PV AI reply - YXBOARD (whole position, including the move that
        //      was just played) + YXNBEST N. The engine searches the side to move (= itself),
        //      reports N PV blocks live and must still print exactly one final move line, equal to
        //      PV0's first move, which it also applies to its own board. ----
        System.out.println();
        System.out.println("========== case E: YXBOARD + YXNBEST 3 (multi-PV reply) ==========");
        String[] posE = {"7,7,1", "8,8,2", "7,8,1"}; // black just played 7,8; white (engine) to move
        mark = snapshot().size();
        send("INFO TIMEOUT_TURN 3000");
        send("INFO STRENGTH 100");
        sendBoard(posE);
        send("YXNBEST 3");
        String caseE = awaitMove(mark, 30000);
        List<String> afterE = snapshot();
        int pvIndices = 0;
        String numPv = null;
        String lastPv0Bestline = null;
        boolean inPv0 = false;
        for (int i = mark; i < afterE.size(); i++) {
            String s = afterE.get(i).trim();
            if (s.startsWith("INFO PV ")) {
                inPv0 = s.equals("INFO PV 0");
                if (!s.endsWith("DONE")) {
                    pvIndices = Math.max(pvIndices, Integer.parseInt(s.substring(8).trim()) + 1);
                }
                continue;
            }
            if (s.startsWith("INFO NUMPV ")) {
                numPv = s.substring(11).trim();
            }
            // PV0 is refreshed every iteration: keep the newest one, which is what the engine plays
            if (inPv0 && s.startsWith("INFO BESTLINE ")) {
                lastPv0Bestline = s.substring(14).trim();
            }
        }
        System.out.println("case E reply = " + caseE);
        System.out.println("case E reported NUMPV = " + numPv + ", distinct PV indices = " + pvIndices
                + ", last PV0 first move = "
                + (lastPv0Bestline == null ? null : firstCoord(lastPv0Bestline)));
        check("case E: multi-PV reply reports 3 routes", "3".equals(numPv) && pvIndices == 3);
        check("case E: exactly one final move line, equal to the last PV0 first move",
                caseE != null && lastPv0Bestline != null && caseE.equals(firstCoord(lastPv0Bestline))
                        && !hasError(mark));

        send("END");
        Thread.sleep(500);
        System.out.println();
        System.out.println(failed ? "RESULT: FAIL" : "RESULT: PASS");
        System.exit(failed ? 1 : 0);
    }
}
