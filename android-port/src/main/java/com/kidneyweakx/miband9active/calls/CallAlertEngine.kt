/*
 * Copyright (C) 2015-2024 Andreas Shimokawa, Carsten Pfeiffer, José Rebelo and other
 *                         Gadgetbridge contributors (PhoneCallReceiver, GBCallControlReceiver,
 *                         NotificationListener.handleCallNotification, XiaomiPhonebookService)
 *
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 */
package com.kidneyweakx.miband9active.calls

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.notifications.MiBand9NotificationListener
import com.kidneyweakx.miband9active.xiaomi.notifications.NotificationForwarder
import com.kidneyweakx.miband9active.xiaomi.notifications.NotificationPrefs
import com.kidneyweakx.miband9active.xiaomi.notifications.PhonebookCmd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

/**
 * Incoming-call alerts on the band (XiaomiNotificationService.onSetCallState)
 * and band → phone call control (CMD_CALL_REJECT / CMD_CALL_IGNORE).
 *
 * Two ringing sources, merged:
 *  1. The dialer's CATEGORY_CALL notification (needs only notification access;
 *     carries the caller's display name). VoIP apps (CATEGORY_CALL from any
 *     other package) are handled the same way when the app is allow-listed.
 *  2. TelephonyCallback / PhoneStateListener when READ_PHONE_STATE is granted
 *     (reliable ringing/offhook/idle, but no number without READ_CALL_LOG).
 *     A RINGING from telephony waits [NAME_GRACE_MS] for the dialer
 *     notification so the band shows a name instead of "?".
 *
 * All state lives on the main thread.
 */
object CallAlertEngine {
    private const val TAG = "MB9A_Call"
    private const val NAME_GRACE_MS = 800L

    /** upstream NotificationListener.PHONE_CALL_APPS (+ a few OEM in-call UIs). */
    private val PHONE_CALL_APPS = setOf(
        "com.android.dialer",
        "com.android.incallui",
        "com.android.server.telecom",
        "com.asus.asusincallui",
        "com.google.android.dialer",
        "com.samsung.android.incallui",
        "com.samsung.android.dialer",
        "com.oneplus.dialer",
        "com.miui.voip",
        "org.fossify.phone",
    )

    /** upstream #5113 — Firefox marks recording notifications as calls. */
    private val FIREFOX = setOf(
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.fennec_aurora",
        "org.mozilla.focus",
        "org.mozilla.fennec_fdroid",
    )

    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- main-thread state
    private var ringing = false
    private var sentToBand = false
    private var callerName: String? = null
    private var callerNumber: String? = null
    private var callKey: String? = null
    private var callPackage: String? = null
    private var callAppLabel: String? = null
    private var pendingSend: Runnable? = null
    private var lastTelephonyState = TelephonyManager.CALL_STATE_IDLE
    private var savedRingerMode: Int? = null
    private var telephonyHandle: Any? = null

    // ------------------------------------------------------------------ lifecycle

    /** Called when the notification listener connects (process has notification access). */
    fun start(context: Context) {
        val app = context.applicationContext
        main.post { if (NotificationPrefs.callAlertsEnabled) registerTelephony(app) }
    }

    fun stopTelephony(context: Context) {
        val app = context.applicationContext
        main.post { unregisterTelephony(app) }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        NotificationPrefs.setCallAlertsEnabled(enabled)
        val app = context.applicationContext
        main.post {
            if (enabled) {
                registerTelephony(app)
            } else {
                unregisterTelephony(app)
                finishCall()
            }
        }
    }

    /** Re-try registration after the user granted READ_PHONE_STATE at runtime. */
    fun refreshPermissions(context: Context) {
        val app = context.applicationContext
        main.post { if (NotificationPrefs.callAlertsEnabled) registerTelephony(app) }
    }

    // ------------------------------------------------------------------ telephony source

