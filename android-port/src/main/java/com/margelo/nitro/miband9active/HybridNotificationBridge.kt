/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.margelo.nitro.core.Promise

class HybridNotificationBridge : HybridHybridNotificationBridgeSpec() {
    override val notificationAccessGranted: Boolean get() = false
    override val muteWhenDnd: Boolean get() = false

    override fun requestAccess() {}
    override fun push(request: NotificationPushRequest): Promise<Unit> = Promise.async { Unit }
    override fun getFilters(): Array<NotificationFilter> = emptyArray()
    override fun setFilter(filter: NotificationFilter) {}
    override fun removeFilter(sourceId: String) {}
    override fun setMuteWhenDnd(enabled: Boolean) {}
}
