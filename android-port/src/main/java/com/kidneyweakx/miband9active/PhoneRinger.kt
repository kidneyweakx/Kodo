/*  Copyright (C) 2018-2024 Andreas Shimokawa, Anemograph, Carsten Pfeiffer,
 *                          Cre3per, Daniele Gobbetti, Dmitriy Bogdanov, José Rebelo,
 *                          Pauli Salmenrinne                  (Gadgetbridge FindPhoneActivity)
 *  Copyright (C) 2023-2025 Andreas Shimokawa, José Rebelo, LuK1337,
 *                          Yoran Vulker                       (Gadgetbridge XiaomiSystemService)
 *  Copyright (C) 2026 kidneyweakx                              (Kotlin port)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Band -> phone "Find phone" (XiaomiSystemService CMD_FIND_PHONE = 17,
 *  System.findDevice: 0 = start, anything else = stop).
 *
 *  Mirrors upstream FindPhoneActivity: rings on the ALARM stream at max
 *  volume (restoring the user's volume afterwards) and vibrates. Upstream does
 *  NOT honour phone DnD for this — the user explicitly asked the band to find
 *  the phone. When the user stops it on the phone ("found it"), we tell the
 *  band (findDevice = 1, XiaomiSystemService.onFindPhone(false)) so its UI
 *  closes too. A band-side stop never echoes back.
 *
 *  Deviation: a 2-minute safety stop, so a lost/forgotten session can't drain
 *  the battery (docs/POWER.md).
 *
 *  start()/stop() are idempotent: HybridBandLink's legacy observer and
 *  DeviceFeatures may both deliver the same band event.
 */
package com.kidneyweakx.miband9active

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.kidneyweakx.miband9active.xiaomi.services.BandChannel
import com.kidneyweakx.miband9active.xiaomi.services.DeviceFeatures
import com.kidneyweakx.miband9active.xiaomi.services.SystemCommands
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

object PhoneRinger {

    private const val TAG = "PhoneRinger"
    private const val CHANNEL_ID = "miband9active.find_phone"
    private const val NOTIFICATION_ID = 9002
    private const val SAFETY_TIMEOUT_MS = 2 * 60 * 1000L

    @Volatile private var ringtone: Ringtone? = null
    @Volatile private var savedAlarmVolume: Int = -1
    @Volatile var isRinging: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private val safetyStop = Runnable { stopFromUser() }

    /** Ringing-state listener (true = started, false = stopped). */
    fun addListener(listener: (Boolean) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    @Synchronized
    fun start(context: Context = AppContext.context) {
        if (isRinging) return
        isRinging = true
        val ctx = context.applicationContext
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            if (am != null) {
                savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                try {
                    am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                } catch (t: Throwable) {
                    Log.w(TAG, "could not raise alarm volume", t)
                    savedAlarmVolume = -1
                }
            }
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val rt = uri?.let { RingtoneManager.getRingtone(ctx, it) }
            if (rt != null) {
                rt.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) rt.isLooping = true
                rt.play()
                ringtone = rt
            }
            vibrate(ctx)
            postNotification(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "start failed", t)
        }
        handler.removeCallbacks(safetyStop)
        handler.postDelayed(safetyStop, SAFETY_TIMEOUT_MS)
        listeners.forEach { runCatching { it(true) } }
    }

    /** Band-initiated (or legacy) stop. Does not message the band. */
    fun stop(context: Context = AppContext.context) {
        stopInternal(context, notifyBand = false)
    }

    /** User pressed "found it" on the phone: stop + tell the band. */
    fun stopFromUser(context: Context = AppContext.context) {
        stopInternal(context, notifyBand = true)
    }

    @Synchronized
    private fun stopInternal(context: Context, notifyBand: Boolean) {
        handler.removeCallbacks(safetyStop)
        val wasRinging = isRinging
        isRinging = false
        val ctx = context.applicationContext
        try { ringtone?.stop() } catch (_: Throwable) {}
        ringtone = null
        try { vibratorOf(ctx)?.cancel() } catch (_: Throwable) {}
        if (savedAlarmVolume >= 0) {
            try {
                ctx.getSystemService(AudioManager::class.java)
                    ?.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
            } catch (_: Throwable) {}
            savedAlarmVolume = -1
        }
        try { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) } catch (_: Throwable) {}
        if (notifyBand && wasRinging) {
            DeviceFeatures.launch {
                // XiaomiSystemService.onFindPhone(false): "find phone stop" -> findDevice = 1
                BandChannel.send(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_FIND_PHONE) {
                    setSystem(XiaomiProto.System.newBuilder().setFindDevice(1))
                }
            }
        }
        if (wasRinging) listeners.forEach { runCatching { it(false) } }
    }

    private fun vibrate(ctx: Context) {
        val vib = vibratorOf(ctx) ?: return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
    }

    private fun vibratorOf(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }

    private fun postNotification(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val zh = Locale.getDefault().language == "zh"
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    if (zh) "尋找手機" else "Find phone",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { setSound(null, null) },
            )
        }
        val stopIntent = PendingIntent.getBroadcast(
            ctx,
            0,
            Intent(ctx, FindPhoneStopReceiver::class.java).setAction(FindPhoneStopReceiver.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (zh) "手環正在尋找手機" else "Your band is looking for this phone")
            .setContentText(if (zh) "點「找到了」停止響鈴" else "Tap \"Found it\" to stop ringing")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setDeleteIntent(stopIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel),
                    if (zh) "找到了" else "Found it",
                    stopIntent,
                ).build(),
            )
        if (launch != null) builder.setContentIntent(launch)
        try {
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed (POST_NOTIFICATIONS?)", t)
        }
    }
}

/**
 * "Found it" action on the find-phone notification. Manifest:
 *   <receiver android:name="com.kidneyweakx.miband9active.FindPhoneStopReceiver" android:exported="false"/>
 */
class FindPhoneStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) PhoneRinger.stopFromUser(context)
    }

    companion object {
        const val ACTION_STOP = "com.kidneyweakx.miband9active.FIND_PHONE_STOP"
    }
}
