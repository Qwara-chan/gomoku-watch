// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.gomoku.engine

/**
 * 扫描目标：曲线里还缺评估的手数（全谱分析的「补缺」就是把它扫完）。
 *
 * 补缺而不是整条重算，是扫描能被中断的关键：中断（熄屏、取消、退后台）随时会发生，
 * 从头再来的代价是把手表上已经算出来的那些手数再算一遍；[holes] 让它们原地保留。
 * 纯逻辑，便于 JVM 单测。
 */
object ScanPlan {
    /** 1..[total] 中尚无评估值（缺键或 NaN）的手数，升序 */
    fun holes(curve: Map<Int, Float>, total: Int): List<Int> =
        (1..total).filter { k -> curve[k]?.let { !it.isNaN() } != true }
}
