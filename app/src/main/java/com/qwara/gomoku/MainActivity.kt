package com.qwara.gomoku

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qwara.gomoku.ui.screens.AnalysisScreen
import com.qwara.gomoku.ui.screens.GameScreen
import com.qwara.gomoku.ui.screens.MenuScreen
import com.qwara.gomoku.ui.screens.SettingsScreen
import com.qwara.gomoku.ui.theme.GomokuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GomokuTheme {
                GomokuRoot()
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
