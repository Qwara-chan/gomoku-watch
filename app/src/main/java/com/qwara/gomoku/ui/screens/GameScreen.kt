package com.qwara.gomoku.ui.screens

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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
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
import com.qwara.gomoku.GameMode
import com.qwara.gomoku.GameOver
import com.qwara.gomoku.GameUiState
import com.qwara.gomoku.MainViewModel
import com.qwara.gomoku.R
import com.qwara.gomoku.game.Board
import com.qwara.gomoku.ui.board.GomokuBoard
import com.qwara.gomoku.ui.components.BottomArcButtons
import com.qwara.gomoku.ui.components.ChoiceButton
import com.qwara.gomoku.ui.components.ChromeVisibility
import com.qwara.gomoku.ui.components.CircleIconButton
import com.qwara.gomoku.ui.components.EdgeCapsule
import com.qwara.gomoku.ui.theme.BlackStone
import com.qwara.gomoku.ui.theme.CreamWhite
import com.qwara.gomoku.ui.theme.DeepBlack
import com.qwara.gomoku.ui.theme.PanelDark
import com.qwara.gomoku.ui.theme.WoodAmber
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

    LaunchedEffect(state.moves.size, state.engineThinking, state.message) { poke() }
    LaunchedEffect(state.gameOver) {
        if (state.gameOver != null) {
            hideJob?.cancel()
            chromeVisible = false
        } else {
            poke()
        }
    }

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
        GomokuBoard(
            state = state,
            onTap = vm::onBoardTap,
            modifier = Modifier.fillMaxSize(),
            onUserInteraction = { poke() },
        )

        // 顶部状态胶囊 + 返回钮（整体居中，下移避开圆屏弧顶最窄处）
        ChromeVisibility(
            visible = chromeVisible,
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
                        onClick = vm::toMenu,
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
            }
        }

        // 底部弧线按钮（圆心均布在屏圆周同心圆上，贴合边缘）
        ChromeVisibility(
            visible = chromeVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxSize(),
        ) {
            BottomArcButtons(buttonCount = 4) { i ->
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
                        icon = Icons.Default.Star,
                        onClick = vm::hint,
                        enabled = !state.engineThinking && state.gameOver == null,
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

        if (state.gameOver != null) {
            GameOverOverlay(
                state = state,
                onRestart = vm::restart,
                onMenu = vm::toMenu,
            )
        }
    }

    if (showMenu) {
        GameMenuDialog(
            state = state,
            vm = vm,
            onDismiss = { showMenu = false },
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
            confirmText = stringResource(R.string.confirm_yes),
            dismissText = stringResource(R.string.confirm_no),
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
    val turnColor = if (state.sideToMove == Board.Color.BLACK) BlackStone else CreamWhite
    val statusText = when {
        state.engineThinking -> stringResource(R.string.status_engine_thinking)
        state.editMode -> stringResource(
            R.string.status_edit_mode,
            if (state.editColor == Board.Color.BLACK) {
                stringResource(R.string.stone_black)
            } else {
                stringResource(R.string.stone_white)
            },
        )
        state.sideToMove == Board.Color.BLACK -> stringResource(R.string.status_black_turn)
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

@Composable
private fun GameOverOverlay(
    state: GameUiState,
    onRestart: () -> Unit,
    onMenu: () -> Unit,
) {
    val title = when (state.gameOver) {
        GameOver.BLACK_WIN -> stringResource(R.string.game_over_black)
        GameOver.WHITE_WIN -> stringResource(R.string.game_over_white)
        GameOver.DRAW -> stringResource(R.string.status_game_over_draw)
        null -> return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(DeepBlack.copy(alpha = 0.85f))
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, color = WoodAmber)
            FilledTonalButton(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_play_again))
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
                            selected = state.editColor == Board.Color.BLACK,
                            onClick = { vm.setEditColor(Board.Color.BLACK) },
                        )
                        ChoiceButton(
                            text = stringResource(R.string.stone_white),
                            selected = state.editColor == Board.Color.WHITE,
                            onClick = { vm.setEditColor(Board.Color.WHITE) },
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
