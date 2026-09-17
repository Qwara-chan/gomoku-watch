// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoBoardTest {

    private fun play(b: GoBoard, x: Int, y: Int, c: GoBoard.Color): GoBoard.PlaceResult =
        b.place(x, y, c)

    @Test
    fun `single stone capture in corner`() {
        val b = GoBoard(9)
        play(b, 0, 0, GoBoard.Color.BLACK)
        val r1 = play(b, 1, 0, GoBoard.Color.WHITE)
        assertTrue(r1.legal)
        val r2 = play(b, 0, 1, GoBoard.Color.WHITE)
        assertEquals(listOf(0 to 0), r2.captures)
        assertEquals(GoBoard.Color.EMPTY, b.get(0, 0))
        assertEquals(3, b.moveCount)
    }

    @Test
    fun `multi stone group capture`() {
        val b = GoBoard(9)
        // 黑两子横排 (3,3)(4,3)，白包围
        play(b, 3, 3, GoBoard.Color.BLACK)
        play(b, 4, 3, GoBoard.Color.BLACK)
        play(b, 3, 4, GoBoard.Color.WHITE)
        play(b, 4, 4, GoBoard.Color.WHITE)
        play(b, 3, 2, GoBoard.Color.WHITE)
        play(b, 4, 2, GoBoard.Color.WHITE)
        play(b, 2, 3, GoBoard.Color.WHITE)
        val r = play(b, 5, 3, GoBoard.Color.WHITE)
        assertEquals(2, r.captures.size)
        assertTrue(r.captures.containsAll(listOf(3 to 3, 4 to 3)))
        assertEquals(GoBoard.Color.EMPTY, b.get(3, 3))
        assertEquals(GoBoard.Color.EMPTY, b.get(4, 3))
    }

    @Test
    fun `occupied point is illegal`() {
        val b = GoBoard(9)
        play(b, 4, 4, GoBoard.Color.BLACK)
        val r = play(b, 4, 4, GoBoard.Color.WHITE)
        assertEquals(GoBoard.Illegal.OCCUPIED, r.illegal)
        assertEquals(1, b.moveCount)
    }

    @Test
    fun `suicide is illegal`() {
        val b = GoBoard(9)
        // 白把 (4,4) 四面包住，黑下进去是自杀
        play(b, 4, 3, GoBoard.Color.WHITE)
        play(b, 4, 5, GoBoard.Color.WHITE)
        play(b, 3, 4, GoBoard.Color.WHITE)
        play(b, 5, 4, GoBoard.Color.WHITE)
        val r = play(b, 4, 4, GoBoard.Color.BLACK)
        assertEquals(GoBoard.Illegal.SUICIDE, r.illegal)
        assertEquals(GoBoard.Color.EMPTY, b.get(4, 4))
    }

    @Test
    fun `capturing first avoids suicide`() {
        val b = GoBoard(9)
        // 黑 (4,4) 只剩一气，白 (4,5) 先提掉 → 合法且带一提
        play(b, 4, 4, GoBoard.Color.BLACK)
        play(b, 4, 3, GoBoard.Color.WHITE)
        play(b, 3, 4, GoBoard.Color.WHITE)
        play(b, 5, 4, GoBoard.Color.WHITE)
        val r = play(b, 4, 5, GoBoard.Color.WHITE)
        assertTrue(r.legal)
        assertEquals(listOf(4 to 4), r.captures)
    }

    /**
     * 教科书劫形：
     *   y=3: . . B . .
     *   y=4: . B W B .
     *   y=5: . W . W .
     *   y=6: . . W . .
     * 白单子 (4,4) 只剩 (4,5) 一气；黑 (4,5) 提白后自己也被白三面围住只剩 (4,4) 一气；
     * 白立即回提将还原提子前局面 → 劫争禁着。
     */
    private fun koBoard(): GoBoard {
        val k = GoBoard(9)
        k.place(3, 4, GoBoard.Color.BLACK)
        k.place(5, 4, GoBoard.Color.BLACK)
        k.place(4, 3, GoBoard.Color.BLACK)
        k.place(3, 5, GoBoard.Color.WHITE)
        k.place(5, 5, GoBoard.Color.WHITE)
        k.place(4, 6, GoBoard.Color.WHITE)
        val w = k.place(4, 4, GoBoard.Color.WHITE)
        assertTrue("setup white failed: ${w.illegal}", w.legal)
        return k
    }

    @Test
    fun `simple ko is forbidden`() {
        val k = koBoard()
        val capture = k.place(4, 5, GoBoard.Color.BLACK)
        assertTrue(capture.legal)
        assertEquals(listOf(4 to 4), capture.captures)
        // 白立即回提将还原提子前局面 → 劫争禁着
        assertEquals(GoBoard.Illegal.KO, k.place(4, 4, GoBoard.Color.WHITE).illegal)
        // 双方各停一手/走他处后劫消失，白可回提（只提黑 (4,5) 一子）
        k.pass(GoBoard.Color.WHITE)
        k.place(0, 0, GoBoard.Color.BLACK)
        val nowLegal = k.place(4, 4, GoBoard.Color.WHITE)
        assertTrue(nowLegal.legal)
        assertEquals(listOf(4 to 5), nowLegal.captures)
    }

    @Test
    fun `ko legality survives undo rebuild`() {
        val k = koBoard()
        k.place(4, 5, GoBoard.Color.BLACK) // 黑提白 (4,4)
        assertEquals(GoBoard.Illegal.KO, k.place(4, 4, GoBoard.Color.WHITE).illegal)
        // 悔掉黑提的那一手：哈希历史重建后 (4,4) 应仍被白占据
        k.undo()
        assertEquals(GoBoard.Illegal.OCCUPIED, k.place(4, 4, GoBoard.Color.WHITE).illegal)
        // 再悔一手（白 setup 子），劫判定应始终自洽
        k.undo()
        val r = k.place(4, 4, GoBoard.Color.WHITE)
        assertTrue(r.legal || r.illegal == GoBoard.Illegal.OCCUPIED)
    }

    @Test
    fun `pass increments count and tracks consecutive passes`() {
        val b = GoBoard(9)
        assertEquals(0, b.consecutivePasses)
        b.pass(GoBoard.Color.BLACK)
        assertEquals(1, b.consecutivePasses)
        b.place(3, 3, GoBoard.Color.WHITE)
        assertEquals(0, b.consecutivePasses)
        b.pass(GoBoard.Color.BLACK)
        b.pass(GoBoard.Color.WHITE)
        assertEquals(2, b.consecutivePasses)
        assertEquals(4, b.moveCount)
    }

    @Test
    fun `undo restores captured stones`() {
        val b = GoBoard(9)
        play(b, 0, 0, GoBoard.Color.BLACK)
        play(b, 1, 0, GoBoard.Color.WHITE)
        val captured = play(b, 0, 1, GoBoard.Color.WHITE)
        assertEquals(1, captured.captures.size)
        val undone = b.undo()
        assertEquals(GoBoard.Move(0, 1, GoBoard.Color.WHITE), undone)
        assertEquals(GoBoard.Color.BLACK, b.get(0, 0))
        assertEquals(GoBoard.Color.WHITE, b.get(1, 0))
        assertEquals(2, b.moveCount)
    }

    @Test
    fun `undo pass keeps board`() {
        val b = GoBoard(9)
        play(b, 3, 3, GoBoard.Color.BLACK)
        b.pass(GoBoard.Color.WHITE)
        b.undo()
        assertEquals(1, b.moveCount)
        assertEquals(0, b.consecutivePasses)
        assertEquals(GoBoard.Color.BLACK, b.get(3, 3))
    }

    @Test
    fun `score empty board with komi`() {
        val b = GoBoard(9)
        val s = b.score(5.5f)
        assertEquals(0f, s.blackPoints, 0.001f)
        assertEquals(5.5f, s.whitePoints, 0.001f)
        assertTrue(s.diff < 0)
    }

    @Test
    fun `score counts territory and stones`() {
        val b = GoBoard(9)
        // 黑占 (0..2, 0..2) 九宫格（8 子围出 1 目内空）+ 白一子 (8,8) 让外围成单官
        for (x in 0..2) for (y in 0..2) {
            if (x == 1 && y == 1) continue
            b.place(x, y, GoBoard.Color.BLACK)
        }
        b.place(8, 8, GoBoard.Color.WHITE)
        val s = b.score(0f)
        assertEquals(9f, s.blackPoints, 0.001f) // 8 子 + 1 目
        assertEquals(1, s.blackTerritory)
        assertEquals(1f, s.whitePoints, 0.001f)
        assertTrue(s.dame > 0)
    }

    @Test
    fun `score shared region is dame`() {
        val b = GoBoard(9)
        // 黑 (0,0) 白 (8,8)，中间空点与双方都相邻 → 单官
        b.place(0, 0, GoBoard.Color.BLACK)
        b.place(8, 8, GoBoard.Color.WHITE)
        val s = b.score(6.5f)
        assertTrue(s.dame > 0)
        assertEquals(1f, s.blackPoints, 0.001f)
        assertEquals(1f + 6.5f, s.whitePoints, 0.001f)
    }

    @Test
    fun `board sizes are independent`() {
        val b13 = GoBoard(13)
        val b19 = GoBoard(19)
        assertTrue(b13.inBounds(12, 12))
        assertFalse(b13.inBounds(13, 13))
        assertTrue(b19.inBounds(18, 18))
        b13.place(12, 12, GoBoard.Color.BLACK)
        b19.place(18, 18, GoBoard.Color.WHITE)
        assertEquals(GoBoard.Color.BLACK, b13.get(12, 12))
        assertEquals(GoBoard.Color.WHITE, b19.get(18, 18))
    }

    @Test
    fun `snapshot restore roundtrip`() {
        val b = GoBoard(9)
        b.place(2, 2, GoBoard.Color.BLACK)
        b.place(3, 3, GoBoard.Color.WHITE)
        val snap = b.snapshot()
        val moves = b.moves.toList()
        b.place(4, 4, GoBoard.Color.BLACK)
        b.restore(snap, moves)
        assertEquals(2, b.moveCount)
        assertEquals(GoBoard.Color.EMPTY, b.get(4, 4))
        // 恢复后规则行为一致（占用判定仍工作）
        assertEquals(GoBoard.Illegal.OCCUPIED, b.place(2, 2, GoBoard.Color.WHITE).illegal)
    }
}
