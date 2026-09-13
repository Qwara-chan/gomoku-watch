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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import com.qwara.gomoku.MainViewModel
import com.qwara.gomoku.R
import com.qwara.gomoku.game.Board
import com.qwara.gomoku.ui.components.WideButton
import com.qwara.gomoku.ui.theme.WoodAmber

@Composable
fun MenuScreen(vm: MainViewModel) {
    var showColorDialog by remember { mutableStateOf(false) }

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
                AlertDialogDefaults.ConfirmButton(onClick = { showColorDialog = false })
            },
            content = {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                showColorDialog = false
                                vm.newAiGame(Board.Color.BLACK)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.play_as_black))
                        }
                        FilledTonalButton(
                            onClick = {
                                showColorDialog = false
                                vm.newAiGame(Board.Color.WHITE)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.play_as_white))
                        }
                    }
                }
            },
        )
    }
}
