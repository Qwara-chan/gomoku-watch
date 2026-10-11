// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.gomoku.engine

import com.qwara.gomoku.game.Board
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 胜率换算：引擎给的是行棋方胜率，曲线统一按黑方存。
 * 换算只认**真实**行棋方（摆谱可连摆同色子、翻转棋盘可让白先行），手数奇偶推断会整段取反。
 */
class EngineValueTest {

    @Test
    fun `black to move keeps the rate`() {
        assertEquals(0.61f, EngineValue.blackWinRate(0.61f, Board.Color.BLACK), 0f)
    }

    @Test
    fun `white to move mirrors the rate`() {
        assertEquals(0.39f, EngineValue.blackWinRate(0.61f, Board.Color.WHITE), 0f)
    }

    @Test
    fun `non alternating placements still convert by the real colour`() {
        // 白棋行棋、却处在第 2 手（黑黑连摆的摆谱局面）：按奇偶会原样保留，正确的必须取反
        assertEquals(0.75f, EngineValue.blackWinRate(0.25f, Board.Color.WHITE), 0f)
    }
}

/** 扫描目标：曲线里缺评估的手数决定「补缺」要扫哪些手。 */
class ScanPlanTest {

    @Test
    fun `a complete curve has no holes`() {
        val curve = (1..5).associateWith { 0.5f }
        assertEquals(emptyList<Int>(), ScanPlan.holes(curve, 5))
    }

    @Test
    fun `missing keys are reported in ascending order`() {
        val curve = mapOf(1 to 0.5f, 3 to 0.4f, 5 to 0.6f)
        assertEquals(listOf(2, 4), ScanPlan.holes(curve, 5))
    }

    @Test
    fun `NaN counts as missing`() {
        val curve = mapOf(1 to Float.NaN, 2 to 0.5f)
        assertEquals(listOf(1), ScanPlan.holes(curve, 2))
    }

    /** 曲线里比当前手数更远的手数（悔棋后又摆回的新分支）不属于本次扫描范围 */
    @Test
    fun `plies beyond the current move count are ignored`() {
        val curve = mapOf(1 to 0.5f, 9 to 0.5f)
        assertEquals(listOf(2, 3), ScanPlan.holes(curve, 3))
    }

    @Test
    fun `an empty curve wants every ply`() {
        assertEquals(listOf(1, 2, 3), ScanPlan.holes(emptyMap(), 3))
    }
}
