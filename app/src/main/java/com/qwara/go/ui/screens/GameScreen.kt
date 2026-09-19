// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.qwara.go.GameMode
import com.qwara.go.GameUiState
import com.qwara.go.MainViewModel
import com.qwara.go.R
import com.qwara.go.engine.EngineStatus
import com.qwara.go.game.GoBoard
import com.qwara.go.ui.board.GoBoardView
import com.qwara.go.ui.components.BottomArcButtons
import com.qwara.go.ui.components.ChoiceButton
import com.qwara.go.ui.components.ChromeVisibility
import com.qwara.go.ui.components.CircleIconButton
import com.qwara.go.ui.components.EdgeCapsule
import com.qwara.go.ui.components.EvalCurvePanel
import com.qwara.go.ui.theme.BlackStone
import com.qwara.go.ui.theme.CreamWhite
import com.qwara.go.ui.theme.DeepBlack
import com.qwara.go.ui.theme.PanelDark
import com.qwara.go.ui.theme.WoodAmber
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CHROME_AUTO_HIDE_MS = 3200L

@Composable
fun GameScreen(vm: MainViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    // 对局中也能查评估曲线：菜单里打开，覆盖在棋盘上，不动局面
    var curveOpen by remember { mutableStateOf(false) }
    // 终局遮罩可临时收起，便于查看棋盘上的胜利连线
    var overlayDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(state.gameOver) { if (state.gameOver == null) overlayDismissed = false }

    // 对局期间保持屏幕常亮（思考/长考不被熄屏打断）
    val screenView = LocalView.current
    DisposableEffect(Unit) {
        screenView.keepScreenOn = true
        onDispose { screenView.keepScreenOn = false }
    }

    // 悬浮控件：点按/表冠/落子唤出，静置后自动隐藏；终局时隐藏
    var chromeVisible by remember { mutableStateOf(true) }
    var hideJob by remember { mutableStateOf<Job?>(null) }

    fun scheduleHide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(CHROME_AUTO_HIDE_MS)
            if (!vm.ui.value.engineThinking && vm.ui.value.message == null && vm.ui.value.gameOver == null) {
                chromeVisible = false
            }
        }
    }

    fun poke() {
        chromeVisible = true
        scheduleHide()
    }

    LaunchedEffect(state.moves.size, state.engineThinking, state.message, state.hintBusy, state.scan) {
        poke()
    }
    LaunchedEffect(state.gameOver) {
        if (state.gameOver != null) {
            hideJob?.cancel()
            chromeVisible = false
        } else {
            poke()
        }
    }

    // 终局遮罩还遮着时强制收起悬浮控件：终局后的引擎回包会再 poke 一次，
    // 只靠 chromeVisible 会让过期的"黑棋行棋"胶囊在遮罩下重新浮现。
    // 判断依据是「遮罩是否遮着」而不是「是否终局」——点了「查看棋盘」之后仍要能唤出菜单
    val overlayShowing = state.gameOver != null && !overlayDismissed

    BackHandler {
        if (state.moves.isNotEmpty() && state.gameOver == null) {
            showLeaveConfirm = true
        } else {
            vm.toMenu()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack),
    ) {
        GoBoardView(
            state = state,
            onTap = vm::onBoardTap,
            modifier = Modifier.fillMaxSize(),
            onUserInteraction = { poke() },
        )

        // 顶部状态胶囊 + 返回钮（整体居中，下移避开圆屏弧顶最窄处）
        ChromeVisibility(
            visible = chromeVisible && !overlayShowing,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircleIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        // 与 BackHandler 同一套语义：盘上有子且未终局时弹离开确认，
                        // 否则（空盘/已终局）直接回主菜单
                        onClick = {
                            if (state.moves.isNotEmpty() && state.gameOver == null) {
                                showLeaveConfirm = true
                            } else {
                                vm.toMenu()
                            }
                        },
                        size = 28.dp,
                    )
                    Spacer(Modifier.width(4.dp))
                    EdgeCapsule {
                        StatusCapsuleContent(state)
                    }
                }
                state.message?.let { msg ->
                    Text(
                        text = msg,
                        color = WoodAmber,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                ThinkingReadout(state)
            }
        }

        // 对局中查看评估曲线：覆盖面板，关闭/回到当前都不会改动局面
        if (curveOpen) {
            EvalCurvePanel(
                curve = state.curve,
                maxPly = state.moves.size,
                markerPly = state.viewPly ?: state.moves.size,
                progress = state.scan?.let { it.done to it.total },
                // 对局页的变例来自“提示”，与曲线不是一回事，不在这里列候选点
                candidates = emptyList(),
                onScrub = { ply ->
                    // 扫描期间棋盘由扫描任务驱动，忽略拖动
                    if (state.scan == null) {
                        vm.setViewPly(if (ply >= state.moves.size) null else ply)
                    }
                },
                onScan = vm::startFullScan,
                onCancelScan = vm::cancelFullScan,
                onBackToCurrent = { vm.setViewPly(null) },
                onClose = {
                    curveOpen = false
                    // 收起面板必须同时退出复盘，否则画面停在历史局面且没有出口
                    vm.setViewPly(null)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 78.dp, start = 10.dp, end = 10.dp),
            )
        }

        // 底部弧线按钮（圆心均布在屏圆周同心圆上，贴合边缘）
        ChromeVisibility(
            visible = chromeVisible && !overlayShowing,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxSize(),
        ) {
            BottomArcButtons(buttonCount = 5) { i ->
                when (i) {
                    0 -> CircleIconButton(
                        icon = Icons.Default.Refresh,
                        onClick = vm::undo,
                        enabled = state.canUndo && !state.engineThinking,
                        label = stringResource(R.string.action_undo),
                    )
                    1 -> CircleIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        onClick = vm::redo,
                        enabled = state.canRedo && !state.engineThinking,
                        label = stringResource(R.string.action_redo),
                    )
                    2 -> CircleIconButton(
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        onClick = vm::pass,
                        enabled = !state.engineThinking && state.gameOver == null,
                        label = stringResource(R.string.action_pass),
                    )
                    3 -> CircleIconButton(
                        icon = Icons.Default.Star,
                        onClick = vm::hint,
                        enabled = !state.engineThinking && state.gameOver == null,
                        // 灰的也要能点出个说法；分析中保持可点＝再点一次取消
                        onDisabledClick = vm::hint,
                        highlight = true,
                        label = stringResource(R.string.action_hint),
                    )
                    else -> CircleIconButton(
                        icon = Icons.Default.Menu,
                        onClick = { showMenu = true },
                        label = stringResource(R.string.action_menu),
                    )
                }
            }
        }

        if (state.gameOver != null && !overlayDismissed) {
            GameOverOverlay(
                state = state,
                onRestart = vm::restart,
                onMenu = vm::toMenu,
                onViewBoard = { overlayDismissed = true },
            )
        }
    }

    if (showMenu) {
        GameMenuDialog(
            state = state,
            vm = vm,
            onDismiss = { showMenu = false },
            onShowCurve = {
                showMenu = false
                curveOpen = true
            },
            onRestartRequest = {
                showMenu = false
                if (state.moves.isEmpty()) vm.restart() else showRestartConfirm = true
            },
        )
    }

    if (showRestartConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_restart_title),
            text = stringResource(R.string.confirm_restart_text),
            // 重新开始用自己的一套文案：复用"离开/继续"会让确认键写着"离开"
            confirmText = stringResource(R.string.confirm_restart_yes),
            dismissText = stringResource(R.string.confirm_restart_no),
            onConfirm = {
                showRestartConfirm = false
                vm.restart()
            },
            onDismiss = { showRestartConfirm = false },
        )
    }

    if (showLeaveConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_leave_title),
            text = stringResource(R.string.confirm_leave_text),
            confirmText = stringResource(R.string.confirm_yes),
            dismissText = stringResource(R.string.confirm_no),
            onConfirm = {
                showLeaveConfirm = false
                vm.toMenu()
            },
            onDismiss = { showLeaveConfirm = false },
        )
    }
}

