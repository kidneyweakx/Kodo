/*
 * Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo (Gadgetbridge)
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
package com.kidneyweakx.miband9active.xiaomi.notifications

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.util.LruCache
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.calls.CallAlertEngine
import com.kidneyweakx.miband9active.xiaomi.services.MiBand9DataUploader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

/**
 * Phone ↔ band notification protocol (command type 7). Port of
 * XiaomiNotificationService: onNotification / onDeleteNotification /
 * onSetCallState / handleCommand / icon query+request.
 *
 * Power rules (docs/POWER.md):
 *  - every entry point returns immediately when the band is not connected
 *    (`DriverHolder.current == null`) — we never start a connection;
 *  - posts/dismisses are coalesced in a 250 ms window and flushed back-to-back,
 *    and repeated updates of the same Android notification inside that window
 *    collapse into one send.
 */
object NotificationForwarder {
    private const val TAG = "MB9A_Notif"
    private const val COALESCE_MS = 250L
    private const val ICON_UPLOAD_TIMEOUT_MS = 20_000L

    /** Not upstream (Xiaomi has no limit); keeps a 10 kB e-mail from costing seconds of airtime. */
    private const val MAX_TITLE_CODEPOINTS = 128
    private const val MAX_BODY_CODEPOINTS = 1024

    private val TIMESTAMP_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    /**
     * Seeded from wall-clock seconds so ids stay unique across process restarts
     * (the band may still hold notifications from a previous process). 0 is
     * reserved for the incoming-call notification.
     */
    private val nextId = AtomicInteger(((System.currentTimeMillis() / 1000L) % 1_000_000_000L).toInt())

    private data class Posted(val pkg: String, val key: String?)

    // All guarded by [lock]. Upstream keeps these across reconnects on purpose (#6265).
    private val postedById = lruMap<Int, Posted>(128)
    private val idsByKey = lruMap<String, MutableList<Int>>(128)
    private val recentPackages = ArrayDeque<String>() // for truncated icon-query package names, max 32
    private val pending = ArrayList<PendingOp>()
    private var flushJob: Job? = null

    private val appInfoCache = LruCache<String, ApplicationInfo>(32)

    @Volatile private var iconPackageName: String? = null
    private val iconMutex = Mutex()

    private sealed class PendingOp {
        abstract val command: XiaomiProto.Command
        data class Send(val key: String?, val id: Int, override val command: XiaomiProto.Command) : PendingOp()
        data class Dismiss(override val command: XiaomiProto.Command) : PendingOp()
    }

    data class Outgoing(
        val packageName: String,
        val appName: String?,
        val title: String?,
        val body: String?,
        val whenMs: Long,
        /** StatusBarNotification.key; null for manual pushes (then no "open on phone"). */
        val key: String?,
    )

    // ------------------------------------------------------------------ phone → band

    fun rememberAppInfo(pkg: String, info: ApplicationInfo) {
        appInfoCache.put(pkg, info)
    }

    fun enqueue(n: Outgoing) {
        if (DriverHolder.current == null) return

        val id = allocateId()
        val title = n.title?.let { truncateCodePoints(it, MAX_TITLE_CODEPOINTS) }.orEmpty()
        val body = n.body?.let { truncateCodePoints(it, MAX_BODY_CODEPOINTS) }

        val notification3 = XiaomiProto.Notification3.newBuilder()
            .setId(id)
            .setUnknown4("")
            .setTimestamp(formatTimestamp(n.whenMs))
            .setPackage(n.packageName)
            // upstream: "Should never happen, but notification is not shown otherwise"
            .setAppName(n.appName?.takeIf { it.isNotEmpty() } ?: "UNKNOWN")
        if (title.isNotEmpty()) notification3.setTitle(title)
        if (body != null) notification3.setBody(body)
        if (n.key != null) {
            notification3.setKey(n.key)
            notification3.setOpenOnPhone(true)
        }

        val command = XiaomiProto.Command.newBuilder()
            .setType(NotificationCmd.TYPE)
            .setSubtype(NotificationCmd.NOTIFICATION_SEND)
            .setNotification(
                XiaomiProto.Notification.newBuilder().setNotification2(
                    XiaomiProto.Notification2.newBuilder().setNotification3(notification3),
                ),
            )
            .build()

        synchronized(lock) {
            postedById[id] = Posted(n.packageName, n.key)
            if (n.key != null) {
                // An update of the same notification inside the window replaces the queued one.
                val superseded = pending.filterIsInstance<PendingOp.Send>().filter { it.key == n.key }
                if (superseded.isNotEmpty()) {
                    pending.removeAll(superseded.toSet())
                    val ids = idsByKey[n.key]
                    superseded.forEach { s ->
                        postedById.remove(s.id)
                        ids?.remove(s.id)
                    }
                }
                idsByKey.getOrPut(n.key) { ArrayList(2) }.add(id)
            }
            recentPackages.remove(n.packageName)
            recentPackages.addLast(n.packageName)
            while (recentPackages.size > 32) recentPackages.removeFirst()
            pending += PendingOp.Send(n.key, id, command)
            scheduleFlushLocked()
        }
    }

