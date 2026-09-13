package com.qwara.gomoku.ui.board

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import com.qwara.gomoku.GameUiState
import com.qwara.gomoku.game.Board
import com.qwara.gomoku.ui.components.CircleIconButton
import com.qwara.gomoku.ui.theme.BlackStone
import com.qwara.gomoku.ui.theme.CreamWhite
import com.qwara.gomoku.ui.theme.ErrorRed
import com.qwara.gomoku.ui.theme.HintGreen
import com.qwara.gomoku.ui.theme.WoodAmber
import com.qwara.gomoku.ui.theme.WoodBrown
import com.qwara.gomoku.ui.theme.WoodBrownEdge
import com.qwara.gomoku.ui.theme.WoodBrownLight
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

private const val STARS_AT = 3
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 3f

/**
 * 15x15 五子棋棋盘。
 *
 * 交互：
 *  - 点按交叉点落子；
 *  - 旋转表冠缩放棋盘（Wear 标准旋转输入）：缩小纵览全局棋形，放大局部精准落子；
 *  - 单指拖拽平移视角（棋盘四角附近不响应拖拽，避免与角落操作冲突）；
 *  - 视图偏离默认时右上角出现"复位"按钮。
 */
@Composable
fun GomokuBoard(
    state: GameUiState,
    onTap: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    onUserInteraction: (() -> Unit)? = null,
) {
    val size = state.boardSize
    val hintPulseState = rememberHintPulseState()

    // ---- 视图状态：缩放 + 平移 ----
    var viewScale by remember { mutableStateOf(MIN_SCALE) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }

    fun clampPan(p: Offset, scale: Float): Offset {
        val side = min(canvasSize.width, canvasSize.height).toFloat()
        val half = side * (scale - 1f) / 2f
        if (half <= 0f) return Offset.Zero
        return Offset(
            p.x.coerceIn(-half, half),
            p.y.coerceIn(-half, half),
        )
    }

    /** 屏幕坐标（Canvas 局部）→ 未缩放基准坐标（棋盘占满 Canvas 的坐标系） */
    fun toBaseCoords(screen: Offset): Offset {
        val cx = canvasSize.width / 2f
        val cy = canvasSize.height / 2f
        return (screen - pan - Offset(cx, cy)) / viewScale + Offset(cx, cy)
    }

    /** 计算以屏幕上某点为焦点缩放后的目标状态（scale, pan） */
    fun focusZoomTarget(screenPt: Offset, newScale: Float): Pair<Float, Offset> {
        val cx = canvasSize.width / 2f
        val cy = canvasSize.height / 2f
        val base = toBaseCoords(screenPt)
        val targetPan = clampPan(
            Offset(screenPt.x - cx, screenPt.y - cy) - Offset(base.x - cx, base.y - cy) * newScale,
            newScale,
        )
        return newScale to targetPan
    }

    // 双击缩放动画：过渡期间用户不会落子，动画结束后状态稳定，消除"点了没显示就再点"的错位
    val animScope = rememberCoroutineScope()
    var zoomAnimJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun animateZoomTo(targetScale: Float, targetPan: Offset) {
        zoomAnimJob?.cancel()
        val fromScale = viewScale
        val fromPan = pan
        zoomAnimJob = animScope.launch {
            val start = System.nanoTime()
            val durationNs = 250_000_000L
            while (true) {
                val t = ((System.nanoTime() - start).toFloat() / durationNs).coerceAtMost(1f)
                val e = 1f - (1f - t) * (1f - t)
                viewScale = fromScale + (targetScale - fromScale) * e
                pan = fromPan + (targetPan - fromPan) * e
                if (t >= 1f) break
                kotlinx.coroutines.yield()
            }
            viewScale = targetScale
            pan = targetPan
        }
    }

    fun applyRotary(dy: Float) {
        // 表冠向前（dy < 0）放大，向后缩小；每格约 10%
        val factor = 1f - dy * 0.10f
        viewScale = (viewScale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        pan = clampPan(pan, viewScale)
    }

    // Wear 专用 rotary 焦点：向系统注册"本组件处理表冠滚动"（与 ScalingLazyColumn 同机制）。
    // 注意：不要叠加 View.OnGenericMotionListener——它会抢先消费 rotary 事件，
    // 导致此焦点路径永远收不到（OPPO 表冠曾因此被误诊断为"系统不分发"）。
    val rotaryFocusRequester = rememberActiveFocusRequester()
    LaunchedEffect(Unit) { rotaryFocusRequester.requestFocus() }
    fun nearCorner(screen: Offset): Boolean {
        val base = toBaseCoords(screen)
        val side = min(canvasSize.width, canvasSize.height).toFloat()
        val cell = side / size
        val pad = cell / 2f
        val cornerDist = cell * 1.3f
        for (cx in intArrayOf(0, size - 1)) for (cy in intArrayOf(0, size - 1)) {
            val ix = pad + cx * cell
            val iy = pad + cy * cell
            if (hypot(base.x - ix, base.y - iy) <= cornerDist) return true
        }
        return false
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .aspectRatio(1f)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        val slop = viewConfiguration.touchSlop
                        var lastTapAt = 0L
                        while (true) {
                            // 等待按下
                            val press = awaitPointerEvent()
                            val downChange = press.changes.firstOrNull { it.pressed } ?: continue
                            onUserInteraction?.invoke()
                            val start = downChange.position
                            var end: PointerInputChange? = null
                            var dragging = false
                            var last = start
                            // 双指捏合状态
                            var pinching = false
                            var pinchStartDist = 0f
                            var pinchStartScale = 1f
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressedList = event.changes.filter { it.pressed }
                                if (pressedList.size >= 2) {
                                    // 双指捏合：两指距离比例缩放、中点平移
                                    pinching = true
                                    val p0 = pressedList[0].position
                                    val p1 = pressedList[1].position
                                    val center = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                                    val dist = hypot(p0.x - p1.x, p0.y - p1.y)
                                    if (pinchStartDist <= 0f) {
                                        pinchStartDist = dist
                                        pinchStartScale = viewScale
                                        last = center
                                    } else if (pinchStartDist > 0f) {
                                        viewScale = (pinchStartScale * dist / pinchStartDist)
                                            .coerceIn(MIN_SCALE, MAX_SCALE)
                                        pan = clampPan(pan + (center - last), viewScale)
                                        last = center
                                    }
                                    if (pressedList.isEmpty()) break
                                    continue
                                }
                                val change = event.changes.first()
                                when {
                                    !change.pressed && change.previousPressed -> end = change
                                    change.pressed -> {
                                        if (!dragging && !pinching &&
                                            hypot(change.position.x - start.x, change.position.y - start.y) > slop
                                        ) {
                                            // 超出手势阈值：进入拖拽（四角附近除外，避免误触）
                                            dragging = !nearCorner(start)
                                        }
                                        if (dragging) {
                                            if (viewScale <= MIN_SCALE + 0.02f) {
                                                // 全局视图下拖入：随拖拽距离渐进放大（避免跳变），无需先转表冠
                                                onUserInteraction?.invoke()
                                                val dragDist = hypot(
                                                    change.position.x - start.x,
                                                    change.position.y - start.y,
                                                )
                                                viewScale = 1f + (dragDist / 280f).coerceAtMost(0.6f)
                                            }
                                            pan = clampPan(pan + (change.position - last), viewScale)
                                        }
                                    }
                                }
                                last = change.position
                                if (end != null || event.changes.none { it.pressed }) break
                            }
                            if (dragging || pinching) continue
                            val offset = toBaseCoords(end?.position ?: start)
                            val side = min(canvasSize.width, canvasSize.height).toFloat()
                            val cell = side / size
                            val pad = cell / 2f
                            // 最近交叉点（四舍五入），距离阈值放宽以容忍手指偏差
                            val gx = ((offset.x - pad) / cell + 0.5f).toInt()
                            val gy = ((offset.y - pad) / cell + 0.5f).toInt()
                            if (gx in 0 until size && gy in 0 until size) {
                                val cx = pad + gx * cell
                                val cy = pad + gy * cell
                                val dist = hypot(offset.x - cx, offset.y - cy)
                                // 高倍放大时放宽吸附：格内大部分区域都落到最近交叉点，
                                // 避免"看着点在这里却落在旁边交叉点"的错位感
                                val threshold = cell * (0.55f + 0.25f * (viewScale - 1f).coerceIn(0f, 1f))
                                if (dist <= threshold) onTap(gx, gy)
                                // 双击：在最近交叉点处放大/缩小（单手替代捏合），动画过渡
                                val now = android.os.SystemClock.uptimeMillis()
                                if (now - lastTapAt < 300) {
                                    lastTapAt = 0
                                    if (viewScale > 1.5f) {
                                        animateZoomTo(MIN_SCALE, Offset.Zero)
                                    } else {
                                        val (ts, tp) = focusZoomTarget(end?.position ?: start, 2.2f)
                                        animateZoomTo(ts, tp)
                                    }
                                } else {
                                    lastTapAt = now
                                }
                            }
                        }
                    }
                }
                // 表冠缩放（Compose rotary 路径；真机依赖 activeFocusRequester 注册焦点）
                .onRotaryScrollEvent { event ->
                    onUserInteraction?.invoke()
                    applyRotary(event.verticalScrollPixels * 0.12f)
                    true
                }
                .focusRequester(rotaryFocusRequester)
                .focusable(),
        ) {
            canvasSize = IntSize(this.size.width.toInt(), this.size.height.toInt())
            val side = min(this.size.width, this.size.height)
            val cell = side / size
            val pad = cell / 2f
            val left = (this.size.width - side) / 2f
            val top = (this.size.height - side) / 2f

            withTransform({
                translate(pan.x, pan.y)
                scale(viewScale, viewScale, pivot = Offset(this.size.width / 2f, this.size.height / 2f))
            }) {
                // 木纹底：暖色线性渐变 + 深色轮廓线
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(WoodBrownLight, WoodBrown, WoodBrownLight),
                        start = Offset(left, top),
                        end = Offset(left + side, top + side),
                    ),
                    topLeft = Offset(left, top),
                    size = Size(side, side),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.8f),
                )
                drawRoundRect(
                    color = WoodBrownEdge,
                    topLeft = Offset(left, top),
                    size = Size(side, side),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.8f),
                    style = Stroke(width = (cell * 0.08f).coerceAtLeast(1.5f)),
                )

                val gridColor = BlackStone.copy(alpha = 0.5f)
                val lineWidth = (cell * 0.035f).coerceAtLeast(1f)
                for (i in 0 until size) {
                    val p = pad + i * cell
                    drawLine(
                        color = gridColor,
                        start = Offset(left + pad, top + p),
                        end = Offset(left + side - pad, top + p),
                        strokeWidth = lineWidth,
                    )
                    drawLine(
                        color = gridColor,
                        start = Offset(left + p, top + pad),
                        end = Offset(left + p, top + side - pad),
                        strokeWidth = lineWidth,
                    )
                }

                val starRadius = cell * 0.11f
                for ((sx, sy) in listOf(
                    STARS_AT to STARS_AT,
                    STARS_AT to size - 1 - STARS_AT,
                    size - 1 - STARS_AT to STARS_AT,
                    size - 1 - STARS_AT to size - 1 - STARS_AT,
                    size / 2 to size / 2,
                )) {
                    drawCircle(
                        color = BlackStone.copy(alpha = 0.75f),
                        radius = starRadius,
                        center = Offset(left + pad + sx * cell, top + pad + sy * cell),
                    )
                }

                for (y in 0 until size) {
                    for (x in 0 until size) {
                        when (state.colorAt(x, y)) {
                            Board.Color.BLACK -> drawStone(
                                center = Offset(left + pad + x * cell, top + pad + y * cell),
                                radius = cell * 0.44f,
                                dark = true,
                            )
                            Board.Color.WHITE -> drawStone(
                                center = Offset(left + pad + x * cell, top + pad + y * cell),
                                radius = cell * 0.44f,
                                dark = false,
                            )
                            Board.Color.EMPTY -> Unit
                        }
                    }
                }

                state.hint?.let { (hx, hy) ->
                    if (hx in 0 until size && hy in 0 until size) {
                        drawCircle(
                            color = HintGreen.copy(alpha = hintPulseState.value),
                            radius = cell * 0.42f,
                            center = Offset(left + pad + hx * cell, top + pad + hy * cell),
                            style = Stroke(width = (cell * 0.14f).coerceAtLeast(1.5f)),
                        )
                    }
                }

                for ((fx, fy) in state.forbidden) {
                    if (fx !in 0 until size || fy !in 0 until size) continue
                    val fx0 = left + pad + fx * cell
                    val fy0 = top + pad + fy * cell
                    val r = cell * 0.2f
                    val w = (cell * 0.1f).coerceAtLeast(1.5f)
                    drawLine(ErrorRed, Offset(fx0 - r, fy0 - r), Offset(fx0 + r, fy0 + r), w, StrokeCap.Round)
                    drawLine(ErrorRed, Offset(fx0 + r, fy0 - r), Offset(fx0 - r, fy0 + r), w, StrokeCap.Round)
                }

                state.moves.lastOrNull()?.let { last ->
                    if (last.x in 0 until size && last.y in 0 until size) {
                        drawCircle(
                            color = if (last.color == Board.Color.BLACK) CreamWhite else BlackStone,
                            radius = cell * 0.1f,
                            center = Offset(left + pad + last.x * cell, top + pad + last.y * cell),
                        )
                    }
                }
            }
        }

        // 复位按钮：圆形图标钮，吸附右边缘；视图偏离默认时出现
        if (viewScale > MIN_SCALE + 0.02f || pan != Offset.Zero) {
            CircleIconButton(
                icon = Icons.Default.Home,
                onClick = {
                    viewScale = MIN_SCALE
                    pan = Offset.Zero
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp),
                size = 34.dp,
            )
        }
    }
}

/** 提示圈脉冲动画状态：返回 State 而不读取值——避免组合阶段建立观察导致每帧重组整盘 */
@Composable
private fun rememberHintPulseState(): State<Float> =
    rememberInfiniteTransition(label = "hint").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "hintAlpha",
    )

private fun DrawScope.drawStone(center: Offset, radius: Float, dark: Boolean) {
    val colors = if (dark) {
        listOf(
            Color(0xFF5A524A),
            BlackStone,
            Color(0xFF000000),
        )
    } else {
        listOf(
            Color.White,
            CreamWhite,
            Color(0xFFBFB6A4),
        )
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = colors,
            center = Offset(center.x - radius * 0.32f, center.y - radius * 0.32f),
            radius = radius * 1.35f,
        ),
        radius = radius,
        center = center,
    )
    drawCircle(
        color = if (dark) WoodAmber.copy(alpha = 0.10f) else WoodBrownEdge.copy(alpha = 0.45f),
        radius = radius,
        center = center,
        style = Stroke(width = radius * 0.09f),
    )
}
