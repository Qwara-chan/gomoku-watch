package com.qwara.gomoku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.qwara.gomoku.ui.theme.BlackStone
import com.qwara.gomoku.ui.theme.CreamWhite
import com.qwara.gomoku.ui.theme.PanelDark
import com.qwara.gomoku.ui.theme.WoodAmber

/** 黑白小圆点，用于回合指示。 */
@Composable
fun ColorDot(color: Color, size: Int = 10, modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** 铺满宽度的菜单项按钮，左侧图标 + 标题（+ 可选副标题）。 */
@Composable
fun WideButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = PanelDark,
            contentColor = CreamWhite,
        ),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = text)
            if (subtitle != null) {
                Text(text = subtitle, color = CreamWhite.copy(alpha = 0.7f))
            }
        }
    }
}

/** 底部操作按钮：窄条圆钮，图标在上、标签在下，适合圆屏底部横排。 */
@Composable
fun ActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp)
    val content: @Composable () -> Unit = {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(15.dp))
            Text(text = label, fontSize = 9.sp, maxLines = 1, softWrap = false)
        }
    }
    if (highlight) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(44.dp),
            contentPadding = contentPadding,
        ) { content() }
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(44.dp),
            contentPadding = contentPadding,
        ) { content() }
    }
}

/** 状态点 + 文本行。 */
@Composable
fun StatusRow(
    dotColor: Color,
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        ColorDot(color = dotColor)
        Spacer(Modifier.width(6.dp))
        Text(text = text)
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}

/** 二选一的小按钮，选中态使用木色高亮。 */
@Composable
fun ChoiceButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = WoodAmber,
                contentColor = BlackStone,
            ),
        ) {
            Text(text = text)
        }
    } else {
        TextButton(onClick = onClick, modifier = modifier) {
            Text(text = text)
        }
    }
}
