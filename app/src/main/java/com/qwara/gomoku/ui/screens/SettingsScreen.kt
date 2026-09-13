package com.qwara.gomoku.ui.screens

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.qwara.gomoku.MainViewModel
import com.qwara.gomoku.R
import com.qwara.gomoku.game.Rule
import com.qwara.gomoku.ui.components.ChoiceButton
import com.qwara.gomoku.ui.theme.CreamWhite
import com.qwara.gomoku.ui.theme.WoodAmber

private const val MIN_ENGINE_SEC = 1
private const val MAX_ENGINE_SEC = 10

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    BackHandler { vm.backFromSettings() }

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::backFromSettings) {
                    androidx.wear.compose.material3.Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                }
                Text(
                    text = stringResource(R.string.menu_settings),
                    color = WoodAmber,
                    fontSize = 20.sp,
                )
            }
        }

        item {
            SettingLabel(stringResource(R.string.settings_rule))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceButton(
                    text = stringResource(R.string.settings_rule_freestyle),
                    selected = settings.rule == Rule.FREESTYLE,
                    onClick = { vm.setRule(Rule.FREESTYLE) },
                )
                ChoiceButton(
                    text = stringResource(R.string.settings_rule_renju),
                    selected = settings.rule == Rule.RENJU,
                    onClick = { vm.setRule(Rule.RENJU) },
                )
            }
        }

        item {
            SettingLabel(stringResource(R.string.settings_engine_time))
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(
                    onClick = { vm.setEngineTime((settings.engineTimeSec - 1).coerceAtLeast(MIN_ENGINE_SEC)) },
                    enabled = settings.engineTimeSec > MIN_ENGINE_SEC,
                ) {
                    MinusGlyph()
                }
                Text(
                    text = stringResource(R.string.settings_engine_time_value, settings.engineTimeSec),
                    color = WoodAmber,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                IconButton(
                    onClick = { vm.setEngineTime((settings.engineTimeSec + 1).coerceAtMost(MAX_ENGINE_SEC)) },
                    enabled = settings.engineTimeSec < MAX_ENGINE_SEC,
                ) {
                    androidx.wear.compose.material3.Icon(Icons.Default.Add, stringResource(R.string.settings_increase))
                }
            }
        }

        item {
            SwitchButton(
                checked = settings.sound,
                onCheckedChange = vm::setSound,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_sound)) },
            )
        }
        item {
            SwitchButton(
                checked = settings.vibrate,
                onCheckedChange = vm::setVibrate,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_vibrate)) },
            )
        }

        item {
            SettingLabel(stringResource(R.string.settings_about))
        }
        item {
            Text(
                text = stringResource(R.string.settings_about_version, versionName),
                color = CreamWhite,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                text = stringResource(R.string.settings_about_powered),
                color = CreamWhite.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text,
        color = WoodAmber,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    )
}

/** 减号图标：icons-core 没有 Remove，直接画一条横线。 */
@Composable
private fun MinusGlyph() {
    val description = stringResource(R.string.settings_decrease)
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .semantics { contentDescription = description },
    ) {
        val stroke = size.width * 0.12f
        drawLine(
            color = Color.White,
            start = Offset(size.width * 0.25f, size.height * 0.5f),
            end = Offset(size.width * 0.75f, size.height * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
