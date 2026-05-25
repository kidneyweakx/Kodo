/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.margelo.nitro.core.Promise

class HybridCalendarBridge : HybridHybridCalendarBridgeSpec() {
    override fun pushEvents(events: Array<CalendarEventPush>): Promise<Unit> = Promise.async { Unit }
    override fun clearEvents(): Promise<Unit> = Promise.async { Unit }
}
