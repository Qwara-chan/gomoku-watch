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
import kotlinx.coroutines.delay
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
 *  - [playUserMove] 用户走子后让引擎应着（整盘 YXBOARD + YXNBEST），挂起等待引擎着法；
 *  - [takeback] 引擎内部局面回退一手（TAKEBACK）；
 *  - [syncAndAnalyze] 同步任意局面（YXBOARD）并开始 N 路分析（YXNBEST）；
 *  - [scanAnalyze] 限时分析单个局面并等待引擎回到空闲（全谱扫描逐手评估）；
 *  - [stop] 停止搜索（STOP）；[stopAndAwait] 停止并等待空闲（改局面前调用）。
 *
 * 所有写操作在单线程调度器上串行执行；读线程逐行解析并更新 [status]。
 * 注意引擎在思考期间会丢弃除 STOP/END 外的全部命令，因此发任何改局面命令前
 * 都必须先 [stopAndAwait] 成功。
 */
class RapfiEngine(private val context: Context) {

    companion object {
        private const val TAG = "RapfiEngine"
        private val MOVE_LINE: Pattern = Pattern.compile("^\\d+,\\d+( \\d+,\\d+)*$")
        private val COORD: Pattern = Pattern.compile("(\\d+),(\\d+)")
        private val MATE_EVAL: Pattern = Pattern.compile("([+-])M(\\d+)")

        /** YXBOARD 中表示“停一手”的坐标，用于还原白先局面（引擎默认首手为黑） */
        private const val PASS_ENTRY = "-1,-1,1"

        /** INFO STRENGTH 的满强度值：分析/提示必须用满强度，否则引擎会随机挑点 */
        private const val FULL_STRENGTH = 100

        /** INFO STRENGTH 的取值范围（引擎侧 uint16，语义为 0–100） */
        private const val MAX_STRENGTH = 100

        /** AI 应着前等待引擎空闲的上限：在途的提示搜索只有 2 秒，正常空闲时零开销直接返回 */
        private const val STOP_BEFORE_TURN_MS = 2000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status

    /** 以下字段跨读线程与写调度线程访问，加 @Volatile 保证可见性 */
    @Volatile
    private var curPv: PvLine? = null

    @Volatile
    private var pvAccumulator = linkedMapOf<Int, PvLine>()

    /** 等待最终着法行的挂起点（playUserMove / engineMoveFirst） */
    @Volatile
    private var awaitingMoves: CompletableDeferred<List<Pt>>? = null

    @Volatile
    private var readerThread: Thread? = null

    val isRunning: Boolean
        get() = runCatching { RapfiNative.isRunning() }.getOrDefault(false)

    // --------------------------------------------------------- 会话管理

    /** 启动新会话：结束旧会话（若有），初始化选项，START。 */
    suspend fun startNewGame(rule: Rule, timeMs: Int): Result<Unit> =
        withContext(writeDispatcher) {
            try {
                val dir = EngineInstaller.ensureWeightsInstalled(context)
                // 结束旧会话并等待协议线程退出（挂起等待，不占住写线程）
                if (RapfiNative.isRunning()) {
                    RapfiNative.write("END")
                    var waited = 0L
                    while (RapfiNative.isRunning() && waited < 3000) {
                        delay(50)
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
                pvAccumulator.clear()
                curPv = null
                // 会话重建后旧请求不可能再有应答：立即失败，别让它等超时
                awaitingMoves?.completeExceptionally(EngineException("引擎会话已重启"))
                awaitingMoves = null
                _status.value = EngineStatus(phase = EngineStatus.Phase.IDLE)
                Result.success(Unit)
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                Result.failure(t)
            }
        }

    private fun startReader() {
        // 旧会话的读线程可能还在收尾：先等它退出，避免两个读线程争抢同一输出队列
        readerThread?.let { old ->
            if (old.isAlive) {
                old.join(1000)
                if (old.isAlive) Log.w(TAG, "previous reader still alive, lines may interleave")
            }
        }
        readerThread = Thread {
            try {
                while (true) {
                    val line = RapfiNative.readLine() ?: break
                    val text = line.trim()
                    if (text.isNotEmpty()) handleLine(text)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "reader ended", t)
            } finally {
                // 会话异常结束：让仍在等待的挂起点立即失败，而不是永久挂起
                awaitingMoves?.completeExceptionally(EngineException("引擎会话已结束"))
                awaitingMoves = null
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

    /** 引擎执黑先行，返回引擎着法；[strength] 为棋力档位（0–100，100=满强度） */
    suspend fun engineMoveFirst(strength: Int): Result<Pt> = withContext(writeDispatcher) {
        runCatching {
            prepareSearch(EngineStatus.Phase.THINKING)
            // 先装挂起点再发命令：引擎应答可能早于挂起点建立
            requestMoves {
                sendStrength(strength)
                send("BEGIN")
            }.first()
        }
    }

    /**
     * 用户走子后让引擎应着，返回引擎着法。
     *
     * [position] 是**含刚落下这一手**的完整局面。这里刻意不发 `TURN`：引擎的 `turn()` 把
     * `options.multiPV` 写死为 1，对局中就永远只有一路推荐（盘上只能画出一个候选点）。
     * 改用分析语义的 `YXBOARD` + `YXNBEST [multiPv]`：引擎会一边流式上报多路候选（UI 借此显示
     * 棋盘候选点与深度/胜率读数），一边在搜索结束时把选中的着手落回自己的盘面并打印一行坐标——
     * 终态与 `TURN` 完全一致，而每次应着都按 App 的局面重摆，引擎盘面不可能漂移。
     *
     * [multiPv] 用设置的“分析路数”：它越大，同样 [timeMs] 预算要摊到越多根线上、搜索越浅
     * （1 = 单路，棋力与旧的 TURN 行为一致）。
     *
     * 会先把每步时限恢复为 [timeMs]（此前的“提示/分析”可能改过它）。
     * [strength] 只在人机对战中生效；分析与提示固定满强度（见 [syncAndAnalyze]）。
     */
    suspend fun playUserMove(
        position: List<Board.Move>,
        multiPv: Int,
        timeMs: Int,
        strength: Int,
    ): Result<Pt> {
        // 在途搜索（提示/分析的残局）期间引擎会丢弃 YXBOARD/YXNBEST：先等空闲。
        // 必须先于 requestMoves 装挂起点，否则被停掉的那次搜索的着法行会被当成本次应着。
        if (!stopAndAwait(STOP_BEFORE_TURN_MS)) return Result.failure(EngineException("引擎无响应"))
        return withContext(writeDispatcher) {
            runCatching {
                prepareSearch(EngineStatus.Phase.THINKING)
                requestMoves {
                    sendBoard(position)
                    send("INFO TIMEOUT_TURN $timeMs")
                    sendStrength(strength)
                    send("YXNBEST ${multiPv.coerceAtLeast(1)}")
                }.first()
            }
        }
    }

    /** 悔棋一步（引擎内部局面回退一手） */
    suspend fun takeback(): Result<Unit> = withContext(writeDispatcher) {
        runCatching { send("TAKEBACK 0,0") }
    }

    // --------------------------------------------------------- 分析

    /**
     * 同步任意局面并开始分析。
     * @param moves        完整着法序列（绝对颜色，首手可以是白——编辑/翻转后的局面）
     * @param nbest        输出前 N 路变化
     * @param timeMs       每步时限（0 表示无限——纯分析模式，须用 [stop] 手动停止）
     */
    suspend fun syncAndAnalyze(
        moves: List<Board.Move>,
        nbest: Int,
        timeMs: Int,
    ): Result<Unit> = withContext(writeDispatcher) {
        runCatching { sendBoardCommands(moves, nbest, timeMs) }
    }

    /**
     * 分析单个局面并在 [timeMs] 到达后返回（引擎限时搜索结束会自行回到空闲）。
     * 供全谱扫描逐手取评估值使用；调用前必须确保引擎空闲。
     */
    suspend fun scanAnalyze(moves: List<Board.Move>, timeMs: Int): Result<Unit> =
        withContext(writeDispatcher) {
            runCatching {
                sendBoardCommands(moves, nbest = 1, timeMs = timeMs)
                // 限时搜索结束后引擎输出最终着法行并转 IDLE；等不到就放弃这一手
                val idle = withTimeoutOrNull(timeMs * 4L + 4000) {
                    _status.first { it.phase == EngineStatus.Phase.IDLE }
                }
                if (idle == null) throw EngineException("分析超时")
            }
        }

    /** 发盘面与分析命令（YXBOARD + YXNBEST），调用方保证后续等待逻辑 */
    private fun sendBoardCommands(moves: List<Board.Move>, nbest: Int, timeMs: Int) {
        // 注意：分析模式必须 TIMEOUT_TURN=0，否则到达时限后引擎会自行结束并吐坐标，
        // 且退出分析模式；timeMs>0 仅用于“提示/扫描”这种一次性场景
        send("INFO TIMEOUT_TURN $timeMs")
        // 关键：棋力档位会限制深度并在候选中随机挑点，会污染分析结论，分析前必须恢复满强度
        sendStrength(FULL_STRENGTH)
        prepareSearch(EngineStatus.Phase.ANALYZING)
        sendBoard(moves)
        // YXBOARD 不触发思考；开始 N 路分析
        send("YXNBEST $nbest")
    }

    /** 只发盘面（YXBOARD），不改阶段、不改时限、不触发搜索 */
    private fun sendBoard(moves: List<Board.Move>) {
        send("YXBOARD")
        // 引擎模型里首手固定为黑，白先局面先用一个 PASS 换手
        if (moves.isNotEmpty() && moves.first().color != Board.Color.BLACK) {
            send(PASS_ENTRY)
        }
        for (m in moves) {
            send("${m.x},${m.y},${if (m.color == Board.Color.BLACK) 1 else 2}")
        }
        send("DONE")
    }

    private fun sendStrength(level: Int) {
        send("INFO STRENGTH ${level.coerceIn(0, MAX_STRENGTH)}")
    }

    /** 停止当前搜索；引擎会输出最终最佳着法 */
    fun stop() {
        scope.launch(writeDispatcher) { send("STOP") }
    }

    /**
     * 只同步盘面，不触发搜索（YXBOARD 后不发 YXNBEST）。
     * 对局中做过全谱扫描/复盘后，引擎内部盘面停在扫描的最后一手，这里把它摆回实战局面，
     * 使 [takeback] 等基于引擎自有盘面的命令仍然对得上（应着本身每次整盘重摆，不依赖它）。
     * 调用前需确保引擎空闲（思考中 YXBOARD 会被丢弃）。
     */
    suspend fun syncBoard(moves: List<Board.Move>): Result<Unit> = withContext(writeDispatcher) {
        runCatching { sendBoard(moves) }
    }

    /**
     * 停止搜索并等待引擎回到空闲。改局面/重新分析前调用，避免与在途搜索竞争协议状态。
     * @return 是否已确认回到空闲；false 表示超时（此时发命令会被引擎丢弃）
     */
    suspend fun stopAndAwait(timeoutMs: Long = 4000): Boolean {
        if (_status.value.phase == EngineStatus.Phase.IDLE) return true
        withContext(writeDispatcher) { send("STOP") }
        val idle = withTimeoutOrNull(timeoutMs) {
            _status.first { it.phase == EngineStatus.Phase.IDLE }
        }
        if (idle == null) Log.w(TAG, "stopAndAwait timed out")
        return idle != null
    }

    /**
     * 强制回到空闲。命令被引擎丢弃时（思考中只认 STOP/END）不会有着法行来清相位，
     * 相位会一直停在 THINKING/ANALYZING，UI 永远显示“引擎思考中”并锁死全部输入。
     */
    fun resetPhaseIfStuck() {
        _status.update {
            if (it.phase == EngineStatus.Phase.IDLE) it else it.copy(phase = EngineStatus.Phase.IDLE)
        }
    }

    // --------------------------------------------------------- 内部

    /** 标记一次搜索开始：清空上一次的 PV/推荐点，并把阶段置为搜索中。 */
    private fun prepareSearch(phase: EngineStatus.Phase) {
        curPv = null
        pvAccumulator = linkedMapOf()
        _status.update {
            it.copy(phase = phase, pvLines = emptyList(), bestMoves = emptyList())
        }
    }

    /** 装好挂起点后执行 [action]，等待引擎输出最终着法行。 */
    private suspend fun requestMoves(action: () -> Unit): List<Pt> {
        val def = CompletableDeferred<List<Pt>>()
        awaitingMoves = def
        return try {
            action()
            def.await()
        } catch (t: Throwable) {
            // 超时/取消：让引擎停下来，否则 THINKING 相位会一直留在原地锁死输入
            runCatching { send("STOP") }
            throw t
        } finally {
            if (awaitingMoves === def) awaitingMoves = null
        }
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
            "SELDEPTH" -> curPv = curPv?.let { it.copy(selDepth = value.toIntOrNull() ?: it.selDepth) }
            "NUMPV" -> curPv = curPv?.let { it.copy(numPv = value.toIntOrNull() ?: it.numPv) }
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
