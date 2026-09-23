/*  Copyright (C) 2023-2024 José Rebelo  (Gadgetbridge XiaomiCalendarService)
 *  Copyright (C) 2022-2024 José Rebelo  (Gadgetbridge CalendarManager)
 *  Copyright (C) 2026 kidneyweakx       (Kotlin port, slimmed)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Command type 12, subtype 1 (CMD_CALENDAR_SET) with a full CalendarSync
 *  list each time ("we must sync everything"), or `disabled = true` when
 *  calendar sync is off. Events come from CalendarContract.Instances for the
 *  next `lookaheadDays` (upstream default 7), max 50 events; the first ALERT
 *  reminder becomes `notifyMinutesBefore`. A set identical to the last one
 *  pushed in this connection is not re-sent (upstream `lastSync`).
 *
 *  Needs android.permission.READ_CALENDAR.
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kidneyweakx.miband9active.AppContext
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

object CalendarService {
    private const val TAG = "MB9A_Calendar"
    private const val MAX_EVENTS = 50 // upstream: TODO confirm actual limit
    private const val WORK_NAME = "miband9active.calendar_push"

    private const val KEY_ENABLED = "calendar_sync_enabled"
    private const val KEY_LOOKAHEAD = "calendar_lookahead_days"
    private const val KEY_ALL_DAY = "calendar_include_all_day"
    private const val KEY_LAST_SYNC = "calendar_last_synced_at"

    data class Settings(val enabled: Boolean, val lookaheadDays: Int, val includeAllDay: Boolean)

    data class Event(
        val title: String,
        val description: String,
        val location: String,
        val beginMillis: Long,
        val endMillis: Long,
        val allDay: Boolean,
        val notifyMinutesBefore: Int,
    )

    private val mutex = Mutex()
    @Volatile private var lastSync: Set<Event>? = null

    fun getSettings(): Settings {
        val p = FeatureStore.prefs
        return Settings(
            enabled = p.getBoolean(KEY_ENABLED, false),
            lookaheadDays = p.getInt(KEY_LOOKAHEAD, 7).coerceIn(1, 30),
            includeAllDay = p.getBoolean(KEY_ALL_DAY, true),
        )
    }

    fun hasPermission(): Boolean =
        AppContext.context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun lastSyncedAt(): Long? = FeatureStore.prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 }

    /** Persist, (un)schedule the >= 6 h worker, and sync now. */
    suspend fun setSettings(s: Settings): Int {
        val eff = s.copy(lookaheadDays = s.lookaheadDays.coerceIn(1, 30))
        FeatureStore.prefs.edit()
            .putBoolean(KEY_ENABLED, eff.enabled)
            .putInt(KEY_LOOKAHEAD, eff.lookaheadDays)
            .putBoolean(KEY_ALL_DAY, eff.includeAllDay)
            .apply()
        schedule(eff.enabled)
        lastSync = null
        return if (eff.enabled) sync() else if (sendDisabled()) 0 else -1
    }

    /** XiaomiCalendarService.syncCalendar(). -1 when disabled, no permission, or not connected. */
    suspend fun sync(): Int = mutex.withLock {
        val s = getSettings()
        if (!s.enabled) return@withLock -1
        if (!hasPermission()) {
            Log.w(TAG, "Calendar sync is enabled, but calendar access is not granted")
            return@withLock -1
        }
        if (!BandChannel.isConnected) return@withLock -1
        val events = withContext(Dispatchers.IO) { readEvents(s) }
        val set = events.toSet()
        if (set == lastSync) {
            Log.d(TAG, "Already synced this set of events, won't send to device")
            return@withLock events.size
        }
        if (!sendEvents(events)) return@withLock -1
        lastSync = set
        events.size
    }

    /** JS-provided list (bypasses CalendarContract). */
    suspend fun pushEvents(events: List<Event>): Boolean = mutex.withLock {
        val ok = sendEvents(events.take(MAX_EVENTS))
        if (ok) lastSync = events.take(MAX_EVENTS).toSet()
        ok
    }

    suspend fun sendDisabled(): Boolean {
        lastSync = null
        return BandChannel.send(CalendarCommands.COMMAND_TYPE, CalendarCommands.CMD_CALENDAR_SET) {
            setCalendar(XiaomiProto.Calendar.newBuilder().setCalendarSync(XiaomiProto.CalendarSync.newBuilder().setDisabled(true)))
        }
    }

    private suspend fun sendEvents(events: List<Event>): Boolean {
        val sync = XiaomiProto.CalendarSync.newBuilder()
        for (e in events) {
            sync.addEvent(
                XiaomiProto.CalendarEvent.newBuilder()
                    .setTitle(e.title)
                    .setDescription(e.description)
                    .setLocation(e.location)
                    .setStart((e.beginMillis / 1000).toInt())
                    .setEnd((e.endMillis / 1000).toInt())
                    .setAllDay(e.allDay)
                    .setNotifyMinutesBefore(e.notifyMinutesBefore)
                    .build(),
            )
        }
        Log.d(TAG, "Syncing ${events.size} calendar events")
        val ok = BandChannel.send(CalendarCommands.COMMAND_TYPE, CalendarCommands.CMD_CALENDAR_SET) {
            setCalendar(XiaomiProto.Calendar.newBuilder().setCalendarSync(sync))
        }
        if (ok) FeatureStore.prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
        return ok
    }

    /** CalendarManager.getCalendarEvents(): Instances in [now, now + lookahead), BEGIN ASC. */
    private fun readEvents(s: Settings): List<Event> {
        val resolver = AppContext.context.contentResolver
        val start = System.currentTimeMillis()
        val end = start + s.lookaheadDays * 24L * 60 * 60 * 1000
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_ID,
        )
        val selection = if (s.includeAllDay) null else "${CalendarContract.Instances.ALL_DAY} != ?"
        val args = if (s.includeAllDay) null else arrayOf("1")
        val out = mutableListOf<Event>()
        resolver.query(uri, projection, selection, args, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.size < MAX_EVENTS) {
                val begin = c.getLong(0)
                var stop = c.getLong(1)
                if (stop == 0L) stop = begin // upstream parses DURATION; Instances.END is populated for instances
                val eventId = c.getLong(6)
                out += Event(
                    title = c.getString(2) ?: "",
                    description = c.getString(3) ?: "",
                    location = c.getString(4) ?: "",
                    beginMillis = begin,
                    endMillis = stop,
                    allDay = c.getInt(5) != 0,
                    notifyMinutesBefore = firstAlertMinutes(eventId),
                )
            }
        }
        return out
    }

    /** First METHOD_ALERT reminder of the event, in minutes (0 when none), like upstream. */
    private fun firstAlertMinutes(eventId: Long): Int = try {
        AppContext.context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { c ->
            var minutes = 0
            while (c.moveToNext()) {
                if (c.getInt(1) == CalendarContract.Reminders.METHOD_ALERT) {
                    minutes = c.getInt(0)
                    break
                }
            }
            minutes
        } ?: 0
    } catch (t: Throwable) {
        Log.w(TAG, "failed to get reminder for event", t)
        0
    }

    private fun schedule(enabled: Boolean) {
        val wm = WorkManager.getInstance(AppContext.context)
        if (enabled) {
            // docs/POWER.md "CalendarPushWorker": every 6 h, battery not low. Only pushes when already connected.
            val req = PeriodicWorkRequestBuilder<CalendarPushWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(WORK_NAME)
        }
    }

    /** XiaomiCalendarService.initialize(): lastSync.clear(); syncCalendar(). */
    suspend fun onConnected() {
        lastSync = null
        if (getSettings().enabled) sync() else sendDisabled()
    }
}

class CalendarPushWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        Log.i("MB9A_POWER", "calendar worker wake")
        CalendarService.sync()
        return Result.success()
    }
}
