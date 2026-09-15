// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

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

    @Test
    fun `gapped four counts toward double four`() {
        // 横向 XX_XX：补 (7,7) 即五连，属“四”；纵向 (9,5)-(9,8) 为活四 → 四四禁手
        val b = boardWith(
            black = listOf(5 to 7, 6 to 7, 8 to 7, 9 to 5, 9 to 6, 9 to 8),
        )
        assertEquals(RenjuRules.Forbidden.DOUBLE_FOUR, RenjuRules.check(b, 9, 7))
    }

    @Test
    fun `four plus open three is allowed`() {
        // 横向 (4,7)(6,7)(8,7)+候选：补 (5,7) 成五，该方向是“四”；
        // 纵向 (7,5)(7,6)+候选是活三。四三为胜势着法，合法。
        val b = boardWith(black = listOf(4 to 7, 6 to 7, 8 to 7, 7 to 5, 7 to 6))
        assertEquals(RenjuRules.Forbidden.NONE, RenjuRules.check(b, 7, 7))
    }

    @Test
    fun `jump three counts toward double three`() {
        // 横向 (5,7)(6,7) + 候选 (8,7) 为跳三（补 (7,7) 成活四）；
        // 纵向 (8,5)(8,6) + 候选为直活三 → 三三禁手
        val b = boardWith(black = listOf(5 to 7, 6 to 7, 8 to 5, 8 to 6))
        assertEquals(RenjuRules.Forbidden.DOUBLE_THREE, RenjuRules.check(b, 8, 7))
    }

    @Test
    fun `gapped four family X_XXX is counted`() {
        // 横向 (5,7)(7,7)(8,7)+候选 (9,7)：X_XXX，补 (6,7) 成五 → 四；
        // 纵向 (9,5)(9,6)(9,8)+候选 (9,7) 为连四 → 四四禁手
        val b = boardWith(
            black = listOf(5 to 7, 7 to 7, 8 to 7, 9 to 5, 9 to 6, 9 to 8),
        )
        assertEquals(RenjuRules.Forbidden.DOUBLE_FOUR, RenjuRules.check(b, 9, 7))
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

    /** 按给定颜色依次落子（Board.place 不校验轮次），供连线测试摆局面 */
    private fun boardWith(vararg stones: Triple<Int, Int, Board.Color>): Board {
        val b = Board()
        stones.forEach { b.place(it.first, it.second, it.third) }
        return b
    }

    private fun black(x: Int, y: Int) = Triple(x, y, Board.Color.BLACK)
    private fun white(x: Int, y: Int) = Triple(x, y, Board.Color.WHITE)

    @Test
    fun `win line lists five cells in order`() {
        val b = boardWith(*(4..8).map { black(it, 7) }.toTypedArray())
        assertEquals((4..8).map { it to 7 }, b.winLine(8, 7, Board.Color.BLACK))
    }

    @Test
    fun `win line covers all four directions`() {
        val vertical = boardWith(*(4..8).map { black(7, it) }.toTypedArray())
        assertEquals((4..8).map { 7 to it }, vertical.winLine(7, 6, Board.Color.BLACK))

        val diag = boardWith(*(4..8).map { black(it, it) }.toTypedArray())
        assertEquals((4..8).map { it to it }, diag.winLine(5, 5, Board.Color.BLACK))

        val anti = boardWith(*(4..8).map { black(it, 12 - it) }.toTypedArray())
        assertEquals((4..8).map { it to 12 - it }, anti.winLine(7, 5, Board.Color.BLACK))
    }

    @Test
    fun `win line is null without five`() {
        val four = boardWith(*(4..7).map { black(it, 7) }.toTypedArray())
        assertEquals(null, four.winLine(7, 7, Board.Color.BLACK))
        // 四连中已落子的点不是五连
        assertFalse(four.checkWin(7, 7, Board.Color.BLACK))
    }

    @Test
    fun `win line stops at opponent stone`() {
        val b = boardWith(
            black(4, 7), black(5, 7), black(6, 7), black(8, 7), black(9, 7),
            white(7, 7),
        )
        assertEquals(null, b.winLine(6, 7, Board.Color.BLACK))
    }

    @Test
    fun `freestyle overline line includes all six cells`() {
        val b = boardWith(*(4..9).map { black(it, 7) }.toTypedArray())
        assertEquals(6, b.winLine(9, 7, Board.Color.BLACK, Rule.FREESTYLE)?.size)
    }

    @Test
    fun `renju overline has no win line for black`() {
        val b = boardWith(*(4..9).map { black(it, 7) }.toTypedArray())
        assertEquals(null, b.winLine(9, 7, Board.Color.BLACK, Rule.RENJU))
        assertFalse(b.checkWin(9, 7, Board.Color.BLACK, Rule.RENJU))
    }

    @Test
    fun `white overline line includes all six cells in renju`() {
        val b = boardWith(*(4..9).map { white(it, 7) }.toTypedArray())
        assertEquals(6, b.winLine(9, 7, Board.Color.WHITE, Rule.RENJU)?.size)
    }

    @Test
    fun `exact five beats overline in another direction`() {
        // 竖向补子后成六连（长连禁手），横向恰好成五：F5 优先，仍判黑胜并给出横向五连
        val b = boardWith(
            black(7, 3), black(7, 4), black(7, 5), black(7, 6), black(7, 8),
            black(5, 7), black(6, 7), black(8, 7), black(9, 7),
            black(7, 7),
        )
        assertEquals((5..9).map { it to 7 }, b.winLine(7, 7, Board.Color.BLACK, Rule.RENJU))
    }

    @Test
    fun `win line of empty cell is null`() {
        val b = boardWith(*(4..8).map { black(it, 7) }.toTypedArray())
        assertEquals(null, b.winLine(3, 7, Board.Color.BLACK))
        assertEquals(null, b.winLine(8, 7, Board.Color.WHITE))
    }

    @Test
    fun `place applies board rule when judging win`() {
        // 连珠规则下黑棋长连不算胜：place() 必须按 Board.rule 判，而不是固定无禁手
        val renju = Board().apply { rule = Rule.RENJU }
        var result: Board.PlaceResult? = null
        for (x in 4..9) result = renju.place(x, 7, Board.Color.BLACK)
        assertFalse(result!!.win)

        val freestyle = Board().apply { rule = Rule.FREESTYLE }
        var free: Board.PlaceResult? = null
        for (x in 4..9) free = freestyle.place(x, 7, Board.Color.BLACK)
        assertTrue(free!!.win)
    }
}
