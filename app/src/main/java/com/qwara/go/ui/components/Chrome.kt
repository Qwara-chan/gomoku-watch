// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import com.qwara.go.ui.theme.BlackStone
import com.qwara.go.ui.theme.CreamWhite
import com.qwara.go.ui.theme.PanelDark
import com.qwara.go.ui.theme.WoodAmber
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 吸附屏幕边缘的半透明胶囊容器。 */
@Composable
fun EdgeCapsule(
    modifier: Modifier = Modifier,
    /** 底色透明度：信息多的胶囊适当调低，便于看清被盖住的棋子与标记 */
    alpha: Float = 0.68f,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(PanelDark.copy(alpha = alpha))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

/** 边缘悬浮圆形图标按钮：自绘圆形底，图标严格居中；纯图标无文字。 */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    highlight: Boolean = false,
    size: Dp = 34.dp,
    label: String? = null,
) {
    val bg = if (highlight) WoodAmber else PanelDark
    val fg = if (highlight) BlackStone else CreamWhite
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.38f)
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = fg, modifier = Modifier.size(18.dp))
    }
}

/**
 * 沿圆屏下边缘弧线吸附的按钮容器。
 * 按钮圆心均布在屏幕圆周的内同心圆上（底部扇区），与圆屏边缘等距贴合。
 */
@Composable
fun BottomArcButtons(
    modifier: Modifier = Modifier,
    buttonCount: Int = 4,
    spreadDeg: Float = 48f,
    content: @Composable (Int) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val h = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f
        val btnR = with(density) { 34.dp.toPx() } / 2f
        val ring = radius - btnR - with(density) { 3.dp.toPx() }
        for (i in 0 until buttonCount) {
            val frac = if (buttonCount > 1) i / (buttonCount - 1f) else 0.5f
            // i 从左到右递增：左侧角度 > 90°，右侧 < 90°
            val rad = Math.toRadians((90.0 - (frac * 2.0 - 1.0) * spreadDeg))
            val x = cx + (ring * cos(rad)).toFloat()
            val y = cy + (ring * sin(rad)).toFloat()
            Box(
                modifier = Modifier.offset {
                    IntOffset((x - btnR).roundToInt(), (y - btnR).roundToInt())
                },
            ) {
                content(i)
            }
        }
    }
}

/** 悬浮控件的淡入淡出封装。 */
@Composable
fun ChromeVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(250)),
    ) {
        content()
    }
}
