// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全谱分析逐手的等待预算：首帧上报要等某个点攒够 500 次模拟，慢设备上远超固定窗口，
 * 所以预算必须能自己放宽（见 [ScanBudget] 的 KDoc）。
 */
class ScanBudgetTest {

    /** 首手不知道设备有多慢，用最宽的一档：这一手只作「这台设备多慢」的标定 */
    @Test
    fun `first ply gets the generous startup budget`() {
        assertEquals(12000, ScanBudget().next())
    }

    @Test
    fun `fast ply drops the budget to the floor`() {
        val b = ScanBudget()
        b.succeeded(700)
        assertEquals(8000, b.next())
    }

    @Test
    fun `slow ply widens the next budget`() {
        val b = ScanBudget()
        b.succeeded(5000)
        assertEquals(10000, b.next())
    }

    @Test
    fun `timeout doubles the budget`() {
        val b = ScanBudget()
        b.timedOut()
        assertEquals(24000, b.next())
    }

    @Test
    fun `budget never exceeds ceiling`() {
        val b = ScanBudget()
        repeat(8) { b.timedOut() }
        assertEquals(24000, b.next())
    }

    @Test
    fun `success after a timeout pulls the budget back`() {
        val b = ScanBudget()
        b.timedOut()
        assertEquals(24000, b.next())
        b.succeeded(1000)
        assertEquals(8000, b.next())
    }

    @Test
    fun `widened budget never drops below floor even with a tiny measurement`() {
        val b = ScanBudget()
        b.timedOut()
        b.succeeded(10)
        assertEquals(8000, b.next())
    }

    /** 慢设备（每手 5 秒）连扫时预算稳定在“实测 ×2”，不会一路翻倍到封顶 */
    @Test
    fun `steady slow device settles above the measured cost`() {
        val b = ScanBudget()
        repeat(5) { b.succeeded(5000) }
        assertEquals(10000, b.next())
    }

    /** 满负载实测 9.5 秒才拿到首帧：标定这一手给得下，之后按 2× 放宽 */
    @Test
    fun `heavily loaded device still fits inside the budgets`() {
        val b = ScanBudget()
        assertTrue(b.next() >= 9500)
        b.succeeded(9500)
        assertTrue(b.next() >= 19000)
    }
}
