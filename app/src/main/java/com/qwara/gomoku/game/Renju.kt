// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.gomoku.game

/**
 * 连珠（Renju）规则禁手判定：黑棋的三三、四四、长连禁手；白棋无禁手。
 * 判定基于“假想落子”——调用前需保证 (x,y) 为空且轮到黑棋。
 *
 * 按标准定义实现，而不是按局部形状模式匹配：
 *  - 四：四颗黑子，再落一子恰好成五（含跳四 XX_XX / X_XXX / XXX_X 等断点形状）；
 *  - 活四：四连黑子两端皆空，且两端各补一子都能成五（不能变成长连）；
 *  - 活三：再落一子可成活四的三。
 * 长连（>=6）优先于三/四；恰好五连优先于长连（与 Rapfi 的 F5 优先一致）。
 */
object RenjuRules {

    /** (dx, dy) 四个方向 */
    private val DIRS = arrayOf(
        intArrayOf(1, 0),
        intArrayOf(0, 1),
        intArrayOf(1, 1),
        intArrayOf(1, -1),
    )

    enum class Forbidden { NONE, DOUBLE_THREE, DOUBLE_FOUR, OVERLINE }

    /**
     * 判断黑棋在 (x,y) 落子是否禁手。
     */
    fun check(board: Board, x: Int, y: Int): Forbidden {
        require(board.get(x, y) == Board.Color.EMPTY)

        var fours = 0                  // “四”的方向数（每个方向至多计一个）
        var openThrees = 0             // 活三的方向数
        var overline = false

        for ((dx, dy) in DIRS) {
            val (cells, center) = collectLine(board, x, y, dx, dy)
            val run = runLength(cells, center)

            // 恰好五连：胜，绝非禁手（长连不看这里，由下面的 OVERLINE 处理）
            if (run == 5) return Forbidden.NONE
            if (run >= 6) overline = true

            // 与 Rapfi 一致：一个方向若已成四，不再按活三重复计入
            if (countsAsFour(cells, center)) {
                fours++
            } else if (countsAsOpenThree(cells, center)) {
                openThrees++
            }
        }

        return when {
            overline -> Forbidden.OVERLINE
            fours >= 2 -> Forbidden.DOUBLE_FOUR
            openThrees >= 2 -> Forbidden.DOUBLE_THREE
            else -> Forbidden.NONE
        }
    }

    /**
     * 该方向是否形成“四”：某个含中心黑子的 5 连窗内恰有 4 黑 1 空，
     * 且在该空位补子后恰好成五（补子后变成长连的不算四）。
     */
    private fun countsAsFour(cells: IntArray, center: Int): Boolean {
        val n = cells.size
        for (i in 0..n - 5) {
            if (i > center || center > i + 4) continue
            var emptyIdx = -1
            var blacks = 0
            var ok = true
            for (j in i until i + 5) {
                when (cells[j]) {
                    1 -> blacks++
                    2 -> if (emptyIdx < 0) emptyIdx = j else ok = false
                    else -> ok = false
                }
                if (!ok) break
            }
            if (!ok || blacks != 4) continue
            cells[emptyIdx] = 1
            val run = runLength(cells, emptyIdx)
            cells[emptyIdx] = 2
            if (run == 5) return true
        }
        return false
    }

    /** 该方向是否形成“活三”：某个空位补子后，存在含中心与该空位的活四。 */
    private fun countsAsOpenThree(cells: IntArray, center: Int): Boolean {
        val n = cells.size
        // 成四时补的子必落在四连段内，故只需试中心附近 4 格内的空点
        for (e in maxOf(0, center - 3)..minOf(n - 1, center + 3)) {
            if (e == center || cells[e] != 2) continue
            cells[e] = 1
            val openFour = hasOpenFour(cells, center, e)
            cells[e] = 2
            if (openFour) return true
        }
        return false
    }

    /**
     * 假定 e 已补子，是否存在同时含 center 与 e 的 4 连黑段，
     * 其两端皆空、且两端各补一子都能恰好成五（外侧隔一格的己方子会把成五变成长连）。
     */
    private fun hasOpenFour(cells: IntArray, center: Int, e: Int): Boolean {
        val n = cells.size
        var j = 0
        while (j < n) {
            if (cells[j] != 1) {
                j++
                continue
            }
            var k = j
            while (k + 1 < n && cells[k + 1] == 1) k++
            if (k - j + 1 == 4 && j <= center && center <= k && j <= e && e <= k) {
                val before = if (j - 1 >= 0) cells[j - 1] else 0
                val after = if (k + 1 < n) cells[k + 1] else 0
                if (before == 2 && after == 2) {
                    val beyondBefore = if (j - 2 >= 0) cells[j - 2] else 0
                    val beyondAfter = if (k + 2 < n) cells[k + 2] else 0
                    if (beyondBefore != 1 && beyondAfter != 1) return true
                }
            }
            j = k + 1
        }
        return false
    }

    /** at 处的连续黑子段长度（at 本身须为黑）。 */
    private fun runLength(cells: IntArray, at: Int): Int {
        var lo = at
        while (lo - 1 >= 0 && cells[lo - 1] == 1) lo--
        var hi = at
        while (hi + 1 < cells.size && cells[hi + 1] == 1) hi++
        return hi - lo + 1
    }

    /** 收集过 (x,y) 沿 (dx,dy) 的整行；返回(格子数组, 中心下标)。格值：0=界外/白子 1=黑 2=空 */
    private fun collectLine(board: Board, x: Int, y: Int, dx: Int, dy: Int): Pair<IntArray, Int> {
        // 先向左上扩展到界
        var sx = x
        var sy = y
        var before = 0
        while (board.inBounds(sx - dx, sy - dy)) {
            sx -= dx
            sy -= dy
            before++
        }
        var ex = x
        var ey = y
        var after = 0
        while (board.inBounds(ex + dx, ey + dy)) {
            ex += dx
            ey += dy
            after++
        }
        val n = before + 1 + after
        val cells = IntArray(n)
        var cx = sx
        var cy = sy
        for (j in 0 until n) {
            cells[j] = when {
                j == before -> 1 // 假想落子视为黑
                board.get(cx, cy) == Board.Color.BLACK -> 1
                board.get(cx, cy) == Board.Color.EMPTY -> 2
                else -> 0 // 白子/界外封死
            }
            cx += dx
            cy += dy
        }
        return cells to before
    }
}
