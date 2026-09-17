// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.ui.board

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圆屏几何不变量：四角交叉点在任何倍率下都必须能被看见、点到。
 * 这里不依赖 Android，纯算坐标。9/13/19 路各自验证。
 */
class BoardViewportTest {

    private val side = 466f          // 手表画布边长（方形）
    private val center = side / 2f
    private val circleRadius = side / 2f   // 圆屏可视半径

    private val sizes = listOf(9, 13, 19)

    private fun scales(size: Int) = listOf(
        BoardViewport.minScale(size),
        0.8f,
        BoardViewport.DEFAULT_SCALE,
        1.5f,
        2f,
        BoardViewport.MAX_SCALE,
    )

    @Test
    fun `corner intersection is visible at minimum scale`() {
        for (size in sizes) {
            val cell = BoardViewport.cellOf(side, size)
            val corner = BoardViewport.baseOf(0, side, size)
            val screen = BoardViewport.screenOf(corner, center, BoardViewport.minScale(size), 0f)
            val r = BoardViewport.radiusOf(screen, screen, center)
            // 连棋子半径一起算：角上的棋子也要完整落在圆屏内
            assertTrue(
                "size=$size min scale corner r=$r must fit inside $circleRadius (with stone)",
                r + cell * 0.44f <= circleRadius,
            )
        }
    }

    @Test
    fun `every intersection can be panned to the screen centre`() {
        for (size in sizes) for (scale in scales(size)) {
            val limit = BoardViewport.panLimit(side, size, scale)
            for (index in listOf(0, size - 1)) {
                val base = BoardViewport.baseOf(index, side, size)
                // screen == center 时所需平移：pan = -scale * (base - center)
                val needed = -scale * (base - center)
                assertTrue(
                    "size=$size scale=$scale index=$index needs |pan|=$needed but limit is $limit",
                    kotlin.math.abs(needed) <= limit + 0.001f,
                )
            }
        }
    }

    @Test
    fun `corner can be brought inside the circle at every scale`() {
        for (size in sizes) for (scale in scales(size)) {
            val limit = BoardViewport.panLimit(side, size, scale)
            val base = BoardViewport.baseOf(0, side, size)
            // 把角点推到允许的极限位置
            val pan = (-scale * (base - center)).coerceIn(-limit, limit)
            val screen = BoardViewport.screenOf(base, center, scale, pan)
            val r = BoardViewport.radiusOf(screen, screen, center)
            assertTrue("size=$size scale=$scale corner r=$r must be inside $circleRadius", r <= circleRadius)
        }
    }

    @Test
    fun `pan limit grows with zoom and covers board reach`() {
        for (size in sizes) {
            var previous = 0f
            for (scale in scales(size)) {
                val limit = BoardViewport.panLimit(side, size, scale)
                assertTrue(
                    "size=$size pan limit must grow with scale ($scale): $limit <= $previous",
                    limit > previous,
                )
                previous = limit
            }
        }
    }

    @Test
    fun `minimum scale shrinks whole board for every size`() {
        for (size in sizes) {
            val min = BoardViewport.minScale(size)
            assertTrue("size=$size min scale $min should be < 1", min < 1f)
            assertTrue("size=$size min scale $min should be > 0.5", min > 0.5f)
        }
    }
}
