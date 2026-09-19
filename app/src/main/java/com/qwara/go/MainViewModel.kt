// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qwara.go.data.SettingsRepository
import com.qwara.go.engine.EngineStatus
import com.qwara.go.engine.EngineValue
import com.qwara.go.engine.HintPoint
import com.qwara.go.engine.PachiEngine
import com.qwara.go.engine.PvLine
import com.qwara.go.engine.ScanBudget
import com.qwara.go.game.GoBoard
import com.qwara.go.ui.util.FeedbackHelper
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 屏幕导航 */
enum class Screen { MENU, GAME, ANALYSIS, SETTINGS }

enum class GameMode { TWO_PLAYER, AI, ANALYSIS }

/**
 * 「提示」等待引擎第一帧候选上报的上限。pachi 要等某个点攒够 500 次模拟才吐第一行，
 * 首帧延迟由设备算力决定（宿主 0.6s / 模拟器 1.1s / 手表更慢），所以这里只当兜底上限——
 * 数据一到就停，放宽不会拖慢正常情况，但慢设备上不会再「点了没反应」。
 */
private const val HINT_FIRST_REPORT_MS = 15000

/** 提示在首帧之后再留一会儿：首帧只有 ~500 次模拟，首选点还很不稳 */
private const val HINT_EXTRA_MS = 1000

/**
 * 搜索中段 PV 状态推送的最小间隔。引擎每次上报都是一次全量 GameUiState 拷贝 +
 * 整屏重组 + 整盘重绘，压到最多约 8 次/秒肉眼依旧流畅；相位变化（含 IDLE）不受限。
 */
private val PV_PUSH_INTERVAL = 120.milliseconds

/**
 * 注意：grid 为 ByteArray，data class 的 equals 对它按引用比较。刷新时总是生成新数组，
 * 因此状态变更不会被 equals 吞掉；请不要复用同一个数组实例去改内容。
 */
