// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.Text
import com.qwara.go.MainViewModel
import com.qwara.go.R
import com.qwara.go.WeightsUi
import com.qwara.go.engine.EngineWeights
import com.qwara.go.ui.components.ChoiceButton
import com.qwara.go.ui.components.WideButton
import com.qwara.go.ui.theme.CreamWhite
import com.qwara.go.ui.theme.PanelDark
import com.qwara.go.ui.theme.WoodAmber

/** 权重状态的一行摘要，权重页与设置页共用 */
@Composable
fun weightsStatusText(w: WeightsUi): String = when (w) {
    WeightsUi.Checking -> stringResource(R.string.weights_status_checking)
    is WeightsUi.Ready -> stringResource(R.string.weights_status_ready, EngineWeights.formatBytes(w.sizeOnDisk))
    is WeightsUi.Missing ->
        if (w.resumedBytes > 0) {
            stringResource(
                R.string.weights_status_missing_resume,
                EngineWeights.formatBytes(w.resumedBytes),
                EngineWeights.formatBytes(w.totalBytes),
            )
        } else {
            stringResource(R.string.weights_status_missing, EngineWeights.formatBytes(w.totalBytes))
        }
    is WeightsUi.Downloading -> stringResource(
        R.string.weights_downloading_progress,
        EngineWeights.formatBytes(w.progress.totalDone),
        EngineWeights.formatBytes(w.progress.totalBytes),
        w.progress.sourceLabel,
    )
    is WeightsUi.Failed -> stringResource(R.string.weights_status_failed, w.message)
}

/**
 * 权重管理页：下载（多源/断点续传）、重下、删除。
 * 进入途径：设置页「引擎权重」，以及缺权重时被引擎功能门控（人机开局/分析/提示）带过来。
 */
@Composable
fun WeightsScreen(vm: MainViewModel) {
    val w by vm.weights.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // 删除是破坏性的：第一次点只切到确认态，再点才真删。
    // key 用「是否就绪」而非整个状态：下载进度每次刷新都是新对象，不能把 effect 反复重启
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(w is WeightsUi.Ready) { if (w !is WeightsUi.Ready) confirmDelete = false }

    BackHandler { vm.closeWeights() }

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
                IconButton(onClick = vm::closeWeights) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                }
                Text(
                    text = stringResource(R.string.weights_title),
                    color = WoodAmber,
                    fontSize = 20.sp,
                )
            }
        }

        item {
            Text(
                text = weightsStatusText(w),
                color = CreamWhite,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (w !is WeightsUi.Ready && w !is WeightsUi.Downloading) {
            item {
                Text(
                    text = stringResource(R.string.weights_desc, EngineWeights.formatBytes(EngineWeights.TOTAL_BYTES)),
                    color = CreamWhite.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.weights_source),
                    color = WoodAmber,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
            item {
                // 0=自动；i+1 固定用 SOURCES[i]
                val labels = listOf(stringResource(R.string.weights_source_auto)) +
                    EngineWeights.SOURCES.map { it.label }
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    labels.forEachIndexed { index, label ->
                        ChoiceButton(
                            text = label,
                            selected = settings.weightsSource == index,
                            onClick = { vm.setWeightsSource(index) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        when (val s = w) {
            is WeightsUi.Downloading -> {
                item {
                    Spacer(Modifier.height(6.dp))
                    DownloadBar(
                        fraction = if (s.progress.totalBytes > 0) {
                            s.progress.totalDone.toFloat() / s.progress.totalBytes
                        } else {
                            0f
                        },
                    )
                }
                item {
                    Text(
                        text = stringResource(
                            R.string.weights_downloading_file,
                            s.progress.fileIndex + 1,
                            EngineWeights.FILES.size,
                            s.progress.fileName,
                        ),
                        color = CreamWhite.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    WideButton(
                        text = stringResource(R.string.weights_action_cancel),
                        icon = Icons.Default.Close,
                        onClick = vm::cancelWeightsDownload,
                    )
                }
            }
            is WeightsUi.Missing, WeightsUi.Checking -> {
                item {
                    WideButton(
                        text = stringResource(R.string.weights_action_download),
                        icon = Icons.Default.PlayArrow,
                        onClick = { vm.startWeightsDownload() },
                        enabled = s is WeightsUi.Missing,
                    )
                }
            }
            is WeightsUi.Failed -> {
                item {
                    WideButton(
                        text = stringResource(R.string.weights_action_retry),
                        icon = Icons.Default.Refresh,
                        onClick = { vm.startWeightsDownload() },
                    )
                }
            }
            is WeightsUi.Ready -> {
                item {
                    WideButton(
                        text = stringResource(R.string.weights_action_redownload),
                        icon = Icons.Default.Refresh,
                        onClick = { vm.startWeightsDownload(clearFirst = true) },
                    )
                }
                item {
                    WideButton(
                        text = stringResource(
                            if (confirmDelete) R.string.weights_action_delete_confirm else R.string.weights_action_delete,
                        ),
                        icon = Icons.Default.Delete,
                        onClick = {
                            if (confirmDelete) vm.deleteWeights() else confirmDelete = true
                        },
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 简单的横向进度条（不依赖 wear material3 的 ProgressIndicator API 差异） */
@Composable
private fun DownloadBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(PanelDark),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(WoodAmber),
        )
    }
}
