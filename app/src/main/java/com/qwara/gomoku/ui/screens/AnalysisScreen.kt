package com.qwara.gomoku.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Text
import com.qwara.gomoku.MainViewModel
import com.qwara.gomoku.R
import com.qwara.gomoku.engine.EngineStatus
import com.qwara.gomoku.engine.EngineValue
import com.qwara.gomoku.engine.PvLine
import com.qwara.gomoku.ui.board.GomokuBoard
import com.qwara.gomoku.ui.components.BottomArcButtons
import com.qwara.gomoku.ui.components.CircleIconButton
import com.qwara.gomoku.ui.components.EdgeCapsule
import com.qwara.gomoku.ui.theme.CreamWhite
import com.qwara.gomoku.ui.theme.DeepBlack
import com.qwara.gomoku.ui.theme.HintGreen
import com.qwara.gomoku.ui.theme.PanelDark
import com.qwara.gomoku.ui.theme.WoodAmber

@Composable
fun AnalysisScreen(vm: MainViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val primary = state.pvLines.firstOrNull()

    // 分析期间保持屏幕常亮
    val screenView = LocalView.current
    DisposableEffect(Unit) {
        screenView.keepScreenOn = true
        onDispose { screenView.keepScreenOn = false }
    }

    BackHandler { vm.toMenu() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack),
    ) {
        GomokuBoard(
            state = state,
            onTap = vm::onBoardTap,
            modifier = Modifier.fillMaxSize(),
        )

        // 顶部：返回钮 + 胜率胶囊（整体居中，下移避开圆屏弧顶）
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
                    MiniEvalContent(primary)
                }
            }
            if (state.moves.isEmpty()) {
                Text(
                    text = stringResource(R.string.analysis_empty_hint),
                    color = CreamWhite.copy(alpha = 0.75f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // 底部中间空隙：深度/nps + PV 胶囊（抬高避开两侧弧线按钮）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 84.dp),
        ) {
            EdgeCapsule {
                DetailCapsuleContent(primary)
            }
        }

        // 底部弧线按钮（贴合圆屏边缘）
        BottomArcButtons(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxSize(),
            buttonCount = 4,
        ) { i ->
            when (i) {
                0 -> CircleIconButton(
                    icon = Icons.Default.Refresh,
                    onClick = vm::undo,
                    enabled = state.canUndo,
                    label = stringResource(R.string.action_undo),
                )
                1 -> CircleIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    onClick = vm::redo,
                    enabled = state.canRedo,
                    label = stringResource(R.string.action_redo),
                )
                2 -> {
                    val analyzing = state.enginePhase == EngineStatus.Phase.ANALYZING
                    CircleIconButton(
                        icon = if (analyzing) Icons.Default.Close else Icons.Default.PlayArrow,
                        onClick = vm::toggleAnalysis,
                        highlight = true,
                        label = stringResource(R.string.action_start_search),
                    )
                }
                else -> CircleIconButton(
                    icon = Icons.Default.Check,
                    onClick = vm::applyBestMove,
                    enabled = state.bestMoves.isNotEmpty() || primary?.moves?.isNotEmpty() == true,
                    label = stringResource(R.string.action_apply_move),
                )
            }
        }
    }
}

/** 顶部窄胶囊：胜率条 + 胜率值/将杀。宽度克制，适配圆屏顶部弧边。 */
@Composable
private fun MiniEvalContent(pv: PvLine?) {
    val winRate = pv?.winRate ?: Float.NaN
    val mate = pv?.let { EngineValue.mateText(it.eval) }
    val fraction = if (winRate.isNaN()) 0f else winRate.coerceIn(0f, 1f)
    val label = when {
        mate != null -> mate
        winRate.isNaN() -> "—"
        else -> "${(fraction * 100).toInt()}%"
    }
    Text(
        text = stringResource(R.string.analysis_eval),
        color = CreamWhite.copy(alpha = 0.8f),
        fontSize = 10.sp,
    )
    Spacer(Modifier.width(5.dp))
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(PanelDark),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(HintGreen),
        )
    }
    Spacer(Modifier.width(5.dp))
    Text(text = label, color = WoodAmber, fontSize = 11.sp, maxLines = 1, softWrap = false)
}

/** 底部胶囊：深度 · nps · 主变例（单行截断）。 */
@Composable
private fun DetailCapsuleContent(pv: PvLine?) {
    Text(
        text = "D${pv?.depth ?: 0} · " +
            stringResource(R.string.analysis_nps, formatCount(pv?.speed ?: 0)) +
            " · " + pvText(pv),
        color = CreamWhite.copy(alpha = 0.85f),
        fontSize = 10.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun pvText(pv: PvLine?): String {
    val moves = pv?.moves.orEmpty()
    return if (moves.isEmpty()) {
        "—"
    } else {
        moves.joinToString(" ") { coord(it.first, it.second) }
    }
}

private fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> "${value / 100_000 / 10.0}M"
    value >= 1_000 -> "${value / 100 / 10.0}k"
    else -> value.toString()
}

/** 坐标显示为 A1 形式：列用字母，行用数字（1 起始）。 */
private fun coord(x: Int, y: Int): String = "${('A' + x)}${y + 1}"
