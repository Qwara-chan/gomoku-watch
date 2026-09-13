package com.qwara.gomoku

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qwara.gomoku.data.SettingsRepository
import com.qwara.gomoku.engine.EngineStatus
import com.qwara.gomoku.engine.PvLine
import com.qwara.gomoku.engine.RapfiEngine
import com.qwara.gomoku.game.Board
import com.qwara.gomoku.game.RenjuRules
import com.qwara.gomoku.game.Rule
import com.qwara.gomoku.ui.util.FeedbackHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 屏幕导航 */
enum class Screen { MENU, GAME, ANALYSIS, SETTINGS }

enum class GameMode { TWO_PLAYER, AI, ANALYSIS }

enum class GameOver { BLACK_WIN, WHITE_WIN, DRAW }

data class GameUiState(
    val mode: GameMode = GameMode.TWO_PLAYER,
    val moves: List<Board.Move> = emptyList(),
    val grid: ByteArray = ByteArray(15 * 15),
    val sideToMove: Board.Color = Board.Color.BLACK,
    val gameOver: GameOver? = null,
    val humanColor: Board.Color = Board.Color.BLACK,
    val engineThinking: Boolean = false,
    val enginePhase: EngineStatus.Phase = EngineStatus.Phase.IDLE,
    val engineReady: Boolean = false,
    val pvLines: List<PvLine> = emptyList(),
    val bestMoves: List<Pair<Int, Int>> = emptyList(),
    val hint: Pair<Int, Int>? = null,
    val forbidden: Set<Pair<Int, Int>> = emptySet(),
    val editMode: Boolean = false,
    val editColor: Board.Color = Board.Color.BLACK,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: String? = null,
    val rule: Rule = Rule.FREESTYLE,
    val engineTimeSec: Int = 3,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
) {
    val boardSize: Int get() = 15

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
    private var messageJob: kotlinx.coroutines.Job? = null

    init {
        // 合并设置流
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                _ui.update {
                    it.copy(rule = s.rule, engineTimeSec = s.engineTimeSec, sound = s.sound, vibrate = s.vibrate)
                }
            }
        }
        // 合并引擎状态流
        viewModelScope.launch {
            engine.status.collect { st ->
                _ui.update { ui ->
                    val hint = if (hintActive && st.phase == EngineStatus.Phase.IDLE && st.bestMoves.isNotEmpty()) {
                        st.bestMoves.first()
                    } else ui.hint
                    if (hintActive && st.phase == EngineStatus.Phase.IDLE) hintActive = false
                    ui.copy(
                        enginePhase = st.phase,
                        pvLines = st.pvLines,
                        bestMoves = st.bestMoves,
                        hint = hint,
                        engineThinking = st.phase == EngineStatus.Phase.THINKING,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- 导航

    fun toMenu() {
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
        _screen.value = Screen.ANALYSIS
        _ui.update { it.copy(mode = GameMode.ANALYSIS) }
    }

    fun sendGameToAnalysis() {
        // 保留当前局面，进入分析
        openAnalysis()
    }

    private fun startGame(mode: GameMode, humanColor: Board.Color) {
        board.clear()
        redoStack.clear()
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
            )
        }
        _screen.value = Screen.GAME
        refresh()
        if (mode == GameMode.AI) {
            viewModelScope.launch {
                val ok = engine.startNewGame(settingsRepo.settings.value.rule, settingsRepo.settings.value.engineTimeSec * 1000)
                if (ok.isFailure) {
                    showMessage("引擎启动失败")
                    return@launch
                }
                _ui.update { it.copy(engineReady = true) }
                if (humanColor == Board.Color.WHITE) {
                    // 引擎执黑先行
                    _ui.update { it.copy(engineThinking = true) }
                    val mv = engine.engineMoveFirst()
                    _ui.update { it.copy(engineThinking = false) }
                    mv.onSuccess { (x, y) ->
                        placeStone(x, y, Board.Color.BLACK, fromEngine = true)
                    }.onFailure { showMessage("引擎出错：${it.message}") }
                }
            }
        } else {
            // 非 AI 模式也需要引擎会话用于提示/禁手提示，延迟到首次使用时启动
            _ui.update { it.copy(engineReady = false) }
        }
    }

    // ------------------------------------------------------------- 输入

    fun onBoardTap(x: Int, y: Int) {
        val s = _ui.value
        if (s.engineThinking) return
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

        // 禁手校验（本地）：连珠规则下黑棋
        if (s.rule == Rule.RENJU && color == Board.Color.BLACK) {
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
        val result = board.place(x, y, color)
        redoStack.clear()
        feedback.playPlaceSound(s.sound)
        feedback.vibrate(s.vibrate)

        when {
            result.win -> {
                _ui.update { it.copy(gameOver = if (color == Board.Color.BLACK) GameOver.BLACK_WIN else GameOver.WHITE_WIN) }
                feedback.vibrate(s.vibrate, 80)
            }
            result.draw -> _ui.update { it.copy(gameOver = GameOver.DRAW) }
        }
        refresh()

        // AI 模式：用户落子后驱动引擎
        if (!fromEngine && s.mode == GameMode.AI && result.win.not() && result.draw.not()) {
            requestEngineMove(x, y)
        }
    }

    private fun requestEngineMove(x: Int, y: Int) {
        val timeMs = _ui.value.engineTimeSec * 1000
        val timeout = timeMs * 4L + 8000
        viewModelScope.launch {
            _ui.update { it.copy(engineThinking = true) }
            val mv = withTimeoutOrNull(timeout) { engine.playUserMove(x, y, timeMs) }
                ?: Result.failure(RapfiEngine.EngineException("引擎超时"))
            _ui.update { it.copy(engineThinking = false) }
            mv.onSuccess { (ex, ey) ->
                val userColor = board.moves.lastOrNull()?.color ?: Board.Color.BLACK
                placeStone(ex, ey, userColor.opponent, fromEngine = true)
            }.onFailure {
                showMessage("引擎出错：${it.message}")
            }
        }
    }

    // ------------------------------------------------------------- 悔棋/重做/清空

    fun undo() {
        val s = _ui.value
        if (s.engineThinking) return
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
        _ui.update { it.copy(gameOver = null, hint = null) }
        refresh()
        if (s.mode == GameMode.ANALYSIS) restartAnalysisIfActive()
    }

    fun redo() {
        val s = _ui.value
        if (s.engineThinking) return
        if (s.mode == GameMode.AI) return
        val m = redoStack.removeLastOrNull() ?: return
        board.place(m.x, m.y, m.color)
        refresh()
        if (s.mode == GameMode.ANALYSIS) restartAnalysisIfActive()
    }

    fun clearBoard() {
        if (_ui.value.engineThinking) return
        board.clear()
        redoStack.clear()
        _ui.update { it.copy(gameOver = null, hint = null) }
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
        if (_ui.value.engineThinking) return
        val flipped = board.moves.map { Board.Move(it.x, it.y, it.color.opponent) }
        board.clear()
        flipped.forEach { board.place(it.x, it.y, it.color) }
        redoStack.clear()
        refresh()
    }

    // ------------------------------------------------------------- 提示与分析

    fun hint() {
        val s = _ui.value
        if (s.engineThinking) return
        viewModelScope.launch {
            ensureEngine()
            // 若有在途分析（如分析模式），先安全停止再发新搜索
            engine.stopAndAwait()
            hintActive = true
            engine.syncAndAnalyze(board.moves.toList(), null, 1, 2000)
        }
    }

    fun toggleAnalysis() {
        val s = _ui.value
        if (s.enginePhase == EngineStatus.Phase.ANALYZING) {
            engine.stop()
            return
        }
        viewModelScope.launch {
            ensureEngine()
            // timeMs=0：进入无限分析模式，由停止按钮或局面变更（stopAndAwait）结束
            engine.syncAndAnalyze(board.moves.toList(), null, 3, 0)
        }
    }

    fun stopAnalysis() {
        if (engine.status.value.phase == EngineStatus.Phase.ANALYZING) engine.stop()
    }

    private fun restartAnalysisIfActive() {
        if (_ui.value.enginePhase == EngineStatus.Phase.ANALYZING) {
            viewModelScope.launch {
                engine.stopAndAwait()
                engine.syncAndAnalyze(board.moves.toList(), null, 3, 0)
            }
        }
    }

    /** 采纳引擎最佳着法（分析模式） */
    fun applyBestMove() {
        val s = _ui.value
        if (s.engineThinking) return
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
        if (s.mode == GameMode.ANALYSIS) restartAnalysisIfActive()
    }

    private suspend fun ensureEngine() {
        if (engine.isRunning) return
        val r = engine.startNewGame(settingsRepo.settings.value.rule, settingsRepo.settings.value.engineTimeSec * 1000)
        _ui.update { it.copy(engineReady = r.isSuccess) }
        if (r.isFailure) showMessage("引擎启动失败：${r.exceptionOrNull()?.message}")
    }

    // ------------------------------------------------------------- 设置

    val settings = settingsRepo.settings

    fun setRule(rule: Rule) = settingsRepo.setRule(rule)
    fun setEngineTime(sec: Int) = settingsRepo.setEngineTime(sec)
    fun setSound(on: Boolean) = settingsRepo.setSound(on)
    fun setVibrate(on: Boolean) = settingsRepo.setVibrate(on)

    // ------------------------------------------------------------- 内部

    private fun refresh() {
        val s = _ui.value
        val forbidden = if (s.rule == Rule.RENJU && s.sideToMoveFor(board) == Board.Color.BLACK &&
            s.gameOver == null && board.moveCount > 0
        ) {
            computeForbidden()
        } else emptySet()
        _ui.update {
            it.copy(
                moves = board.moves.toList(),
                grid = board.snapshot(),
                sideToMove = s.sideToMoveFor(board),
                forbidden = forbidden,
                canUndo = board.moveCount > 0 && !s.engineThinking,
                canRedo = redoStack.isNotEmpty() && s.mode != GameMode.AI,
            )
        }
    }

    private fun GameUiState.sideToMoveFor(b: Board): Board.Color =
        if (b.moveCount == 0) Board.Color.BLACK else b.moves.last().color.opponent

    private fun computeForbidden(): Set<Pair<Int, Int>> {
        val result = HashSet<Pair<Int, Int>>()
        for (x in 0 until board.size) for (y in 0 until board.size) {
            if (board.get(x, y) == Board.Color.EMPTY &&
                RenjuRules.check(board, x, y) != RenjuRules.Forbidden.NONE
            ) result.add(x to y)
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
        engine.shutdown()
        super.onCleared()
    }
}
