package com.qwara.gomoku.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenjuRulesTest {

    private fun boardWith(black: List<Pair<Int, Int>>, white: List<Pair<Int, Int>> = emptyList()): Board {
        val b = Board()
        // 按任意顺序摆黑子与白子（仅供禁手判定，不模拟真实行棋顺序时需注意黑白交替）。
        // 为安全起见，此处约定传入坐标已满足黑=候选方，白子仅作阻挡。
        black.forEach { b.place(it.first, it.second, Board.Color.BLACK) }
        white.forEach { b.place(it.first, it.second, Board.Color.WHITE) }
        return b
    }

    @Test
    fun `double three is forbidden`() {
        // 黑在 (7,8) 同时形成竖向与横向活三
        val b = boardWith(
            black = listOf(7 to 7, 7 to 9, 6 to 8, 8 to 8),
        )
        assertEquals(RenjuRules.Forbidden.DOUBLE_THREE, RenjuRules.check(b, 7, 8))
    }

    @Test
    fun `live four alone is not forbidden`() {
        // 竖向 ".XXXX." 的活四只算一个四
        val b = boardWith(black = listOf(7 to 6, 7 to 7, 7 to 9))
        assertEquals(RenjuRules.Forbidden.NONE, RenjuRules.check(b, 7, 8))
    }

    @Test
    fun `double four is forbidden`() {
        // 竖向活四 + 横向活四
        val b = boardWith(
            black = listOf(7 to 6, 7 to 7, 7 to 9, 7 to 11, 5 to 8, 6 to 8, 8 to 8),
        )
        assertEquals(RenjuRules.Forbidden.DOUBLE_FOUR, RenjuRules.check(b, 7, 8))
    }

    @Test
    fun `overline is forbidden`() {
        // 已有 5 连，再延伸成 6 连 = 长连禁手
        val b = boardWith(black = listOf(7 to 5, 7 to 6, 7 to 7, 7 to 8, 7 to 9))
        assertEquals(RenjuRules.Forbidden.OVERLINE, RenjuRules.check(b, 7, 10))
    }

    @Test
    fun `four three is allowed`() {
        // 一四一三为胜势着法，合法
        val b = boardWith(black = listOf(7 to 7, 7 to 9, 7 to 10, 6 to 8, 8 to 8))
        assertEquals(RenjuRules.Forbidden.NONE, RenjuRules.check(b, 7, 8))
    }

    @Test
    fun `making five wins and is never forbidden`() {
        val b = boardWith(black = listOf(7 to 6, 7 to 7, 7 to 8, 7 to 9, 6 to 8, 8 to 8))
        // (7,10) 成五连，即使同时形成其他威胁也合法
        assertEquals(RenjuRules.Forbidden.NONE, RenjuRules.check(b, 7, 10))
    }

    @Test
    fun `blocked three is not counted`() {
        // 一端被白子挡住的“眠三”不构成三三禁手
        val b = boardWith(
            black = listOf(7 to 7, 7 to 9, 6 to 8, 8 to 8),
            white = listOf(7 to 6, 5 to 8),
        )
        // 竖向活三仍在（(7,6) 被挡后不是活三），横向也只剩一个活三 → 合法
        assertEquals(RenjuRules.Forbidden.NONE, RenjuRules.check(b, 7, 8))
    }

    @Test
    fun `dead four does not count`() {
        // 两端被挡的死四不算“四”
        val b = boardWith(
            black = listOf(6 to 8, 8 to 8, 9 to 8),
            white = listOf(5 to 8, 10 to 8),
        )
        assertEquals(RenjuRules.Forbidden.NONE, RenjuRules.check(b, 7, 8))
    }
}

class BoardTest {

    @Test
    fun `five in a row wins freestyle`() {
        val b = Board()
        var result: Board.PlaceResult? = null
        for (x in 4..8) result = b.place(x, 7, Board.Color.BLACK)
        assertTrue(result!!.win)
        assertTrue(b.checkWin(8, 7, Board.Color.BLACK, Rule.FREESTYLE))
    }

    @Test
    fun `overline does not win for black in renju`() {
        val b = Board()
        // 黑六连：renju 规则下黑棋长连不算胜
        for (x in 4..9) b.place(x, 7, Board.Color.BLACK)
        assertFalse(b.checkWin(9, 7, Board.Color.BLACK, Rule.RENJU))
        assertTrue(b.checkWin(9, 7, Board.Color.BLACK, Rule.FREESTYLE))
    }

    @Test
    fun `overline wins for white in renju`() {
        val b = Board()
        for (x in 4..9) b.place(x, 7, Board.Color.WHITE)
        assertTrue(b.checkWin(9, 7, Board.Color.WHITE, Rule.RENJU))
    }

    @Test
    fun `undo removes stone`() {
        val b = Board()
        b.place(7, 7, Board.Color.BLACK)
        val m = b.undo()
        assertEquals(Board.Move(7, 7, Board.Color.BLACK), m)
        assertEquals(Board.Color.EMPTY, b.get(7, 7))
        assertEquals(0, b.moveCount)
    }
}
