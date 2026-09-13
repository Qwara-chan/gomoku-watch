package com.qwara.gomoku.ui.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 落子音效与震动 */
class FeedbackHelper(context: Context) {

    private val appContext = context.applicationContext
    private var toneGenerator: ToneGenerator? = null

    init {
        runCatching { toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }
    }

    fun playPlaceSound(enabled: Boolean) {
        if (!enabled) return
        runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 45) }
    }

    fun playErrorSound(enabled: Boolean) {
        if (!enabled) return
        runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 120) }
    }

    fun vibrate(enabled: Boolean, millis: Long = 25) {
        if (!enabled) return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(millis)
            }
        }
    }
}
