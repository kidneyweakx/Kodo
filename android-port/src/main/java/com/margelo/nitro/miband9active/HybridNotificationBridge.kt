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
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.notifications.MiBand9NotificationListener
import com.kidneyweakx.miband9active.xiaomi.services.NotificationCommands
import com.margelo.nitro.core.Promise
import java.util.concurrent.atomic.AtomicInteger
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

class HybridNotificationBridge : HybridHybridNotificationBridgeSpec() {

    private val notifIdCounter = AtomicInteger(1)

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
        val drv = DriverHolder.current ?: return@async
        val isCall = request.category == NotificationCategory.CALL
        val notif3 = XiaomiProto.Notification3.newBuilder()
            .setPackage(request.sourceId)
            .setAppName(request.appName)
            .setTitle(request.title)
            .setBody(request.body)
            .setTimestamp(java.time.Instant.ofEpochMilli(request.postedAt.toLong()).toString())
            .setId(notifIdCounter.getAndIncrement())
            .setIsCall(isCall)
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
        drv.sendCommand(cmd)
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

    init {
        // Listener forwards system notifications into our push() path automatically.
        MiBand9NotificationListener.setForwarder { n ->
            val req = NotificationPushRequest(
                sourceId = n.packageName,
                appName = n.packageName,
                title = n.title,
                body = n.body,
                postedAt = n.postedAtMs.toDouble(),
                category = NotificationCategory.MESSAGE,
                iconBase64 = null,
            )
            push(req)
        }
    }
}
