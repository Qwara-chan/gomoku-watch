// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme

private val GoColorScheme = androidx.wear.compose.material3.ColorScheme(
    primary = WoodAmber,
    primaryDim = WoodAmberDark,
    onPrimary = BlackStone,
    primaryContainer = WoodAmberDark,
    onPrimaryContainer = BlackStone,
    secondary = CreamWhite,
    secondaryDim = OutlineGray,
    onSecondary = BlackStone,
    secondaryContainer = WoodBrown,
    onSecondaryContainer = CreamWhite,
    tertiary = HintGreen,
    tertiaryDim = HintGreenDark,
    onTertiary = BlackStone,
    tertiaryContainer = HintGreenDark,
    onTertiaryContainer = CreamWhite,
    background = DeepBlack,
    onBackground = CreamWhite,
    surfaceContainerLow = DeepBlack,
    surfaceContainer = PanelDark,
    surfaceContainerHigh = PanelLight,
    onSurface = CreamWhite,
    onSurfaceVariant = CreamWhite,
    error = ErrorRed,
    errorDim = ErrorRedDark,
    onError = CreamWhite,
    errorContainer = ErrorRedDark,
    onErrorContainer = CreamWhite,
    outline = OutlineGray,
    outlineVariant = OutlineGrayDark,
)

@Composable
fun GoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GoColorScheme, content = content)
}