/** 胶囊内的状态内容：回合点 + 状态文字 + 手数。 */
@Composable
private fun StatusCapsuleContent(state: GameUiState) {
    val turnColor = when {
        state.gameOver != null -> WoodAmber
        state.sideToMove == GoBoard.Color.BLACK -> BlackStone
        else -> CreamWhite
    }
    val statusText = when {
        // 终局后显示结算文案；否则唤醒 chrome 会把过期的「黑棋行棋 N 手」盖在已终局的盘面上
        state.gameOver != null -> state.gameOver
        // 提示 / 全谱分析都在等引擎第一帧上报，慢设备上要好几秒：点按必须立刻有交代
        state.hintBusy -> stringResource(R.string.status_hint_analyzing)
        state.scan != null -> stringResource(
            R.string.curve_scanning,
            state.scan.done,
            state.scan.total,
        )
        state.engineThinking -> stringResource(R.string.status_engine_thinking)
        state.editMode -> stringResource(
            R.string.status_edit_mode,
            if (state.editColor == GoBoard.Color.BLACK) {
                stringResource(R.string.stone_black)
            } else {
                stringResource(R.string.stone_white)
            },
        )
        state.sideToMove == GoBoard.Color.BLACK -> stringResource(R.string.status_black_turn)
        else -> stringResource(R.string.status_white_turn)
    }
    Box(
        modifier = Modifier
            .width(9.dp)
            .height(9.dp)
            .clip(RoundedCornerShape(50))
            .background(turnColor),
    )
    Spacer(Modifier.width(5.dp))
    Text(text = statusText, color = CreamWhite, fontSize = 11.sp)
    Spacer(Modifier.width(5.dp))
    Text(
        text = stringResource(R.string.status_move_count_short, state.moves.size),
        color = CreamWhite.copy(alpha = 0.65f),
        fontSize = 10.sp,
    )
}

