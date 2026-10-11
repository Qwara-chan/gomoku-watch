// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.gomoku.engine

import com.qwara.gomoku.game.Board

/** 一路着法坐标（协议坐标：x 列，y 行，0 起始） */
typealias Pt = Pair<Int, Int>

/** 分析/搜索中的一条 PV 信息（对应 INFO PV 块） */
data class PvLine(
    val index: Int = 0,
    val depth: Int = 0,
    /** 选择性深度（SELDEPTH），通常大于 depth */
    val selDepth: Int = 0,
    /** 本次搜索的路数（NUMPV） */
    val numPv: Int = 0,
    val nodes: Long = 0,
    val totalNodes: Long = 0,
    val timeMs: Long = 0,
    val speed: Long = 0,
    val eval: Int = 0,
    val winRate: Float = Float.NaN,
    val moves: List<Pt> = emptyList(),
)

/** 引擎全局状态 */
data class EngineStatus(
    val phase: Phase = Phase.IDLE,
    /** 搜索结束后的最佳着法（可能多行：YXNBEST） */
    val bestMoves: List<Pt> = emptyList(),
    val pvLines: List<PvLine> = emptyList(),
    /**
     * [pvLines] 属于哪个局面：该局面的手数与该局面行棋方（-1 = 还没有局面）。
     * 停止搜索只把相位落回 IDLE、不清候选，而中断/取消（退后台、被扫描打断）随时可能发生，
     * 所以「现在在搜索吗」并不足以说明这批候选是哪个局面的——按手数写曲线时必须核对这里，
     * 否则会把上一次搜索（别的局面、别的行棋方）的胜率写到当前手数上。
     */
    val pvPly: Int = -1,
    val pvToMove: Board.Color = Board.Color.BLACK,
) {
    enum class Phase { IDLE, THINKING, ANALYZING }
}

/** 将杀分换算：eval >= 29500 表示将杀，数值 = 30000 - 距杀步数( ply ) */
object EngineValue {
    const val MATE = 30000
    const val MATE_IN_MAX = 29500

    fun isMate(eval: Int) = eval >= MATE_IN_MAX || eval <= -MATE_IN_MAX

    /** 返回 "M8" 形式的将杀距离（步数 = ply/2 向上取整），非将杀返回 null */
    fun mateText(eval: Int): String? {
        if (!isMate(eval)) return null
        val ply = MATE - kotlin.math.abs(eval)
        val moves = (ply + 1) / 2
        return if (eval > 0) "M$moves" else "-M$moves"
    }

    /**
     * 引擎的 WINRATE 是“行棋方胜率”；评估曲线统一按黑方胜率存储。
     * @param toMove 该局面的**真实**行棋方。不能用手数奇偶推断：分析页摆谱可以连摆同色子，
     *   翻转棋盘还能让白先行，奇偶一旦与实际不符，曲线会从某一手起整段取反。
     */
    fun blackWinRate(winRate: Float, toMove: Board.Color): Float =
        if (toMove == Board.Color.BLACK) winRate else 1f - winRate
}