    /** Android notification removed → remove it from the band too (upstream autoremove_notifications). */
    fun onRemoved(key: String) {
        val commands = synchronized(lock) {
            val ids = idsByKey.remove(key) ?: return
            // Anything still queued for this key never reached the band: just drop it.
            val stillPending = pending.filterIsInstance<PendingOp.Send>().filter { it.key == key }
            pending.removeAll(stillPending.toSet())
            val pendingIds = stillPending.map { it.id }.toSet()
            ids.mapNotNull { id ->
                val posted = postedById.remove(id) ?: return@mapNotNull null
                if (id in pendingIds) null else dismissCommand(id, posted)
            }
        }
        if (commands.isEmpty() || DriverHolder.current == null) return
        synchronized(lock) {
            commands.forEach { pending += PendingOp.Dismiss(it) }
            scheduleFlushLocked()
        }
    }

    private fun dismissCommand(id: Int, posted: Posted): XiaomiProto.Command {
        val notificationId = XiaomiProto.NotificationId.newBuilder()
            .setId(id)
            .setPackage(posted.pkg)
        // upstream 7727ee9bec: the key is optional on the wire; omit when we have none.
        if (posted.key != null) notificationId.setKey(posted.key)
        return XiaomiProto.Command.newBuilder()
            .setType(NotificationCmd.TYPE)
            .setSubtype(NotificationCmd.NOTIFICATION_DISMISS)
            .setNotification(
                XiaomiProto.Notification.newBuilder().setNotificationDismiss(
                    XiaomiProto.NotificationDismiss.newBuilder().addNotificationId(notificationId),
                ),
            )
            .build()
    }

    /** onSetCallState(CALL_INCOMING). Not coalesced — ringing is latency-critical. */
    fun sendIncomingCall(title: String, body: String): Boolean {
        val drv = DriverHolder.current ?: return false
        val notification3 = XiaomiProto.Notification3.newBuilder()
            .setId(NotificationCmd.CALL_ID)
            .setUnknown4("")
            .setIsCall(true)
            // We hold no SEND_SMS permission, so never offer canned SMS replies.
            .setRepliesAllowed(false)
            .setTimestamp(formatTimestamp(System.currentTimeMillis()))
            .setPackage(NotificationCmd.CALL_PACKAGE)
            .setAppName(NotificationCmd.CALL_PACKAGE)
            .setTitle(title)
            .setBody(body)
        val command = XiaomiProto.Command.newBuilder()
            .setType(NotificationCmd.TYPE)
            .setSubtype(NotificationCmd.NOTIFICATION_SEND)
            .setNotification(
                XiaomiProto.Notification.newBuilder().setNotification2(
                    XiaomiProto.Notification2.newBuilder().setNotification3(notification3),
                ),
            )
            .build()
        scope.launch {
            try { drv.sendCommand(command) } catch (t: Throwable) { Log.w(TAG, "send call failed", t) }
        }
        return true
    }