/**
 * 人机对战里引擎应着思考中的一行读数：胜率（行棋方就是引擎自己）· 首选点。
 * 数据是搜索期间持续推送的 pvLines（收集器已按 PV_PUSH_INTERVAL 节流），不需要额外引擎命令；
 * 相位一回到 IDLE（着法落地）就收起。
 */
@Composable
private fun ThinkingReadout(state: GameUiState) {
    if (state.mode != GameMode.AI || state.enginePhase != EngineStatus.Phase.THINKING) return
    val pv = state.pvLines.firstOrNull() ?: return
    val (bx, by) = pv.moves.firstOrNull() ?: return
    val point = goCoord(bx, by, state.boardSize)
    val rate = if (pv.winRate.isNaN()) "—" else "${(pv.winRate.coerceIn(0f, 1f) * 100).toInt()}%"
    Text(
        text = stringResource(R.string.status_thinking_rate, rate, point),
        color = CreamWhite.copy(alpha = 0.72f),
        fontSize = 10.sp,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** 坐标显示为 GTP 习惯：列字母（跳 I），行号从底边数 1 起 */
private fun goCoord(x: Int, y: Int, size: Int): String {
    val col = if (x < 8) ('A' + x) else ('A' + x + 1)
    return "$col${size - y}"
}

@Composable
private fun GameOverOverlay(
    state: GameUiState,
    onRestart: () -> Unit,
    onMenu: () -> Unit,
    onViewBoard: () -> Unit,
) {
    val title = state.gameOver ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            // 半透明：终局后仍能看见棋盘上的胜利连线
            .background(DeepBlack.copy(alpha = 0.55f))
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, color = WoodAmber)
            FilledTonalButton(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_play_again))
            }
            FilledTonalButton(
                onClick = onViewBoard,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = PanelDark,
                    contentColor = CreamWhite,
                ),
            ) {
                Text(stringResource(R.string.action_view_board))
            }
            FilledTonalButton(
                onClick = onMenu,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = PanelDark,
                    contentColor = CreamWhite,
                ),
            ) {
                Text(stringResource(R.string.dialog_back_to_menu))
            }
        }
    }
}

@Composable
private fun GameMenuDialog(
    state: GameUiState,
    vm: MainViewModel,
    onDismiss: () -> Unit,
    onShowCurve: () -> Unit,
    onRestartRequest: () -> Unit,
) {
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_menu)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = onDismiss)
        },
        content = {
            item {
                ScalingMenuItem(
                    label = stringResource(R.string.action_restart),
                    icon = { androidx.wear.compose.material3.Icon(Icons.Default.Refresh, null) },
                    onClick = onRestartRequest,
                )
            }
            item {
                ScalingMenuItem(
                    label = stringResource(R.string.curve_title),
                    icon = { androidx.wear.compose.material3.Icon(Icons.Default.Search, null) },
                    onClick = onShowCurve,
                )
            }
            item {
                SwitchButton(
                    checked = state.editMode,
                    onCheckedChange = { vm.toggleEditMode() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dialog_edit_mode)) },
                )
            }
            if (state.editMode) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.dialog_edit_color),
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        ChoiceButton(
                            text = stringResource(R.string.stone_black),
                            selected = state.editColor == GoBoard.Color.BLACK,
                            onClick = { vm.setEditColor(GoBoard.Color.BLACK) },
                        )
                        ChoiceButton(
                            text = stringResource(R.string.stone_white),
                            selected = state.editColor == GoBoard.Color.WHITE,
                            onClick = { vm.setEditColor(GoBoard.Color.WHITE) },
                        )
                    }
                }
            }
            if (state.mode == GameMode.ANALYSIS) {
                item {
                    ScalingMenuItem(
                        label = stringResource(R.string.analysis_flip),
                        icon = { androidx.wear.compose.material3.Icon(Icons.Default.Refresh, null) },
                        onClick = { vm.flipColors() },
                    )
                }
            }
            if (state.mode != GameMode.ANALYSIS) {
                item {
                    ScalingMenuItem(
                        label = stringResource(R.string.dialog_analysis),
                        icon = { androidx.wear.compose.material3.Icon(Icons.Default.Star, null) },
                        onClick = {
                            onDismiss()
                            vm.sendGameToAnalysis()
                        },
                    )
                }
            }
            item {
                ScalingMenuItem(
                    label = stringResource(R.string.dialog_back_to_menu),
                    icon = { androidx.wear.compose.material3.Icon(Icons.Default.Menu, null) },
                    onClick = {
                        onDismiss()
                        vm.toMenu()
                    },
                )
            }
            item {
                // 底部留白，避免列表末项与对话框确认按钮重叠误触
                Spacer(Modifier.height(56.dp))
            }
        },
    )
}

@Composable
private fun ScalingMenuItem(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            FilledTonalButton(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText) }
        },
    )
}
