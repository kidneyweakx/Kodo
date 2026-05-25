/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. Filter + emoji-aware encoding semantics mirrored from
 *  Gadgetbridge's NotificationListener + XiaomiNotificationService.
 *
 *  Listens for system notifications, drops anything not on the allow-list
 *  *before* waking the BLE stack, then hands the payload to whoever
 *  registered as a [forwarder]. The actual BLE write happens elsewhere so
 *  this listener has zero band-state coupling.
 */
package com.kidneyweakx.miband9active.xiaomi.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.SharedPreferences
import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.CopyOnWriteArraySet

class MiBand9NotificationListener : NotificationListenerService() {

    private val filters = CopyOnWriteArraySet<String>()
    private var muteDuringDnd: Boolean = false

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
        forwarder?.invoke(NotificationPush(pkg, sbn.notification.tickerText?.toString().orEmpty(), title, body, sbn.postTime))
    }

    companion object {
        const val PREFS = "miband9active_notifications"
        const val KEY_ALLOWED = "allowed_packages"
        const val KEY_MUTE_DND = "mute_when_dnd"

        @Volatile private var instance: MiBand9NotificationListener? = null
        @Volatile var forwarder: ((NotificationPush) -> Unit)? = null
            private set

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
    }
}

data class NotificationPush(
    val packageName: String,
    val ticker: String,
    val title: String,
    val body: String,
    val postedAtMs: Long,
)
