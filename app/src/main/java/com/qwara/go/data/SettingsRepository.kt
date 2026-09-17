// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 轻量设置存储（SharedPreferences） */
class SettingsRepository(context: Context) {

    data class Settings(
        /** 棋盘路数（9/13/19）；改动后由 ViewModel 清空棋盘并重建引擎会话 */
        val boardSize: Int = 19,
        /** 贴目（中国规则数子法，加到白方） */
        val komi: Float = 6.5f,
        /** 引擎每步思考秒数（pachi 的读秒时限；也是棋力主杠杆） */
        val engineTimeSec: Int = 3,
        /** 分析输出路数（多点分析，取 lz 上报的前 N 路） */
        val analysisLines: Int = 3,
        val showMoveNumbers: Boolean = true,
        val showCandidates: Boolean = true,
        val sound: Boolean = true,
        val vibrate: Boolean = true,
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("go_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings

    private fun load(): Settings = Settings(
        boardSize = prefs.getInt(KEY_BOARD_SIZE, 19).takeIf { it in BOARD_SIZES } ?: 19,
        komi = prefs.getFloat(KEY_KOMI, 6.5f).takeIf { k -> KOMI_PRESETS.any { it == k } } ?: 6.5f,
        engineTimeSec = prefs.getInt(KEY_TIME, 3).takeIf { it in ENGINE_TIMES } ?: 3,
        analysisLines = prefs.getInt(KEY_LINES, 3).takeIf { it in ANALYSIS_LINES } ?: 3,
        showMoveNumbers = prefs.getBoolean(KEY_SHOW_NUMBERS, true),
        showCandidates = prefs.getBoolean(KEY_SHOW_CANDIDATES, true),
        sound = prefs.getBoolean(KEY_SOUND, true),
        vibrate = prefs.getBoolean(KEY_VIBRATE, true),
    )

    private fun save(s: Settings) {
        prefs.edit()
            .putInt(KEY_BOARD_SIZE, s.boardSize)
            .putFloat(KEY_KOMI, s.komi)
            .putInt(KEY_TIME, s.engineTimeSec)
            .putInt(KEY_LINES, s.analysisLines)
            .putBoolean(KEY_SHOW_NUMBERS, s.showMoveNumbers)
            .putBoolean(KEY_SHOW_CANDIDATES, s.showCandidates)
            .putBoolean(KEY_SOUND, s.sound)
            .putBoolean(KEY_VIBRATE, s.vibrate)
            .apply()
        _settings.value = s
    }

    fun setBoardSize(size: Int) = save(_settings.value.copy(boardSize = size))
    fun setKomi(komi: Float) = save(_settings.value.copy(komi = komi))
    fun setEngineTime(sec: Int) = save(_settings.value.copy(engineTimeSec = sec))
    fun setAnalysisLines(lines: Int) = save(_settings.value.copy(analysisLines = lines))
    fun setShowMoveNumbers(on: Boolean) = save(_settings.value.copy(showMoveNumbers = on))
    fun setShowCandidates(on: Boolean) = save(_settings.value.copy(showCandidates = on))
    fun setSound(on: Boolean) = save(_settings.value.copy(sound = on))
    fun setVibrate(on: Boolean) = save(_settings.value.copy(vibrate = on))

    companion object {
        val BOARD_SIZES = intArrayOf(9, 13, 19)
        val KOMI_PRESETS = floatArrayOf(0.5f, 3.5f, 5.5f, 6.5f, 7.5f, 9.5f)
        val ENGINE_TIMES = intArrayOf(1, 2, 3, 5, 10)
        val ANALYSIS_LINES = intArrayOf(1, 3, 5)

        private const val KEY_BOARD_SIZE = "board_size"
        private const val KEY_KOMI = "komi"
        private const val KEY_TIME = "engine_time"
        private const val KEY_LINES = "analysis_lines"
        private const val KEY_SHOW_NUMBERS = "show_move_numbers"
        private const val KEY_SHOW_CANDIDATES = "show_candidates"
        private const val KEY_SOUND = "sound"
        private const val KEY_VIBRATE = "vibrate"
    }
}
