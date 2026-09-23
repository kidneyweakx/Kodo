/*
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
 *
 * JS facade over ScheduleService (XiaomiScheduleService port): alarms,
 * reminders and the sleep-mode schedule.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.xiaomi.services.DeviceFeatures
import com.kidneyweakx.miband9active.xiaomi.services.HourMin
import com.kidneyweakx.miband9active.xiaomi.services.ScheduleService
import com.margelo.nitro.core.Promise

class HybridSchedule : HybridHybridScheduleSpec() {

    init {
        DeviceFeatures.ensureStarted()
    }

    // ---- alarms -------------------------------------------------------------

    override fun getCachedAlarms(): AlarmList? = ScheduleService.getCachedAlarms()?.toNitro()

    override fun fetchAlarms(): Promise<AlarmList> = Promise.async { ScheduleService.fetchAlarms().toNitro() }

    override fun createAlarm(draft: AlarmDraft): Promise<AlarmList> = Promise.async {
        ScheduleService.createAlarm(draft.toEngine()).toNitro()
    }

    override fun updateAlarm(id: Double, draft: AlarmDraft): Promise<AlarmList> = Promise.async {
        ScheduleService.updateAlarm(id.toInt(), draft.toEngine()).toNitro()
    }

    override fun deleteAlarms(ids: DoubleArray): Promise<AlarmList> = Promise.async {
        ScheduleService.deleteAlarms(ids.map { it.toInt() }).toNitro()
    }

    private fun AlarmDraft.toEngine() = ScheduleService.AlarmDraft(
        hour = time.hour.toInt(),
        minute = time.minute.toInt(),
        enabled = enabled,
        smart = smartWakeup,
        repeatDays = repeatDays.toInt(),
    )

    private fun ScheduleService.AlarmList.toNitro() = AlarmList(
        maxAlarms = maxAlarms.toDouble(),
        alarms = alarms.map {
            BandAlarm(
                id = it.id.toDouble(),
                time = TimeOfDay(hour = it.hour.toDouble(), minute = it.minute.toDouble()),
                enabled = it.enabled,
                smartWakeup = it.smart,
                repeatDays = it.repeatDays.toDouble(),
            )
        }.toTypedArray(),
        fetchedAt = fetchedAt.toDouble(),
    )

    // ---- reminders ----------------------------------------------------------

    override fun getCachedReminders(): ReminderList? = ScheduleService.getCachedReminders()?.toNitro()

    override fun fetchReminders(): Promise<ReminderList> = Promise.async { ScheduleService.fetchReminders().toNitro() }

    override fun createReminder(draft: ReminderDraft): Promise<ReminderList> = Promise.async {
        ScheduleService.createReminder(draft.toEngine()).toNitro()
    }

    override fun updateReminder(id: Double, draft: ReminderDraft): Promise<ReminderList> = Promise.async {
        ScheduleService.updateReminder(id.toInt(), draft.toEngine()).toNitro()
    }

    override fun deleteReminders(ids: DoubleArray): Promise<ReminderList> = Promise.async {
        ScheduleService.deleteReminders(ids.map { it.toInt() }).toNitro()
    }

    private fun ReminderDraft.toEngine() = ScheduleService.ReminderDraft(
        title = title,
        atMillis = at.toLong(),
        repeat = when (repeat) {
            ReminderRepeat.ONCE -> "once"
            ReminderRepeat.DAILY -> "daily"
            ReminderRepeat.WEEKLY -> "weekly"
            ReminderRepeat.MONTHLY -> "monthly"
            ReminderRepeat.YEARLY -> "yearly"
        },
    )

    private fun ScheduleService.ReminderList.toNitro() = ReminderList(
        maxReminders = maxReminders.toDouble(),
        reminders = reminders.map {
            BandReminder(
                id = it.id.toDouble(),
                title = it.title,
                at = it.atMillis.toDouble(),
                repeat = when (it.repeat) {
                    "daily" -> ReminderRepeat.DAILY
                    "weekly" -> ReminderRepeat.WEEKLY
                    "monthly" -> ReminderRepeat.MONTHLY
                    "yearly" -> ReminderRepeat.YEARLY
                    else -> ReminderRepeat.ONCE
                },
            )
        }.toTypedArray(),
        fetchedAt = fetchedAt.toDouble(),
    )

    // ---- sleep mode ---------------------------------------------------------

    override fun getSleepMode(): SleepModeConfig? = ScheduleService.getSleepMode()?.toNitro()

    override fun refreshSleepMode(): Promise<SleepModeConfig?> = Promise.async {
        ScheduleService.refreshSleepMode()?.toNitro()
    }

    override fun setSleepMode(config: SleepModeConfig): Promise<SleepModeConfig> = Promise.async {
        ScheduleService.setSleepMode(
            ScheduleService.SleepMode(
                enabled = config.enabled,
                start = HourMin(config.start.hour.toInt(), config.start.minute.toInt()),
                end = HourMin(config.end.hour.toInt(), config.end.minute.toInt()),
            ),
        ).toNitro()
    }

    private fun ScheduleService.SleepMode.toNitro() = SleepModeConfig(
        enabled = enabled,
        start = TimeOfDay(hour = start.hour.toDouble(), minute = start.minute.toDouble()),
        end = TimeOfDay(hour = end.hour.toDouble(), minute = end.minute.toDouble()),
    )
}
