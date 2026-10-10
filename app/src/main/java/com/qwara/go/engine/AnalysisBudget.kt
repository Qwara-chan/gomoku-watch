// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine

/**
 * 全谱分析逐手的等待预算。
 *
 * pachi 的第一次候选上报要等某个点攒够 500 次模拟才吐第一行
 * （`uct/walk.c` 的 `uct_get_best_moves(..., 500)` 之后还有一句 `if (!best.n) return;`），
 * 耗时完全由设备算力决定：实测宿主 0.6s、模拟器 0.8~1.5s、模拟器满负载 9.5s、手表更慢。
 * 所以这里的预算只当**上限**兜底——数据一到就收手，正常设备给再宽也不会变慢。
 *
 * 规则：首手用 [firstMs]（这时还不知道这台设备有多慢，给足）；此后按实测最慢一手放宽
 * [factor] 倍（不低于 [floorMs]）；某一手耗光预算仍无上报则翻倍，[ceilingMs] 封顶。
 * 纯逻辑，便于 JVM 单测。
 */
class ScanBudget(
    private val firstMs: Int = 12000,
    private val floorMs: Int = 8000,
    private val ceilingMs: Int = 24000,
    private val factor: Double = 2.0,
) {
    private var budgetMs: Int = firstMs

    /** 下一手的等待上限（毫秒） */
    fun next(): Int = budgetMs

    /** 允许的最宽等待（毫秒）：重试空洞时直接给到这一档——那一手已经用较窄的窗口失败过一次 */
    fun ceiling(): Int = ceilingMs

    /** 该手在第 [elapsedMs] 毫秒拿到首帧：按实测放宽，快的时候会收回 [floorMs] */
    fun succeeded(elapsedMs: Int) {
        budgetMs = maxOf((elapsedMs * factor).toInt(), floorMs).coerceAtMost(ceilingMs)
    }

    /** 该手耗光预算仍无上报：翻倍再试，封顶 [ceilingMs] */
    fun timedOut() {
        budgetMs = (budgetMs * 2).coerceAtMost(ceilingMs)
    }
}

/**
 * 扫描目标：曲线里还缺评估的手数（全谱分析的「补缺」就是把它扫完）。
 *
 * 补缺而不是整条重算，是手表上能不能扫完的关键：每手要等引擎攒够 500 次模拟，
 * 中断（熄屏、取消）后从头再来的代价是几分钟；而 [holes] 让那些已经算出来的手数原地保留。
 */
object ScanPlan {
    /** 1..[total] 中尚无评估值（缺键或 NaN）的手数，升序 */
    fun holes(curve: Map<Int, Float>, total: Int): List<Int> =
        (1..total).filter { k -> curve[k]?.let { !it.isNaN() } != true }
}
