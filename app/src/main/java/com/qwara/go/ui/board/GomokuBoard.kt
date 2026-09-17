// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.ui.board

import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import com.qwara.go.GameMode
import com.qwara.go.GameUiState
import com.qwara.go.engine.EngineStatus
import com.qwara.go.game.Board
import com.qwara.go.game.RenjuRules
import com.qwara.go.ui.components.CircleIconButton
import com.qwara.go.ui.theme.BlackStone
import com.qwara.go.ui.theme.CreamWhite
import com.qwara.go.ui.theme.ErrorRed
import com.qwara.go.ui.theme.HintGreen
import com.qwara.go.ui.theme.PanelDark
import com.qwara.go.ui.theme.WoodAmber
import com.qwara.go.ui.theme.WoodBrown
import com.qwara.go.ui.theme.WoodBrownEdge
import com.qwara.go.ui.theme.WoodBrownLight
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.launch

private const val TAG = "GomokuBoard"
private const val STARS_AT = 3

// 缩放范围与平移上限见 BoardViewport（纯几何 + 单测覆盖圆屏四角可达性）
private const val MIN_SCALE = BoardViewport.MIN_SCALE
private const val MAX_SCALE = BoardViewport.MAX_SCALE
private const val DEFAULT_SCALE = BoardViewport.DEFAULT_SCALE

/**
 * 滚轮源表冠的缩放灵敏度：applyRotary 的输入 = 事件增量 × 本系数。
 * 实测 OPPO OWW251：转表冠时每秒约 25 个 Scroll 事件、单个事件增量常见 1~12，
 * 因此每单位系数必须很小，否则一次转动就冲到最大/最小倍率。
 * 取 0.09：常见事件约 3.5%~7% 缩放，1x→3x 约需一秒的连续转动。
 */
private const val WHEEL_STEP = 0.09f

/** 单个滚轮/Scroll 事件的输入上限，避免个别大增量造成跳变 */
private const val WHEEL_DELTA_CAP = 8f