data class GameUiState(
    val mode: GameMode = GameMode.TWO_PLAYER,
    val moves: List<GoBoard.Move> = emptyList(),
    val grid: ByteArray = ByteArray(19 * 19),
    val boardSize: Int = 19,
    val sideToMove: GoBoard.Color = GoBoard.Color.BLACK,
    /** 终局结果文案（"黑胜 · 3.5 目" / "白中盘胜"），null 表示未终局 */
    val gameOver: String? = null,
    val humanColor: GoBoard.Color = GoBoard.Color.BLACK,
    val engineThinking: Boolean = false,
    val enginePhase: EngineStatus.Phase = EngineStatus.Phase.IDLE,
    val pvLines: List<PvLine> = emptyList(),
    val bestMoves: List<Pair<Int, Int>> = emptyList(),
    val hint: Pair<Int, Int>? = null,
    /** 提示的引擎分析在途（状态胶囊显示「提示分析中…」，让点按立刻有反馈） */
    val hintBusy: Boolean = false,
    /** 提示圈脉冲代号：同一个点再次提示也要重新呼吸，所以脉冲以它为键 */
    val hintNonce: Int = 0,
    /** 复盘浏览：非空时棋盘显示前 N 手的局面（null = 当前局面） */
    val viewPly: Int? = null,
    /** 评估曲线：手数 → 黑方胜率（0..1） */
    val curve: Map<Int, Float> = emptyMap(),
    /** 全谱分析进度（已分析手数 / 总手数） */
    val scan: Progress? = null,
    val editMode: Boolean = false,
    val editColor: GoBoard.Color = GoBoard.Color.BLACK,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: String? = null,
    val komi: Float = 6.5f,
    val engineTimeSec: Int = 3,
    /** 分析路数（多点分析，取 lz 上报的前 N 路） */
    val analysisLines: Int = 3,
    val showMoveNumbers: Boolean = true,
    val showCandidates: Boolean = true,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
) {
    data class Progress(val done: Int, val total: Int)

    /** 复盘浏览时只显示前 [viewPly] 手，否则显示全部 */
    val visibleMoves: List<GoBoard.Move>
        get() = viewPly?.let { moves.take(it) } ?: moves

    fun colorAt(x: Int, y: Int): GoBoard.Color {
        val v = grid.getOrElse(y * boardSize + x) { 0 }.toInt()
        return when (v) {
            1 -> GoBoard.Color.BLACK
            2 -> GoBoard.Color.WHITE
            else -> GoBoard.Color.EMPTY
        }
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val engine = PachiEngine(app)
    private val feedback = FeedbackHelper(app)

    private var board = GoBoard(settingsRepo.settings.value.boardSize)
    private val redoStack = ArrayDeque<GoBoard.Move>()

    private val _screen = MutableStateFlow(Screen.MENU)
    val screen: StateFlow<Screen> = _screen

    private val _ui = MutableStateFlow(GameUiState())
    val ui: StateFlow<GameUiState> = _ui

    private var messageJob: Job? = null

    /** 在途的提示分析；非空表示「提示分析中」，再点一次＝取消 */
    private var hintJob: Job? = null

    /** 本地下达的引擎请求是否在途（应着请求/全谱扫描，仅主线程访问） */
    private var aiMoveInFlight = false

    /** 全谱分析任务；非空表示正在扫描（此时禁止一切改局面操作） */
    private var scanJob: Job? = null

    /**
     * 全谱分析代号：开扫与取消都 +1。取消后旧任务的 `finally` 会看到代号已变，
     * 于是不再去清理/回写（否则「取消后立刻重扫」会被旧任务把进度、局面和引擎盘面一起覆盖）。
     */
    private var scanGeneration = 0

    /** 进入分析页的来源：来自对局时「返回」回对局 */
    private var analysisOrigin: Screen = Screen.MENU

    /** 当前这盘棋的模式（双人/人机） */
    private var gameMode: GameMode = GameMode.TWO_PLAYER

    /** 当前引擎会话的路数+贴目；不一致时 ensureEngine 重建 */
    private var sessionKey: Pair<Int, Float>? = null

    /** 宿主已 onStop：熄屏/切后台 */
    private var backgrounded = false

    /** 后台时停掉了正在进行的实时分析：回前台需重启 */
    private var resumeAnalysisOnStart = false

    /** 后台时取消了正在进行的全谱扫描：回前台需重扫 */
    private var scanWasRunning = false

    init {
        // 合并设置流：路数变化需要换棋盘对象；贴目/时间只是状态同步
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                val sizeChanged = board.size != s.boardSize
                if (sizeChanged) {
                    board = GoBoard(s.boardSize)
                    redoStack.clear()
                    sessionKey = null
                }
                _ui.update {
                    it.copy(
                        boardSize = s.boardSize,
                        komi = s.komi,
                        engineTimeSec = s.engineTimeSec,
                        analysisLines = s.analysisLines,
                        showMoveNumbers = s.showMoveNumbers,
                        showCandidates = s.showCandidates,
                        sound = s.sound,
                        vibrate = s.vibrate,
                    )
                }
                if (sizeChanged) {
                    _ui.update {
                        it.copy(
                            gameOver = null, hint = null, viewPly = null,
                            curve = emptyMap(), pvLines = emptyList(), bestMoves = emptyList(),
                        )
                    }
                    refresh()
                }
            }
        }
        // 合并引擎状态流：相位变化（含 IDLE，驱动提示与输入锁）立即推送；搜索中段的
        // PV 刷屏按 PV_PUSH_INTERVAL 节流
        viewModelScope.launch {
            var lastPush = TimeSource.Monotonic.markNow()
            engine.status.collect { st ->
                val phaseChanged = st.phase != _ui.value.enginePhase
                val searching = st.phase == EngineStatus.Phase.THINKING ||
                    st.phase == EngineStatus.Phase.ANALYZING
                if (searching && !phaseChanged && lastPush.elapsedNow() < PV_PUSH_INTERVAL) {
                    return@collect
                }
                lastPush = TimeSource.Monotonic.markNow()
                _ui.update { ui ->
                    // 曲线记录并入同一次状态更新，避免每路 PV 触发两次全量拷贝
                    var curve = ui.curve
                    if (scanJob == null && !ui.editMode) {
                        val wr = st.pvLines.firstOrNull()?.winRate
                        if (wr != null && !wr.isNaN()) {
                            val ply = board.moveCount
                            val black = EngineValue.blackWinRate(wr, ply)
                            if (ui.curve[ply] != black) curve = ui.curve + (ply to black)
                        }
                    }
                    ui.copy(
                        enginePhase = st.phase,
                        pvLines = st.pvLines,
                        bestMoves = st.bestMoves.filter { it.first >= 0 },
                        engineThinking = aiMoveInFlight || st.phase == EngineStatus.Phase.THINKING,
                        curve = curve,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- 导航

    fun toMenu() {
        cancelFullScan()
        stopAnalysis()
        _screen.value = Screen.MENU
    }

    fun openSettings() {
        _screen.value = Screen.SETTINGS
    }

    fun backFromSettings() {
        _screen.value = Screen.MENU
    }

    fun newTwoPlayerGame() {
        startGame(GameMode.TWO_PLAYER, GoBoard.Color.BLACK)
    }

    fun newAiGame(humanColor: GoBoard.Color) {
        startGame(GameMode.AI, humanColor)
    }

    fun openAnalysis() {
        analysisOrigin = _screen.value
        _screen.value = Screen.ANALYSIS
        _ui.update { it.copy(mode = GameMode.ANALYSIS) }
    }

    fun sendGameToAnalysis() {
        openAnalysis()
    }

    fun backFromAnalysis() {
        cancelFullScan()
        stopAnalysis()
        if (analysisOrigin == Screen.GAME || canResumeGame()) {
            resumeGame()
        } else {
            toMenu()
        }
    }

    private fun canResumeGame(): Boolean =
        board.moveCount > 0 && _ui.value.gameOver == null

    fun resumeGame() {
        if (!canResumeGame()) return
        cancelHint()
        _ui.update { it.copy(mode = gameMode, viewPly = null) }
        _screen.value = Screen.GAME
        refresh()
    }

    private fun startGame(mode: GameMode, humanColor: GoBoard.Color) {
        cancelFullScan()
        gameMode = mode
        board.clear()
        redoStack.clear()
        cancelHint()
        _ui.update {
            it.copy(
                mode = mode,
                humanColor = humanColor,
                gameOver = null,
                editMode = false,
                pvLines = emptyList(),
                bestMoves = emptyList(),
                engineThinking = false,
                viewPly = null,
                curve = emptyMap(),
            )
        }
        _screen.value = Screen.GAME
        refresh()
        if (mode == GameMode.AI) {
            viewModelScope.launch {
                val s = settingsRepo.settings.value
                if (!ensureEngine()) return@launch
                if (humanColor == GoBoard.Color.WHITE) {
                    // 引擎执黑先行
                    withAiMoveInFlight { engine.engineMoveFirst(emptyList(), s.engineTimeSec * 1000) }
                        .onSuccess { applyEngineMove(it) }
                        .onFailure { showMessage("引擎出错：${it.message}") }
                }
            }
        }
    }

    // ------------------------------------------------------------- 前后台

    fun onHostStopped() {
        backgrounded = true
        val wasScanning = scanJob != null
        if (wasScanning) {
            scanWasRunning = true
            cancelFullScan()
        }
        if (!wasScanning && hintJob == null &&
            engine.status.value.phase == EngineStatus.Phase.ANALYZING
        ) {
            resumeAnalysisOnStart = true
            viewModelScope.launch {
                if (engine.status.value.phase == EngineStatus.Phase.ANALYZING) engine.stopAndAwait()
            }
        }
    }

    fun onHostStarted() {
        backgrounded = false
        val resumeScan = scanWasRunning
        val resumeAnalysis = resumeAnalysisOnStart
        scanWasRunning = false
        resumeAnalysisOnStart = false
        when {
            resumeScan -> startFullScan()
            resumeAnalysis && _screen.value == Screen.ANALYSIS && _ui.value.mode == GameMode.ANALYSIS -> {
                viewModelScope.launch {
                    if (!ensureEngine()) return@launch
                    if (!engine.stopAndAwait()) return@launch
                    engine.syncAndAnalyze(board.moves.toList(), 0)
                }
            }
        }
    }

    // ------------------------------------------------------------- 输入

    fun onBoardTap(x: Int, y: Int) {
        val s = _ui.value
        if (engineThinkingNow()) return
        if (s.viewPly != null) {
            setViewPly(null)
            showMessage("已回到当前局面")
            return
        }
        // 终局后从主菜单进「局面分析」会带着 gameOver：分析是对局面的自由沙盒，
        // 落子/摆谱必须照常可用（对局页的落子仍被拦，避免终局后误触改盘）
        if (s.gameOver != null && s.mode != GameMode.ANALYSIS) return
        if (!board.inBounds(x, y)) return

        val color = when {
            s.editMode -> s.editColor
            else -> s.sideToMove
        }

        when (val illegal = board.isLegal(x, y, color)) {
            GoBoard.Illegal.OCCUPIED -> {
                feedback.playErrorSound(s.sound)
                showMessage("该点已有棋子")
                return
            }
            GoBoard.Illegal.SUICIDE -> {
                feedback.playErrorSound(s.sound)
                showMessage("自杀禁着")
                return
            }
            GoBoard.Illegal.KO -> {
                feedback.playErrorSound(s.sound)
                showMessage("劫争禁着")
                return
            }
            null -> Unit
        }

        placeStone(x, y, color, fromEngine = false)
    }

    /** 停一手 */
    fun pass() {
        val s = _ui.value
        if (engineThinkingNow()) return
        if (s.viewPly != null) {
            showMessage("复盘浏览中，先点「回到当前」")
            return
        }
        if (s.gameOver != null) return
        val color = if (s.editMode) s.editColor else s.sideToMove
        doPass(color, fromEngine = false)
    }

    private fun doPass(color: GoBoard.Color, fromEngine: Boolean) {
        board.pass(color)
        redoStack.clear()
        feedback.playPlaceSound(_ui.value.sound)
        cancelHint()

        if (!_ui.value.editMode && board.consecutivePasses >= 2) {
            endByDoublePass()
            refresh()
            return
        }

        val engineFollows = !fromEngine && _ui.value.mode == GameMode.AI && _ui.value.gameOver == null
        if (engineFollows) requestEngineMove(board.moves.toList())
        refresh()
        if (_ui.value.mode == GameMode.ANALYSIS) restartAnalysisIfActive()
    }

    /** 双停一手终局：先本地数子立即显示，再尝试引擎数子校正 */
    private fun endByDoublePass() {
        val s = settingsRepo.settings.value
        val score = board.score(s.komi)
        val text = scoreText(score)
        _ui.update { it.copy(gameOver = text) }
        feedback.vibrate(_ui.value.vibrate, 80)
        viewModelScope.launch {
            if (!ensureEngine()) return@launch
            // 会话若是刚重建的，引擎盘面还是空的（startNewGame 只 clear_board、不重放），
            // 数子前必须把终局盘面（含两手 pass）推过去
            engine.syncBoard(board.moves.toList())
            engine.finalScore()
                .onSuccess { eng ->
                    // 引擎数子（"B+3.5"/"W+12"/"0"）比本地权威
                    _ui.update { it.copy(gameOver = engineScoreText(eng)) }
                }
                .onFailure {
                    // "? too early to pass" 等：保留本地结果
                }
        }
    }

    private fun scoreText(score: GoBoard.Score): String {
        val diff = score.diff
        return when {
            diff > 0 -> "黑胜 %.1f 目".format(diff)
            diff < 0 -> "白胜 %.1f 目".format(-diff)
            else -> "和棋"
        }
    }

    private fun engineScoreText(eng: String): String {
        // pachi: "B+3.5" / "W+12" / "0"
        val t = eng.trim()
        if (t == "0") return "和棋"
        val m = Regex("([BW])\\+([0-9.]+)").find(t) ?: return scoreText(board.score(settingsRepo.settings.value.komi))
        val (winner, pts) = m.destructured
        return (if (winner == "B") "黑胜" else "白胜") + " $pts 目"
    }

    private fun placeStone(x: Int, y: Int, color: GoBoard.Color, fromEngine: Boolean) {
        val result = board.place(x, y, color)
        if (!result.legal) {
            showMessage("着法无效")
            return
        }
        redoStack.clear()
        feedback.playPlaceSound(_ui.value.sound)
        feedback.vibrate(_ui.value.vibrate)
        cancelHint()

        // AI 模式：用户落子后驱动引擎。先发起请求，使思考锁在本次刷新时即已生效
        val engineFollows = !fromEngine && _ui.value.mode == GameMode.AI && _ui.value.gameOver == null
        if (engineFollows) requestEngineMove(board.moves.toList())
        refresh()
        if (_ui.value.mode == GameMode.ANALYSIS) restartAnalysisIfActive()
    }

    /** 引擎应着结果落盘：普通点、pass、认输 */
    private fun applyEngineMove(pt: Pair<Int, Int>) {
        val engine = engine
        when {
            pt.first == engine.passMove.first -> {
                showMessage("引擎停一手")
                doPass(_ui.value.sideToMove, fromEngine = true)
            }
            pt.first == engine.resignMove.first -> {
                val winner = if (_ui.value.sideToMove == GoBoard.Color.BLACK) "白" else "黑"
                _ui.update { it.copy(gameOver = "${winner}中盘胜") }
                feedback.vibrate(_ui.value.vibrate, 80)
            }
            else -> {
                val (x, y) = pt
                if (board.inBounds(x, y) && board.get(x, y) == GoBoard.Color.EMPTY) {
                    placeStone(x, y, _ui.value.sideToMove, fromEngine = true)
                } else {
                    showMessage("引擎返回非法点：$x,$y")
                }
            }
        }
    }

    private fun requestEngineMove(position: List<GoBoard.Move>) {
        val s = _ui.value
        val timeMs = s.engineTimeSec * 1000
        val timeout = timeMs * 4L + 8000
        viewModelScope.launch {
            // 会话可能还没开或已被设置改动作废（路数/贴目变了要重建）：
            // 应着前确认一次，重建后整盘重放会立刻把盘面推回去，不会漂移
            if (!ensureEngine()) return@launch
            val mv = withAiMoveInFlight {
                withTimeoutOrNull(timeout) { engine.playUserMove(position, timeMs) }
                    ?: Result.failure(PachiEngine.EngineException("引擎超时"))
            }
            mv.onSuccess { applyEngineMove(it) }.onFailure {
                engine.resetPhaseIfStuck()
                showMessage("引擎出错：${it.message}")
            }
        }
    }

    private suspend fun <T> withAiMoveInFlight(block: suspend () -> T): T {
        aiMoveInFlight = true
        return try {
            block()
        } finally {
            aiMoveInFlight = false
            _ui.update { it.copy(engineThinking = engineThinkingNow()) }
        }
    }

    // ------------------------------------------------------------- 悔棋/重做/清空

    fun undo() {
        val s = _ui.value
        if (engineThinkingNow()) return
        if (s.mode == GameMode.AI) {
            // 需要回退到用户上一手：引擎一手 + 用户一手
            if (board.moveCount < 2) return
            board.undo()
            board.undo()
            redoStack.clear() // AI 模式下不重做（引擎状态难同步）
            viewModelScope.launch { engine.takeback(2) }
        } else {
            val m = board.undo() ?: return
            redoStack.addLast(m)
        }
        cancelHint()
        _ui.update { it.copy(gameOver = null) }
        refresh()
        if (s.mode == GameMode.ANALYSIS) restartAnalysisIfActive()
    }

    fun redo() {
        val s = _ui.value
        if (engineThinkingNow()) return
        if (s.mode == GameMode.AI) return
        val m = redoStack.removeLastOrNull() ?: return
        if (m.isPass) board.pass(m.color) else board.place(m.x, m.y, m.color)
        refresh()
        if (s.mode == GameMode.ANALYSIS) restartAnalysisIfActive()
    }

    fun clearBoard() {
        if (engineThinkingNow()) return
        cancelFullScan()
        board.clear()
        redoStack.clear()
        cancelHint()
        _ui.update {
            it.copy(gameOver = null, viewPly = null, curve = emptyMap())
        }
        refresh()
    }

    fun restart() {
        val s = _ui.value
        when (s.mode) {
            GameMode.AI -> startGame(GameMode.AI, s.humanColor)
            else -> clearBoard()
        }
    }

    fun toggleEditMode() {
        _ui.update { it.copy(editMode = !it.editMode) }
    }

    fun setEditColor(c: GoBoard.Color) {
        _ui.update { it.copy(editColor = c) }
    }

    /** 翻转棋盘（分析模式：黑白互换；贴目不变，仅供研究） */
    fun flipColors() {
        if (engineThinkingNow()) return
        val flipped = board.moves.map { GoBoard.Move(it.x, it.y, it.color.opponent) }
        val size = board.size
        board = GoBoard(size)
        board.restore(ByteArray(size * size), flipped)
        redoStack.clear()
        cancelHint()
        _ui.update { it.copy(viewPly = null, curve = emptyMap()) }
        refresh()
        restartAnalysisIfActive()
    }

    // ------------------------------------------------------------- 提示与分析

    /**
     * 提示：等引擎给出第一帧候选上报，把首选点画成提示圈。
     *
     * 等待窗口只当上限——pachi 的首帧上报要等某个点攒够 500 次模拟（`uct/walk.c` 的
     * `uct_get_best_moves(..., 500)`），手表上远超旧的两秒固定窗口，而旧的固定窗口等不到数据时
     * 什么都不会发生（提示圈画不出来，也没有任何文字反馈）。
     *
     * 分析中再点一次＝取消。按钮在引擎思考中/终局时是灰的，但点下去仍会说明原因，不再静默吞掉。
     */
    fun hint() {
        if (hintJob != null) {
            cancelHint()
            showMessage("已取消提示")
            return
        }
        val s = _ui.value
        if (s.gameOver != null) return
        if (engineThinkingNow()) {
            showMessage("引擎思考中，请稍候再试")
            return
        }
        if (s.viewPly != null) {
            showMessage("复盘浏览中，先点「回到当前」")
            return
        }
        // start 推迟到 hintJob 赋值之后：finally 里要靠身份判断自己是不是「当前那个」提示任务
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                // 先亮「提示分析中…」：引擎会话冷启动在手表上要好几秒，反馈晚于它就是没反馈
                _ui.update { it.copy(hintBusy = true, hint = null) }
                if (!ensureEngine()) return@launch
                if (!engine.stopAndAwait()) {
                    showMessage("引擎无响应，请重试")
                    return@launch
                }
                // 分析必须自己停：pachi 的 lz-analyze 是无限分析，不主动停相位就永远停在
                // ANALYZING（提示圈等的是 IDLE），人机对战里还会把落子闸门一直关着
                val pv = engine
                    .analyzeFor(board.moves.toList(), HINT_FIRST_REPORT_MS, HINT_EXTRA_MS)
                    .getOrNull()
                val pt = pv?.moves?.firstOrNull { it.first >= 0 }
                if (pt != null) {
                    _ui.update { it.copy(hint = pt, hintNonce = it.hintNonce + 1) }
                } else {
                    showMessage("引擎未给出建议，请重试")
                }
            } finally {
                if (hintJob === self) {
                    hintJob = null
                    _ui.update { it.copy(hintBusy = false) }
                }
            }
        }
        hintJob = job
        job.start()
    }

    /** 取消在途的提示分析并清掉提示圈（改局面、退后台都要调，免得旧结果画到新局面上） */
    private fun cancelHint() {
        hintJob?.cancel()
        hintJob = null
        _ui.update { it.copy(hintBusy = false, hint = null) }
    }

    fun toggleAnalysis() {
        val s = _ui.value
        if (s.viewPly != null) {
            showMessage("复盘浏览中，先点「回到当前」")
            return
        }
        if (scanJob != null) return
        if (s.enginePhase == EngineStatus.Phase.ANALYZING) {
            engine.stop()
            return
        }
        viewModelScope.launch {
            if (!ensureEngine()) return@launch
            if (!engine.stopAndAwait()) {
                showMessage("引擎无响应，请重试")
                return@launch
            }
            engine.syncAndAnalyze(board.moves.toList(), 0)
        }
    }

    fun stopAnalysis() {
        if (engine.status.value.phase == EngineStatus.Phase.ANALYZING) engine.stop()
    }

    private fun restartAnalysisIfActive() {
        if (backgrounded) return
        if (_ui.value.enginePhase != EngineStatus.Phase.ANALYZING) return
        viewModelScope.launch {
            if (!engine.stopAndAwait()) return@launch
            engine.syncAndAnalyze(board.moves.toList(), 0)
        }
    }

    // ------------------------------------------------------------- 复盘与评估曲线

    fun setViewPly(ply: Int?) {
        val v = ply?.coerceIn(0, board.moveCount)
        if (_ui.value.viewPly == v) return
        cancelHint()
        _ui.update { it.copy(viewPly = v) }
        refresh()
    }

    /** 全谱分析：逐手分析（每手等到引擎第一次上报就停），生成整局评估曲线；用 [cancelFullScan] 中断 */
    fun startFullScan() {
        val s = _ui.value
        if (scanJob != null) return
        if (engineThinkingNow()) {
            showMessage("引擎思考中，稍后再试")
            return
        }
        if (board.moveCount == 0) {
            showMessage("先在棋盘上走出着法")
            return
        }
        // 复盘浏览中也能开扫：扫描自己会逐手把局面推给 viewPly。以前这里直接 return，
        // 而曲线图表只要被碰到一下就会设 viewPly，等于把扫描键悄无声息地锁死。
        if (s.viewPly != null) setViewPly(null)
        cancelHint()
        val generation = ++scanGeneration
        scanJob = viewModelScope.launch {
            aiMoveInFlight = true
            val moves = board.moves.toList()
            val total = moves.size
            // 未扫到的手数沿用已有曲线：中断/失败不至于把上一次的数据一并抹掉
            val history = HashMap(_ui.value.curve.filterKeys { it <= total })
            val budget = ScanBudget()
            var holes = 0
            var finished = false
            try {
                if (!ensureEngine()) return@launch
                _ui.update { it.copy(scan = GameUiState.Progress(0, total)) }
                for (k in 1..total) {
                    if (!engine.stopAndAwait()) {
                        showMessage("引擎无响应，分析中断")
                        break
                    }
                    // 等待上限由 ScanBudget 按实测放宽：首帧上报要等某个点攒够 500 次模拟，
                    // 手表上远超模拟器，固定 3 秒会让每一手都空手而归（曲线永远是空的）
                    val startedAt = SystemClock.elapsedRealtime()
                    val pv = engine.scanAnalyze(moves.take(k), budget.next()).getOrNull()
                    val elapsed = (SystemClock.elapsedRealtime() - startedAt).toInt()
                    val wr = pv?.winRate
                    if (wr != null && !wr.isNaN()) {
                        history[k] = EngineValue.blackWinRate(wr, k)
                        budget.succeeded(elapsed)
                    } else {
                        budget.timedOut()
                        holes++
                    }
                    _ui.update {
                        it.copy(
                            scan = GameUiState.Progress(k, total),
                            curve = history.toMap(),
                            viewPly = k,
                        )
                    }
                    refresh()
                    finished = true
                }
                if (finished) {
                    if (holes == total) showMessage("引擎未返回评估，请重试")
                    else if (holes > 0) showMessage("全谱分析完成 · $holes 手无评估")
                }
            } finally {
                // 代号变了说明这次扫描已经被取消并可能已有新扫描接手：不要再回写现状
                if (scanGeneration == generation) {
                    scanJob = null
                    aiMoveInFlight = false
                    _ui.update {
                        it.copy(scan = null, viewPly = null, engineThinking = engineThinkingNow())
                    }
                    refresh()
                    val mode = _ui.value.mode
                    if (_screen.value == Screen.ANALYSIS && mode == GameMode.ANALYSIS) {
                        restartAnalysisIfActive()
                    } else if (mode == GameMode.AI) {
                        // 取消路径（后台/用户中断）时任务已取消，挂起调用必须包 NonCancellable
                        withContext(NonCancellable) {
                            if (engine.stopAndAwait()) engine.syncBoard(board.moves.toList())
                        }
                    }
                }
            }
        }
    }

    fun cancelFullScan() {
        val job = scanJob ?: return
        scanGeneration++
        scanJob = null
        job.cancel()
        engine.stop()
        // 立刻把界面从「扫描中」放出来（被取消任务自己的 finally 已过期，不会再做清理）
        aiMoveInFlight = false
        _ui.update { it.copy(scan = null, viewPly = null, engineThinking = engineThinkingNow()) }
        refresh()
    }

    /** 采纳引擎最佳着法（分析模式） */
    fun applyBestMove() {
        val s = _ui.value
        if (engineThinkingNow()) return
        if (s.viewPly != null) {
            showMessage("复盘浏览中，先点「回到当前」")
            return
        }
        val best = HintPoint.choose(s.bestMoves, s.pvLines) ?: return
        if (board.get(best.first, best.second) != GoBoard.Color.EMPTY) return
        val color = if (s.editMode) s.editColor else s.sideToMove
        placeStone(best.first, best.second, color, fromEngine = false)
    }

    /** 确保引擎会话可用且与当前路数/贴目一致 */
    private suspend fun ensureEngine(): Boolean {
        val s = settingsRepo.settings.value
        val key = s.boardSize to s.komi
        if (engine.isRunning && sessionKey == key) return true
        val r = engine.startNewGame(s.boardSize, s.komi, s.engineTimeSec * 1000)
        sessionKey = if (r.isSuccess) key else null
        if (r.isFailure) showMessage("引擎启动失败：${r.exceptionOrNull()?.message}")
        return r.isSuccess
    }

    // ------------------------------------------------------------- 设置

    val settings = settingsRepo.settings

    fun setBoardSize(size: Int) = settingsRepo.setBoardSize(size)
    fun setKomi(komi: Float) = settingsRepo.setKomi(komi)
    fun setEngineTime(sec: Int) = settingsRepo.setEngineTime(sec)
    fun setAnalysisLines(lines: Int) = settingsRepo.setAnalysisLines(lines)
    fun setShowMoveNumbers(on: Boolean) = settingsRepo.setShowMoveNumbers(on)
    fun setShowCandidates(on: Boolean) = settingsRepo.setShowCandidates(on)
    fun setSound(on: Boolean) = settingsRepo.setSound(on)
    fun setVibrate(on: Boolean) = settingsRepo.setVibrate(on)

    // ------------------------------------------------------------- 内部

    private fun engineThinkingNow(): Boolean =
        aiMoveInFlight || engine.status.value.phase == EngineStatus.Phase.THINKING

    private fun refresh() {
        val s = _ui.value
        val reviewing = s.viewPly != null
        val grid = s.viewPly?.let { gridAt(board.moves, it) } ?: board.snapshot()
        val sideToMove = s.viewPly?.let { ply ->
            board.moves.getOrNull(ply - 1)?.color?.opponent ?: GoBoard.Color.BLACK
        } ?: sideToMoveNow()
        _ui.update {
            it.copy(
                moves = board.moves.toList(),
                grid = grid,
                sideToMove = sideToMove,
                canUndo = board.moveCount > 0 && !engineThinkingNow() && !reviewing,
                canRedo = redoStack.isNotEmpty() && s.mode != GameMode.AI && !reviewing,
            )
        }
    }

    private fun sideToMoveNow(): GoBoard.Color =
        if (board.moveCount == 0) GoBoard.Color.BLACK else board.moves.last().color.opponent

    /** 前 [ply] 手形成的盘面（复盘浏览用；重放含提子，与真实对局一致） */
    private fun gridAt(moves: List<GoBoard.Move>, ply: Int): ByteArray {
        val replay = GoBoard(board.size)
        for (i in 0 until ply.coerceIn(0, moves.size)) {
            val m = moves[i]
            if (m.isPass) replay.pass(m.color) else replay.place(m.x, m.y, m.color)
        }
        return replay.snapshot()
    }

    private fun showMessage(msg: String) {
        messageJob?.cancel()
        _ui.update { it.copy(message = msg) }
        messageJob = viewModelScope.launch {
            delay(2200)
            _ui.update { it.copy(message = null) }
        }
    }

    override fun onCleared() {
        feedback.release()
        engine.shutdown()
        super.onCleared()
    }
}
