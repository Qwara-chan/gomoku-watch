// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.data

import android.content.Context
import android.content.SharedPreferences
import com.qwara.go.game.Rule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** 轻量设置存储（SharedPreferences） */
class SettingsRepository(context: Context) {

    data class Settings(
        val rule: Rule = Rule.FREESTYLE,
        val engineTimeSec: Int = 3,
        /** 引擎棋力档位 0–100（100=满强度）。仅作用于人机对战；分析/提示始终满强度 */
        val strength: Int = MAX_STRENGTH,
        /** 分析输出路数（多点分析） */
        val analysisLines: Int = 3,
        val showMoveNumbers: Boolean = true,
        val showForbidden: Boolean = true,
        val showWinLine: Boolean = true,
        val showCandidates: Boolean = true,
        val sound: Boolean = true,
        val vibrate: Boolean = true,
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gomoku_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings

    private fun load(): Settings = Settings(
        rule = if (prefs.getString(KEY_RULE, "FREESTYLE") == "RENJU") Rule.RENJU else Rule.FREESTYLE,
        engineTimeSec = prefs.getInt(KEY_TIME, 3),
        strength = prefs.getInt(KEY_STRENGTH, MAX_STRENGTH).coerceIn(0, MAX_STRENGTH),
        analysisLines = prefs.getInt(KEY_LINES, 3).takeIf { it in ANALYSIS_LINES } ?: 3,
        showMoveNumbers = prefs.getBoolean(KEY_SHOW_NUMBERS, true),
        showForbidden = prefs.getBoolean(KEY_SHOW_FORBIDDEN, true),
        showWinLine = prefs.getBoolean(KEY_SHOW_WIN_LINE, true),
        showCandidates = prefs.getBoolean(KEY_SHOW_CANDIDATES, true),
        sound = prefs.getBoolean(KEY_SOUND, true),
        vibrate = prefs.getBoolean(KEY_VIBRATE, true),
    )

    private fun save(s: Settings) {
        prefs.edit()
            .putString(KEY_RULE, s.rule.name)
            .putInt(KEY_TIME, s.engineTimeSec)
            .putInt(KEY_STRENGTH, s.strength)
            .putInt(KEY_LINES, s.analysisLines)
            .putBoolean(KEY_SHOW_NUMBERS, s.showMoveNumbers)
            .putBoolean(KEY_SHOW_FORBIDDEN, s.showForbidden)
            .putBoolean(KEY_SHOW_WIN_LINE, s.showWinLine)
            .putBoolean(KEY_SHOW_CANDIDATES, s.showCandidates)
            .putBoolean(KEY_SOUND, s.sound)
            .putBoolean(KEY_VIBRATE, s.vibrate)
            .apply()
        _settings.value = s
    }

    fun setRule(rule: Rule) = save(_settings.value.copy(rule = rule))
    fun setEngineTime(sec: Int) = save(_settings.value.copy(engineTimeSec = sec))
    fun setStrength(level: Int) = save(_settings.value.copy(strength = level.coerceIn(0, MAX_STRENGTH)))
    fun setAnalysisLines(lines: Int) = save(_settings.value.copy(analysisLines = lines))
    fun setShowMoveNumbers(on: Boolean) = save(_settings.value.copy(showMoveNumbers = on))
    fun setShowForbidden(on: Boolean) = save(_settings.value.copy(showForbidden = on))
    fun setShowWinLine(on: Boolean) = save(_settings.value.copy(showWinLine = on))
    fun setShowCandidates(on: Boolean) = save(_settings.value.copy(showCandidates = on))
    fun setSound(on: Boolean) = save(_settings.value.copy(sound = on))
    fun setVibrate(on: Boolean) = save(_settings.value.copy(vibrate = on))

    companion object {
        const val MAX_STRENGTH = 100

        /** 棋力档位对应的引擎 INFO STRENGTH 值（低档降深度并在候选中随机挑点） */
        val STRENGTH_PRESETS = intArrayOf(0, 30, 60, 85, MAX_STRENGTH)

        val ANALYSIS_LINES = intArrayOf(1, 3, 5)

        /** 把任意已存档位映射到最近的预设下标，供 UI 步进器显示 */
        fun nearestStrengthIndex(level: Int): Int =
            STRENGTH_PRESETS.indices.minByOrNull { abs(STRENGTH_PRESETS[it] - level) } ?: 0

        private const val KEY_RULE = "rule"
        private const val KEY_TIME = "engine_time"
        private const val KEY_STRENGTH = "strength"
        private const val KEY_LINES = "analysis_lines"
        private const val KEY_SHOW_NUMBERS = "show_move_numbers"
        private const val KEY_SHOW_FORBIDDEN = "show_forbidden"
        private const val KEY_SHOW_WIN_LINE = "show_win_line"
        private const val KEY_SHOW_CANDIDATES = "show_candidates"
        private const val KEY_SOUND = "sound"
        private const val KEY_VIBRATE = "vibrate"
    }
}
