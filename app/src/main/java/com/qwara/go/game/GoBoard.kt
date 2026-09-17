// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.game

/**
 * 围棋棋盘核心逻辑：纯 Kotlin，无 Android 依赖。中国规则（数子法）：子空皆地、
 * 自杀禁着、打劫禁着（单劫）、双方连续停一手终局。
 * 坐标系：x 列 0..size-1（从左到右），y 行 0..size-1（从上到下）。
 */
class GoBoard(val size: Int = DEFAULT_SIZE) {

    enum class Color(val value: Byte) {
        EMPTY(0), BLACK(1), WHITE(2);

        val opponent: Color
            get() = when (this) {
                BLACK -> WHITE
                WHITE -> BLACK
                EMPTY -> EMPTY
            }
    }

    /** 一手着法；x = -1 表示停一手（pass） */
    data class Move(val x: Int, val y: Int, val color: Color) {
        val isPass: Boolean get() = x < 0
    }

    enum class Illegal { OCCUPIED, SUICIDE, KO }

    /** 落子结果：captures 为本手提掉的对方棋子；illegal 非空时本手未生效 */
    data class PlaceResult(
        val captures: List<Pair<Int, Int>> = emptyList(),
        val illegal: Illegal? = null,
    ) {
        val legal: Boolean get() = illegal == null
    }

    /** 终局数子结果（中国规则：黑方子空 + 白方子空 + 贴目） */
    data class Score(
        val blackPoints: Float,
        val whitePoints: Float,
        val blackTerritory: Int,
        val whiteTerritory: Int,
        val dame: Int,
    ) {
        /** 黑胜目数（负值表示白胜），不含贴目的差由 points 已含 */
        val diff: Float get() = blackPoints - whitePoints
    }

    /** 0=空 1=黑 2=白，按 y*size+x 索引 */
    private val grid = ByteArray(size * size)

    /** 历史着法（含 pass），悔棋/复盘依赖 */
    val moves = mutableListOf<Move>()

    /** 每个局面（含 pass 后）的完整盘面向量哈希，用于打劫判定 */
    private val hashes = mutableListOf<Long>()

    val moveCount: Int get() = moves.size

    /** 双方连续 pass 次数；>=2 时棋局结束 */
    val consecutivePasses: Int
        get() {
            var n = 0
            for (i in moves.indices.reversed()) {
                if (moves[i].isPass) n++ else break
            }
            return n
        }

    fun inBounds(x: Int, y: Int) = x in 0 until size && y in 0 until size

    fun get(x: Int, y: Int): Color =
        if (inBounds(x, y)) Color.entries.first { it.value == grid[y * size + x] } else Color.EMPTY

    fun isLegal(x: Int, y: Int, color: Color): Illegal? {
        if (!inBounds(x, y)) return Illegal.OCCUPIED
        if (get(x, y) != Color.EMPTY) return Illegal.OCCUPIED
        val result = simulate(x, y, color)
        if (result == null) return Illegal.SUICIDE
        // 单劫：下完后的局面与对手上一手之前相同 → 禁着
        if (hashes.size >= 2 && result.second == hashes[hashes.size - 2]) return Illegal.KO
        return null
    }

    /** 落子；非法时返回 illegal 且盘面不变 */
    fun place(x: Int, y: Int, color: Color): PlaceResult {
        val illegal = isLegal(x, y, color)
        if (illegal != null) return PlaceResult(illegal = illegal)
        val (captures, hash) = simulate(x, y, color)!!
        grid[y * size + x] = color.value
        captures.forEach { grid[it.second * size + it.first] = 0 }
        moves.add(Move(x, y, color))
        hashes.add(hash)
        return PlaceResult(captures = captures)
    }

    /** 停一手 */
    fun pass(color: Color) {
        moves.add(Move(-1, -1, color))
        hashes.add(hashOf())
    }

    fun undo(): Move? {
        val m = moves.removeLastOrNull() ?: return null
        val rest = moves.toList()
        clear()
        rebuildFrom(rest)
        return m
    }

    fun clear() {
        grid.fill(0)
        moves.clear()
        hashes.clear()
    }

    fun snapshot(): ByteArray = grid.copyOf()

    fun restore(snapshot: ByteArray, moveList: List<Move>) {
        require(snapshot.size == grid.size)
        clear()
        rebuildFrom(moveList)
    }

    /** 从着法序列重建盘面与哈希（悔棋/复盘/恢复用；着法必须全部合法） */
    private fun rebuildFrom(moveList: List<Move>) {
        for (m in moveList) {
            if (m.isPass) {
                pass(m.color)
            } else {
                val r = playInternal(m)
                check(r.illegal == null) { "replay failed at $m: ${r.illegal}" }
            }
        }
    }

    fun isFull(): Boolean = grid.all { it != 0.toByte() }

    // ------------------------------------------------------------- 模拟与气

