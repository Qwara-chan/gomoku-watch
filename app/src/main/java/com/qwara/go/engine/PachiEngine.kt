// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.qwara.go.game.GoBoard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 * Pachi 引擎封装（GTP 协议 + lz-analyze 扩展），经 [PachiNative] 在进程内驱动。
 *
 * 用法：
 *  - [startNewGame] 启动/重置引擎会话（boardsize/komi/clear_board/每步时限）；
 *  - [engineMoveFirst] 让引擎执黑先行（整盘重放 + lz-genmove_analyze）；
 *  - [playUserMove] 用户走子后让引擎应着（整盘重放 + lz-genmove_analyze），挂起等待引擎着法；
 *  - [syncAndAnalyze] 同步任意局面（整盘重放）并开始实时分析（lz-analyze，[stop] 停止）；
 *  - [analyzeFor] 一次性分析：等引擎第一帧候选上报后自行停止并返回主变例（「提示」用）；
 *  - [scanAnalyze] 同上一手的单局面版本（全谱扫描逐手取评估）；
 *  - [takeback] 引擎内部局面回退 N 手（GTP undo）；
 *  - [finalScore] 双 pass 终局后的数子（引擎判断，失败时 App 回退本地数子）；
 *  - [stopAndAwait] 改局面前调用；[shutdown] 销毁会话（Activity onCleared）。
 *
 * 坐标换算：App 内部 (x,y) 0 起始、y 向下；GTP 列字母 A–T 跳 I、行号 = size-y。
 *
 * 与 Rapfi 的语义差异（实现时关键约束）：
 *  - GTP 每条命令都有应答（"=" / "?" 开头），应答按发送顺序严格排队；
 *  - lz-genmove_analyze 会先立即回 "= "，搜索结束后再吐一行 "play <coord>" 作为着手，
 *    中途的候选走 `info move ...` 行流式上报（一行可含多段）；
 *  - genmove 类搜索限时自止，没有思考中 STOP 命令；分析用 `lz-analyze <color> 0` 停止；
 *  - 改局面只能 clear_board + play 重放（所以每个请求都整盘重摆，引擎盘面不会漂移）。
 */
class PachiEngine(private val context: Context) {

    companion object {
        private const val TAG = "PachiEngine"

        /** GTP 应答： "= ..." 成功 / "? ..." 失败（我们不带命令 id） */
        private val GTP_REPLY: Pattern = Pattern.compile("^([=?])\\s*(.*)$")

        /**
         * lz 输出候选段： `info move D16 visits 2934 winrate 4972 prior 10000 order 0 pv D16 Q4 ...`
         * 一行可含多段（段间以 "info move" 分隔），winrate 为行棋方视角的千分值。
         */
        private val INFO_MOVE: Pattern = Pattern.compile(
            "info move (\\S+) visits (\\d+) winrate (\\d+) prior (\\d+) order (\\d+)(?: pv (.*?))?(?= info move |$)"
        )

        /** lz-genmove_analyze 的收尾着手行 */
        private val PLAY_LINE: Pattern = Pattern.compile("^play (\\S+)$")

        private val COORD: Pattern = Pattern.compile("^([A-Za-z])(\\d+)$")

        /** 分析上报间隔（百分秒）：50 = 0.5s，平衡刷新流畅度与引擎开销 */
        private const val ANALYZE_FREQ_CS = 50

        /** AI 应着前等待引擎空闲的上限 */
        private const val AWAIT_IDLE_MS = 4000L
    }

    /** 特殊着手：pass / resign 以哨兵坐标表达 */
    val passMove: Pt = -1 to -1
    val resignMove: Pt = -2 to -2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writeDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status

    /** 等待最终着手行的挂起点（playUserMove / engineMoveFirst） */
    @Volatile
    private var awaitingMoves: CompletableDeferred<Pt>? = null

