package com.qwara.gomoku.engine

import android.content.Context
import android.util.Log
import com.qwara.gomoku.game.Board
import com.qwara.gomoku.game.Rule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * Rapfi 引擎封装（Piskvork + Yixin 扩展协议），经 [RapfiNative] 在进程内驱动。
 *
 * 用法：
 *  - [startNewGame] 启动/重置引擎会话并设定规则与每步时限；
 *  - [engineMoveFirst] 让引擎执黑先行（BEGIN）；
 *  - [playUserMove] 用户走子（TURN），挂起等待引擎应着；
 *  - [takeback] 引擎内部局面回退一手（TAKEBACK）；
 *  - [syncAndAnalyze] 同步任意局面（YXBOARD）并开始 N 路分析（YXNBEST）；
 *  - [stop] 停止搜索（STOP）；[stopAndAwait] 停止并等待空闲（改局面前调用）。
 *
 * 所有写操作在单线程调度器上串行执行；读线程逐行解析并更新 [status]。
 */
class RapfiEngine(private val context: Context) {

    companion object {
        private const val TAG = "RapfiEngine"
        private val MOVE_LINE: Pattern = Pattern.compile("^\\d+,\\d+( \\d+,\\d+)*$")
        private val COORD: Pattern = Pattern.compile("(\\d+),(\\d+)")
        private val FORBID_POINT: Pattern = Pattern.compile("(\\d{2})(\\d{2})")
        private val MATE_EVAL: Pattern = Pattern.compile("([+-])M(\\d+)")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status

    private var curPv: PvLine? = null
    private var pvAccumulator = linkedMapOf<Int, PvLine>()

    /** 等待最终着法行的挂起点（playUserMove / engineMoveFirst） */
    private var awaitingMoves: CompletableDeferred<List<Pt>>? = null
    private var awaitingForbid: CompletableDeferred<Set<Pt>>? = null

    @Volatile
    private var readerRunning = false

    val isRunning: Boolean
        get() = runCatching { RapfiNative.isRunning() }.getOrDefault(false)

    // --------------------------------------------------------- 会话管理

    /** 启动新会话：结束旧会话（若有），初始化选项，START。 */
    suspend fun startNewGame(rule: Rule, timeMs: Int): Result<Unit> =
        withContext(writeDispatcher) {
            try {
                val dir = EngineInstaller.ensureWeightsInstalled(context)
                // 结束旧会话并等待协议线程退出
                if (RapfiNative.isRunning()) {
                    RapfiNative.write("END")
                    var waited = 0L
                    while (RapfiNative.isRunning() && waited < 3000) {
                        Thread.sleep(50)
                        waited += 50
                    }
                }
                Log.i(TAG, "Starting engine session in ${dir.absolutePath}")
                RapfiNative.start(dir.absolutePath)
                startReader()

                send("YXSHOWINFO")
                send("INFO THREAD_NUM 1")
                send("INFO PONDERING 0")
                // 关键：默认 infoMode=0 不输出任何 INFO，必须显式打开 DETAIL
                send("INFO SHOW_DETAIL 2")
                send("INFO RULE ${ruleToId(rule)}")
                send("INFO TIMEOUT_MATCH 100000000")
                send("INFO TIMEOUT_TURN $timeMs")
                send("INFO TIME_LEFT 100000000")
                // 16 MB 置换表（手表内存受限；HASH_SIZE 单位为 KB）
                send("INFO HASH_SIZE 16384")
                send("START 15")
                _status.value = EngineStatus(phase = EngineStatus.Phase.IDLE)
                Result.success(Unit)
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                Result.failure(t)
            }
        }

    private fun startReader() {
        if (readerRunning) return
        readerRunning = true
        Thread {
            try {
                while (true) {
                    val line = RapfiNative.readLine() ?: break
                    val text = line.trim()
                    if (text.isNotEmpty()) handleLine(text)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "reader ended", t)
            } finally {
                readerRunning = false
            }
        }.apply {
            isDaemon = true
            name = "rapfi-reader"
            start()
        }
    }

    fun shutdown() {
        scope.launch(writeDispatcher) {
            runCatching { if (RapfiNative.isRunning()) RapfiNative.write("END") }
        }
    }

    // --------------------------------------------------------- 对弈

    /** 引擎执黑先行，返回引擎着法 */
    suspend fun engineMoveFirst(): Result<Pt> = withContext(writeDispatcher) {
        runCatching {
            send("BEGIN")
            awaitMoves().first()
        }
    }

    /**
     * 用户走子（引擎内部执另一色），返回引擎应着。
     * 会先把每步时限恢复为 [timeMs]（此前的“提示/分析”可能改过它）。
     */
    suspend fun playUserMove(x: Int, y: Int, timeMs: Int): Result<Pt> = withContext(writeDispatcher) {
        runCatching {
            send("INFO TIMEOUT_TURN $timeMs")
            send("TURN $x,$y")
            awaitMoves().first()
        }
    }

    /** 悔棋一步（引擎内部局面回退一手） */
    suspend fun takeback(): Result<Unit> = withContext(writeDispatcher) {
        runCatching { send("TAKEBACK 0,0") }
    }

    // --------------------------------------------------------- 分析

    /**
     * 同步任意局面并开始分析。
     * @param moves        完整着法序列（绝对颜色）
     * @param engineColor  引擎视角的“本方”颜色（用于 SELF/OPPO 标记；分析模式传 null 表示纯观战）
     * @param nbest        输出前 N 路变化
     * @param timeMs       每步时限（0 表示无限——纯分析模式，须用 [stop] 手动停止）
     */
    suspend fun syncAndAnalyze(
        moves: List<Board.Move>,
        engineColor: Board.Color?,
        nbest: Int,
        timeMs: Int,
    ): Result<Unit> = withContext(writeDispatcher) {
        runCatching {
            // 注意：分析模式必须 TIMEOUT_TURN=0，否则到达时限后引擎会自行结束并吐坐标，
            // 且退出分析模式；timeMs>0 仅用于“提示”这种一次性场景
            send("INFO TIMEOUT_TURN $timeMs")
            send("YXBOARD")
            for (m in moves) {
                val side = when (engineColor) {
                    Board.Color.BLACK -> 1
                    Board.Color.WHITE -> 2
                    else -> if (m.color == Board.Color.BLACK) 1 else 2
                }
                send("${m.x},${m.y},$side")
            }
            send("DONE")
            // YXBOARD 不触发思考；开始 N 路分析
            pvAccumulator.clear()
            curPv = null
            _status.update {
                it.copy(
                    phase = EngineStatus.Phase.ANALYZING,
                    pvLines = emptyList(),
                    bestMoves = emptyList(),
                )
            }
            send("YXNBEST $nbest")
        }
    }

    /** 停止当前搜索；引擎会输出最终最佳着法 */
    fun stop() {
        scope.launch(writeDispatcher) { send("STOP") }
    }

    /** 停止搜索并等待引擎回到空闲。改局面/重新分析前调用，避免与在途搜索竞争协议状态。 */
    suspend fun stopAndAwait(timeoutMs: Long = 4000) {
        if (_status.value.phase == EngineStatus.Phase.IDLE) return
        withContext(writeDispatcher) { send("STOP") }
        withTimeoutOrNull(timeoutMs) {
            _status.first { it.phase == EngineStatus.Phase.IDLE }
        }
    }

    /** 查询连珠规则下当前禁手点 */
    suspend fun queryForbid(): Result<Set<Pt>> = withContext(writeDispatcher) {
        runCatching {
            val def = CompletableDeferred<Set<Pt>>()
            awaitingForbid = def
            send("YXSHOWFORBID")
            def.await()
        }
    }

    // --------------------------------------------------------- 内部

    private suspend fun awaitMoves(): List<Pt> {
        val def = CompletableDeferred<List<Pt>>()
        awaitingMoves = def
        return def.await()
    }

    private fun send(cmd: String) {
        Log.d(TAG, ">> $cmd")
        RapfiNative.write(cmd)
    }

    private fun handleLine(line: String) {
        Log.v(TAG, "<< $line")

        // 最终着法行
        if (MOVE_LINE.matcher(line).matches()) {
            val moves = COORD.matcher(line).let { m ->
                buildList {
                    while (m.find()) add(m.group(1)!!.toInt() to m.group(2)!!.toInt())
                }
            }
            _status.update { it.copy(phase = EngineStatus.Phase.IDLE, bestMoves = moves) }
            awaitingMoves?.complete(moves)
            awaitingMoves = null
            return
        }

        // FORBID 0102 0304.
        if (line.startsWith("FORBID")) {
            val pts = HashSet<Pt>()
            val m = FORBID_POINT.matcher(line)
            while (m.find()) pts.add(m.group(1)!!.toInt() to m.group(2)!!.toInt())
            awaitingForbid?.complete(pts)
            awaitingForbid = null
            return
        }

        if (line == "OK") return

        if (line.startsWith("INFO ")) {
            handleInfo(line)
            return
        }

        if (line.startsWith("ERROR") || line.startsWith("UNKNOWN")) {
            Log.w(TAG, "engine: $line")
            return
        }
        // 其余横幅/MESSAGE/REALTIME 忽略
    }

    private fun handleInfo(line: String) {
        val parts = line.split(' ', limit = 3)
        if (parts.size < 3) return
        val key = parts[1]
        val value = parts[2]
        when (key) {
            "PV" -> {
                if (value == "DONE") {
                    curPv?.let { pv ->
                        pvAccumulator[pv.index] = pv
                        _status.update { s ->
                            s.copy(pvLines = pvAccumulator.values.sortedBy { it.index })
                        }
                    }
                    curPv = null
                } else {
                    val idx = value.toIntOrNull() ?: 0
                    curPv = (pvAccumulator[idx]?.copy() ?: PvLine(index = idx))
                }
            }
            "DEPTH" -> curPv = curPv?.let { it.copy(depth = value.toIntOrNull() ?: it.depth) }
                ?: PvLine(depth = value.toIntOrNull() ?: 0)
            "NODES" -> curPv = curPv?.let { it.copy(nodes = value.toLongOrNull() ?: it.nodes) }
            "TOTALNODES" -> curPv = curPv?.let { it.copy(totalNodes = value.toLongOrNull() ?: it.totalNodes) }
            "TOTALTIME" -> curPv = curPv?.let { it.copy(timeMs = value.toLongOrNull() ?: it.timeMs) }
            "SPEED" -> curPv = curPv?.let { it.copy(speed = value.toLongOrNull() ?: it.speed) }
            "EVAL" -> {
                val ev = parseEval(value)
                curPv = curPv?.let { it.copy(eval = ev ?: it.eval) }
            }
            "WINRATE" -> curPv = curPv?.let {
                it.copy(winRate = value.toFloatOrNull() ?: it.winRate)
            }
            "BESTLINE" -> {
                val moves = COORD.matcher(value).let { m ->
                    buildList {
                        while (m.find()) add(m.group(1)!!.toInt() to m.group(2)!!.toInt())
                    }
                }
                curPv = curPv?.let { it.copy(moves = moves) } ?: PvLine(moves = moves)
            }
        }
    }

    /** 解析 EVAL 值：普通整数，或将杀格式 "+M43"/"-M5"（ply 距离） */
    private fun parseEval(value: String): Int? {
        value.toIntOrNull()?.let { return it }
        val m = MATE_EVAL.matcher(value)
        if (m.matches()) {
            val dist = m.group(2)!!.toInt()
            return if (m.group(1) == "+") EngineValue.MATE - dist else -(EngineValue.MATE - dist)
        }
        return null
    }

    private fun ruleToId(rule: Rule): Int = when (rule) {
        Rule.FREESTYLE -> 0
        Rule.RENJU -> 4
    }

    class EngineException(msg: String) : Exception(msg)
}
