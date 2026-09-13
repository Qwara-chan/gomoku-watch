package com.qwara.gomoku.data

import android.content.Context
import android.content.SharedPreferences
import com.qwara.gomoku.game.Rule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 轻量设置存储（SharedPreferences） */
class SettingsRepository(context: Context) {

    data class Settings(
        val rule: Rule = Rule.FREESTYLE,
        val engineTimeSec: Int = 3,
        val sound: Boolean = true,
        val vibrate: Boolean = true,
        val strength: Int = 100, // 引擎强度 0-100（Rapfi STRENGTH）
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gomoku_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings

    private fun load(): Settings = Settings(
        rule = if (prefs.getString(KEY_RULE, "FREESTYLE") == "RENJU") Rule.RENJU else Rule.FREESTYLE,
        engineTimeSec = prefs.getInt(KEY_TIME, 3),
        sound = prefs.getBoolean(KEY_SOUND, true),
        vibrate = prefs.getBoolean(KEY_VIBRATE, true),
        strength = prefs.getInt(KEY_STRENGTH, 100),
    )

    private fun save(s: Settings) {
        prefs.edit()
            .putString(KEY_RULE, s.rule.name)
            .putInt(KEY_TIME, s.engineTimeSec)
            .putBoolean(KEY_SOUND, s.sound)
            .putBoolean(KEY_VIBRATE, s.vibrate)
            .putInt(KEY_STRENGTH, s.strength)
            .apply()
        _settings.value = s
    }

    fun setRule(rule: Rule) = save(_settings.value.copy(rule = rule))
    fun setEngineTime(sec: Int) = save(_settings.value.copy(engineTimeSec = sec))
    fun setSound(on: Boolean) = save(_settings.value.copy(sound = on))
    fun setVibrate(on: Boolean) = save(_settings.value.copy(vibrate = on))
    fun setStrength(v: Int) = save(_settings.value.copy(strength = v))

    private companion object {
        const val KEY_RULE = "rule"
        const val KEY_TIME = "engine_time"
        const val KEY_SOUND = "sound"
        const val KEY_VIBRATE = "vibrate"
        const val KEY_STRENGTH = "strength"
    }
}