    /**
     * 搜索/分析代号：每次开搜索（[prepareSearch]）或停止（[sendStop]）都 +1。
     * 一次性分析的等待方只停「自己那一代」，免得迟到的定时器把别人刚开的分析掐死
     * （连点两次提示、提示期间开扫描都会撞上）。只在 writeDispatcher 上改动。
     */
    @Volatile
    private var searchEpoch: Long = 0

    /**
     * GTP 应答槽：每条命令一个，按发送顺序被应答行填充（fire-and-forget 命令的槽无人读，GC 即可）。
     * 槽里同时记下命令原文：引擎 stdout 是**进程级** fd（见 engine-src/pachi_jni.c 的 dup2），
     * 同进程里 Skia/EGL 等库写到 fd 1/2 的调试行会混进这条流，必须按命令形状把它们挡在协议之外。
     */
    private val replyQueue = java.util.ArrayDeque<ReplySlot>()

    /** 在途命令 + 它的应答槽 */
    private class ReplySlot(val cmd: String, val deferred: CompletableDeferred<Result<String>>)

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var boardSize: Int = GoBoard.DEFAULT_SIZE

    val isRunning: Boolean
        get() = runCatching { PachiNative.isRunning() }.getOrDefault(false)

    // --------------------------------------------------------- 会话管理

    /** 启动新会话：销毁旧会话（若有），初始化棋盘与规则。 */
    suspend fun startNewGame(boardSize: Int, komi: Float, timeMs: Int): Result<Unit> =
        withContext(writeDispatcher) {
            try {
                val dir = EngineInstaller.ensureEngineDataInstalled(context)
                runCatching { PachiNative.shutdown() }
                // 旧读线程要在新会话起来之前退出：它判断"会话已结束"看的是**全局**会话句柄
                // （pachi_jni.c 的 readLine），新会话一旦开始运行它就会变成不停轮询旧队列的
                // 僵尸线程。此刻 gSession 还是空，它会立刻结束。
                joinReader()
                Log.i(TAG, "Starting engine session in ${dir.absolutePath}")
                this@PachiEngine.boardSize = boardSize
                PachiNative.start(dir.absolutePath)
                startReader()

                send("boardsize $boardSize")
                send("komi $komi")
                send("clear_board")
                send(timeSettingsCmd(timeMs))
                // 会话重建后旧请求不可能再有应答：立即失败，别让它等超时
                awaitingMoves?.completeExceptionally(EngineException("引擎会话已重启"))
                awaitingMoves = null
                replyQueue.forEach { it.deferred.complete(Result.failure(EngineException("引擎会话已重启"))) }
                replyQueue.clear()
                _status.value = EngineStatus(phase = EngineStatus.Phase.IDLE)
                Result.success(Unit)
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                Result.failure(t)
            }
        }

    private fun startReader() {
        joinReader()
        readerThread = Thread {
            try {
                while (true) {
                    val line = PachiNative.readLine() ?: break
                    val text = line.trim()
                    if (text.isNotEmpty()) handleLine(text)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "reader ended", t)
            } finally {
                awaitingMoves?.completeExceptionally(EngineException("引擎会话已结束"))
                awaitingMoves = null
            }
        }.apply {
            isDaemon = true
            name = "pachi-reader"
            start()
        }
    }

    fun shutdown() {
        scope.launch(writeDispatcher) {
            runCatching { PachiNative.shutdown() }
        }
    }

    /**
     * 等旧读线程退出（连同它的线程引用一起丢弃）。会话已销毁时它会自己读到 null 结束，
     * 这里只负责收尸：留着会变成轮询旧队列的僵尸线程。
     */
    private fun joinReader(timeoutMs: Long = 1000) {
        val old = readerThread ?: return
        readerThread = null
        if (!old.isAlive) return
        old.join(timeoutMs)
        if (old.isAlive) Log.w(TAG, "previous reader still alive after ${timeoutMs}ms")
    }

    // --------------------------------------------------------- 对弈

