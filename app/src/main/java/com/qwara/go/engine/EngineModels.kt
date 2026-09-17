// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine

/** 一路着法坐标（App 内部坐标：x 列，y 行，0 起始）；pass = -1,-1，resign = -2,-2（见 PachiEngine） */
typealias Pt = Pair<Int, Int>

/** 分析/搜索中的一条候选变化（对应 lz 输出的一路 info move） */
data class PvLine(
    val index: Int = 0,
    /** 模拟数（visits） */
    val nodes: Long = 0,
    /** 行棋方胜率（0..1，来自 winrate 千分值 / 10000） */
    val winRate: Float = Float.NaN,
    /** 首着 + 后续 pv 坐标 */
    val moves: List<Pt> = emptyList(),
)

/** 引擎全局状态 */
data class EngineStatus(
    val phase: Phase = Phase.IDLE,
    /** 搜索结束后的最佳着（通常一路；pass/resign 用哨兵坐标表示） */
    val bestMoves: List<Pt> = emptyList(),
    val pvLines: List<PvLine> = emptyList(),
) {
    enum class Phase { IDLE, THINKING, ANALYZING }
}

object EngineValue {
    /**
     * 引擎的 winrate 是“行棋方胜率”；评估曲线统一按黑方胜率存储。
     * @param ply 该局面已落子数（偶数时黑方行棋）
     */
    fun blackWinRate(winRate: Float, ply: Int): Float =
        if (ply % 2 == 0) winRate else 1f - winRate
}