/** 多点分析候选点徽章：A/B/C… 与配色 */
private const val CANDIDATE_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private val CANDIDATE_COLORS = listOf(HintGreen, WoodAmber, CreamWhite)

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
    val hintPulseState = rememberHintPulseState(state.hint)
    // pointerInput(Unit) 的手势闭包不会随 state 变化重启，取当前位置需用最新状态
    val latestState by rememberUpdatedState(state)

    // ---- 视图状态：缩放 + 平移 ----
    var viewScale by remember { mutableStateOf(DEFAULT_SCALE) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    // 网格文字（手数/徽章）每帧都要画，必须缓存排版结果；字号由基础格宽决定，故按画布尺寸失效
    val textMeasurer = rememberTextMeasurer()
    val textCache = remember(canvasSize, textMeasurer) { BoardTextCache(textMeasurer) }
    // 基础格宽（未缩放）：下面的绘制资源都只随画布尺寸变化，逐帧重建会不断分配 Skia 对象
    val baseCell = remember(canvasSize) { min(canvasSize.width, canvasSize.height).toFloat() / size }
    // 棋子渐变的 Shader 只在格宽变化时重建（缩放/平移不改变格宽）
    val stones = remember(baseCell) { StoneStyle(baseCell) }
    // 木纹底渐变同样是 Skia Shader，按画布尺寸缓存（画布恒为正方形，渐变起点即左上角）
    val woodBrush = remember(baseCell) {
        val side = baseCell * size
        Brush.linearGradient(
            colors = listOf(WoodBrownLight, WoodBrown, WoodBrownLight),
            start = Offset.Zero,
            end = Offset(side, side),
        )
    }
    // 徽章/序号文字样式：从 draw 块提升，避免每次绘制重新分配 TextStyle。
    // 原先的 toSp() 是 DrawScope（Density）的成员，这里用同一 density 包一层保证字号换算一致
    val density = LocalDensity.current
    val forbiddenBadgeStyle = remember(baseCell, density) {
        TextStyle(fontSize = with(density) { (baseCell * 0.40f).toSp() }, fontWeight = FontWeight.Bold)
    }
    val candidateBadgeStyle = remember(baseCell, density) {
        TextStyle(fontSize = with(density) { (baseCell * 0.42f).toSp() }, fontWeight = FontWeight.Bold)
    }
    val moveNumberCompactStyle = remember(baseCell, density) {
        TextStyle(fontSize = with(density) { (baseCell * 0.36f).toSp() }, fontWeight = FontWeight.Bold)
    }
    val moveNumberStyle = remember(baseCell, density) {
        TextStyle(fontSize = with(density) { (baseCell * 0.44f).toSp() }, fontWeight = FontWeight.Bold)
    }
    // 复位钮可见性用 derivedStateOf：表冠/拖拽逐事件写 viewScale/pan，若在组合层直读会
    // 每事件重组整个棋盘组件；包一层后只在「偏离默认视图」布尔翻转时重组，其余仅 draw 失效
    val viewOffDefault by remember {
        derivedStateOf { abs(viewScale - DEFAULT_SCALE) > 0.02f || pan != Offset.Zero }
    }

    /**
     * 平移范围：允许把任意交叉点（含四个角点）移到屏幕中心。
     * 棋盘中心到角点的距离 = (side - cell)/2，乘上缩放即为所需最大平移量。
     * 圆屏下这条很关键：以前按"棋盘铺满圆屏"夹取时，角点最多只能顶到方形画面的角上，
     * 而那里正好被圆屏切掉，于是四角交叉点永远看不见也点不到。
     */
    fun clampPan(p: Offset, scale: Float): Offset {
        val side = min(canvasSize.width, canvasSize.height).toFloat()
        val half = BoardViewport.panLimit(side, size, scale)
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
            // 必须按帧驱动：早期用 yield() 空转会把主线程占满，实测只剩 5 帧/250ms（单帧 350ms）
            val startFrame = withFrameNanos { it }
            var t = 0f
            while (t < 1f) {
                val now = withFrameNanos { it }
                t = ((now - startFrame) / 250_000_000f).coerceIn(0f, 1f)
                val e = 1f - (1f - t) * (1f - t)
                viewScale = fromScale + (targetScale - fromScale) * e
                pan = fromPan + (targetPan - fromPan) * e
            }
            viewScale = targetScale
            pan = targetPan
        }
    }

    fun applyRotary(dy: Float) {
        // 表冠操作优先：打断可能在跑的双击缩放动画，避免两者互相抢写 viewScale
        zoomAnimJob?.cancel()
        // 表冠向后（dy > 0）放大、向前缩小（用户要求的方向；三条输入通路都汇到这里，
        // 只在这里定符号即可保持一致）。指数形式保证任何步长都不会把系数拉到负值
        val factor = exp(dy * 0.10f)
        viewScale = (viewScale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        pan = clampPan(pan, viewScale)
    }

    // Wear 表冠两条通路：
    //  1) 标准表冠 = SOURCE_ROTARY_ENCODER，走 Compose 的 onRotaryScrollEvent；
    //  2) 部分手表（OPPO OWW251 实测）把表冠做成 SOURCE_MOUSE 的滚轮轴上报 REL_WHEEL，
    //     Compose 的 rotary 只处理 ROTARY_ENCODER，事件永远到不了这里 → 在 View 层兜底。
    // 焦点用官方替代 API requestFocusOnHierarchyActive()：它直接在棋盘节点上请求焦点，
    // 不会像已废弃的 rememberActiveFocusRequester() 那样把焦点交给一个隐形 Box。
    val view = LocalView.current
    DisposableEffect(view) {
        Log.i(TAG, "rotary fallback installed on ${view.javaClass.simpleName}")
        view.setOnGenericMotionListener { _, event ->
            if (event.actionMasked != MotionEvent.ACTION_SCROLL) return@setOnGenericMotionListener false
            val axis = event.getAxisValue(MotionEvent.AXIS_SCROLL)
                .takeIf { it != 0f }
                ?: event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val rotary = event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
            Log.d(
                TAG,
                "crown scroll source=0x${event.source.toString(16)} axis=$axis rotaryEncoder=$rotary",
            )
            // rotary 源仍交给 Compose 路径，避免同一次转动被缩放两次
            if (rotary || axis == 0f) {
                false
            } else {
                onUserInteraction?.invoke()
                applyRotary(axis.coerceIn(-WHEEL_DELTA_CAP, WHEEL_DELTA_CAP) * WHEEL_STEP)
                true
            }
        }
        onDispose { view.setOnGenericMotionListener(null) }
    }

    /** 屏幕点是否落在棋盘四角附近（四角不响应拖拽，避免与角落按钮冲突） */
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
                // 滚轮源表冠的 Compose 通路：Compose 把鼠标滚轮转成 pointer 的 Scroll 事件，
                // 需要单独监听（下面的手势循环只处理按下/拖动，会跳过 Scroll）
                .pointerInput(size) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            Log.d(TAG, "pointer scroll dy=$dy")
                            if (dy != 0f) {
                                onUserInteraction?.invoke()
                                applyRotary(dy.coerceIn(-WHEEL_DELTA_CAP, WHEEL_DELTA_CAP) * WHEEL_STEP)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
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
                                                // 最小倍率下拖入：随拖拽距离渐进放大（避免跳变），无需先转表冠
                                                onUserInteraction?.invoke()
                                                val dragDist = hypot(
                                                    change.position.x - start.x,
                                                    change.position.y - start.y,
                                                )
                                                viewScale = MIN_SCALE +
                                                    (dragDist / 280f).coerceAtMost(0.9f)
                                            }
                                            pan = clampPan(pan + (change.position - last), viewScale)
                                        }
                                    }
                                }
                                last = change.position
                                if (end != null || event.changes.none { it.pressed }) break
                            }
                            if (dragging || pinching) continue
                            // 指针被系统取消（无抬起事件）时不算点击，避免误落子
                            val up = end ?: continue
                            val offset = toBaseCoords(up.position)
                            val side = min(canvasSize.width, canvasSize.height).toFloat()
                            val cell = side / size
                            val pad = cell / 2f
                            // 最近交叉点（四舍五入），距离阈值放宽以容忍手指偏差
                            val gx = ((offset.x - pad) / cell + 0.5f).toInt()
                            val gy = ((offset.y - pad) / cell + 0.5f).toInt()
                            if (gx !in 0 until size || gy !in 0 until size) continue
                            val cx = pad + gx * cell
                            val cy = pad + gy * cell
                            val dist = hypot(offset.x - cx, offset.y - cy)
                            // 高倍放大时放宽吸附：格内大部分区域都落到最近交叉点，
                            // 避免"看着点在这里却落在旁边交叉点"的错位感
                            val threshold = cell * (0.55f + 0.25f * (viewScale - 1f).coerceIn(0f, 1f))
                            if (dist > threshold) continue
                            // 双击已有棋子处：缩放视图（单手替代捏合）；空点上的连续两次点击按两次落子处理
                            val now = android.os.SystemClock.uptimeMillis()
                            val doubleTap = now - lastTapAt < 300 &&
                                latestState.colorAt(gx, gy) != Board.Color.EMPTY
                            if (doubleTap) {
                                lastTapAt = 0
                                if (viewScale > 1.5f) {
                                    animateZoomTo(MIN_SCALE, Offset.Zero)
                                } else {
                                    val (ts, tp) = focusZoomTarget(up.position, 2.2f)
                                    animateZoomTo(ts, tp)
                                }
                            } else {
                                lastTapAt = now
                                onTap(gx, gy)
                            }
                        }
                    }
                }
                // 表冠缩放（标准 rotary 路径，需要本节点持有焦点，见上方 requestFocusOnHierarchyActive）
                .onRotaryScrollEvent { event ->
                    onUserInteraction?.invoke()
                    applyRotary(event.verticalScrollPixels * 0.12f)
                    true
                }
                .onSizeChanged { canvasSize = it }
                .requestFocusOnHierarchyActive()
                .onFocusChanged { Log.d(TAG, "board focus=${it.isFocused}") }
                .focusable(),
        ) {
            val side = min(this.size.width, this.size.height)
            val cell = side / size
            val pad = cell / 2f
            val left = (this.size.width - side) / 2f
            val top = (this.size.height - side) / 2f

            // 放大/平移后棋盘可能移出画面：整块画布先铺深木色，避免露出突兀的黑底
            drawRect(color = WoodBrownEdge.copy(alpha = 0.45f))

            withTransform({
                translate(pan.x, pan.y)
                scale(viewScale, viewScale, pivot = Offset(this.size.width / 2f, this.size.height / 2f))
            }) {
                // 木纹底：暖色线性渐变 + 深色轮廓线（渐变 Brush 已按画布尺寸缓存）
                drawRoundRect(
                    brush = woodBrush,
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
                            Board.Color.BLACK -> stones.draw(
                                this,
                                center = Offset(left + pad + x * cell, top + pad + y * cell),
                                dark = true,
                            )
                            Board.Color.WHITE -> stones.draw(
                                this,
                                center = Offset(left + pad + x * cell, top + pad + y * cell),
                                dark = false,
                            )
                            Board.Color.EMPTY -> Unit
                        }
                    }
                }

                // 胜利连线：压在棋子上层，半透明让手数仍可读
                if (state.showWinLine && state.winLine.size >= 2) {
                    val head = state.winLine.first()
                    val tail = state.winLine.last()
                    drawLine(
                        color = WoodAmber.copy(alpha = 0.55f),
                        start = Offset(left + pad + head.first * cell, top + pad + head.second * cell),
                        end = Offset(left + pad + tail.first * cell, top + pad + tail.second * cell),
                        strokeWidth = cell * 0.16f,
                        cap = StrokeCap.Round,
                    )
                }

                // 禁手点：按类型着色的小徽章 + 单字（三三/四四/长连）
                if (state.showForbidden && state.forbidden.isNotEmpty()) {
                    for ((pt, type) in state.forbidden) {
                        val fx = pt.first
                        val fy = pt.second
                        if (fx !in 0 until size || fy !in 0 until size) continue
                        val glyph = when (type) {
                            RenjuRules.Forbidden.DOUBLE_THREE -> "三" to WoodAmber
                            RenjuRules.Forbidden.DOUBLE_FOUR -> "四" to ErrorRed
                            RenjuRules.Forbidden.OVERLINE -> "长" to CreamWhite
                            RenjuRules.Forbidden.NONE -> continue
                        }
                        val cx = left + pad + fx * cell
                        val cy = top + pad + fy * cell
                        drawCircle(PanelDark.copy(alpha = 0.72f), radius = cell * 0.30f, center = Offset(cx, cy))
                        drawCircle(
                            color = glyph.second,
                            radius = cell * 0.30f,
                            center = Offset(cx, cy),
                            style = Stroke(width = (cell * 0.07f).coerceAtLeast(1f)),
                        )
                        val layout = textCache.layout(glyph.first, glyph.second, forbiddenBadgeStyle)
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f),
                        )
                    }
                }

                // 多点分析：候选点 A/B/C 徽章。人机对战里引擎应着（THINKING）时同样画，
                // 画面上就是它此刻的思考过程；复盘浏览时不画
                val engineSearching = state.enginePhase == EngineStatus.Phase.ANALYZING ||
                    (state.mode == GameMode.AI && state.enginePhase == EngineStatus.Phase.THINKING)
                if (state.showCandidates && state.viewPly == null && engineSearching) {
                    state.pvLines.take(state.analysisLines).forEachIndexed { i, pv ->
                        val pt = pv.moves.firstOrNull() ?: return@forEachIndexed
                        val (gx, gy) = pt
                        if (gx !in 0 until size || gy !in 0 until size) return@forEachIndexed
                        // 引擎吐出的候选点理论上都在空点上，防御性判断避免盖住棋子
                        if (state.colorAt(gx, gy) != Board.Color.EMPTY) return@forEachIndexed
                        val color = CANDIDATE_COLORS[i % CANDIDATE_COLORS.size]
                        val cx = left + pad + gx * cell
                        val cy = top + pad + gy * cell
                        drawCircle(PanelDark.copy(alpha = 0.80f), radius = cell * 0.30f, center = Offset(cx, cy))
                        drawCircle(
                            color = color,
                            radius = cell * 0.30f,
                            center = Offset(cx, cy),
                            style = Stroke(width = (cell * 0.08f).coerceAtLeast(1f)),
                        )
                        val layout = textCache.layout(
                            CANDIDATE_LETTERS[i % CANDIDATE_LETTERS.length].toString(),
                            color,
                            candidateBadgeStyle,
                        )
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f),
                        )
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

                // 手数序号：画在棋子上（黑子米白字 / 白子墨黑字），随视图缩放一起放大
                val shownMoves = state.visibleMoves
                if (state.showMoveNumbers) {
                    val style = if (shownMoves.size >= 100) moveNumberCompactStyle else moveNumberStyle
                    shownMoves.forEachIndexed { i, m ->
                        if (m.x !in 0 until size || m.y !in 0 until size) return@forEachIndexed
                        val color = if (m.color == Board.Color.BLACK) CreamWhite else BlackStone
                        val layout = textCache.layout((i + 1).toString(), color, style)
                        val cx = left + pad + m.x * cell
                        val cy = top + pad + m.y * cell
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f),
                        )
                    }
                }

                // 最后一步：棋子外圈光环（与手数序号不冲突）
                shownMoves.lastOrNull()?.let { last ->
                    if (last.x in 0 until size && last.y in 0 until size) {
                        val center = Offset(left + pad + last.x * cell, top + pad + last.y * cell)
                        val radius = cell * 0.5f
                        drawCircle(
                            color = CreamWhite,
                            radius = radius,
                            center = center,
                            style = Stroke(width = (cell * 0.09f).coerceAtLeast(1.5f)),
                        )
                        drawCircle(
                            color = BlackStone.copy(alpha = 0.45f),
                            radius = radius,
                            center = center,
                            style = Stroke(width = cell * 0.03f),
                        )
                    }
                }
            }
        }

        // 复位按钮：圆形图标钮，吸附右边缘；视图偏离默认时出现
        // 偏离默认视图才显示复位钮：拿 MIN_SCALE 比较会恒真（默认 1.0 > 最小 0.66），
        // 导致复位钮常驻并挡住棋盘右中部
        if (viewOffDefault) {
            CircleIconButton(
                icon = Icons.Default.Home,
                onClick = {
                    viewScale = DEFAULT_SCALE
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

/**
 * 提示圈脉冲：仅在提示出现时呼吸两轮（约 3.5 秒）然后定格全亮，提示消失即复位。
 * 不用 infiniteTransition——那只要棋盘在组合中就常驻帧时钟，即使没有提示也每帧
 * 唤醒 Choreographer，让 CPU 无法空闲（对局/分析页全程画着棋盘，是持续功耗源）。
 * 返回 State 而不读取值——避免组合阶段建立观察导致每帧重组整盘（draw 相位读值只重绘）。
 */
@Composable
private fun rememberHintPulseState(hint: Pair<Int, Int>?): State<Float> {
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(hint) {
        if (hint == null) {
            pulse.snapTo(1f)
        } else {
            pulse.snapTo(0.35f)
            repeat(2) {
                pulse.animateTo(1f, tween(700))
                pulse.animateTo(0.35f, tween(700))
            }
            pulse.animateTo(1f, tween(700))
        }
    }
    // Animatable 不是 State：包一层 derivedStateOf 暴露为 State<Float>，
    // 值不变（动画结束后）时 draw 相位的观察不会收到任何失效
    return remember { derivedStateOf { pulse.value } }
}

/**
 * 棋盘内文字的排版缓存：手数序号与徽章每帧都要画，逐帧 measure 会明显掉帧。
 * 键含文本、颜色与字号，画布尺寸变化时由调用方重建整个缓存。
 */
private class BoardTextCache(private val measurer: TextMeasurer) {

    private data class Key(val text: String, val color: Color, val fontSp: Float)

    private val cache = HashMap<Key, TextLayoutResult>()

    fun layout(text: String, color: Color, style: TextStyle): TextLayoutResult =
        cache.getOrPut(Key(text, color, style.fontSize.value)) {
            measurer.measure(AnnotatedString(text), style.copy(color = color))
        }
}

/** 棋子配色：文件级常量，避免每帧每子重建颜色列表 */
private val DARK_STONE_COLORS = listOf(Color(0xFF5A524A), BlackStone, Color(0xFF000000))
private val LIGHT_STONE_COLORS = listOf(Color.White, CreamWhite, Color(0xFFBFB6A4))

/**
 * 棋子外观缓存：渐变 Brush 会创建 Skia Shader，整盘每帧重建代价很高（弱表上尤其明显），
 * 而它们的参数只跟基础格宽有关，因此按画布尺寸 remember 一份即可。
 * 绘制时先 translate 到棋子中心，再用以原点为基准的固定画刷。
 */
private class StoneStyle(val cell: Float) {
    val radius = cell * 0.44f
    private val rimWidth = (radius * 0.09f).coerceAtLeast(1f)
    private val darkBrush = Brush.radialGradient(
        colors = DARK_STONE_COLORS,
        center = Offset(-radius * 0.32f, -radius * 0.32f),
        radius = radius * 1.35f,
    )
    private val lightBrush = Brush.radialGradient(
        colors = LIGHT_STONE_COLORS,
        center = Offset(-radius * 0.32f, -radius * 0.32f),
        radius = radius * 1.35f,
    )
    private val darkRim = WoodAmber.copy(alpha = 0.10f)
    private val lightRim = WoodBrownEdge.copy(alpha = 0.45f)

    fun draw(scope: DrawScope, center: Offset, dark: Boolean) = with(scope) {
        // 先移到棋子中心，渐变就以原点为基准，画刷可以长期复用
        withTransform({ translate(center.x, center.y) }) {
            drawCircle(
                brush = if (dark) darkBrush else lightBrush,
                radius = radius,
                center = Offset.Zero,
            )
            drawCircle(
                color = if (dark) darkRim else lightRim,
                radius = radius,
                center = Offset.Zero,
                style = Stroke(width = rimWidth),
            )
        }
    }
}
