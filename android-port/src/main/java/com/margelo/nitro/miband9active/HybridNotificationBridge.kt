/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.notifications.IconConverter
import com.kidneyweakx.miband9active.xiaomi.notifications.MiBand9NotificationListener
import com.kidneyweakx.miband9active.xiaomi.services.MiBand9DataUploader
import com.kidneyweakx.miband9active.xiaomi.services.NotificationCommands
import com.margelo.nitro.core.Promise
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

class HybridNotificationBridge : HybridHybridNotificationBridgeSpec() {

    private val notifIdCounter = AtomicInteger(1)
    private val recentPackages = ArrayDeque<String>()
    private var iconPackageName: String? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var iconWatcher: Job? = null

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

        // Remember this package so the band can ask for its icon next time.
        synchronized(recentPackages) {
            if (!recentPackages.contains(request.sourceId)) {
                recentPackages.addFirst(request.sourceId)
                while (recentPackages.size > 16) recentPackages.removeLast()
            }
        }
        ensureIconWatcher()

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
        // Note: listener falls back to direct-to-band send if this forwarder is
        // ever null (e.g. before JS imports notificationBridge), so we don't lose
        // notifications during the listener-bound-but-JS-not-ready window.
        MiBand9NotificationListener.setForwarder { n ->
            val label = MiBand9NotificationListener.resolveAppLabel(AppContext.context, n.packageName)
            val req = NotificationPushRequest(
                sourceId = n.packageName,
                appName = label,
                title = n.title,
                body = n.body,
                postedAt = n.postedAtMs.toDouble(),
                category = NotificationCategory.MESSAGE,
                iconBase64 = null,
            )
            push(req)
        }
    }

    // ----------------------------------------------------- icon request handler

    private fun ensureIconWatcher() {
        if (iconWatcher != null) return
        val drv = DriverHolder.current ?: return
        iconWatcher = scope.launch {
            drv.incoming.collect { msg ->
                if (msg.type != NotificationCommands.COMMAND_TYPE) return@collect
                when (msg.subtype) {
                    16 -> handleIconQuery(msg.command.notification.notificationIconQuery)
                    15 -> handleIconRequest(msg.command.notification.notificationIconRequest)
                }
            }
        }
    }

    private suspend fun handleIconQuery(query: XiaomiProto.NotificationIconPackage) {
        val drv = DriverHolder.current ?: return
        val pkg = query.`package`
        iconPackageName = resolvePackageName(pkg)
        // Tell the band we do have an icon for this package (reply must echo
        // the package name back).
        drv.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(NotificationCommands.COMMAND_TYPE)
                .setSubtype(15)
                .setNotification(
                    XiaomiProto.Notification.newBuilder().setNotificationIconReply(
                        XiaomiProto.NotificationIconPackage.newBuilder().setPackage(iconPackageName),
                    ),
                )
                .build(),
        )
    }

    private suspend fun handleIconRequest(req: XiaomiProto.NotificationIconRequest) {
        val drv = DriverHolder.current ?: return
        if (req.status != 0) return
        val pkg = iconPackageName ?: return
        val ctx = AppContext.context
        val drawable = runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return
        val size = req.size.coerceIn(16, 96)
        val bitmap = IconConverter.fit(drawable, size)
        val bytes = when (req.pixelFormat) {
            0, 1 -> IconConverter.toRgb565(bitmap)
            7, 8 -> IconConverter.toArgb8565(bitmap)
            else -> IconConverter.toRgb565(bitmap)
        }
        MiBand9DataUploader.upload(drv, MiBand9DataUploader.TYPE_NOTIFICATION_ICON, bytes)
    }

    private fun resolvePackageName(maybeTruncated: String): String {
        val pm: PackageManager = AppContext.context.packageManager
        if (runCatching { pm.getApplicationInfo(maybeTruncated, 0) }.isSuccess) return maybeTruncated
        // Fall back to a recent package that starts with the same prefix —
        // the band truncates long package names on the wire.
        return synchronized(recentPackages) {
            recentPackages.firstOrNull { it.startsWith(maybeTruncated) } ?: maybeTruncated
        }
    }
}
