/*  Copyright (C) 2023-2024 José Rebelo  (Gadgetbridge XiaomiScheduleService)
 *  Copyright (C) 2026 kidneyweakx       (Kotlin port)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Command type 17: alarms (0 get, 1 create, 2 edit, 4 delete), sleep mode
 *  (8 get, 9 set) and reminders (14 get, 15 create, 17 edit, 18 delete).
 *
 *  Differences to upstream: there is no Gadgetbridge alarm DB to reconcile, so
 *  the band's list IS the model — every mutation is followed by a GET and the
 *  caller gets the band's answer. World clocks are not ported (slot count 0).
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.util.Log
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.json.JSONArray
import org.json.JSONObject

object ScheduleService {
    private const val TAG = "MB9A_Schedule"

    private const val REPETITION_ONCE = 0
    private const val REPETITION_DAILY = 1
    private const val REPETITION_WEEKLY = 5
    private const val REPETITION_MONTHLY = 7
    private const val REPETITION_YEARLY = 8

    private const val ALARM_SMART = 1
    private const val ALARM_NORMAL = 2

    /** Gadgetbridge Alarm.ALARM_DAILY (Mon..Sun bits). */
    const val REPEAT_DAILY_MASK = 127

    /** XiaomiCoordinator.getMaximumReminderMessageLength() */
    const val MAX_REMINDER_TITLE = 20

    private const val KEY_ALARMS = "schedule_alarms"
    private const val KEY_REMINDERS = "schedule_reminders"
    private const val KEY_SLEEP_MODE = "schedule_sleep_mode"
    private const val DIRTY_SLEEP_MODE = "sleep_mode"

    data class Alarm(val id: Int, val hour: Int, val minute: Int, val enabled: Boolean, val smart: Boolean, val repeatDays: Int)
    data class AlarmDraft(val hour: Int, val minute: Int, val enabled: Boolean, val smart: Boolean, val repeatDays: Int)
    data class AlarmList(val maxAlarms: Int, val alarms: List<Alarm>, val fetchedAt: Long)

    /** repeat: "once" | "daily" | "weekly" | "monthly" | "yearly" */
    data class Reminder(val id: Int, val title: String, val atMillis: Long, val repeat: String)
    data class ReminderDraft(val title: String, val atMillis: Long, val repeat: String)
    data class ReminderList(val maxReminders: Int, val reminders: List<Reminder>, val fetchedAt: Long)

    data class SleepMode(val enabled: Boolean, val start: HourMin, val end: HourMin)

    private class NotConnected : IllegalStateException("Band not connected")

    // ================================================================ alarms

    fun getCachedAlarms(): AlarmList? {
        val o = FeatureStore.getJson(KEY_ALARMS) ?: return null
        val arr = o.optJSONArray("alarms") ?: JSONArray()
        return AlarmList(
            maxAlarms = o.optInt("max"),
            alarms = (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                Alarm(a.optInt("id"), a.optInt("h"), a.optInt("m"), a.optBoolean("en"), a.optBoolean("smart"), a.optInt("rep"))
            },
            fetchedAt = o.optLong("at"),
        )
    }

    suspend fun fetchAlarms(): AlarmList {
        val reply = BandChannel.request(ScheduleCommands.COMMAND_TYPE, ScheduleCommands.CMD_ALARMS_GET)
            ?: throw if (BandChannel.isConnected) IllegalStateException("Band did not answer the alarm list request") else NotConnected()
        if (!reply.hasSchedule() || !reply.schedule.hasAlarms()) throw IllegalStateException("Band returned no alarm list (status=${reply.status})")
        onAlarms(reply.schedule.alarms)
        return getCachedAlarms() ?: throw IllegalStateException("alarm list not stored")
    }

    suspend fun createAlarm(draft: AlarmDraft): AlarmList {
        validate(draft)
        val cached = getCachedAlarms()
        if (cached != null && cached.maxAlarms > 0 && cached.alarms.size >= cached.maxAlarms) {
            throw IllegalStateException("All ${cached.maxAlarms} alarm slots are in use")
        }
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(ScheduleCommands.COMMAND_TYPE)
            .setSubtype(ScheduleCommands.CMD_ALARMS_CREATE)
            .setSchedule(XiaomiProto.Schedule.newBuilder().setCreateAlarm(alarmDetails(draft)))
            .build()
        // Upstream waits for the create ack (17/1) before re-requesting the list.
        val ack = BandChannel.request(cmd) { it.type == ScheduleCommands.COMMAND_TYPE && it.subtype == ScheduleCommands.CMD_ALARMS_CREATE }
        if (ack == null && !BandChannel.isConnected) throw NotConnected()
        if (ack != null && ack.hasStatus() && ack.status != 0) Log.w(TAG, "alarm create status=${ack.status}")
        return fetchAlarms()
    }

    suspend fun updateAlarm(id: Int, draft: AlarmDraft): AlarmList {
        validate(draft)
        val sent = BandChannel.send(ScheduleCommands.COMMAND_TYPE, ScheduleCommands.CMD_ALARMS_EDIT) {
            setSchedule(
                XiaomiProto.Schedule.newBuilder().setEditAlarm(
                    XiaomiProto.Alarm.newBuilder().setId(id).setAlarmDetails(alarmDetails(draft)),
                ),
            )
        }
        if (!sent) throw NotConnected()
        return fetchAlarms()
    }

    suspend fun deleteAlarms(ids: List<Int>): AlarmList {
        if (ids.isEmpty()) return fetchAlarms()
        val sent = BandChannel.send(ScheduleCommands.COMMAND_TYPE, ScheduleCommands.CMD_ALARMS_DELETE) {
            setSchedule(XiaomiProto.Schedule.newBuilder().setDeleteAlarm(XiaomiProto.AlarmDelete.newBuilder().addAllId(ids)))
        }
        if (!sent) throw NotConnected()
        return fetchAlarms()
    }

    private fun validate(d: AlarmDraft) {
        require(d.hour in 0..23 && d.minute in 0..59) { "invalid alarm time" }
        require(d.repeatDays in 0..REPEAT_DAILY_MASK) { "repeatDays must be a Mon=1..Sun=64 bitmask" }
    }

    /** XiaomiScheduleService.onSetAlarms() details builder. */
    private fun alarmDetails(d: AlarmDraft): XiaomiProto.AlarmDetails.Builder {
        val b = XiaomiProto.AlarmDetails.newBuilder()
            .setTime(XiaomiProto.HourMinute.newBuilder().setHour(d.hour).setMinute(d.minute))
            .setEnabled(d.enabled)
            .setSmart(if (d.smart) ALARM_SMART else ALARM_NORMAL)
        when (d.repeatDays) {
            0 -> b.setRepeatMode(REPETITION_ONCE)
            REPEAT_DAILY_MASK -> b.setRepeatMode(REPETITION_DAILY)
            else -> b.setRepeatMode(REPETITION_WEEKLY).setRepeatFlags(d.repeatDays)
        }
        return b
    }

    /** XiaomiScheduleService.handleAlarms() */
    private fun onAlarms(alarms: XiaomiProto.Alarms) {
        FeatureStore.setInt(FeatureStore.PREF_ALARM_SLOTS, alarms.maxAlarms)
        val arr = JSONArray()
        alarms.alarmList.sortedBy { it.id }.forEach { a ->
            val d = a.alarmDetails
            val rep = when (d.repeatMode) {
                REPETITION_ONCE -> 0
                REPETITION_DAILY -> REPEAT_DAILY_MASK
                else -> d.repeatFlags // REPETITION_WEEKLY: GB weekday bitmask as-is
            }
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("h", d.time.hour)
                    .put("m", d.time.minute)
                    .put("en", d.enabled)
                    .put("smart", d.smart == ALARM_SMART)
                    .put("rep", rep),
            )
        }
        FeatureStore.putJson(
            KEY_ALARMS,
            JSONObject().put("max", alarms.maxAlarms).put("alarms", arr).put("at", System.currentTimeMillis()),
        )
    }

    // ================================================================ reminders

    fun getCachedReminders(): ReminderList? {
        val o = FeatureStore.getJson(KEY_REMINDERS) ?: return null
        val arr = o.optJSONArray("reminders") ?: JSONArray()
        return ReminderList(
            maxReminders = o.optInt("max"),
            reminders = (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                Reminder(r.optInt("id"), r.optString("title"), r.optLong("at"), r.optString("repeat", "once"))
            },
            fetchedAt = o.optLong("at"),
        )
    }

    suspend fun fetchReminders(): ReminderList {
        val reply = BandChannel.request(ScheduleCommands.COMMAND_TYPE, ScheduleCommands.CMD_REMINDERS_GET)
            ?: throw if (BandChannel.isConnected) IllegalStateException("Band did not answer the reminder list request") else NotConnected()
        if (!reply.hasSchedule() || !reply.schedule.hasReminders()) throw IllegalStateException("Band returned no reminder list (status=${reply.status})")
        onReminders(reply.schedule.reminders)
        return getCachedReminders() ?: throw IllegalStateException("reminder list not stored")
    }

    suspend fun createReminder(draft: ReminderDraft): ReminderList {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(ScheduleCommands.COMMAND_TYPE)
            .setSubtype(ScheduleCommands.CMD_REMINDERS_CREATE)
            .setSchedule(XiaomiProto.Schedule.newBuilder().setCreateReminder(reminderDetails(draft)))
            .build()
        val ack = BandChannel.request(cmd) { it.type == ScheduleCommands.COMMAND_TYPE && it.subtype == ScheduleCommands.CMD_REMINDERS_CREATE }
        if (ack == null && !BandChannel.isConnected) throw NotConnected()
        return fetchReminders()
    }

    suspend fun updateReminder(id: Int, draft: ReminderDraft): ReminderList {
        val sent = BandChannel.send(ScheduleCommands.COMMAND_TYPE, ScheduleCommands.CMD_REMINDERS_EDIT) {
            setSchedule(
                XiaomiProto.Schedule.newBuilder().setEditReminder(
                    XiaomiProto.Reminder.newBuilder().setId(id).setReminderDetails(reminderDetails(draft)),
                ),
            )
        }
        if (!sent) throw NotConnected()
        return fetchReminders()
    }

    suspend fun deleteReminders(ids: List<Int>): ReminderList {
        if (ids.isEmpty()) return fetchReminders()
        val sent = BandChannel.send(ScheduleCommands.COMMAND_TYPE, ScheduleCommands.CMD_REMINDERS_DELETE) {
            setSchedule(XiaomiProto.Schedule.newBuilder().setDeleteReminder(XiaomiProto.ReminderDelete.newBuilder().addAllId(ids)))
        }
        if (!sent) throw NotConnected()
        return fetchReminders()
    }

    /**
     * XiaomiScheduleService.onSetReminders() details builder. Upstream sends the
     * date/time fields in UTC ("For some reason, the watch expects those in UTC").
     */
    private fun reminderDetails(d: ReminderDraft): XiaomiProto.ReminderDetails.Builder {
        val t = GregorianCalendar.getInstance(TimeZone.getTimeZone("UTC"))
        t.timeInMillis = d.atMillis
        val b = XiaomiProto.ReminderDetails.newBuilder()
            .setTime(
                XiaomiProto.Time.newBuilder()
                    .setHour(t.get(Calendar.HOUR_OF_DAY))
                    .setMinute(t.get(Calendar.MINUTE))
                    .setSecond(t.get(Calendar.SECOND))
                    .setMillisecond(t.get(Calendar.MILLISECOND))
                    .build(),
            )
            .setDate(
                XiaomiProto.Date.newBuilder()
                    .setYear(t.get(Calendar.YEAR))
                    .setMonth(t.get(Calendar.MONTH) + 1)
                    .setDay(t.get(Calendar.DATE))
                    .build(),
            )
            .setTitle(d.title.take(MAX_REMINDER_TITLE))
        when (d.repeat) {
            "daily" -> b.setRepeatMode(REPETITION_DAILY)
            "weekly" -> b.setRepeatMode(REPETITION_WEEKLY).setRepeatFlags((t.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1)
            "monthly" -> b.setRepeatMode(REPETITION_MONTHLY)
            "yearly" -> b.setRepeatMode(REPETITION_YEARLY)
            else -> b.setRepeatMode(REPETITION_ONCE)
        }
        return b
    }

    /** XiaomiScheduleService.handleReminders() */
    private fun onReminders(reminders: XiaomiProto.Reminders) {
        FeatureStore.setInt(FeatureStore.PREF_REMINDER_SLOTS, reminders.maxReminders)
        val arr = JSONArray()
        reminders.reminderList.forEach { r ->
            val d = r.reminderDetails
            val repeat = when (d.repeatMode) {
                REPETITION_DAILY -> "daily"
                REPETITION_WEEKLY -> "weekly"
                REPETITION_MONTHLY -> "monthly"
                REPETITION_YEARLY -> "yearly"
                else -> "once"
            }
            // XiaomiPreferences.toDate(): fields are UTC
            val c = GregorianCalendar.getInstance(TimeZone.getTimeZone("UTC"))
            c.clear()
            c.set(d.date.year, d.date.month - 1, d.date.day, d.time.hour, d.time.minute, d.time.second)
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("title", d.title)
                    .put("at", c.timeInMillis)
                    .put("repeat", repeat),
            )
        }
        FeatureStore.putJson(
            KEY_REMINDERS,
            JSONObject().put("max", reminders.maxReminders).put("reminders", arr).put("at", System.currentTimeMillis()),
        )
    }

    // ================================================================ sleep mode

    fun getSleepMode(): SleepMode? {
        val o = FeatureStore.getJson(KEY_SLEEP_MODE) ?: return null
        return SleepMode(
            o.optBoolean("enabled"),
            // upstream defaults 22:00 - 06:00
            HourMin.fromJson(o.optJSONObject("start"), 22, 0),
            HourMin.fromJson(o.optJSONObject("end"), 6, 0),
        )
    }

    suspend fun refreshSleepMode(): SleepMode? {
        BandChannel.request(ScheduleCommands.COMMAND_TYPE, ScheduleCommands.CMD_SLEEP_MODE_GET)
        return getSleepMode()
    }

    suspend fun setSleepMode(s: SleepMode): SleepMode {
        require(s.start.hour in 0..23 && s.end.hour in 0..23 && s.start.minute in 0..59 && s.end.minute in 0..59) { "invalid time" }
        storeSleepMode(s)
        FeatureStore.setDirty(DIRTY_SLEEP_MODE, true)
        if (sendSleepMode(s)) FeatureStore.setDirty(DIRTY_SLEEP_MODE, false)
        return s
    }

    private fun storeSleepMode(s: SleepMode) {
        FeatureStore.putJson(
            KEY_SLEEP_MODE,
            JSONObject().put("enabled", s.enabled).put("start", s.start.toJson()).put("end", s.end.toJson()),
        )
    }

    /** XiaomiScheduleService.setSleepModeConfig() */
    private suspend fun sendSleepMode(s: SleepMode): Boolean {
        val sleepMode = XiaomiProto.SleepMode.newBuilder()
            .setEnabled(s.enabled)
            .setSchedule(
                XiaomiProto.SleepModeSchedule.newBuilder()
                    .setUnknown3(0)
                    .setStart(XiaomiProto.HourMinute.newBuilder().setHour(s.start.hour).setMinute(s.start.minute))
                    .setEnd(XiaomiProto.HourMinute.newBuilder().setHour(s.end.hour).setMinute(s.end.minute)),
            )
            .build()
        return BandChannel.send(ScheduleCommands.COMMAND_TYPE, ScheduleCommands.CMD_SLEEP_MODE_SET) {
            setSchedule(XiaomiProto.Schedule.newBuilder().setSleepMode(sleepMode))
        }
    }

    // ================================================================ lifecycle

    /** XiaomiScheduleService.initialize() */
    suspend fun onConnected() {
        BandChannel.send(ScheduleCommands.COMMAND_TYPE, ScheduleCommands.CMD_ALARMS_GET)
        BandChannel.send(ScheduleCommands.COMMAND_TYPE, ScheduleCommands.CMD_REMINDERS_GET)
        val s = getSleepMode()
        if (s != null && FeatureStore.isDirty(DIRTY_SLEEP_MODE)) {
            if (sendSleepMode(s)) FeatureStore.setDirty(DIRTY_SLEEP_MODE, false)
        } else {
            BandChannel.send(ScheduleCommands.COMMAND_TYPE, ScheduleCommands.CMD_SLEEP_MODE_GET)
        }
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            ScheduleCommands.CMD_ALARMS_GET ->
                if (cmd.hasSchedule() && cmd.schedule.hasAlarms()) onAlarms(cmd.schedule.alarms)
            ScheduleCommands.CMD_REMINDERS_GET ->
                if (cmd.hasSchedule() && cmd.schedule.hasReminders()) onReminders(cmd.schedule.reminders)
            ScheduleCommands.CMD_SLEEP_MODE_GET -> if (cmd.hasSchedule() && cmd.schedule.hasSleepMode()) {
                FeatureStore.setFeature(FeatureStore.FEAT_SLEEP_MODE_SCHEDULE, true)
                if (!FeatureStore.isDirty(DIRTY_SLEEP_MODE)) {
                    val sm = cmd.schedule.sleepMode
                    storeSleepMode(
                        SleepMode(
                            sm.enabled,
                            HourMin(sm.schedule.start.hour, sm.schedule.start.minute),
                            HourMin(sm.schedule.end.hour, sm.schedule.end.minute),
                        ),
                    )
                }
            }
            ScheduleCommands.CMD_ALARMS_CREATE, ScheduleCommands.CMD_REMINDERS_CREATE,
            ScheduleCommands.CMD_SLEEP_MODE_SET -> Log.d(TAG, "schedule ack ${cmd.subtype}, status=${cmd.status}")
            else -> Log.d(TAG, "unhandled schedule subtype ${cmd.subtype}")
        }
    }
}