    /**
     * 用户走子后让引擎应着，返回引擎着手。
     *
     * [position] 是**含刚落下这一手**的完整局面（pass 用 isPass 表示）。每步都整盘重放：
     * GTP 没有整盘设置命令，clear_board + play 重放保证引擎盘面与 App 永不漂移，
     * 等价于旧实现的 YXBOARD 语义。
     */
    suspend fun playUserMove(position: List<GoBoard.Move>, timeMs: Int): Result<Pt> {
        if (!stopAndAwait(AWAIT_IDLE_MS)) return Result.failure(EngineException("引擎无响应"))
        return withContext(writeDispatcher) {
            runCatching {
                prepareSearch(EngineStatus.Phase.THINKING)
                requestMoves {
                    sendPosition(position)
                    send(timeSettingsCmd(timeMs))
                    val color = sideToMove(position)
                    send("lz-genmove_analyze $color $ANALYZE_FREQ_CS")
                }
            }
        }
    }

    /** 引擎执黑先行；[position] 通常为空盘。返回引擎着手（可能是 passMove/resignMove） */
    suspend fun engineMoveFirst(position: List<GoBoard.Move>, timeMs: Int): Result<Pt> =
        playUserMove(position, timeMs)

    /** 悔棋 N 手（AI 模式一次 2 手：引擎一手 + 用户一手） */
    suspend fun takeback(n: Int): Result<Unit> = withContext(writeDispatcher) {
        runCatching { repeat(n) { send("undo") } }
    }

    // --------------------------------------------------------- 分析

    /**
     * 同步任意局面并开始实时分析（lz-analyze），由 [stop] 停止。
     * @param timeMs 仅用于兼容旧接口：pachi 的分析无限进行，由 [stop] 停止；
     *   需要「有限时长」的场景（提示）请用 [analyzeFor]
     */
    suspend fun syncAndAnalyze(moves: List<GoBoard.Move>, timeMs: Int): Result<Unit> =
        withContext(writeDispatcher) {
            runCatching {
                sendBoardCommands(moves)
                val color = sideToMove(moves)
                send("lz-analyze $color $ANALYZE_FREQ_CS")
            }
        }

    /**
     * 一次性局面分析（「提示」与全谱扫描逐手共用）。
     *
     * 重放局面 → `lz-analyze` → 等第一帧候选上报（最多 [maxWaitMs]）→ 再多留 [extraMs] → 停。
     *
     * **首帧延迟由设备算力决定，不能靠固定窗口**：pachi 要等某个点攒够 500 次模拟才吐第一行
     * （`uct/walk.c` 的 `uct_get_best_moves(..., 500)` 之后还有 `if (!best.n) return;`）。
     * 实测宿主 0.6s、模拟器 0.8~1.5s、模拟器满负载 3~9.5s、手表更慢。所以 [maxWaitMs] 只当上限兜底、
     * 数据一到就收手：固定窗口在慢设备上必然空手而归（提示不出圈、曲线每一手都没值）。
     *
     * 返回主变例；没等到上报则 null（调用方必须给出可见反馈，不能静默）。
     * 取消（用户落子/退到后台）也会尽力停掉自己这一代的分析，引擎不会卡在 ANALYZING。
     */
    private suspend fun analyzeOnce(
        moves: List<GoBoard.Move>,
        maxWaitMs: Int,
        extraMs: Int = 0,
    ): PvLine? {
        sendBoardCommands(moves)
        val epoch = searchEpoch
        val color = sideToMove(moves)
        val startedAt = SystemClock.elapsedRealtime()
        send("lz-analyze $color $ANALYZE_FREQ_CS")
        try {
            val first = withTimeoutOrNull(maxWaitMs.toLong()) {
                _status.first { it.pvLines.isNotEmpty() }.pvLines
            }
            if (first == null) {
                Log.w(
                    TAG,
                    "分析首帧 ${SystemClock.elapsedRealtime() - startedAt}ms 内无上报（上限 ${maxWaitMs}ms）",
                )
                return null
            }
            Log.i(TAG, "分析首帧 ${SystemClock.elapsedRealtime() - startedAt}ms 到位（${first.size} 路）")
            // 首帧只有 ~500 次模拟，首选点还很不稳；多留一会儿让它长结实
            if (extraMs > 0) delay(extraMs.toLong())
            return _status.value.pvLines.minByOrNull { it.index } ?: first.minByOrNull { it.index }
        } finally {
            stopIfCurrent(epoch)
        }
    }

