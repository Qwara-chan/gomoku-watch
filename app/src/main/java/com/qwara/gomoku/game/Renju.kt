package com.qwara.gomoku.game

/**
 * 连珠（Renju）规则禁手判定：黑棋的三三、四四、长连禁手；白棋无禁手。
 * 判定基于“假想落子”——调用前需保证 (x,y) 为空且轮到黑棋。
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

        var fourCount = 0             // “四”的数量（活四两端成五点位相邻，计为一个四）
        var openThrees = 0            // 活三数量（六窗去重）

        for ((dx, dy) in DIRS) {
            // 沿方向收集整条线（以 (x,y) 为中心）
            val line = collectLine(board, x, y, dx, dy)
            val center = line.second
            val cells = line.first // 0=界外/对手(视作封死) 1=黑 2=空
            val n = cells.size

            // 连续黑子段扫描：五连（胜）与四
            var j = 0
            while (j < n) {
                if (cells[j] == 1) {
                    var k = j
                    while (k < n && cells[k] == 1) k++
                    val len = k - j
                    if (len == 5 && j <= center && center < k) {
                        return Forbidden.NONE // 五连优先，绝非禁手
                    }
                    // “四”= 恰含候选子的四连段，且至少一端可成五（死四不计）
                    if (len == 4 && j <= center && center < k) {
                        val leftOpen = j - 1 >= 0 && cells[j - 1] == 2
                        val rightOpen = k < n && cells[k] == 2
                        if (leftOpen || rightOpen) fourCount++
                    }
                    j = k
                } else {
                    j++
                }
            }

            // 6 连窗：活三检测
            for (i in 0..n - 6) {
                if (i + 5 < center || i > center) continue
                // 两端必须为空（界外或对手子视为封死）
                if (cells[i] != 2 || cells[i + 5] != 2) continue
                var black = 0
                for (j in i until i + 6) if (cells[j] == 1) black++
                if (black != 3) continue
                // 是否存在一个空位，填入后该窗出现“两端皆空的四连”
                for (e in i + 1 until i + 5) {
                    if (cells[e] != 2) continue
                    cells[e] = 1
                    if (hasOpenFour(cells, i)) {
                        openThrees++
                        break
                    }
                    cells[e] = 2
                }
            }
        }

        // 长连检测（全局，任一方向含中心的连续黑 >=6）
        var overline = false
        for ((dx, dy) in DIRS) {
            var count = 1
            count += countDir(board, x, y, dx, dy)
            count += countDir(board, x, y, -dx, -dy)
            if (count >= 6) { overline = true; break }
        }

        return when {
            overline -> Forbidden.OVERLINE
            fourCount >= 2 -> Forbidden.DOUBLE_FOUR
            openThrees >= 2 -> Forbidden.DOUBLE_THREE
            else -> Forbidden.NONE
        }
    }

    /** 六窗 [i, i+5] 内（两端已确认为空），填入后是否存在活四 */
    private fun hasOpenFour(cells: IntArray, i: Int): Boolean {
        // 找窗内 4 连黑且两端开放（此处只查窗内连续四子）
        var run = 0
        for (j in i until i + 6) {
            if (cells[j] == 1) {
                run++
                if (run == 4) {
                    val before = cells.getOrElse(j - 4) { 0 }
                    val after = cells.getOrElse(j + 1) { 0 }
                    if (before == 2 && after == 2) return true
                }
            } else run = 0
        }
        return false
    }

    /** 收集过 (x,y) 沿 (dx,dy) 的整行；返回(格子数组, 中心下标)。格值：0=黑? 见实现 */
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

    private fun countDir(board: Board, x: Int, y: Int, dx: Int, dy: Int): Int {
        var c = 0
        var nx = x + dx
        var ny = y + dy
        while (board.inBounds(nx, ny) && board.get(nx, ny) == Board.Color.BLACK) {
            c++
            nx += dx
            ny += dy
        }
        return c
    }
}
