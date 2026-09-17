// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.qwara.go.ui.theme.BlackStone
import com.qwara.go.ui.theme.CreamWhite
import com.qwara.go.ui.theme.PanelDark
import com.qwara.go.ui.theme.WoodAmber

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
