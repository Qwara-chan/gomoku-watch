package com.qwara.gomoku

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qwara.gomoku.data.SettingsRepository
import com.qwara.gomoku.engine.EngineStatus
import com.qwara.gomoku.engine.EngineValue
import com.qwara.gomoku.engine.PvLine
import com.qwara.gomoku.engine.RapfiEngine
import com.qwara.gomoku.game.Board
import com.qwara.gomoku.game.RenjuRules
import com.qwara.gomoku.game.Rule
import com.qwara.gomoku.ui.util.FeedbackHelper
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
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

enum class GameOver { BLACK_WIN, WHITE_WIN, DRAW }

/** 全谱分析每手思考时长（毫秒）：档位越低越快，40 手约 25 秒 */
private const val SCAN_TIME_MS = 600

/**
 * 搜索中段 PV 状态推送的最小间隔。引擎每次迭代×每路变例都会发一次状态，
 * 不节流时分析期间每秒可达数十次全量 GameUiState 拷贝 + 整屏重组 + 整盘重绘；
 * 压到最多约 8 次/秒肉眼依旧流畅，相位变化（含 IDLE）不受此限制、始终立即推送。
 */
private val PV_PUSH_INTERVAL = 120.milliseconds

/**
 * 注意：grid 为 ByteArray，data class 的 equals 对它按引用比较。刷新时总是生成新数组，
 * 因此状态变更不会被 equals 吞掉；请不要复用同一个数组实例去改内容。
 */
