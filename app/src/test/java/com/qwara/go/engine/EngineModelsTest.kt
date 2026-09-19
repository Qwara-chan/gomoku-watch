// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 胜率换算：引擎给的是行棋方胜率，曲线统一按黑方存；偶数手黑方行棋。 */
class EngineValueTest {

    @Test
    fun `even ply is black to move so the rate is kept`() {
        assertEquals(0.61f, EngineValue.blackWinRate(0.61f, 0), 0f)
        assertEquals(0.61f, EngineValue.blackWinRate(0.61f, 4), 0f)
    }

    @Test
    fun `odd ply is white to move so the rate is mirrored`() {
        assertEquals(0.39f, EngineValue.blackWinRate(0.61f, 1), 0f)
        assertEquals(0.39f, EngineValue.blackWinRate(0.61f, 3), 0f)
    }

    /** pass 也占一手：行棋方只看总手数奇偶，与 pass 无关 */
    @Test
    fun `passes keep alternating the side to move`() {
        // 第 2 手是白棋 pass → 轮到黑棋，胜率原样；第 3 手是黑棋 pass → 轮到白棋，取反
        assertEquals(0.25f, EngineValue.blackWinRate(0.25f, 2), 0f)
        assertEquals(0.75f, EngineValue.blackWinRate(0.25f, 3), 0f)
    }
}

/** 提示点：lz-analyze 没有 "play x" 行，首选点只能取主变例；genmove 留下的 bestMoves 兜底。 */
class HintPointTest {

    private fun pv(order: Int, vararg moves: Pt) = PvLine(index = order, moves = moves.toList())

    @Test
    fun `primary pv line wins when there is no genmove result`() {
        val lines = listOf(pv(1, 5 to 5), pv(0, 3 to 3))
        assertEquals(3 to 3, HintPoint.choose(emptyList(), lines))
    }

    @Test
    fun `bestMoves is used when present`() {
        assertEquals(7 to 7, HintPoint.choose(listOf(7 to 7), listOf(pv(0, 3 to 3))))
    }

    @Test
    fun `pass sentinel in bestMoves falls through to the pv line`() {
        assertEquals(3 to 3, HintPoint.choose(listOf(-1 to -1), listOf(pv(0, 3 to 3))))
    }

    @Test
    fun `resign sentinel is never chosen`() {
        assertNull(HintPoint.choose(listOf(-2 to -2), emptyList()))
    }

    @Test
    fun `nothing to suggest returns null`() {
        assertNull(HintPoint.choose(emptyList(), listOf(pv(0))))
    }
}
