/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.xiaomi.notifications.MiBand9NotificationListener
import com.margelo.nitro.core.Promise

class HybridNotificationBridge : HybridHybridNotificationBridgeSpec() {

    override val notificationAccessGranted: Boolean
        get() {
            val ctx = AppContext.context
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val component = ComponentName(ctx, MiBand9NotificationListener::class.java).flattenToString()
            return flat.split(":").any { it.equals(component, ignoreCase = true) }
        }

    override val muteWhenDnd: Boolean
        get() {
            val prefs = AppContext.context.getSharedPreferences(MiBand9NotificationListener.PREFS, Context.MODE_PRIVATE)
            return prefs.getBoolean(MiBand9NotificationListener.KEY_MUTE_DND, false)
        }

    override fun requestAccess() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        AppContext.context.startActivity(intent)
    }

    override fun push(request: NotificationPushRequest): Promise<Unit> = Promise.async {
        // TODO: forward to band via HybridBandLink shared engine instance once wired.
    }

    override fun getFilters(): Array<NotificationFilter> {
        val prefs = AppContext.context.getSharedPreferences(MiBand9NotificationListener.PREFS, Context.MODE_PRIVATE)
        val allowed = prefs.getStringSet(MiBand9NotificationListener.KEY_ALLOWED, emptySet()) ?: emptySet()
        val pm = AppContext.context.packageManager
        return allowed.map { src ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(src, 0)).toString() }.getOrDefault(src)
            NotificationFilter(sourceId = src, appName = label, enabled = true)
        }.toTypedArray()
    }

    override fun setFilter(filter: NotificationFilter) {
        val prefs = AppContext.context.getSharedPreferences(MiBand9NotificationListener.PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(MiBand9NotificationListener.KEY_ALLOWED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (filter.enabled) current.add(filter.sourceId) else current.remove(filter.sourceId)
        MiBand9NotificationListener.setAllowedPackages(AppContext.context, current)
    }

    override fun removeFilter(sourceId: String) {
        val prefs = AppContext.context.getSharedPreferences(MiBand9NotificationListener.PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(MiBand9NotificationListener.KEY_ALLOWED, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(sourceId)
        MiBand9NotificationListener.setAllowedPackages(AppContext.context, current)
    }

    override fun setMuteWhenDnd(enabled: Boolean) {
        MiBand9NotificationListener.setMuteWhenDnd(AppContext.context, enabled)
    }
}
