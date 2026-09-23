/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  JS facade over CalendarService (XiaomiCalendarService port). The old
 *  version used subtype 0 (upstream CMD_CALENDAR_SET is 1), dropped the
 *  description and hard-coded a 10-minute reminder.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.xiaomi.services.CalendarService
import com.kidneyweakx.miband9active.xiaomi.services.DeviceFeatures
import com.margelo.nitro.core.Promise

class HybridCalendarBridge : HybridHybridCalendarBridgeSpec() {

    init {
        DeviceFeatures.ensureStarted()
    }

    override fun pushEvents(events: Array<CalendarEventPush>): Promise<Unit> = Promise.async {
        val ok = CalendarService.pushEvents(
            events.sortedBy { it.startsAt }.map {
                CalendarService.Event(
                    title = it.title,
                    description = "",
                    location = it.location,
                    beginMillis = it.startsAt.toLong(),
                    endMillis = it.endsAt.toLong(),
                    allDay = it.allDay,
                    notifyMinutesBefore = 0, // no reminder info in CalendarEventPush
                )
            },
        )
        if (!ok) throw IllegalStateException("Band not connected")
    }

    override fun clearEvents(): Promise<Unit> = Promise.async {
        if (!CalendarService.sendDisabled()) throw IllegalStateException("Band not connected")
    }

    override fun hasCalendarPermission(): Boolean = CalendarService.hasPermission()

    override fun getSyncSettings(): CalendarSyncSettings {
        val s = CalendarService.getSettings()
        return CalendarSyncSettings(
            enabled = s.enabled,
            lookaheadDays = s.lookaheadDays.toDouble(),
            includeAllDay = s.includeAllDay,
        )
    }

    override fun setSyncSettings(settings: CalendarSyncSettings): Promise<Double> = Promise.async {
        CalendarService.setSettings(
            CalendarService.Settings(
                enabled = settings.enabled,
                lookaheadDays = settings.lookaheadDays.toInt(),
                includeAllDay = settings.includeAllDay,
            ),
        ).toDouble()
    }

    override fun syncNow(): Promise<Double> = Promise.async { CalendarService.sync().toDouble() }

    override fun getLastSyncedAt(): Double? = CalendarService.lastSyncedAt()?.toDouble()
}
