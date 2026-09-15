// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.gomoku.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Text
import com.qwara.gomoku.R
import com.qwara.gomoku.ui.theme.CreamWhite
import com.qwara.gomoku.ui.theme.HintGreen
import com.qwara.gomoku.ui.theme.OutlineGray
import com.qwara.gomoku.ui.theme.PanelDark
import com.qwara.gomoku.ui.theme.WoodAmber
import kotlin.math.roundToInt

/**
 * 评估曲线面板：整局（黑方）胜率走势 + 拖动复盘 + 全谱分析入口 + 候选点列表。
 *
 * 曲线只画有评估值的手数（全谱分析会补齐），拖动图表即跳转到对应手数的局面。
 */
@Composable
fun EvalCurvePanel(
    curve: Map<Int, Float>,
    /** 总手数（曲线横轴右端） */
    maxPly: Int,
    /** 当前标记手数：复盘时为浏览手数，否则为总手数 */
    markerPly: Int,
    /** 全谱分析进度（已完成 / 总数），非空表示正在扫描 */
    progress: Pair<Int, Int>?,
    /** 候选点文案，形如 "A H8 62%" */
    candidates: List<String>,
    /** 拖动图表选择手数 */
    onScrub: (Int) -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onBackToCurrent: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reviewing = progress == null && markerPly < maxPly
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 面板背景吞掉点按：对局中开着面板看曲线时，误触不应该把棋子落到棋盘上
            .pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
            }
            .clip(RoundedCornerShape(14.dp))
            .background(PanelDark.copy(alpha = 0.86f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.curve_title),
                color = WoodAmber,
                fontSize = 11.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(
                    icon = if (progress != null) Icons.Default.Close else Icons.Default.Refresh,
                    onClick = { if (progress != null) onCancelScan() else onScan() },
                    size = 26.dp,
                    label = stringResource(
                        if (progress != null) R.string.curve_scan_cancel else R.string.curve_scan_start,
                    ),
                )
                if (reviewing) {
                    Spacer(Modifier.padding(horizontal = 2.dp))
                    CircleIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        onClick = onBackToCurrent,
                        size = 26.dp,
                        label = stringResource(R.string.curve_back_to_current),
                    )
                }
                Spacer(Modifier.padding(horizontal = 2.dp))
                Text(
                    text = stringResource(R.string.curve_collapse),
                    color = CreamWhite.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
        }

        EvalCurveChart(
            curve = curve,
            maxPly = maxPly,
            markerPly = markerPly,
            onScrub = onScrub,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
        )

        val value = curve[markerPly]
        val readout = when {
            progress != null -> stringResource(R.string.curve_scanning, progress.first, progress.second)
            value != null && !value.isNaN() -> stringResource(
                R.string.curve_readout,
                markerPly,
                (value * 100).roundToInt(),
            )
            curve.isEmpty() -> stringResource(R.string.curve_empty)
            else -> stringResource(R.string.curve_no_value, markerPly)
        }
        Text(
            text = readout,
            color = if (progress != null) HintGreen else CreamWhite.copy(alpha = 0.85f),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (candidates.isNotEmpty()) {
            Text(
                text = candidates.joinToString("   "),
                color = CreamWhite.copy(alpha = 0.85f),
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 曲线本体：横轴手数、纵轴黑方胜率（上=100%），拖动/点按选择手数 */
@Composable
private fun EvalCurveChart(
    curve: Map<Int, Float>,
    maxPly: Int,
    markerPly: Int,
    onScrub: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 手势闭包会随 key 缓存，取最新回调避免用旧局面（如扫描开始前的 maxPly/进度）判断
    val latestScrub by rememberUpdatedState(onScrub)
    // 过滤/排序只随 curve 与 maxPly 变化：拖动 scrub 逐事件重绘时不再每次重排
    // （必须在组合层 remember；Canvas 的 draw lambda 不是组合上下文）
    val points = remember(curve, maxPly) {
        curve.entries
            .filter { !it.value.isNaN() && it.key in 0..maxPly }
            .sortedBy { it.key }
    }
    Canvas(
        modifier = modifier.pointerInput(maxPly) {
            // 手数由触摸位置换算；点按与拖动都直接跳转对应局面
            fun plyAt(x: Float): Int =
                if (maxPly <= 0) 0 else (x / size.width * maxPly).roundToInt().coerceIn(0, maxPly)

            awaitEachGesture {
                val down = awaitFirstDown()
                latestScrub(plyAt(down.position.x))
                drag(down.id) { change -> latestScrub(plyAt(change.position.x)) }
            }
        },
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // 25% / 50% / 75% 参考线
        for (f in listOf(0.25f, 0.5f, 0.75f)) {
            drawLine(
                color = if (f == 0.5f) OutlineGray.copy(alpha = 0.8f) else OutlineGray.copy(alpha = 0.35f),
                start = Offset(0f, h * f),
                end = Offset(w, h * f),
                strokeWidth = 1f,
            )
        }

        fun xOf(ply: Int): Float = if (maxPly <= 0) w / 2f else w * ply / maxPly
        fun yOf(rate: Float): Float = h * (1f - rate.coerceIn(0f, 1f))

        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            // 只连相邻手数，中间缺评估值（未扫描到）时断开
            if (b.key - a.key != 1) continue
            drawLine(
                color = WoodAmber,
                start = Offset(xOf(a.key), yOf(a.value)),
                end = Offset(xOf(b.key), yOf(b.value)),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        for (p in points) {
            drawCircle(color = WoodAmber, radius = 2.dp.toPx(), center = Offset(xOf(p.key), yOf(p.value)))
        }

        // 当前标记：竖线 + 该点高亮
        val mx = xOf(markerPly)
        drawLine(
            color = CreamWhite.copy(alpha = 0.55f),
            start = Offset(mx, 0f),
            end = Offset(mx, h),
            strokeWidth = 1.5f,
        )
        curve[markerPly]?.takeIf { !it.isNaN() }?.let { rate ->
            drawCircle(color = CreamWhite, radius = 3.dp.toPx(), center = Offset(mx, yOf(rate)))
        }
    }
}
