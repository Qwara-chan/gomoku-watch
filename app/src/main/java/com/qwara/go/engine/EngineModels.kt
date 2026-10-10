// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine

import com.qwara.go.game.GoBoard

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
    /**
     * [pvLines] 属于哪个局面：该局面的手数与该局面行棋方（-1 = 还没有局面）。
     * 停止分析只把相位落回 IDLE、不清候选，而中断/取消（退后台、连点提示）随时可能发生，
     * 所以「现在在搜索吗」并不足以说明这两行候选是哪个局面的——按手数写曲线时必须核对这里，
     * 否则会把上一次分析（别的局面、别的行棋方）的胜率写到当前手数上。
     */
    val pvPly: Int = -1,
    val pvToMove: GoBoard.Color = GoBoard.Color.BLACK,
) {
    enum class Phase { IDLE, THINKING, ANALYZING }
}

object EngineValue {
    /**
     * 引擎的 winrate 是“行棋方胜率”；评估曲线统一按黑方胜率存储。
     * @param toMove 该局面的**真实**行棋方。不能用手数奇偶推断：分析页摆谱可以连摆同色子，
     *   翻转棋盘还能让白先行，奇偶一旦与实际不符，曲线会从某一手起整段取反。
     */
    fun blackWinRate(winRate: Float, toMove: GoBoard.Color): Float =
        if (toMove == GoBoard.Color.BLACK) winRate else 1f - winRate
}

/**
 * 提示点选取。lz-analyze 不像 lz-genmove_analyze 那样给最终着法行（"play x"），
 * 首选点只能从主变例（order 最小的一路）首项取；[EngineStatus.bestMoves] 只有 genmove 路径会填，
 * 留作兜底。pass/resign 用负坐标哨兵表示，必须剔除。
 */
object HintPoint {
    fun choose(bestMoves: List<Pt>, pvLines: List<PvLine>): Pt? =
        bestMoves.firstOrNull { it.first >= 0 }
            ?: pvLines.minByOrNull { it.index }?.moves?.firstOrNull { it.first >= 0 }
}
