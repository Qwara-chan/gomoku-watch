// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qwara.go.ui.screens.AnalysisScreen
import com.qwara.go.ui.screens.GameScreen
import com.qwara.go.ui.screens.MenuScreen
import com.qwara.go.ui.screens.SettingsScreen
import com.qwara.go.ui.theme.GomokuTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 熄屏/切后台时暂停引擎搜索（无限分析不主动停会在后台持续满载耗电），回前台自动恢复
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = vm.onHostStarted()
            override fun onStop(owner: LifecycleOwner) = vm.onHostStopped()
        })
        setContent {
            GomokuTheme {
                GomokuRoot(vm)
            }
        }
    }
}

@Composable
fun GomokuRoot(vm: MainViewModel = viewModel()) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    when (screen) {
        Screen.MENU -> MenuScreen(vm)
        Screen.GAME -> GameScreen(vm)
        Screen.ANALYSIS -> AnalysisScreen(vm)
        Screen.SETTINGS -> SettingsScreen(vm)
    }
}