    /** 停掉「自己那一代」的分析：代号已变（别人接手 / 已经停过 / 相位不是分析中）就什么都不做 */
    private suspend fun stopIfCurrent(epoch: Long) {
        withContext(NonCancellable + writeDispatcher) {
            if (searchEpoch == epoch && _status.value.phase == EngineStatus.Phase.ANALYZING) sendStop()
        }
    }

    /**
     * 「提示」用：等到首帧候选后再多看 [extraMs]（让首选点稳一些），返回主变例。
     * 调用前必须确保引擎空闲（分析必须自己结束，否则相位停在 ANALYZING，提示圈画不出来）。
     */
    suspend fun analyzeFor(
        moves: List<GoBoard.Move>,
        maxWaitMs: Int,
        extraMs: Int = 0,
    ): Result<PvLine?> = withContext(writeDispatcher) {
        try {
            Result.success(analyzeOnce(moves, maxWaitMs, extraMs))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * 全谱扫描的单局面版本：拿到第一帧上报就停，返回主变例。
     * 超过 [maxWaitMs] 仍无上报则返回 null（这一手无评估，调用方视作空洞而不是错误）。
     * 调用前必须确保引擎空闲。
     */
    suspend fun scanAnalyze(moves: List<GoBoard.Move>, maxWaitMs: Int): Result<PvLine?> =
        withContext(writeDispatcher) {
            try {
                Result.success(analyzeOnce(moves, maxWaitMs))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    /** 发整盘重放命令；调用方保证后续等待逻辑 */
    private fun sendBoardCommands(moves: List<GoBoard.Move>) {
        prepareSearch(EngineStatus.Phase.ANALYZING)
        sendPosition(moves)
    }

    /** clear_board + play 重放（含 pass）；不改阶段、不触发搜索 */
    private fun sendPosition(moves: List<GoBoard.Move>) {
        send("clear_board")
        for (m in moves) {
            if (m.isPass) {
                send("play ${colorName(m.color)} pass")
            } else {
                send("play ${colorName(m.color)} ${toGtpCoord(m.x, m.y)}")
            }
        }
    }

    private fun sideToMove(moves: List<GoBoard.Move>): String =
        if (moves.isEmpty()) "black" else colorName(moves.last().color.opponent)

    private fun colorName(c: GoBoard.Color): String = if (c == GoBoard.Color.BLACK) "black" else "white"

    private fun timeSettingsCmd(timeMs: Int): String {
        val sec = (timeMs / 1000).coerceAtLeast(1)
        return "kgs-time_settings byoyomi 0 $sec 1"
    }

    // --------------------------------------------------------- 终局与同步

    /** 双 pass 终局后请求引擎数子；失败（如 "? too early to pass"）由调用方回退本地数子 */
    suspend fun finalScore(): Result<String> = withContext(writeDispatcher) {
        try {
            Result.success(sendAwait("final_score").await().getOrThrow())
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** 只同步盘面，不触发搜索（扫描后把引擎内部盘面放回实战局面） */
    suspend fun syncBoard(moves: List<GoBoard.Move>): Result<Unit> = withContext(writeDispatcher) {
        runCatching { sendPosition(moves) }
    }

    // --------------------------------------------------------- 停止

    /** 停止实时分析（genmove 类限时搜索会自止，不用干预） */
    fun stop() {
        scope.launch(writeDispatcher) {
            if (_status.value.phase == EngineStatus.Phase.ANALYZING) {
                sendStop()
            }
        }
    }

    /**
     * 等待引擎回到空闲。改局面/重新分析前调用，避免与在途 lz 输出竞争。
     * genmove 不可中断：限时搜索必定自止，THINKING 时只需等相位回 IDLE。
     */
    suspend fun stopAndAwait(timeoutMs: Long = 4000): Boolean {
        if (_status.value.phase == EngineStatus.Phase.IDLE) return true
        if (_status.value.phase == EngineStatus.Phase.ANALYZING) {
            withContext(writeDispatcher) { sendStop() }
        }
        val idle = withTimeoutOrNull(timeoutMs) {
            _status.first { it.phase == EngineStatus.Phase.IDLE }
        }
        if (idle == null) Log.w(TAG, "stopAndAwait timed out")
        return idle != null
    }

    /** 相位卡死时强制回落（引擎丢命令时 UI 不会永远停在"思考中"） */
    fun resetPhaseIfStuck() {
        _status.update {
            if (it.phase == EngineStatus.Phase.IDLE) it else it.copy(phase = EngineStatus.Phase.IDLE)
        }
    }

    // --------------------------------------------------------- 内部

    /** 标记一次搜索开始：清空上一次的候选，把阶段置为搜索中，并翻新分析代号。 */
    private fun prepareSearch(phase: EngineStatus.Phase) {
        searchEpoch++
        _status.update {
            it.copy(phase = phase, pvLines = emptyList(), bestMoves = emptyList())
        }
    }

    /** 装好挂起点后执行 [action]，等待引擎输出最终着手行（"play <coord>"）。 */
    private suspend fun requestMoves(action: () -> Unit): Pt {
        val def = CompletableDeferred<Pt>()
        awaitingMoves = def
        return try {
            action()
            def.await()
        } catch (t: Throwable) {
            runCatching { send("lz-analyze black 0") }
            throw t
        } finally {
            if (awaitingMoves === def) awaitingMoves = null
        }
    }

    /** 发一条命令并登记应答槽；返回的 deferred 由解析线程按序填充。 */
    private fun sendAwait(cmd: String): CompletableDeferred<Result<String>> {
        val def = CompletableDeferred<Result<String>>()
        replyQueue.add(ReplySlot(cmd, def))
        Log.d(TAG, ">> $cmd")
        PachiNative.write(cmd)
        return def
    }

    /** fire-and-forget：同样占一个应答槽（保持队列与应答严格同序），但不返回句柄 */
    private fun send(cmd: String) {
        sendAwait(cmd)
    }

    /**
     * 停止实时分析：pachi 对 `lz-analyze <color> 0` 只回 `=`，没有终局上报，
     * 相位必须由 App 自己落回 IDLE；竞态中迟到的 info 行会被 IDLE 分支丢弃。
     * 须在 writeDispatcher 上调用，保证与后续命令同序入队。
     */
    private fun sendStop() {
        searchEpoch++
        send("lz-analyze black 0")
        _status.update {
            if (it.phase == EngineStatus.Phase.ANALYZING) it.copy(phase = EngineStatus.Phase.IDLE) else it
        }
    }

    /**
     * 哪些命令的 `=` 应答**可能带内容**。pachi 里只有 final_score 会回分数
     * （gtp.c: gtp_prefix('=') + 各自 handler 的输出），其余命令成功时都是空的 "= "。
     * 空的应答也可能是命令失败（"?" 分支），这条判断只用来识别混进管道的噪声行。
     */
    private fun returnsPayload(cmd: String): Boolean =
        cmd.substringBefore(' ').equals("final_score", ignoreCase = true)

    private fun handleLine(line: String) {
        Log.v(TAG, "<< $line")

        // lz 流式候选（一行可含多段 info move）
        if (line.contains("info move ")) {
            handleInfoMoveLine(line)
            return
        }

        // lz-genmove_analyze 的收尾着手行
        val play = PLAY_LINE.matcher(line)
        if (play.matches()) {
            val move = when (play.group(1)) {
                "pass" -> passMove
                "resign" -> resignMove
                else -> fromGtpCoord(play.group(1))
            }
            _status.update { it.copy(phase = EngineStatus.Phase.IDLE, bestMoves = listOf(move)) }
            awaitingMoves?.complete(move)
            awaitingMoves = null
            return
        }

        // GTP 应答（= / ?）：按序填槽
        val reply = GTP_REPLY.matcher(line)
        if (reply.matches()) {
            val ok = reply.group(1) == "="
            val body = reply.group(2)?.trim().orEmpty()
            val head = replyQueue.peek()
            if (ok && body.isNotEmpty() && head != null && !returnsPayload(head.cmd)) {
                // 队首命令成功时只回 "= "，带内容的 "=" 行不是它的应答：丢弃，别把队列顶偏
                Log.w(TAG, "ignoring stray stdout line on engine pipe: $line")
                return
            }
            val slot = replyQueue.poll()?.deferred
            if (slot != null) {
                slot.complete(if (ok) Result.success(body) else Result.failure(EngineException(body)))
            }
            if (!ok) {
                Log.w(TAG, "engine error: $body")
                awaitingMoves?.completeExceptionally(EngineException(body))
                awaitingMoves = null
                // 相位回落，避免 UI 停在"思考中"
                _status.update { if (it.phase == EngineStatus.Phase.IDLE) it else it.copy(phase = EngineStatus.Phase.IDLE) }
            }
            return
        }
        // 其余横幅/横幅噪声忽略
    }

    /** 解析一行里的所有 `info move` 段，更新候选列表（按 order 排序，取前若干路） */
    private fun handleInfoMoveLine(line: String) {
        if (_status.value.phase == EngineStatus.Phase.IDLE) return
        val lines = mutableListOf<PvLine>()
        val m = INFO_MOVE.matcher(line)
        while (m.find()) {
            val coord = m.group(1)
            if (coord == "pass" || coord == "resign") continue
            val pt = runCatching { fromGtpCoord(coord) }.getOrNull() ?: continue
            val visits = m.group(2)?.toLongOrNull() ?: 0L
            val winrate = (m.group(3)?.toIntOrNull() ?: 0) / 10000f
            val order = m.group(5)?.toIntOrNull() ?: 0
            val pv = m.group(6).orEmpty()
            val moves = pv.split(' ').mapNotNull { c ->
                if (c == "pass" || c == "resign") null
                else runCatching { fromGtpCoord(c) }.getOrNull()
            }
            // pachi 的 pv 首项就是这一手的坐标（"info move C4 ... pv C4 Q17"），
            // 再拼一次会让界面显示成 "C4 C4 Q17"，所以首项与 pt 相同时去掉
            val rest = if (moves.firstOrNull() == pt) moves.drop(1) else moves
            lines.add(
                PvLine(
                    index = order,
                    nodes = visits,
                    winRate = winrate,
                    moves = listOf(pt) + rest,
                )
            )
        }
        if (lines.isEmpty()) return
        _status.update { s ->
            val merged = (s.pvLines.filter { existing -> lines.none { it.index == existing.index } } + lines)
                .sortedBy { it.index }
            s.copy(pvLines = merged)
        }
    }

    // --------------------------------------------------------- 坐标

    private fun toGtpCoord(x: Int, y: Int): String {
        val col = if (x < 8) ('A'.code + x).toChar() else ('A'.code + x + 1).toChar()
        val row = boardSize - y
        return "$col$row"
    }

    private fun fromGtpCoord(s: String): Pt {
        val m = COORD.matcher(s)
        if (!m.matches()) throw EngineException("bad coord: $s")
        var col = m.group(1)[0].uppercaseChar() - 'A'
        if (col >= 8) col-- // GTP 跳过 I
        val row = m.group(2).toInt()
        return col to (boardSize - row)
    }

    class EngineException(msg: String) : Exception(msg)
}
