/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Settings + manual-push surface for notification forwarding and call alerts.
 *  The forwarding pipeline itself is native and independent of JS
 *  (MiBand9NotificationListener → NotificationForwarder, CallAlertEngine),
 *  so nothing is lost while the JS runtime isn't up.
 */
package com.margelo.nitro.miband9active

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.calls.CallAlertEngine
import com.kidneyweakx.miband9active.xiaomi.notifications.BandEventRouter
import com.kidneyweakx.miband9active.xiaomi.notifications.MiBand9NotificationListener
import com.kidneyweakx.miband9active.xiaomi.notifications.NotificationForwarder
import com.kidneyweakx.miband9active.xiaomi.notifications.NotificationPrefs
import com.margelo.nitro.core.Promise

class HybridNotificationBridge : HybridHybridNotificationBridgeSpec() {

    init {
        val ctx = AppContext.context
        NotificationPrefs.init(ctx)
        BandEventRouter.ensureStarted()
        MiBand9NotificationListener.requestRebindIfNeeded(ctx)
        // READ_PHONE_STATE may have been granted since the listener connected.
        if (MiBand9NotificationListener.listenerConnected) CallAlertEngine.refreshPermissions(ctx)
    }

    override val notificationAccessGranted: Boolean
        get() = MiBand9NotificationListener.isAccessGranted(AppContext.context)

    override fun isNotificationAccessGranted(): Boolean =
        MiBand9NotificationListener.isAccessGranted(AppContext.context)

    override val muteWhenDnd: Boolean
        get() = NotificationPrefs.muteWhenDnd

    override val callAlertsEnabled: Boolean
        get() = NotificationPrefs.callAlertsEnabled

    override fun requestAccess() {
        val ctx = AppContext.context
        val component = MiBand9NotificationListener.componentName(ctx)
        val direct = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else {
            null
        }
        val fallback = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        for (intent in listOfNotNull(direct, fallback)) {
            try {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (t: Throwable) {
                Log.w(TAG, "cannot open ${intent.action}", t)
            }
        }
    }

    override fun push(request: NotificationPushRequest): Promise<Unit> = Promise.async {
        // Same wire path as the listener; dropped when the band is disconnected.
        NotificationForwarder.enqueue(
            NotificationForwarder.Outgoing(
                packageName = request.sourceId,
                appName = request.appName,
                title = request.title,
                body = request.body,
                whenMs = request.postedAt.toLong(),
                key = null,
            ),
        )
    }

    override fun getFilters(): Array<NotificationFilter> {
        val ctx = AppContext.context
        val allowed = NotificationPrefs.allowed
        val all = allowed + NotificationPrefs.seen
        return all
            .map { pkg ->
                NotificationFilter(
                    sourceId = pkg,
                    appName = MiBand9NotificationListener.resolveAppLabel(ctx, pkg),
                    enabled = pkg in allowed,
                )
            }
            .sortedWith(compareByDescending<NotificationFilter> { it.enabled }.thenBy { it.appName.lowercase() })
            .toTypedArray()
    }

    override fun setFilter(filter: NotificationFilter) {
        NotificationPrefs.setAllowed(filter.sourceId, filter.enabled)
    }

    override fun removeFilter(sourceId: String) {
        NotificationPrefs.forget(sourceId)
    }

    override fun setMuteWhenDnd(enabled: Boolean) {
        NotificationPrefs.setMuteWhenDnd(enabled)
    }

    override fun setCallAlertsEnabled(enabled: Boolean) {
        CallAlertEngine.setEnabled(AppContext.context, enabled)
    }

    private companion object {
        const val TAG = "HybridNotificationBridge"
    }
}
