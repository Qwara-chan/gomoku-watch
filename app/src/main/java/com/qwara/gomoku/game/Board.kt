// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.gomoku.game

/**
 * 五子棋棋盘核心逻辑：纯 Kotlin，无 Android 依赖。
 * 坐标系：x 列 0..size-1（从左到右），y 行 0..size-1（从上到下）。
 */
class Board(val size: Int = DEFAULT_SIZE) {

    /** 当前规则，影响胜负判定：连珠下黑棋长连不算胜 */
    var rule: Rule = Rule.FREESTYLE

    enum class Color(val value: Byte) {
        EMPTY(0), BLACK(1), WHITE(2);

        val opponent: Color
            get() = when (this) {
                BLACK -> WHITE
                WHITE -> BLACK
                EMPTY -> EMPTY
            }
    }

    data class Move(val x: Int, val y: Int, val color: Color)

    /** 0=空 1=黑 2=白，按 y*size+x 索引 */
    private val grid = ByteArray(size * size)

    /** 历史着法（含被悔棋移除后又重做的，仅当前序列） */
    val moves = mutableListOf<Move>()

    val moveCount: Int get() = moves.size

    fun inBounds(x: Int, y: Int) = x in 0 until size && y in 0 until size

    fun get(x: Int, y: Int): Color =
        if (inBounds(x, y)) Color.entries.first { it.value == grid[y * size + x] } else Color.EMPTY

    /** 返回刚下的子是否构成五连（含规则校验后的真实胜负） */
    fun place(x: Int, y: Int, color: Color): PlaceResult {
        require(inBounds(x, y)) { "out of bounds: $x,$y" }
        require(grid[y * size + x] == 0.toByte()) { "occupied: $x,$y" }
        grid[y * size + x] = color.value
        moves.add(Move(x, y, color))
        val win = checkWin(x, y, color, rule)
        return PlaceResult(win = win, draw = !win && isFull())
    }

    fun undo(): Move? {
        val m = moves.removeLastOrNull() ?: return null
        grid[m.y * size + m.x] = 0
        return m
    }

    fun clear() {
        grid.fill(0)
        moves.clear()
    }

    fun snapshot(): ByteArray = grid.copyOf()

    fun restore(snapshot: ByteArray, moveList: List<Move>) {
        require(snapshot.size == grid.size)
        System.arraycopy(snapshot, 0, grid, 0, grid.size)
        moves.clear()
        moves.addAll(moveList)
    }

    /** 以某点最后落子判断五连；连珠规则下黑棋长连(>=6)不算胜 */
    fun checkWin(x: Int, y: Int, color: Color, rule: Rule = Rule.FREESTYLE): Boolean =
        winLine(x, y, color, rule) != null

    /**
     * 返回经过 (x,y) 的完整连子（含该点与两端），按 (x,y) 升序排列（同一直线上即从一端到另一端）；
     * 未成五连、或连珠下黑棋长连（>=6，禁手不算胜）时返回 null。
     */
    fun winLine(x: Int, y: Int, color: Color, rule: Rule = Rule.FREESTYLE): List<Pair<Int, Int>>? {
        if (color == Color.EMPTY || !inBounds(x, y) || get(x, y) != color) return null
        // 遍历四个方向：恰好五连优先（F5），只有全部方向都不成五时才判长连禁手
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            val before = runCells(x, y, -dx, -dy, color)
            val after = runCells(x, y, dx, dy, color)
            val count = before.size + 1 + after.size
            if (count < 5) continue
            // count > 5：无禁手规则下长连也算胜；连珠下仅黑棋长连为禁手
            if (count > 5 && rule == Rule.RENJU && color == Color.BLACK) continue
            return (before.asReversed() + (x to y) + after)
                .sortedWith(compareBy({ it.first }, { it.second }))
        }
        return null
    }

    private fun runCells(x: Int, y: Int, dx: Int, dy: Int, color: Color): List<Pair<Int, Int>> {
        val cells = ArrayList<Pair<Int, Int>>()
        var nx = x + dx
        var ny = y + dy
        while (inBounds(nx, ny) && get(nx, ny) == color) {
            cells.add(nx to ny)
            nx += dx
            ny += dy
        }
        return cells
    }

    fun isFull(): Boolean = grid.all { it != 0.toByte() }

    data class PlaceResult(val win: Boolean, val draw: Boolean)

    companion object {
        const val DEFAULT_SIZE = 15
    }
}

enum class Rule { FREESTYLE, RENJU }
