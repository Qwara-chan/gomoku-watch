// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.gomoku.ui.board

/**
 * 圆屏下的棋盘视图几何（纯函数，无 Android 依赖，便于单测）。
 *
 * 约定：基准坐标下棋盘铺满整个方形画布（边长 side，共 size 路），
 * 屏幕位置 = center + scale * (base - center) + pan；
 * 圆屏可视区是以画布中心为圆心、半径 = side/2 的圆。
 *
 * 关键不变量（见 BoardViewportTest）：在 [MIN_SCALE, MAX_SCALE] 内，
 * 任意交叉点都能被平移到屏幕中心，因此四个角点在圆屏下也始终可达。
 */
internal object BoardViewport {

    /**
     * 最小放大倍率：15x15 棋盘缩到圆屏内接正方形以内，
     * 四个角点连同棋子都落在圆屏可视范围内——圆屏下唯一"一眼看全棋盘"的倍率。
     */
    const val MIN_SCALE = 0.66f

    /** 最大放大倍率：查看局部棋形 */
    const val MAX_SCALE = 3f

    /** 进入棋盘 / 复位时的倍率：铺满屏幕的全局视图 */
    const val DEFAULT_SCALE = 1f

    fun cellOf(side: Float, size: Int): Float = side / size

    /** 第 index 路交叉点在基准坐标下的位置（0 起始） */
    fun baseOf(index: Int, side: Float, size: Int): Float =
        cellOf(side, size) / 2f + index * cellOf(side, size)

    /**
     * 平移上限：把最远的交叉点（四角）移到屏幕中心所需的距离，
     * 即"棋盘中心到角点距离 × 缩放"。
     */
    fun panLimit(side: Float, size: Int, scale: Float): Float =
        (side - cellOf(side, size)) / 2f * scale

    /** 某条轴上的屏幕坐标 */
    fun screenOf(base: Float, center: Float, scale: Float, pan: Float): Float =
        center + scale * (base - center) + pan

    /** 某点到画布中心的距离（用于判断是否落在圆屏可视区内） */
    fun radiusOf(x: Float, y: Float, center: Float): Float =
        kotlin.math.hypot(x - center, y - center)
}
