/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Drives the phone's ringtone + vibration when the band sends "Find phone"
 *  (XiaomiProto CMD_FIND_PHONE=17, system.findDevice operation 0 = start, !=0 = stop).
 *  Centralised here so HybridSystemControl (JS-exposed) and HybridBandLink's
 *  incoming-command observer can share the same audio session — they used to
 *  race each other otherwise.
 */
package com.kidneyweakx.miband9active

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object PhoneRinger {

    @Volatile private var ringtone: Ringtone? = null

    @Synchronized
    fun start(context: Context = AppContext.context) {
        stop()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val rt = RingtoneManager.getRingtone(context, uri) ?: return
            if (Build.VERSION.SDK_INT >= 28) {
                rt.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                rt.streamType = AudioManager.STREAM_ALARM
            }
            rt.play()
            ringtone = rt
            vibratorOf(context)?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), 0),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "start failed", t)
        }
    }

    @Synchronized
    fun stop(context: Context = AppContext.context) {
        try { ringtone?.stop() } catch (_: Throwable) {}
        ringtone = null
        try { vibratorOf(context)?.cancel() } catch (_: Throwable) {}
    }

    private fun vibratorOf(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }

    private const val TAG = "PhoneRinger"
}