    /** onSetCallState(CALL_END / CALL_START / …): dismiss {id 0, package "phone"}. */
    fun sendCallEnd() {
        val drv = DriverHolder.current ?: return
        val command = XiaomiProto.Command.newBuilder()
            .setType(NotificationCmd.TYPE)
            .setSubtype(NotificationCmd.NOTIFICATION_DISMISS)
            .setNotification(
                XiaomiProto.Notification.newBuilder().setNotificationDismiss(
                    XiaomiProto.NotificationDismiss.newBuilder().addNotificationId(
                        XiaomiProto.NotificationId.newBuilder()
                            .setId(NotificationCmd.CALL_ID)
                            .setPackage(NotificationCmd.CALL_PACKAGE),
                    ),
                ),
            )
            .build()
        scope.launch {
            try { drv.sendCommand(command) } catch (t: Throwable) { Log.w(TAG, "send call end failed", t) }
        }
    }

    private fun scheduleFlushLocked() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(COALESCE_MS)
            val batch = synchronized(lock) {
                val copy = pending.map { it.command }
                pending.clear()
                flushJob = null
                copy
            }
            val drv = DriverHolder.current ?: return@launch // disconnected meanwhile → drop
            Log.d("MB9A_POWER", "notification flush: ${batch.size} command(s)")
            for (cmd in batch) {
                try { drv.sendCommand(cmd) } catch (t: Throwable) { Log.w(TAG, "notification send failed", t) }
            }
        }
    }

    // ------------------------------------------------------------------ band → phone

    /** Called by [BandEventRouter] for every type-7 command. Must not block. */
    fun onBandCommand(subtype: Int, cmd: XiaomiProto.Command) {
        when (subtype) {
            NotificationCmd.NOTIFICATION_DISMISS -> {
                val ids = cmd.notification.notificationDismiss.notificationIdList.map { it.id }
                Log.i(TAG, "band dismissed ${ids.size} notification(s)")
                ids.forEach { dismissOnPhone(it) }
            }
            NotificationCmd.CALL_REJECT -> CallAlertEngine.onBandReject()
            NotificationCmd.CALL_IGNORE -> CallAlertEngine.onBandIgnore()
            NotificationCmd.OPEN_ON_PHONE -> openOnPhone(cmd.notification.openOnPhone.id)
            // upstream a7b4a6552c: OPEN_ON_PHONE must not fall through into canned messages.
            NotificationCmd.SCREEN_ON_ON_NOTIFICATIONS_GET,
            NotificationCmd.CANNED_MESSAGES_GET -> Log.d(TAG, "ignoring band response subtype=$subtype")
            NotificationCmd.CALL_REPLY_SEND -> {
                // Canned SMS reply to a call. We never set repliesAllowed and hold no
                // SEND_SMS, so report failure instead of pretending it was sent.
                Log.w(TAG, "band asked to send a canned SMS reply; unsupported")
                ackSmsReply(false)
            }
            NotificationCmd.NOTIFICATION_ICON_QUERY ->
                scope.launch { handleIconQuery(cmd.notification.notificationIconQuery) }
            NotificationCmd.NOTIFICATION_ICON_REQUEST ->
                scope.launch { handleIconRequest(cmd.notification.notificationIconRequest) }
            else -> Log.w(TAG, "unhandled notification command $subtype")
        }
    }

    private fun dismissOnPhone(id: Int) {
        val key = synchronized(lock) {
            val posted = postedById.remove(id) ?: return
            val key = posted.key ?: return
            // Forget the mapping first so onNotificationRemoved doesn't echo a dismiss back.
            idsByKey.remove(key)
            key
        }
        val listener = MiBand9NotificationListener.instance ?: return
        try {
            listener.cancelNotification(key)
        } catch (t: Throwable) {
            Log.w(TAG, "cancelNotification failed", t)
        }
    }

    private fun openOnPhone(id: Int) {
        val key = synchronized(lock) { postedById[id]?.key } ?: run {
            Log.i(TAG, "open on phone: unknown id $id")
            return
        }
        val listener = MiBand9NotificationListener.instance ?: return
        try {
            val sbn = listener.getActiveNotifications(arrayOf(key))?.firstOrNull() ?: return
            val pi = sbn.notification?.contentIntent ?: return
            sendAllowingBackgroundActivityStart(pi)
        } catch (t: Throwable) {
            Log.w(TAG, "open on phone failed", t)
        }
    }

    /** upstream 3c3d33d317: Android 14+ needs our BAL privileges passed explicitly. */
    private fun sendAllowingBackgroundActivityStart(pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
            pi.send(options)
        } else {
            pi.send()
        }
    }

    private fun ackSmsReply(success: Boolean) {
        val drv = DriverHolder.current ?: return
        val command = XiaomiProto.Command.newBuilder()
            .setType(NotificationCmd.TYPE)
            .setSubtype(NotificationCmd.CALL_REPLY_ACK)
            .setNotification(XiaomiProto.Notification.newBuilder().setNotificationReplyStatus(if (success) 0 else 1))
            .build()
        scope.launch {
            try { drv.sendCommand(command) } catch (t: Throwable) { Log.w(TAG, "sms ack failed", t) }
        }
    }

    // ------------------------------------------------------------------ icons

    private fun loadAppIcon(pkg: String): Drawable? {
        val pm = AppContext.context.packageManager
        return try {
            pm.getApplicationIcon(pkg)
        } catch (_: Exception) {
            // Package-visibility fallback: the ApplicationInfo parcelled into the
            // notification extras works even when the package isn't queryable.
            appInfoCache.get(pkg)?.let { info -> runCatching { info.loadIcon(pm) }.getOrNull() }
        }
    }

    private suspend fun handleIconQuery(query: XiaomiProto.NotificationIconPackage) {
        val queried = query.`package`
        Log.d(TAG, "band queries icon for $queried")
        var resolved = queried
        if (loadAppIcon(resolved) == null) {
            // The band truncates long package names; match against recently sent ones.
            resolved = synchronized(lock) { recentPackages.lastOrNull { it.startsWith(queried) } } ?: run {
                Log.w(TAG, "no full package for $queried")
                return
            }
            if (loadAppIcon(resolved) == null) {
                Log.w(TAG, "no icon for $queried / $resolved")
                return
            }
        }
        iconPackageName = resolved
        val drv = DriverHolder.current ?: return
        // Reply echoes the band's own (possibly truncated) package, like upstream.
        drv.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(NotificationCmd.TYPE)
                .setSubtype(NotificationCmd.NOTIFICATION_ICON_REQUEST)
                .setNotification(XiaomiProto.Notification.newBuilder().setNotificationIconReply(query))
                .build(),
        )
    }

    private suspend fun handleIconRequest(req: XiaomiProto.NotificationIconRequest) {
        val pkg = iconPackageName ?: run {
            Log.w(TAG, "icon request without a preceding query")
            return
        }
        Log.d(TAG, "icon request size=${req.size} status=${req.status} fmt=${IconConverter.pixelFormatName(req.pixelFormat)} for $pkg")
        if (req.status != 0) return
        val size = req.size
        if (size <= 0 || size > 256) return
        val drawable = loadAppIcon(pkg) ?: return
        val bytes = IconConverter.convertToPixelFormat(req.pixelFormat, drawable, size, size)
        if (bytes == null || bytes.isEmpty()) {
            Log.e(TAG, "unsupported icon pixel format ${req.pixelFormat}")
            return
        }
        iconMutex.withLock {
            val drv = DriverHolder.current ?: return
            val ok = withTimeoutOrNull(ICON_UPLOAD_TIMEOUT_MS) {
                MiBand9DataUploader.upload(drv, MiBand9DataUploader.TYPE_NOTIFICATION_ICON, bytes)
            }
            Log.d(TAG, "icon upload for $pkg finished: $ok")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun allocateId(): Int {
        while (true) {
            val id = nextId.incrementAndGet()
            if (id > 0) return id
            nextId.compareAndSet(id, 0) // wrapped: restart at 1, never hand out 0 (call id)
        }
    }

    private fun formatTimestamp(ms: Long): String =
        Instant.ofEpochMilli(if (ms > 0) ms else System.currentTimeMillis())
            .atZone(ZoneId.systemDefault())
            .format(TIMESTAMP_FMT)

    /** Truncate on a code-point boundary so emoji / surrogate pairs are never split. */
    internal fun truncateCodePoints(s: String, max: Int): String {
        if (s.codePointCount(0, s.length) <= max) return s
        val end = s.offsetByCodePoints(0, max - 1)
        return s.substring(0, end) + "…"
    }

    private fun <K, V> lruMap(max: Int): LinkedHashMap<K, V> =
        object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
        }
}
