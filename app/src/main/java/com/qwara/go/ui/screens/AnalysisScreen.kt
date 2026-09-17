// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.qwara.go.GameUiState
import com.qwara.go.MainViewModel
import com.qwara.go.R
import com.qwara.go.engine.EngineStatus
import com.qwara.go.engine.PvLine
import com.qwara.go.ui.board.GoBoardView
import com.qwara.go.ui.components.BottomArcButtons
import com.qwara.go.ui.components.CircleIconButton
import com.qwara.go.ui.components.EdgeCapsule
import com.qwara.go.ui.components.EvalCurvePanel
import com.qwara.go.ui.theme.CreamWhite
import com.qwara.go.ui.theme.DeepBlack
import com.qwara.go.ui.theme.HintGreen
import com.qwara.go.ui.theme.PanelDark
import com.qwara.go.ui.theme.WoodAmber
import java.util.Locale

@Composable
fun AnalysisScreen(vm: MainViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val primary = state.pvLines.firstOrNull()
    // 曲线面板：点顶部评估胶囊展开/收起
    var curveOpen by remember { mutableStateOf(false) }

    // 分析期间保持屏幕常亮
    val screenView = LocalView.current
    DisposableEffect(Unit) {
        screenView.keepScreenOn = true
        onDispose { screenView.keepScreenOn = false }
    }

    // 从对局进来的话，返回键回对局（局面保留），否则回主菜单
    BackHandler { vm.backFromAnalysis() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack),
    ) {
        GoBoardView(
            state = state,
            onTap = vm::onBoardTap,
            modifier = Modifier.fillMaxSize(),
        )

        // 顶部：返回钮 + 胜率胶囊（整体居中，下移避开圆屏弧顶）；点胶囊展开评估曲线
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
                    onClick = vm::backFromAnalysis,
                    size = 28.dp,
                )
                Spacer(Modifier.width(4.dp))
                EdgeCapsule(modifier = Modifier.clickable { curveOpen = !curveOpen }) {
                    MiniEvalContent(state, primary)
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

        // 底部中间空隙：深度/节点/nps/用时 + 主变例胶囊（下压到弧线按钮上方，少挡棋盘中路）
        if (!curveOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp),
            ) {
                EdgeCapsule(alpha = 0.55f) {
                    // 复盘浏览时实时变例属于“当前局面”，与屏幕上的历史局面不符：改显示历史评估
                    DetailCapsuleContent(primary, reviewReadout(state.viewPly, state.curve), state.boardSize)
                }
            }
        } else {
            EvalCurvePanel(
                curve = state.curve,
                maxPly = state.moves.size,
                markerPly = state.viewPly ?: state.moves.size,
                progress = state.scan?.let { it.done to it.total },
                // 候选点只对“当前局面”有意义：复盘浏览/扫描期间不显示
                candidates = if (state.viewPly == null && state.scan == null) {
                    candidateRows(state.pvLines, state.analysisLines, state.showCandidates, state.boardSize)
                } else {
                    emptyList()
                },
                onScrub = { ply ->
                    // 扫描期间棋盘由扫描任务驱动，忽略拖动
                    if (state.scan == null) {
                        // 拖到最右端即回到当前局面
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

/**
 * 顶部窄胶囊：胜率条 + 胜率值/将杀 + 最佳着法坐标。
 * 复盘浏览时改用曲线上记录的历史胜率，避免显示当前局面的实时评估。
 */
@Composable
private fun MiniEvalContent(state: GameUiState, pv: PvLine?) {
    val ply = state.viewPly
    val reviewing = ply != null
    val winRate = if (ply != null) (state.curve[ply] ?: Float.NaN) else (pv?.winRate ?: Float.NaN)
    val fraction = if (winRate.isNaN()) 0f else winRate.coerceIn(0f, 1f)
    val best = if (reviewing) null else pv?.moves?.firstOrNull()
    val label = if (winRate.isNaN()) "—" else "${(fraction * 100).toInt()}%"
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
    Text(
        text = if (best == null) label else "$label ${coord(best.first, best.second, state.boardSize)}",
        color = WoodAmber,
        fontSize = 11.sp,
        maxLines = 1,
        softWrap = false,
    )
}

/** 底部胶囊：深度/选择深度 · 节点 · nps · 用时（两行：数据 + 主变例）。 */
@Composable
private fun DetailCapsuleContent(pv: PvLine?, reviewReadout: String?, boardSize: Int) {
    Column {
        Text(
            text = reviewReadout ?: detailText(pv),
            color = CreamWhite.copy(alpha = 0.85f),
            fontSize = 10.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        if (reviewReadout == null) {
            Text(
                text = pvText(pv, boardSize),
                color = CreamWhite.copy(alpha = 0.7f),
                fontSize = 10.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 复盘浏览时顶部/底部胶囊使用的历史评估文案 */
@Composable
private fun reviewReadout(ply: Int?, curve: Map<Int, Float>): String? {
    if (ply == null) return null
    val value = curve[ply]
    return if (value == null || value.isNaN()) {
        stringResource(R.string.curve_no_value, ply)
    } else {
        stringResource(R.string.curve_readout, ply, (value * 100).toInt())
    }
}

/** 思考细节：模拟数 + 胜率 */
private fun detailText(pv: PvLine?): String {
    if (pv == null) return "—"
    val rate = if (pv.winRate.isNaN()) "—" else "${(pv.winRate.coerceIn(0f, 1f) * 100).toInt()}%"
    return "${formatCount(pv.nodes)} 模拟 · $rate"
}

private fun pvText(pv: PvLine?, size: Int): String {
    val moves = pv?.moves.orEmpty()
    return if (moves.isEmpty()) {
        "—"
    } else {
        moves.joinToString(" ") { coord(it.first, it.second, size) }
    }
}

/** 候选点文案（多点分析）："A Q16 62%"，与棋盘上的字母徽章对应 */
private fun candidateRows(pvLines: List<PvLine>, lines: Int, enabled: Boolean, size: Int): List<String> {
    if (!enabled) return emptyList()
    return pvLines.take(lines).mapIndexedNotNull { i, pv ->
        val pt = pv.moves.firstOrNull() ?: return@mapIndexedNotNull null
        val pct = if (pv.winRate.isNaN()) "—" else "${(pv.winRate.coerceIn(0f, 1f) * 100).toInt()}%"
        "${('A' + i)} ${coord(pt.first, pt.second, size)} $pct"
    }
}

/** 节点数简写；固定用 ROOT/美国式小数点，避免某些语言下出现 "1,5k"。 */
private fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.ROOT, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

/** 坐标显示为 GTP 习惯：列字母（跳 I），行号从底边数 1 起。 */
private fun coord(x: Int, y: Int, size: Int): String {
    val col = if (x < 8) ('A' + x) else ('A' + x + 1)
    return "$col${size - y}"
}