data class GameUiState(
    val mode: GameMode = GameMode.TWO_PLAYER,
    val moves: List<Board.Move> = emptyList(),
    val grid: ByteArray = ByteArray(15 * 15),
    val sideToMove: Board.Color = Board.Color.BLACK,
    val gameOver: GameOver? = null,
    val humanColor: Board.Color = Board.Color.BLACK,
    val engineThinking: Boolean = false,
    val enginePhase: EngineStatus.Phase = EngineStatus.Phase.IDLE,
    val pvLines: List<PvLine> = emptyList(),
    val bestMoves: List<Pair<Int, Int>> = emptyList(),
    val hint: Pair<Int, Int>? = null,
    /** 禁手点及其类型（仅连珠规则下计算） */
    val forbidden: Map<Pair<Int, Int>, RenjuRules.Forbidden> = emptyMap(),
    /** 终局时的胜利连线（复盘/对局结束展示） */
    val winLine: List<Pair<Int, Int>> = emptyList(),
    /** 复盘浏览：非空时棋盘显示前 N 手的局面（null = 当前局面） */
    val viewPly: Int? = null,
    /** 评估曲线：手数 → 黑方胜率（0..1） */
    val curve: Map<Int, Float> = emptyMap(),
    /** 全谱分析进度（已分析手数 / 总手数+1） */
    val scan: Progress? = null,
    val editMode: Boolean = false,
    val editColor: Board.Color = Board.Color.BLACK,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: String? = null,
    val rule: Rule = Rule.FREESTYLE,
    val engineTimeSec: Int = 3,
    /** 引擎棋力档位（仅人机对战；100=满强度） */
    val strength: Int = 100,
    /** 分析路数（多点分析） */
    val analysisLines: Int = 3,
    val showMoveNumbers: Boolean = true,
    val showForbidden: Boolean = true,
    val showWinLine: Boolean = true,
    val showCandidates: Boolean = true,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
) {
    data class Progress(val done: Int, val total: Int)

    val boardSize: Int get() = 15

    /** 复盘浏览时只显示前 [viewPly] 手，否则显示全部 */
    val visibleMoves: List<Board.Move>
        get() = viewPly?.let { moves.take(it) } ?: moves

    fun colorAt(x: Int, y: Int): Board.Color {
        val v = grid.getOrElse(y * 15 + x) { 0 }.toInt()
        return when (v) {
            1 -> Board.Color.BLACK
            2 -> Board.Color.WHITE
            else -> Board.Color.EMPTY
        }
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val engine = RapfiEngine(app)
    private val feedback = FeedbackHelper(app)

    private val board = Board()
    private val redoStack = ArrayDeque<Board.Move>()

    private val _screen = MutableStateFlow(Screen.MENU)
    val screen: StateFlow<Screen> = _screen

    private val _ui = MutableStateFlow(GameUiState())
    val ui: StateFlow<GameUiState> = _ui

    private var hintActive = false
    private var messageJob: Job? = null

    /** 本地下达的引擎请求是否在途（应着请求/全谱扫描，仅主线程访问） */
    private var aiMoveInFlight = false

    /** 全谱分析任务；非空表示正在扫描（此时禁止一切改局面操作） */
    private var scanJob: Job? = null

    /** 进入分析页的来源：来自对局时「返回」一定回对局 */
    private var analysisOrigin: Screen = Screen.MENU

    /** 当前这盘棋的模式（双人/人机）。棋盘在内存里一直保留，回对局时用它恢复 */
    private var gameMode: GameMode = GameMode.TWO_PLAYER

    /** 当前引擎会话使用的规则；与设置不一致时需要重建会话 */
    private var sessionRule: Rule? = null

    /** 宿主（Activity）已 onStop：熄屏/切后台。此间禁止恢复分析，回前台后由 onHostStarted 统一恢复 */
    private var backgrounded = false

    /** 后台时停掉了正在进行的实时分析：回前台需重启 */
    private var resumeAnalysisOnStart = false

    /** 后台时取消了正在进行的全谱扫描：回前台需重扫 */
    private var scanWasRunning = false

    init {
        board.rule = settingsRepo.settings.value.rule
        // 合并设置流
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                board.rule = s.rule
                // 影响棋盘渲染的设置变化（禁手集是否计算）需要重新投影状态
                val needsRefresh = _ui.value.rule != s.rule || _ui.value.showForbidden != s.showForbidden
                _ui.update {
                    it.copy(
                        rule = s.rule,
                        engineTimeSec = s.engineTimeSec,
                        strength = s.strength,
                        analysisLines = s.analysisLines,
                        showMoveNumbers = s.showMoveNumbers,
                        showForbidden = s.showForbidden,
                        showWinLine = s.showWinLine,
                        showCandidates = s.showCandidates,
                        sound = s.sound,
                        vibrate = s.vibrate,
                    )
                }
                if (needsRefresh) refresh()
            }
        }
        // 合并引擎状态流：相位变化（含 IDLE，驱动提示与输入锁）立即推送；搜索中段的
        // PV 刷屏按 PV_PUSH_INTERVAL 节流，抑制高频整屏重组/整盘重绘
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
                val idle = st.phase == EngineStatus.Phase.IDLE
                val pendingHint = hintActive && idle && st.bestMoves.isNotEmpty()
                if (hintActive && idle) hintActive = false
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
                        bestMoves = st.bestMoves,
                        hint = if (pendingHint) st.bestMoves.first() else ui.hint,
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
        startGame(GameMode.TWO_PLAYER, Board.Color.BLACK)
    }

    fun newAiGame(humanColor: Board.Color) {
        startGame(GameMode.AI, humanColor)
    }

    fun openAnalysis() {
        // 记下来源：从对局进入时「返回」回对局并保留局面与模式
        analysisOrigin = _screen.value
        _screen.value = Screen.ANALYSIS
        _ui.update { it.copy(mode = GameMode.ANALYSIS) }
    }

    fun sendGameToAnalysis() {
        // 保留当前局面，进入分析
        openAnalysis()
    }

    /**
     * 从分析页返回：只要内存里还留着一盘棋（不管是从对局还是主菜单进来的）就回对局继续下，
     * 否则回主菜单。棋盘一直保留在内存中，主菜单/分析都不清空它。
     */
    fun backFromAnalysis() {
        cancelFullScan()
        stopAnalysis()
        if (analysisOrigin == Screen.GAME || canResumeGame()) {
            resumeGame()
        } else {
            toMenu()
        }
    }

    /** 内存里是否还留着一盘没下完的棋 */
    private fun canResumeGame(): Boolean =
        board.moveCount > 0 && _ui.value.gameOver == null

    /** 主菜单「继续对局」/ 从分析返回：带着棋盘回到对局页 */
    fun resumeGame() {
        if (!canResumeGame()) return
        _ui.update { it.copy(mode = gameMode, viewPly = null, hint = null) }
        _screen.value = Screen.GAME
        refresh()
    }

    private fun startGame(mode: GameMode, humanColor: Board.Color) {
        cancelFullScan()
        gameMode = mode
        board.clear()
        board.rule = settingsRepo.settings.value.rule
        redoStack.clear()
        hintActive = false
        _ui.update {
            it.copy(
                mode = mode,
                humanColor = humanColor,
                gameOver = null,
                editMode = false,
                hint = null,
                pvLines = emptyList(),
                bestMoves = emptyList(),
                engineThinking = false,
                winLine = emptyList(),
                viewPly = null,
                curve = emptyMap(),
            )
        }
        _screen.value = Screen.GAME
        refresh()
        if (mode == GameMode.AI) {
            viewModelScope.launch {
                val s = settingsRepo.settings.value
                val ok = engine.startNewGame(s.rule, s.engineTimeSec * 1000)
                if (ok.isFailure) {
                    sessionRule = null
                    showMessage("引擎启动失败")
                    return@launch
                }
                sessionRule = s.rule
                if (humanColor == Board.Color.WHITE) {
                    // 引擎执黑先行
                    withAiMoveInFlight { engine.engineMoveFirst(s.strength) }
                        .onSuccess { (x, y) -> placeStone(x, y, Board.Color.BLACK, fromEngine = true) }
                        .onFailure { showMessage("引擎出错：${it.message}") }
                }
            }
        }
    }

    // ------------------------------------------------------------- 前后台与熄屏

    /**
     * 宿主进入后台或熄屏（Activity onStop）：停掉无限搜索。引擎搜索跑在 native 线程，
     * 不受任何协程作用域约束，不主动停就会在后台持续满载耗电。
     * AI 应着（TIMEOUT_TURN 限时）与提示（2 秒）有界，让其自行结束，不打断。
     */
    fun onHostStopped() {
        backgrounded = true
        val wasScanning = scanJob != null
        if (wasScanning) {
            // 全谱扫描是持续 CPU 任务：后台一律取消，回前台自动重扫
            scanWasRunning = true
            cancelFullScan()
        }
        if (!wasScanning && !hintActive &&
            engine.status.value.phase == EngineStatus.Phase.ANALYZING
        ) {
            resumeAnalysisOnStart = true
            viewModelScope.launch {
                // 期间可能恰好被用户手动停止：再确认一次仍在分析再发 STOP
                if (engine.status.value.phase == EngineStatus.Phase.ANALYZING) engine.stopAndAwait()
            }
        }
    }

    /** 宿主回到前台（Activity onStart）：恢复被暂停的全谱扫描或实时分析 */
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
                    // 与后台收尾中的 stopAndAwait 竞争时先等空闲，避免恢复命令被思考中的引擎丢弃
                    if (!engine.stopAndAwait()) return@launch
                    engine.syncAndAnalyze(board.moves.toList(), lines(), 0)
                }
            }
        }
    }

    // ------------------------------------------------------------- 输入

    fun onBoardTap(x: Int, y: Int) {
        val s = _ui.value
        if (engineThinkingNow()) return
        if (s.viewPly != null) {
            // 复盘浏览中：本次点按用于回到实战局面，避免棋盘被“冻住”又找不到出口
            setViewPly(null)
            showMessage("已回到当前局面")
            return
        }
        // AI 模式下引擎分析（提示）期间禁止落子，避免与在途搜索竞争协议状态
        if (s.mode == GameMode.AI && s.enginePhase == EngineStatus.Phase.ANALYZING) return
        if (s.gameOver != null) return
        if (!board.inBounds(x, y)) return

        val color = when {
            s.editMode -> s.editColor
            else -> s.sideToMove
        }

        if (board.get(x, y) != Board.Color.EMPTY) {
            feedback.playErrorSound(s.sound)
            showMessage("该点已有棋子")
            return
        }

        // 禁手校验（本地）：连珠规则下黑棋。编辑模式只摆放子，允许摆出禁手形状供研究
        if (!s.editMode && s.rule == Rule.RENJU && color == Board.Color.BLACK) {
            val forbid = RenjuRules.check(board, x, y)
            if (forbid != RenjuRules.Forbidden.NONE) {
                feedback.playErrorSound(s.sound)
                showMessage("禁手：${forbidText(forbid)}")
                return
            }
        }

        placeStone(x, y, color, fromEngine = false)
    }

    private fun placeStone(x: Int, y: Int, color: Board.Color, fromEngine: Boolean) {
        val s = _ui.value
        // 引擎与本地局面失同步时可能返回非法点：提示而不是让 Board.place 抛异常
        if (!board.inBounds(x, y) || board.get(x, y) != Board.Color.EMPTY) {
            showMessage("着法无效：$x,$y")
            return
        }
        // 引擎应着走 YXBOARD + YXNBEST：要把刚落的这一手也摆进引擎盘面
        val result = board.place(x, y, color)
        redoStack.clear()
        feedback.playPlaceSound(s.sound)
        feedback.vibrate(s.vibrate)
        hintActive = false
        _ui.update { it.copy(hint = null, winLine = emptyList()) }
        // 编辑模式（摆谱）只是摆放子：不判胜负，避免摆出五连就弹终局遮罩、丢掉题面
        if (!s.editMode) announceResult(x, y, color, result)

        // AI 模式：用户落子后驱动引擎。先发起请求，使思考锁在本次刷新时即已生效
        val engineFollows = !fromEngine && s.mode == GameMode.AI && !result.win && !result.draw
        if (engineFollows) requestEngineMove(board.moves.toList())
        refresh()
        // 分析模式下局面已变：重开分析，否则显示的是旧局面的胜率/变例
        if (s.mode == GameMode.ANALYSIS) restartAnalysisIfActive()
    }

    /** 记录胜负/和棋并给出终局反馈 */
    private fun announceResult(x: Int, y: Int, color: Board.Color, result: Board.PlaceResult) {
        when {
            result.win -> {
                // 终局时记下连子坐标，供棋盘画胜利连线
                val line = board.winLine(x, y, color, board.rule).orEmpty()
                _ui.update {
                    it.copy(
                        gameOver = if (color == Board.Color.BLACK) GameOver.BLACK_WIN else GameOver.WHITE_WIN,
                        winLine = line,
                    )
                }
                feedback.vibrate(_ui.value.vibrate, 80)
            }
            result.draw -> _ui.update { it.copy(gameOver = GameOver.DRAW, winLine = emptyList()) }
        }
    }

    /**
     * 驱动引擎应着。[position] 为含刚落下这一手的完整局面；路数取设置的“分析路数”，
     * 对局中因此也能看到引擎的实时多路候选（路数越大同一时限下搜索越浅）。
     */
    private fun requestEngineMove(position: List<Board.Move>) {
        val s = _ui.value
        val timeMs = s.engineTimeSec * 1000
        val timeout = timeMs * 4L + 8000
        viewModelScope.launch {
            val mv = withAiMoveInFlight {
                withTimeoutOrNull(timeout) { engine.playUserMove(position, lines(), timeMs, s.strength) }
                    ?: Result.failure(RapfiEngine.EngineException("引擎超时"))
            }
            mv.onSuccess { (ex, ey) ->
                val userColor = board.moves.lastOrNull()?.color ?: Board.Color.BLACK
                placeStone(ex, ey, userColor.opponent, fromEngine = true)
            }.onFailure {
                // 命令被引擎丢弃时它根本没开始搜索、不会有着法行来清相位：这里主动回落空闲，
                // 否则 UI 会永远停在“引擎思考中”，棋盘与按钮全部锁死
                engine.resetPhaseIfStuck()
                showMessage("引擎出错：${it.message}")
            }
        }
    }

    /** 标记引擎应着请求在途，使输入锁不依赖状态流的更新时机 */
    private suspend fun <T> withAiMoveInFlight(block: suspend () -> T): T {
        aiMoveInFlight = true
        return try {
            block()
        } finally {
            aiMoveInFlight = false
            // 状态流可能先于本 finally 收到 IDLE（那时锁还在，engineThinking 被算成 true）：
            // 这里再算一次，否则显示会一直停在“引擎思考中”、按钮保持灰显
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
            viewModelScope.launch {
                engine.takeback()
                engine.takeback()
            }
        } else {
            val m = board.undo() ?: return
            redoStack.addLast(m)
        }
        hintActive = false
        _ui.update { it.copy(gameOver = null, hint = null, winLine = emptyList()) }
        refresh()
        if (s.mode == GameMode.ANALYSIS) restartAnalysisIfActive()
    }

    fun redo() {
        val s = _ui.value
        if (engineThinkingNow()) return
        if (s.mode == GameMode.AI) return
        val m = redoStack.removeLastOrNull() ?: return
        val result = board.place(m.x, m.y, m.color)
        announceResult(m.x, m.y, m.color, result)
        refresh()
        if (s.mode == GameMode.ANALYSIS) restartAnalysisIfActive()
    }

    fun clearBoard() {
        if (engineThinkingNow()) return
        cancelFullScan()
        board.clear()
        redoStack.clear()
        hintActive = false
        _ui.update {
            it.copy(gameOver = null, hint = null, winLine = emptyList(), viewPly = null, curve = emptyMap())
        }
        refresh()
    }

    /** 重新开始当前对局（AI 模式会重建引擎会话，保证引擎内部局面同步） */
    fun restart() {
        val s = _ui.value
        when (s.mode) {
            GameMode.AI -> startGame(GameMode.AI, s.humanColor)
            else -> clearBoard()
        }
    }

    /** 摆谱编辑开关与编辑用子色 */
    fun toggleEditMode() {
        _ui.update { it.copy(editMode = !it.editMode) }
    }

    fun setEditColor(c: Board.Color) {
        _ui.update { it.copy(editColor = c) }
    }

    /** 翻转棋盘（分析模式：黑白互换） */
    fun flipColors() {
        if (engineThinkingNow()) return
        val flipped = board.moves.map { Board.Move(it.x, it.y, it.color.opponent) }
        board.clear()
        flipped.forEach { board.place(it.x, it.y, it.color) }
        redoStack.clear()
        hintActive = false
        // 黑白互换后旧评估曲线不再适用
        _ui.update { it.copy(hint = null, winLine = emptyList(), viewPly = null, curve = emptyMap()) }
        refresh()
        restartAnalysisIfActive()
    }

    // ------------------------------------------------------------- 提示与分析

    fun hint() {
        val s = _ui.value
        if (engineThinkingNow() || s.gameOver != null) return
        if (s.viewPly != null) {
            showMessage("复盘浏览中，先点「回到当前」")
            return
        }
        viewModelScope.launch {
            if (!ensureEngine()) return@launch
            // 若有在途搜索（分析/上一次提示），必须先停下：引擎思考期间会丢弃 YXBOARD
            if (!engine.stopAndAwait()) {
                showMessage("引擎无响应，请重试")
                return@launch
            }
            hintActive = true
            // 提示也按“分析路数”要候选：盘上会画 A/B/C…，首选点仍由引擎的最终着法行决定（画成提示圈）
            engine.syncAndAnalyze(board.moves.toList(), lines(), 2000)
        }
    }

    fun toggleAnalysis() {
        val s = _ui.value
        if (s.viewPly != null) {
            showMessage("复盘浏览中，先点「回到当前」")
            return
        }
        // 全谱扫描期间由扫描任务独占引擎
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
            // timeMs=0：进入无限分析模式，由停止按钮或局面变更（stopAndAwait）结束
            engine.syncAndAnalyze(board.moves.toList(), lines(), 0)
        }
    }

    fun stopAnalysis() {
        if (engine.status.value.phase == EngineStatus.Phase.ANALYZING) engine.stop()
    }

    private fun restartAnalysisIfActive() {
        // 后台期间一律不恢复分析：取消扫描等路径会经过这里，恢复统一交给 onHostStarted
        if (backgrounded) return
        if (_ui.value.enginePhase != EngineStatus.Phase.ANALYZING) return
        viewModelScope.launch {
            if (!engine.stopAndAwait()) return@launch
            engine.syncAndAnalyze(board.moves.toList(), lines(), 0)
        }
    }

    private fun lines(): Int = settingsRepo.settings.value.analysisLines

    // ------------------------------------------------------------- 复盘与评估曲线

    /**
     * 复盘浏览：非空时棋盘显示前 [ply] 手局面（供曲线拖动定位），null 回到当前局面。
     * 浏览期间禁止落子/悔棋/分析等会改局面的操作。
     */
    fun setViewPly(ply: Int?) {
        val v = ply?.coerceIn(0, board.moveCount)
        if (_ui.value.viewPly == v) return
        _ui.update { it.copy(viewPly = v, hint = null) }
        refresh()
    }

    /** 全谱分析：逐手限时搜索，生成整局评估曲线；用 [cancelFullScan] 中断 */
    fun startFullScan() {
        val s = _ui.value
        if (engineThinkingNow() || scanJob != null || s.viewPly != null) return
        if (board.moveCount == 0) {
            showMessage("先在棋盘上走出着法")
            return
        }
        scanJob = viewModelScope.launch {
            aiMoveInFlight = true
            val moves = board.moves.toList()
            // 从第 1 手开始：空盘由引擎开局库直接应答，不输出任何 INFO PV/胜率，扫了也没有评估值
            val total = moves.size
            val history = HashMap<Int, Float>()
            try {
                if (!ensureEngine()) return@launch
                _ui.update { it.copy(scan = GameUiState.Progress(0, total), curve = emptyMap()) }
                for (k in 1..total) {
                    // 逐手改盘面前必须确认引擎空闲：思考期间 YXBOARD 会被丢弃
                    if (!engine.stopAndAwait()) {
                        showMessage("引擎无响应，分析中断")
                        break
                    }
                    engine.scanAnalyze(moves.take(k), SCAN_TIME_MS)
                    val wr = engine.status.value.pvLines.firstOrNull()?.winRate
                    if (wr != null && !wr.isNaN()) history[k] = EngineValue.blackWinRate(wr, k)
                    // 棋盘跟随扫描进度显示被分析的局面，曲线同步增长
                    _ui.update {
                        it.copy(
                            scan = GameUiState.Progress(k, total),
                            curve = history.toMap(),
                            viewPly = k,
                        )
                    }
                    refresh()
                }
            } finally {
                scanJob = null
                aiMoveInFlight = false
                // 同 withAiMoveInFlight：清零锁后重算思考态，避免停在“引擎思考中”
                _ui.update {
                    it.copy(scan = null, viewPly = null, engineThinking = engineThinkingNow())
                }
                refresh()
                val mode = _ui.value.mode
                if (_screen.value == Screen.ANALYSIS && mode == GameMode.ANALYSIS) {
                    // 扫描把引擎盘面/时限改成了最后一手：仍在分析页时恢复实时分析
                    restartAnalysisIfActive()
                } else if (mode == GameMode.AI) {
                    // 对局中被扫描打断：把引擎内部盘面同步回实战局面。应着现在每次都整盘重摆
                    // （playUserMove 发完整 YXBOARD），所以这一步只是让引擎保持在对局局面上、
                    // 顺手把可能还在跑的搜索停干净。
                    // 取消路径（后台/用户中断）时任务已取消，挂起调用必须包 NonCancellable
                    // 才能真正执行，否则 withContext 在入口就抛取消异常、引擎盘面留在扫描中途
                    withContext(NonCancellable) {
                        if (engine.stopAndAwait()) engine.syncBoard(board.moves.toList())
                    }
                }
            }
        }
    }

    fun cancelFullScan() {
        val job = scanJob ?: return
        scanJob = null
        job.cancel()
        // 扫描可能正停在一次引擎搜索里：补发 STOP，避免后续命令被引擎丢弃
        engine.stop()
    }

    /** 采纳引擎最佳着法（分析模式） */
    fun applyBestMove() {
        val s = _ui.value
        if (engineThinkingNow()) return
        if (s.viewPly != null) {
            showMessage("复盘浏览中，先点「回到当前」")
            return
        }
        val best = s.bestMoves.firstOrNull() ?: s.pvLines.firstOrNull()?.moves?.firstOrNull() ?: return
        if (board.get(best.first, best.second) != Board.Color.EMPTY) return
        val color = if (s.editMode) s.editColor else s.sideToMove
        if (s.rule == Rule.RENJU && color == Board.Color.BLACK &&
            RenjuRules.check(board, best.first, best.second) != RenjuRules.Forbidden.NONE
        ) {
            showMessage("该点为禁手，无法采纳")
            return
        }
        placeStone(best.first, best.second, color, fromEngine = false)
    }

    /** 确保引擎会话可用且与当前规则一致 */
    private suspend fun ensureEngine(): Boolean {
        val s = settingsRepo.settings.value
        if (engine.isRunning && sessionRule == s.rule) return true
        val r = engine.startNewGame(s.rule, s.engineTimeSec * 1000)
        sessionRule = if (r.isSuccess) s.rule else null
        if (r.isFailure) showMessage("引擎启动失败：${r.exceptionOrNull()?.message}")
        return r.isSuccess
    }

    // ------------------------------------------------------------- 设置

    val settings = settingsRepo.settings

    fun setRule(rule: Rule) = settingsRepo.setRule(rule)
    fun setEngineTime(sec: Int) = settingsRepo.setEngineTime(sec)
    fun setStrength(level: Int) = settingsRepo.setStrength(level)
    fun setAnalysisLines(lines: Int) = settingsRepo.setAnalysisLines(lines)
    fun setShowMoveNumbers(on: Boolean) = settingsRepo.setShowMoveNumbers(on)
    fun setShowForbidden(on: Boolean) = settingsRepo.setShowForbidden(on)
    fun setShowWinLine(on: Boolean) = settingsRepo.setShowWinLine(on)
    fun setShowCandidates(on: Boolean) = settingsRepo.setShowCandidates(on)
    fun setSound(on: Boolean) = settingsRepo.setSound(on)
    fun setVibrate(on: Boolean) = settingsRepo.setVibrate(on)

    // ------------------------------------------------------------- 内部

    /** 引擎是否正在思考（应着 YXNBEST / 先行 BEGIN 搜索）：期间禁止一切落子/悔棋/改局操作 */
    private fun engineThinkingNow(): Boolean =
        aiMoveInFlight || engine.status.value.phase == EngineStatus.Phase.THINKING

    private fun refresh() {
        val s = _ui.value
        val reviewing = s.viewPly != null
        // 禁手集：连珠规则下才有意义；对局中仅黑方行棋时显示，分析/摆谱时始终显示便于研究
        val forbidden = if (s.rule == Rule.RENJU && s.showForbidden && s.gameOver == null && !reviewing &&
            board.moveCount > 0 &&
            (s.sideToMoveFor(board) == Board.Color.BLACK || s.mode == GameMode.ANALYSIS || s.editMode)
        ) {
            computeForbidden()
        } else emptyMap()
        val grid = s.viewPly?.let { gridAt(board.moves, it) } ?: board.snapshot()
        // 复盘浏览时行棋方按浏览到的局面算，否则按真实局面
        val sideToMove = s.viewPly?.let { ply ->
            board.moves.getOrNull(ply - 1)?.color?.opponent ?: Board.Color.BLACK
        } ?: s.sideToMoveFor(board)
        _ui.update {
            it.copy(
                moves = board.moves.toList(),
                grid = grid,
                sideToMove = sideToMove,
                forbidden = forbidden,
                canUndo = board.moveCount > 0 && !engineThinkingNow() && !reviewing,
                canRedo = redoStack.isNotEmpty() && s.mode != GameMode.AI && !reviewing,
            )
        }
    }

    private fun GameUiState.sideToMoveFor(b: Board): Board.Color =
        if (b.moveCount == 0) Board.Color.BLACK else b.moves.last().color.opponent

    /** 前 [ply] 手形成的盘面（复盘浏览用；只读，不影响真实对局） */
    private fun gridAt(moves: List<Board.Move>, ply: Int): ByteArray {
        val g = ByteArray(board.size * board.size)
        for (i in 0 until ply.coerceIn(0, moves.size)) {
            val m = moves[i]
            g[m.y * board.size + m.x] = m.color.value
        }
        return g
    }

    private fun computeForbidden(): Map<Pair<Int, Int>, RenjuRules.Forbidden> {
        val result = HashMap<Pair<Int, Int>, RenjuRules.Forbidden>()
        for (x in 0 until board.size) for (y in 0 until board.size) {
            if (board.get(x, y) != Board.Color.EMPTY) continue
            val f = RenjuRules.check(board, x, y)
            if (f != RenjuRules.Forbidden.NONE) result[x to y] = f
        }
        return result
    }

    private fun showMessage(msg: String) {
        messageJob?.cancel()
        _ui.update { it.copy(message = msg) }
        messageJob = viewModelScope.launch {
            delay(2200)
            _ui.update { it.copy(message = null) }
        }
    }

    private fun forbidText(f: RenjuRules.Forbidden) = when (f) {
        RenjuRules.Forbidden.DOUBLE_THREE -> "三三禁手"
        RenjuRules.Forbidden.DOUBLE_FOUR -> "四四禁手"
        RenjuRules.Forbidden.OVERLINE -> "长连禁手"
        RenjuRules.Forbidden.NONE -> ""
    }

    override fun onCleared() {
        feedback.release()
        engine.shutdown()
        super.onCleared()
    }
}
