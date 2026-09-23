/*
 * Copyright (C) 2015-2024 Andreas Shimokawa, Carsten Pfeiffer, Daniele Gobbetti, José Rebelo and
 *                         other Gadgetbridge contributors (NotificationListener.java)
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

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.calls.CallAlertEngine
import com.kidneyweakx.miband9active.media.MediaSessionTracker

/**
 * Android notification source. Filtering mirrors upstream NotificationListener
 * (shouldIgnoreSource / shouldIgnoreNotification / repeat prevention), then
 * hands the survivors to [NotificationForwarder].
 *
 * Everything that decides "drop" runs before any BLE work, and a disconnected
 * band means drop — the listener never starts a connection (docs/POWER.md).
 *
 * Also the anchor for the two features that need the notification-access grant:
 * media sessions ([MediaSessionTracker]) and call detection ([CallAlertEngine]).
 */
class MiBand9NotificationListener : NotificationListenerService() {

    /** Upstream notificationOldRepeatPrevention: package → newest `when` forwarded. Main thread only. */
    private val lastWhenByPackage = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 128
    }

    /** sbn.key → hash of what we last forwarded, to drop no-op updates. Main thread only. */
    private val lastContentByKey = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 128
    }

    override fun onCreate() {
        super.onCreate()
        NotificationPrefs.init(this)
        instance = this
        BandEventRouter.ensureStarted()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        MediaSessionTracker.start(applicationContext)
        CallAlertEngine.start(applicationContext)
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        MediaSessionTracker.stop()
        CallAlertEngine.stopTelephony(applicationContext)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        onNotificationPosted(sbn, runCatching { currentRanking }.getOrNull())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        try {
            handlePosted(sbn, rankingMap)
        } catch (t: Throwable) {
            Log.w(TAG, "onNotificationPosted failed for ${sbn.packageName}", t)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        try {
            handleRemoved(sbn)
        } catch (t: Throwable) {
            Log.w(TAG, "onNotificationRemoved failed for ${sbn.packageName}", t)
        }
    }

    private fun handlePosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val pkg: String = sbn.packageName ?: return
        if (pkg == packageName) return // our own notifications (sync FGS etc.)

        val notification: Notification = sbn.notification ?: return
        val extras: Bundle = notification.extras ?: Bundle.EMPTY

        // Media sessions are tracked natively; their notifications are never forwarded.
        if (isMediaNotification(notification, extras)) {
            MediaSessionTracker.onMediaNotification()
            return
        }

        val ranking = rankingMap?.let { map ->
            val r = Ranking()
            if (map.getRanking(sbn.key, r)) r else null
        }
        val dndSuppressed = ranking?.let { !it.matchesInterruptionFilter() } ?: isDndBlockingEverything()

        if (Notification.CATEGORY_CALL == notification.category) {
            CallAlertEngine.onCallNotificationPosted(this, sbn, dndSuppressed)
            return
        }

        if (pkg in SYSTEM_SOURCES) return

        val flags = notification.flags
        if ((flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0 &&
            pkg !in FITNESS_APPS
        ) return
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0 &&
            pkg !in GROUP_SUMMARY_WHITELIST && !hasWearableActions(notification)
        ) return
        if ((flags and Notification.FLAG_LOCAL_ONLY) != 0 && pkg !in LOCAL_ONLY_EXCEPTIONS) return
        if (Notification.CATEGORY_PROGRESS == notification.category &&
            (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b")
        ) return

        NotificationPrefs.recordSeen(pkg)
        if (!NotificationPrefs.isAllowed(pkg)) return

        // Power rule: band not connected → drop, never queue, never connect.
        if (DriverHolder.current == null) return

        if (ranking != null) {
            if (Build.VERSION.SDK_INT >= 28 && ranking.isSuspended) return
            val importance = ranking.importance
            if (importance == NotificationManager.IMPORTANCE_NONE) return
            // upstream notifications_ignore_low_priority (default on)
            if (importance != NotificationManager.IMPORTANCE_UNSPECIFIED &&
                importance < NotificationManager.IMPORTANCE_DEFAULT
            ) return
            if (importance == NotificationManager.IMPORTANCE_UNSPECIFIED && isLowPriorityLegacy(notification)) return
        } else if (isLowPriorityLegacy(notification)) {
            return
        }
        if (NotificationPrefs.muteWhenDnd && dndSuppressed) return

        // Repeat prevention (upstream notificationOldRepeatPrevention).
        val whenMs = notification.`when`
        val lastWhen = lastWhenByPackage[pkg]
        if (lastWhen != null && whenMs <= lastWhen && pkg !in FITNESS_APPS) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let { sanitizeUnicode(it) }
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        val text = if (!bigText.isNullOrBlank()) bigText else extras.getCharSequence(Notification.EXTRA_TEXT)
        val body = text?.toString()?.let { sanitizeUnicode(it) }
        if (title.isNullOrBlank() && body.isNullOrBlank()) return

        // Same Android notification re-posted with identical content → nothing new to show.
        val contentHash = (title.orEmpty() + '\u0000' + body.orEmpty()).hashCode()
        if (lastContentByKey[sbn.key] == contentHash) return
        lastContentByKey[sbn.key] = contentHash

        val appInfo = applicationInfoFrom(extras)
        if (appInfo != null) NotificationForwarder.rememberAppInfo(pkg, appInfo)

        NotificationForwarder.enqueue(
            NotificationForwarder.Outgoing(
                packageName = pkg,
                appName = resolveAppLabel(this, pkg, appInfo),
                title = title,
                body = body,
                whenMs = if (whenMs > 0) whenMs else sbn.postTime,
                key = sbn.key,
            ),
        )

        val now = System.currentTimeMillis()
        // #4327: never record "future" whens (Outlook reminders), and 0 means the app doesn't set it.
        if (whenMs != 0L && whenMs - now <= 30_000L) lastWhenByPackage[pkg] = whenMs
    }

    private fun handleRemoved(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val notification = sbn.notification
        if (notification != null && Notification.CATEGORY_CALL == notification.category) {
            CallAlertEngine.onCallNotificationRemoved(sbn.key)
        }
        lastContentByKey.remove(sbn.key)
        NotificationForwarder.onRemoved(sbn.key)
    }

    private fun isDndBlockingEverything(): Boolean {
        val filter = runCatching { currentInterruptionFilter }.getOrDefault(NotificationListenerService.INTERRUPTION_FILTER_UNKNOWN)
        return filter == NotificationListenerService.INTERRUPTION_FILTER_NONE ||
            filter == NotificationListenerService.INTERRUPTION_FILTER_ALARMS
    }

    companion object {
        private const val TAG = "MB9A_NotifListener"

        /** Hidden framework extra: the posting app's ApplicationInfo. */
        private const val EXTRA_BUILDER_APPLICATION_INFO = "android.appInfo"

        @Volatile
        var instance: MiBand9NotificationListener? = null
            private set

        @Volatile
        var listenerConnected: Boolean = false
            private set

        /** upstream shouldIgnoreSource: system events and the stock dialers. */
        private val SYSTEM_SOURCES = setOf(
            "android",
            "com.android.systemui",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.cyanogenmod.eleven",
        )

        private val GROUP_SUMMARY_WHITELIST = setOf(
            "com.microsoft.office.lync15",
            "com.skype.raider",
            "mikado.bizcalpro",
        )

        /** upstream: types allowed through FLAG_LOCAL_ONLY (WeChat, Telegram, Outlook, Skype). */
        private val LOCAL_ONLY_EXCEPTIONS = setOf(
            "com.tencent.mm",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
            "com.microsoft.office.outlook",
            "com.skype.raider",
        )

        private val FITNESS_APPS = setOf(
            "de.dennisguse.opentracks",
            "de.dennisguse.opentracks.debug",
            "de.dennisguse.opentracks.nightly",
            "de.dennisguse.opentracks.playstore",
            "de.tadris.fitness",
            "de.tadris.fitness.debug",
        )

        private val CONTROL_CHARS = Regex("[\\p{C}&&\\S]")

        /** upstream sanitizeUnicode: strip control/format chars (Telegram adds lots), keep whitespace. */
        fun sanitizeUnicode(s: String): String = s.replace(CONTROL_CHARS, "")

        fun componentName(context: Context): ComponentName =
            ComponentName(context, MiBand9NotificationListener::class.java)

        fun isAccessGranted(context: Context): Boolean {
            val component = componentName(context)
            if (Build.VERSION.SDK_INT >= 27) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm != null) return runCatching { nm.isNotificationListenerAccessGranted(component) }.getOrDefault(false)
            }
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.split(':').any { ComponentName.unflattenFromString(it) == component }
        }

        /** Ask the system to (re)bind us when access is granted but we're not connected. */
        fun requestRebindIfNeeded(context: Context) {
            if (listenerConnected || !isAccessGranted(context)) return
            runCatching { NotificationListenerService.requestRebind(componentName(context)) }
                .onFailure { Log.w(TAG, "requestRebind failed", it) }
        }

        fun resolveAppLabel(context: Context, pkg: String, info: ApplicationInfo? = null): String {
            val pm = context.packageManager
            try {
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                // Not visible to us (package visibility) — fall through.
            }
            if (info != null) {
                runCatching { pm.getApplicationLabel(info).toString() }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            return pkg
        }

        private fun applicationInfoFrom(extras: Bundle): ApplicationInfo? = try {
            @Suppress("DEPRECATION")
            extras.getParcelable<Parcelable>(EXTRA_BUILDER_APPLICATION_INFO) as? ApplicationInfo
        } catch (_: Exception) {
            null
        }

        private fun isMediaNotification(n: Notification, extras: Bundle): Boolean =
            extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                Notification.CATEGORY_TRANSPORT == n.category

        @Suppress("DEPRECATION")
        private fun isLowPriorityLegacy(n: Notification): Boolean = n.priority < Notification.PRIORITY_DEFAULT

        private fun hasWearableActions(n: Notification): Boolean =
            runCatching { Notification.WearableExtender(n).actions.isNotEmpty() }.getOrDefault(false)
    }
}
