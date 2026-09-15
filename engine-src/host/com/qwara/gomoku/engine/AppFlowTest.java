package com.qwara.gomoku.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Replays the command sequence RapfiEngine actually issues, to confirm the JNI bridge supports the
 * app's real usage: session boot with HASH_SIZE, an engine-first game, user moves, and unlimited
 * analysis with YXNBEST plus STOP.
 */
public final class AppFlowTest {

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

    private static void send(String cmd) {
        System.out.println(">>> " + cmd);
        RapfiNative.write(cmd);
    }

    /** Wait for a final move line appearing at or after `from`. */
    private static String awaitMove(int from, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<String> all = snapshot();
            for (int i = from; i < all.size(); i++) {
                String s = all.get(i).trim();
                if (s.matches("^-?\\d+,-?\\d+( -?\\d+,-?\\d+)*$")) {
                    return s;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    /** Wait until an INFO PV block with the given DEPTH has been fully emitted. */
    private static boolean awaitDepth(int from, int depth, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<String> all = snapshot();
            for (int i = from; i < all.size(); i++) {
                if (all.get(i).equals("INFO DEPTH " + depth)) {
                    return true;
                }
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static void printNew(int from) {
        List<String> all = snapshot();
        for (int i = from; i < all.size(); i++) {
            System.out.println("<<< " + all.get(i));
        }
    }

    public static void main(String[] args) throws Exception {
        String workDir = args[0];

        // ---- session boot, exactly like RapfiEngine.startNewGame ----
        System.out.println("========== session boot (as RapfiEngine.startNewGame) ==========");
        RapfiNative.start(workDir);
        startReader();
        send("YXSHOWINFO");
        send("INFO THREAD_NUM 1");
        send("INFO PONDERING 0");
        send("INFO SHOW_DETAIL 2");
        send("INFO RULE 0");
        send("INFO TIMEOUT_MATCH 100000000");
        send("INFO TIMEOUT_TURN 1500");
        send("INFO TIME_LEFT 100000000");
        send("INFO HASH_SIZE 16384");
        send("START 15");
        Thread.sleep(800);
        int mark = snapshot().size();
        printNew(0);

        // ---- engine plays first ----
        System.out.println();
        System.out.println("========== engineMoveFirst (BEGIN) ==========");
        mark = snapshot().size();
        send("BEGIN");
        String engFirst = awaitMove(mark, 20000);
        printNew(mark);
        System.out.println("engine first move = " + engFirst);

        // ---- user replies, engine answers ----
        System.out.println();
        // The engine just took 7,7 as black, so the user answers on a free point.
        System.out.println("========== playUserMove (TURN 8,8) ==========");
        mark = snapshot().size();
        send("INFO TIMEOUT_TURN 1500");
        send("TURN 8,8");
        String engReply = awaitMove(mark, 20000);
        System.out.println("engine reply = " + engReply);

        // ---- takeback ----
        System.out.println();
        System.out.println("========== takeback ==========");
        mark = snapshot().size();
        send("TAKEBACK 0,0");
        Thread.sleep(500);
        printNew(mark);

        // ---- unlimited analysis: YXBOARD + YXNBEST, then STOP ----
        System.out.println();
        System.out.println("========== syncAndAnalyze (YXBOARD + YXNBEST 3) ==========");
        send("INFO TIMEOUT_TURN 0");
        send("YXBOARD");
        send("7,7,1");
        send("8,8,2");
        send("7,8,1");
        send("DONE");
        mark = snapshot().size();
        send("YXNBEST 3");
        boolean reachedDepth8 = awaitDepth(mark, 8, 25000);
        System.out.println("analysis reached depth 8 = " + reachedDepth8);
        System.out.println("(suppressing the per-depth stream, showing the last PV blocks)");
        List<String> mid = snapshot();
        int showFrom = Math.max(mark, mid.size() - 42);
        for (int i = showFrom; i < mid.size(); i++) {
            System.out.println("<<< " + mid.get(i));
        }

        mark = snapshot().size();
        send("STOP");
        String stopped = awaitMove(mark, 15000);
        printNew(mark);
        System.out.println("final move after STOP = " + stopped);

        // ---- queryForbid (renju only) ----
        System.out.println();
        System.out.println("========== queryForbid (YXSHOWFORBID, renju) ==========");
        mark = snapshot().size();
        send("END");
        Thread.sleep(800);
        RapfiNative.start(workDir);
        startReader();
        send("YXSHOWINFO");
        send("INFO SHOW_DETAIL 2");
        send("INFO THREAD_NUM 1");
        send("INFO RULE 4");
        send("START 15");
        send("YXBOARD");
        send("7,7,1");
        send("8,8,2");
        send("7,8,1");
        send("DONE");
        mark = snapshot().size();
        send("YXSHOWFORBID");
        Thread.sleep(1500);
        printNew(mark);

        send("END");
        Thread.sleep(500);
        System.out.println();
        System.out.println("done. total lines = " + snapshot().size());
        System.exit(0);
    }
}
