package com.qwara.gomoku.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qwara.gomoku.MainViewModel
import com.qwara.gomoku.R
import com.qwara.gomoku.game.Board
import com.qwara.gomoku.ui.components.ChoiceButton
import com.qwara.gomoku.ui.components.WideButton
import com.qwara.gomoku.ui.theme.WoodAmber

@Composable
fun MenuScreen(vm: MainViewModel) {
    var showColorDialog by remember { mutableStateOf(false) }
    // 弹窗里选中的执子：勾按它开局，叉取消
    var chosenColor by remember { mutableStateOf(Board.Color.BLACK) }
    val state by vm.ui.collectAsStateWithLifecycle()
    // 内存里还留着没下完的棋：给一个明确的"回去接着下"入口
    val resumable = state.moves.isNotEmpty() && state.gameOver == null

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.menu_title),
                color = WoodAmber,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
            )
        }
        if (resumable) {
            item {
                WideButton(
                    text = stringResource(R.string.menu_resume),
                    subtitle = stringResource(R.string.menu_resume_sub, state.moves.size),
                    icon = Icons.Default.PlayArrow,
                    onClick = vm::resumeGame,
                )
            }
        }
        item {
            WideButton(
                text = stringResource(R.string.menu_two_player),
                subtitle = stringResource(R.string.menu_two_player_sub),
                icon = Icons.Default.Person,
                onClick = vm::newTwoPlayerGame,
            )
        }
        item {
            WideButton(
                text = stringResource(R.string.menu_ai),
                subtitle = stringResource(R.string.menu_ai_sub),
                icon = Icons.Default.Star,
                onClick = { showColorDialog = true },
            )
        }
        item {
            WideButton(
                text = stringResource(R.string.menu_analysis),
                subtitle = stringResource(R.string.menu_analysis_sub),
                icon = Icons.Default.Search,
                onClick = vm::openAnalysis,
            )
        }
        item {
            WideButton(
                text = stringResource(R.string.menu_settings),
                icon = Icons.Default.Settings,
                onClick = vm::openSettings,
            )
        }
        item {
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showColorDialog) {
        AlertDialog(
            visible = true,
            onDismissRequest = { showColorDialog = false },
            title = { Text(stringResource(R.string.menu_choose_color_title)) },
            confirmButton = {
                // 勾 = 用选中的执子开始人机对战
                AlertDialogDefaults.ConfirmButton(onClick = {
                    showColorDialog = false
                    vm.newAiGame(chosenColor)
                })
            },
            dismissButton = {
                // 叉 = 取消（不开局）
                AlertDialogDefaults.DismissButton(onClick = { showColorDialog = false })
            },
            content = {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChoiceButton(
                            text = stringResource(R.string.play_as_black),
                            selected = chosenColor == Board.Color.BLACK,
                            onClick = { chosenColor = Board.Color.BLACK },
                        )
                        ChoiceButton(
                            text = stringResource(R.string.play_as_white),
                            selected = chosenColor == Board.Color.WHITE,
                            onClick = { chosenColor = Board.Color.WHITE },
                        )
                    }
                }
            },
        )
    }
}