    private fun registerTelephony(context: Context) {
        if (telephonyHandle != null) return
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "READ_PHONE_STATE not granted; relying on dialer notifications only")
            return
        }
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        try {
            telephonyHandle = if (Build.VERSION.SDK_INT >= 31) {
                val cb = CallStateCallback31()
                tm.registerTelephonyCallback(context.mainExecutor, cb)
                cb
            } else {
                val listener = LegacyCallStateListener()
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                listener
            }
            // The first callback reports the current state; don't treat it as a transition.
            @Suppress("DEPRECATION")
            val current = runCatching { tm.callState }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
            lastTelephonyState = current
        } catch (e: SecurityException) {
            Log.w(TAG, "telephony registration refused", e)
            telephonyHandle = null
        }
    }

    private fun unregisterTelephony(context: Context) {
        val handle = telephonyHandle ?: return
        telephonyHandle = null
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31 && handle is TelephonyCallback) {
                tm.unregisterTelephonyCallback(handle)
            } else if (handle is PhoneStateListener) {
                @Suppress("DEPRECATION")
                tm.listen(handle, PhoneStateListener.LISTEN_NONE)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "telephony unregister failed", t)
        }
    }

    @RequiresApi(31)
    private class CallStateCallback31 : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = onTelephonyState(state, null)
    }

    @Suppress("DEPRECATION")
    private class LegacyCallStateListener : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) =
            onTelephonyState(state, phoneNumber)
    }

    /** upstream PhoneCallReceiver.onCallStateChanged. Main thread. */
    private fun onTelephonyState(state: Int, number: String?) {
        if (state == lastTelephonyState) return
        lastTelephonyState = state
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (!number.isNullOrEmpty()) callerNumber = number
                beginRinging(graceMs = if (callerName == null) NAME_GRACE_MS else 0L, dndSuppressed = null)
            }
            // OFFHOOK after RINGING = answered (CALL_START), IDLE = ended/missed.
            // Both remove the call from the band; outgoing calls are ignored by finishCall().
            TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_IDLE -> finishCall()
        }
    }

    // ------------------------------------------------------------------ notification source

    /** From MiBand9NotificationListener (main thread) for every CATEGORY_CALL notification. */
    fun onCallNotificationPosted(context: Context, sbn: StatusBarNotification, dndSuppressed: Boolean) {
        if (!NotificationPrefs.callAlertsEnabled) return
        val pkg = sbn.packageName ?: return
        if (pkg in FIREFOX) return
        val isDialer = pkg in PHONE_CALL_APPS
        // VoIP calls behave like any other app: only when allow-listed.
        if (!isDialer && !NotificationPrefs.isAllowed(pkg)) return
        val n = sbn.notification ?: return

        if (isIncoming(n)) {
            if (callKey != null && callKey != sbn.key && sentToBand) return // a second ringing call; keep the first
            callKey = sbn.key
            callPackage = pkg
            val extras = n.extras
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            if (title != null) callerName = MiBand9NotificationListener.sanitizeUnicode(title)
            numberFromPeople(n)?.let { callerNumber = it }
            if (!isDialer) {
                callAppLabel = MiBand9NotificationListener.resolveAppLabel(context, pkg)
            }
            beginRinging(graceMs = 0L, dndSuppressed = dndSuppressed)
        } else if (sbn.key == callKey) {
            // Same call notification turned into the ongoing-call one → answered.
            finishCall()
        }
    }

    fun onCallNotificationRemoved(key: String) {
        if (key == callKey) finishCall()
    }

    /**
     * Distinguish the ringing notification from the ongoing-call one. Android 12+
     * CallStyle tells us directly; otherwise ringing UIs carry a full-screen
     * intent and answer+decline actions, ongoing ones a chronometer.
     */
    private fun isIncoming(n: Notification): Boolean {
        val extras = n.extras
        if (Build.VERSION.SDK_INT >= 31 && extras != null) {
            when (extras.getInt(Notification.EXTRA_CALL_TYPE, -1)) {
                Notification.CallStyle.CALL_TYPE_INCOMING -> return true
                Notification.CallStyle.CALL_TYPE_ONGOING,
                Notification.CallStyle.CALL_TYPE_SCREENING -> return false
            }
        }
        if (extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) == true) return false
        if (n.fullScreenIntent != null) return true
        return (n.actions?.size ?: 0) >= 2
    }

    private fun numberFromPeople(n: Notification): String? {
        val extras = n.extras ?: return null
        if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            val people = runCatching { extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST) }.getOrNull()
            val uri = people?.firstOrNull()?.uri
            if (uri != null && uri.startsWith("tel:")) return Uri.decode(uri.removePrefix("tel:"))
        }
        @Suppress("DEPRECATION")
        val legacy = runCatching { extras.getStringArray(Notification.EXTRA_PEOPLE) }.getOrNull()?.firstOrNull()
        if (legacy != null && legacy.startsWith("tel:")) return Uri.decode(legacy.removePrefix("tel:"))
        return null
    }

    // ------------------------------------------------------------------ state machine

    private fun beginRinging(graceMs: Long, dndSuppressed: Boolean?) {
        if (!NotificationPrefs.callAlertsEnabled) return
        val suppressed = dndSuppressed ?: isDndBlockingCalls()
        if (NotificationPrefs.muteWhenDnd && suppressed) {
            Log.i(TAG, "incoming call suppressed by Do Not Disturb")
            return
        }
        ringing = true
        if (sentToBand) return
        pendingSend?.let { main.removeCallbacks(it) }
        pendingSend = null
        if (graceMs > 0) {
            val r = Runnable { pendingSend = null; sendIncomingToBand() }
            pendingSend = r
            main.postDelayed(r, graceMs)
        } else {
            sendIncomingToBand()
        }
    }

    private fun sendIncomingToBand() {
        if (!ringing || sentToBand) return
        // Power rule: no connection → nothing to alert; never connect for a call.
        if (DriverHolder.current == null) return
        // upstream onSetCallState: title = name ("?" when unknown), body = number ("?").
        // VoIP: body carries the app label so the band shows e.g. "Alice / Signal".
        val title = callerName ?: callerNumber ?: "?"
        val body = callAppLabel ?: callerNumber ?: "?"
        sentToBand = NotificationForwarder.sendIncomingCall(title, body)
    }

    private fun finishCall() {
        pendingSend?.let { main.removeCallbacks(it) }
        pendingSend = null
        if (sentToBand) NotificationForwarder.sendCallEnd()
        restoreRinger()
        ringing = false
        sentToBand = false
        callerName = null
        callerNumber = null
        callKey = null
        callPackage = null
        callAppLabel = null
    }

    private fun isDndBlockingCalls(): Boolean {
        val nm = AppContext.context.getSystemService(NotificationManager::class.java) ?: return false
        return when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
            // PRIORITY: we can't evaluate starred/repeat-caller rules without policy access; let it through.
            else -> false
        }
    }

    // ------------------------------------------------------------------ band → phone

    /** CMD_CALL_REJECT → end the call (GBCallControlReceiver REJECT), else at least silence it. */
    fun onBandReject() {
        main.post {
            if (!ringing) return@post
            val ctx = AppContext.context
            val ended = endCall(ctx)
            Log.i(TAG, "band reject → endCall=$ended")
            if (!ended) silenceRinger(ctx)
        }
    }

    /** CMD_CALL_IGNORE → mute the ringer (upstream MUTE_CALL). */
    fun onBandIgnore() {
        main.post {
            if (!ringing) return@post
            silenceRinger(AppContext.context)
        }
    }

    private fun endCall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false // no public API before P (upstream uses ITelephony reflection)
        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "ANSWER_PHONE_CALLS not granted; cannot end call")
            return false
        }
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        return try {
            @Suppress("DEPRECATION")
            val ended = telecom.endCall()
            ended
        } catch (e: SecurityException) {
            Log.w(TAG, "endCall refused", e)
            false
        } catch (t: Throwable) {
            Log.w(TAG, "endCall failed", t)
            false
        }
    }

    /**
     * upstream PhoneCallReceiver MUTE_CALL: switch the ringer to silent for the
     * rest of the call, restore on idle. Silent needs Do-Not-Disturb access on
     * N+, so fall back to vibrate (which still stops the ringtone).
     */
    private fun silenceRinger(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        if (savedRingerMode == null) savedRingerMode = am.ringerMode
        try {
            am.ringerMode = AudioManager.RINGER_MODE_SILENT
            return
        } catch (e: SecurityException) {
            Log.i(TAG, "silent ringer needs DND access; trying vibrate")
        }
        try {
            am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot change ringer mode", e)
            savedRingerMode = null
        }
    }

    private fun restoreRinger() {
        val mode = savedRingerMode ?: return
        savedRingerMode = null
        val am = AppContext.context.getSystemService(AudioManager::class.java) ?: return
        try {
            am.ringerMode = mode
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot restore ringer mode", e)
        }
    }

    // ------------------------------------------------------------------ phonebook (type 21)

    /** XiaomiPhonebookService.handleCommand: the band asks for a contact name by number. */
    fun onPhonebookCommand(subtype: Int, cmd: XiaomiProto.Command) {
        if (subtype != PhonebookCmd.GET_CONTACT) {
            Log.d(TAG, "unhandled phonebook command $subtype")
            return
        }
        val number = cmd.phonebook.requestedPhoneNumber
        if (number.isNullOrEmpty()) return
        io.launch {
            val name = lookupContactName(AppContext.context, number)
            val drv = DriverHolder.current ?: return@launch
            try {
                drv.sendCommand(
                    XiaomiProto.Command.newBuilder()
                        .setType(PhonebookCmd.TYPE)
                        .setSubtype(PhonebookCmd.GET_CONTACT_RESPONSE)
                        .setPhonebook(
                            XiaomiProto.Phonebook.newBuilder().setContactInfo(
                                // Empty name → the band shows the number (upstream behaviour).
                                XiaomiProto.ContactInfo.newBuilder().setDisplayName(name).setPhoneNumber(number),
                            ),
                        )
                        .build(),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "contact response failed", t)
            }
        }
    }

    private fun lookupContactName(context: Context, number: String): String {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }
                .orEmpty()
        } catch (e: Exception) {
            ""
        }
    }
}
