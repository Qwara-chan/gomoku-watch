package com.qwara.gomoku.game

/**
 * 五子棋棋盘核心逻辑：纯 Kotlin，无 Android 依赖。
 * 坐标系：x 列 0..size-1（从左到右），y 行 0..size-1（从上到下）。
 */
class Board(val size: Int = DEFAULT_SIZE) {

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
        val win = checkWin(x, y, color)
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
    fun checkWin(x: Int, y: Int, color: Color, rule: Rule = Rule.FREESTYLE): Boolean {
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            var count = 1
            count += countDir(x, y, dx, dy, color)
            count += countDir(x, y, -dx, -dy, color)
            if (count >= 5) {
                return when {
                    rule == Rule.RENJU && color == Color.BLACK && count > 5 -> false // 长连禁手，黑不算胜
                    else -> true
                }
            }
        }
        return false
    }

    private fun countDir(x: Int, y: Int, dx: Int, dy: Int, color: Color): Int {
        var c = 0
        var nx = x + dx
        var ny = y + dy
        while (inBounds(nx, ny) && get(nx, ny) == color) {
            c++
            nx += dx
            ny += dy
        }
        return c
    }

    fun isFull(): Boolean = grid.all { it != 0.toByte() }

    data class PlaceResult(val win: Boolean, val draw: Boolean)

    companion object {
        const val DEFAULT_SIZE = 15
    }
}

enum class Rule { FREESTYLE, RENJU }
