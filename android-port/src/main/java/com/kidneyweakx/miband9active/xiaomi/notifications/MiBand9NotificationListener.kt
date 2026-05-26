/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. Filter + emoji-aware encoding semantics mirrored from
 *  Gadgetbridge's NotificationListener + XiaomiNotificationService.
 *
 *  Listens for system notifications, drops anything not on the allow-list
 *  *before* waking the BLE stack, then either:
 *    (a) hands the payload to a JS-set forwarder (if HybridNotificationBridge
 *        was constructed and registered one), or
 *    (b) sends directly to the band via DriverHolder. (a) wins when present.
 *
 *  Path (b) avoids the race where the listener service is bound by the OS
 *  *before* JS imports notificationBridge, which would otherwise drop every
 *  notification arriving in that window on the floor.
 */
package com.kidneyweakx.miband9active.xiaomi.notifications

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.edit
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.services.NotificationCommands
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

class MiBand9NotificationListener : NotificationListenerService() {

    private val filters = CopyOnWriteArraySet<String>()
    private var muteDuringDnd: Boolean = false
    private val sendScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        filters.addAll(prefs.getStringSet(KEY_ALLOWED, emptySet()) ?: emptySet())
        muteDuringDnd = prefs.getBoolean(KEY_MUTE_DND, false)
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (filters.isNotEmpty() && pkg !in filters) return

        // DnD mirroring — if phone is in DnD and the user opted in, drop.
        if (muteDuringDnd && currentInterruptionFilter == INTERRUPTION_FILTER_NONE) return

        val title = sbn.notification.extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""
        val body = sbn.notification.extras.getString(android.app.Notification.EXTRA_TEXT) ?: ""
        val push = NotificationPush(
            packageName = pkg,
            ticker = sbn.notification.tickerText?.toString().orEmpty(),
            title = title,
            body = body,
            postedAtMs = sbn.postTime,
        )

        val fn = forwarder
        if (fn != null) {
            try {
                fn(push)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "JS forwarder threw; falling back to direct band send", t)
            }
        }
        sendDirect(push)
    }

    /** Direct BLE push used when no JS-side forwarder is registered. */
    private fun sendDirect(n: NotificationPush) {
        val drv = DriverHolder.current ?: return
        val appName = resolveAppLabel(this, n.packageName)
        sendScope.launch {
            val notif3 = XiaomiProto.Notification3.newBuilder()
                .setPackage(n.packageName)
                .setAppName(appName)
                .setTitle(n.title)
                .setBody(n.body)
                .setTimestamp(java.time.Instant.ofEpochMilli(n.postedAtMs).toString())
                .setId(notifIdCounter.getAndIncrement())
                .setIsCall(false)
                .build()
            val cmd = XiaomiProto.Command.newBuilder()
                .setType(NotificationCommands.COMMAND_TYPE)
                .setSubtype(NotificationCommands.CMD_NOTIFICATION_SEND)
                .setNotification(
                    XiaomiProto.Notification.newBuilder().setNotification2(
                        XiaomiProto.Notification2.newBuilder().setNotification3(notif3),
                    ),
                )
                .build()
            try { drv.sendCommand(cmd) } catch (t: Throwable) {
                Log.w(TAG, "sendDirect failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "MiBand9NotifListener"
        const val PREFS = "miband9active_notifications"
        const val KEY_ALLOWED = "allowed_packages"
        const val KEY_MUTE_DND = "mute_when_dnd"

        @Volatile private var instance: MiBand9NotificationListener? = null
        @Volatile var forwarder: ((NotificationPush) -> Unit)? = null
            private set
        private val notifIdCounter = AtomicInteger(1)

        fun setForwarder(fn: ((NotificationPush) -> Unit)?) { forwarder = fn }

        fun setAllowedPackages(context: Context, pkgs: Set<String>) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putStringSet(KEY_ALLOWED, pkgs)
            }
            instance?.filters?.let { f -> f.clear(); f.addAll(pkgs) }
        }

        fun setMuteWhenDnd(context: Context, mute: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putBoolean(KEY_MUTE_DND, mute)
            }
            instance?.muteDuringDnd = mute
        }

        fun isRunning(): Boolean = instance != null

        /** Resolve human-readable app label, fallback to package name. */
        fun resolveAppLabel(context: Context, pkg: String): String {
            val pm = context.packageManager
            return runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }
    }
}

data class NotificationPush(
    val packageName: String,
    val ticker: String,
    val title: String,
    val body: String,
    val postedAtMs: Long,
)
