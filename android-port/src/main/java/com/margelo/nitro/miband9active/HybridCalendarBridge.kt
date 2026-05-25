/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.services.CalendarCommands
import com.margelo.nitro.core.Promise
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

class HybridCalendarBridge : HybridHybridCalendarBridgeSpec() {

    override fun pushEvents(events: Array<CalendarEventPush>): Promise<Unit> = Promise.async {
        val drv = DriverHolder.current ?: return@async
        val sync = XiaomiProto.CalendarSync.newBuilder().setDisabled(false)
        events.forEach { ev ->
            sync.addEvent(
                XiaomiProto.CalendarEvent.newBuilder()
                    .setTitle(ev.title)
                    .setLocation(ev.location)
                    .setStart((ev.startsAt / 1000.0).toInt())
                    .setEnd((ev.endsAt / 1000.0).toInt())
                    .setAllDay(ev.allDay)
                    .setNotifyMinutesBefore(10)
                    .build(),
            )
        }
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(CalendarCommands.COMMAND_TYPE)
            .setSubtype(CalendarCommands.CMD_EVENTS_SET)
            .setCalendar(XiaomiProto.Calendar.newBuilder().setCalendarSync(sync))
            .build()
        drv.sendCommand(cmd)
    }

    override fun clearEvents(): Promise<Unit> = Promise.async {
        val drv = DriverHolder.current ?: return@async
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(CalendarCommands.COMMAND_TYPE)
            .setSubtype(CalendarCommands.CMD_EVENTS_SET)
            .setCalendar(
                XiaomiProto.Calendar.newBuilder().setCalendarSync(
                    XiaomiProto.CalendarSync.newBuilder().setDisabled(true),
                ),
            )
            .build()
        drv.sendCommand(cmd)
    }
}
