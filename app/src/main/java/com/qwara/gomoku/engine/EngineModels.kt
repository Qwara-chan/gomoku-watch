package com.qwara.gomoku.engine

/** 一路着法坐标（协议坐标：x 列，y 行，0 起始） */
typealias Pt = Pair<Int, Int>

/** 分析/搜索中的一条 PV 信息（对应 INFO PV 块） */
data class PvLine(
    val index: Int = 0,
    val depth: Int = 0,
    val selDepth: Int = 0,
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
    /** 连珠规则下黑棋禁手点（由 YXSHOWFORBID 返回） */
    val forbidPoints: Set<Pt> = emptySet(),
    val engineInfo: String = "",
) {
    enum class Phase { IDLE, THINKING, ANALYZING }

    val primaryPv: PvLine? get() = pvLines.firstOrNull()
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
}