    /**
     * 模拟落子（含提子），返回 (被提棋子, 落子后哈希)；自杀（提完对方仍无气）返回 null。
     * 不修改盘面。
     */
    private fun simulate(x: Int, y: Int, color: Color): Pair<List<Pair<Int, Int>>, Long>? {
        val opponent = color.opponent
        grid[y * size + x] = color.value  // 临时落子
        val captures = mutableListOf<Pair<Int, Int>>()
        for ((nx, ny) in neighbors(x, y)) {
            if (get(nx, ny) != opponent) continue
            if (libertiesOfGroup(nx, ny) == 0) captures.addAll(groupOf(nx, ny))
        }
        // 提子
        captures.forEach { grid[it.second * size + it.first] = 0 }
        val alive = libertiesOfGroup(x, y) > 0
        // 还原
        captures.forEach { grid[it.second * size + it.first] = opponent.value }
        grid[y * size + x] = 0
        if (!alive) return null
        // 计算落子后哈希：盘面 = 原盘 - 被提子 + 本手新子
        captures.forEach { grid[it.second * size + it.first] = 0 }
        grid[y * size + x] = color.value
        val h = hashOf()
        grid[y * size + x] = 0
        captures.forEach { grid[it.second * size + it.first] = opponent.value }
        return captures.distinct() to h
    }

    private fun playInternal(m: Move): PlaceResult {
        val (caps, h) = simulate(m.x, m.y, m.color) ?: return PlaceResult(illegal = Illegal.SUICIDE)
        grid[m.y * size + m.x] = m.color.value
        caps.forEach { grid[it.second * size + it.first] = 0 }
        moves.add(m)
        hashes.add(h)
        return PlaceResult(captures = caps)
    }

    /** 不校验劫争的落子（重放用：劫争校验依赖完整哈希历史，重放时已自然满足） */

    private fun neighbors(x: Int, y: Int): List<Pair<Int, Int>> {
        // 注意：buildList 的 lambda 接收者是 MutableList，裸写 size 会被列表自身的
        // size（构建中，从 0 开始）遮蔽——角点会拿到空邻居表而被误判自杀。先缓存棋盘路数。
        val n = size
        return buildList(4) {
            if (x > 0) add(x - 1 to y)
            if (x < n - 1) add(x + 1 to y)
            if (y > 0) add(x to y - 1)
            if (y < n - 1) add(x to y + 1)
        }
    }

    private fun groupOf(sx: Int, sy: Int): List<Pair<Int, Int>> {
        val color = get(sx, sy)
        val seen = HashSet<Int>()
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(sx to sy)
        seen.add(sy * size + sx)
        val out = mutableListOf<Pair<Int, Int>>()
        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeLast()
            out.add(x to y)
            for ((nx, ny) in neighbors(x, y)) {
                val idx = ny * size + nx
                if (idx in seen || get(nx, ny) != color) continue
                seen.add(idx)
                stack.add(nx to ny)
            }
        }
        return out
    }

    private fun libertiesOfGroup(sx: Int, sy: Int): Int {
        val color = get(sx, sy)
        val seen = HashSet<Int>()
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(sx to sy)
        seen.add(sy * size + sx)
        var liberties = 0
        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeLast()
            for ((nx, ny) in neighbors(x, y)) {
                val idx = ny * size + nx
                if (idx in seen) continue
                val c = get(nx, ny)
                if (c == Color.EMPTY) {
                    seen.add(idx)
                    liberties++
                } else if (c == color) {
                    seen.add(idx)
                    stack.add(nx to ny)
                }
            }
        }
        return liberties
    }

    // ------------------------------------------------------------- 数子

    /**
     * 中国规则数子：双方活子 + 只与一方相邻的空点（势力范围）计为该方地；
     * 与双方都相邻的空点为单官（dame）不计。贴目从黑方扣除：[komi] 目加到白方。
     */
    fun score(komi: Float): Score {
        val blackStones = grid.count { it == Color.BLACK.value }
        val whiteStones = grid.count { it == Color.WHITE.value }
        val visited = BooleanArray(size * size)
        var blackTerritory = 0
        var whiteTerritory = 0
        var dame = 0
        for (i in grid.indices) {
            if (visited[i] || grid[i] != 0.toByte()) continue
            // 空点泛洪：统计这块空地域的邻接颜色
            val region = mutableListOf<Pair<Int, Int>>()
            val borders = HashSet<Color>()
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.add(i % size to i / size)
            visited[i] = true
            while (stack.isNotEmpty()) {
                val (x, y) = stack.removeLast()
                region.add(x to y)
                for ((nx, ny) in neighbors(x, y)) {
                    val c = get(nx, ny)
                    if (c == Color.EMPTY) {
                        val idx = ny * size + nx
                        if (!visited[idx]) {
                            visited[idx] = true
                            stack.add(nx to ny)
                        }
                    } else {
                        borders.add(c)
                    }
                }
            }
            when {
                borders == setOf(Color.BLACK) -> blackTerritory += region.size
                borders == setOf(Color.WHITE) -> whiteTerritory += region.size
                else -> dame += region.size
            }
        }
        return Score(
            blackPoints = (blackStones + blackTerritory).toFloat(),
            whitePoints = whiteStones + whiteTerritory + komi,
            blackTerritory = blackTerritory,
            whiteTerritory = whiteTerritory,
            dame = dame,
        )
    }

    // ------------------------------------------------------------- 哈希

    /** 盘面向量哈希（FNV 变体）：打劫判定与重建用 */
    private fun hashOf(): Long {
        var h = 1469598103934665603L
        for (i in grid.indices) {
            h = (h xor grid[i].toLong()) * 1099511628211L
        }
        return h
    }

    companion object {
        const val DEFAULT_SIZE = 19
    }
}
