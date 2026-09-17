// SPDX-FileCopyrightText: 2026 Qwara-chan
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qwara.go.ui.util

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

    /** Vibrator 服务只取一次（原先每次发声都重新 getSystemService） */
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    /** ToneGenerator 持有 AudioTrack：首次发声才创建（创建失败如资源占用，下次发声重试） */
    private var toneGenerator: ToneGenerator? = null

    private fun obtainTone(): ToneGenerator? {
        if (toneGenerator == null) {
            toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull()
        }
        return toneGenerator
    }

    fun playPlaceSound(enabled: Boolean) {
        if (!enabled) return
        runCatching { obtainTone()?.startTone(ToneGenerator.TONE_PROP_ACK, 45) }
    }

    fun playErrorSound(enabled: Boolean) {
        if (!enabled) return
        runCatching { obtainTone()?.startTone(ToneGenerator.TONE_PROP_NACK, 120) }
    }

    fun vibrate(enabled: Boolean, millis: Long = 25) {
        if (!enabled) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(millis)
            }
        }
    }

    /** 释放音频资源（ToneGenerator 持有 AudioTrack，不用时必须释放） */
    fun release() {
        runCatching { toneGenerator?.release() }
        toneGenerator = null
    }
}
