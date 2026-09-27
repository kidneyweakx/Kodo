/*
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * Periodic background sync (docs/POWER.md "DailySummarySyncWorker"):
 *   - PeriodicWorkRequest, interval >= 30 min, requiresBatteryNotLow.
 *   - No network constraint (BLE only). No foreground service: the whole
 *     job is bounded well inside WorkManager's 10-minute execution window
 *     (connect <= 30 s, ActivitySync <= 180 s, HC export seconds).
 *   - Band out of range / BT off / not paired → Result.success() (no retry
 *     storm; the next periodic run tries again).
 *
 * Steps: DriverHolder.ensureConnected → ActivitySync.run → export yesterday
 * + today to Health Connect when at least one WRITE permission is granted.
 *
 * Link ownership: an already-up link is reused and never torn down. When the
 * band is down, ensureConnected() adopts/replaces the passive autoConnect
 * GATT client (the driver keeps exactly one) and, on failure, re-arms the
 * passive reconnect. ActivitySync.run() serialises with a user-started sync
 * (a second caller awaits the running exchange).
 *
 * The enabled/interval setting is persisted natively ([PREFS]) so it can be
 * re-applied idempotently at process start ([reconcile]) — covers app
 * upgrades, a restored backup and a cleared WorkManager database, with JS
 * not running at all.
 */
package com.kidneyweakx.miband9active.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kidneyweakx.miband9active.BandStore
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.healthconnect.HealthConnectExporter
import com.kidneyweakx.miband9active.xiaomi.protocol.PowerLog
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class MiBand9PeriodicSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /** Persisted user setting. */
    data class Setting(val enabled: Boolean, val intervalMinutes: Long)

    /** Live WorkManager view of the job (what the diagnostics screen shows). */
    data class WorkState(val enabled: Boolean, val intervalMinutes: Long, val nextRunAtMillis: Long?)

    override suspend fun doWork(): Result {
        PowerLog.event(PowerLog.WORKER, "periodic sync (attempt $runAttemptCount)", wakeup = true)

        if (BandStore.load() == null) {
            // Unpaired but the job survived (e.g. data cleared around forget()): stop waking up.
            Log.i(TAG, "no paired band — cancelling periodic sync")
            disable(applicationContext)
            return Result.success()
        }
        if (BandStore.userDisconnected) {
            // Respect an explicit disconnect: no connect from the background.
            Log.i(TAG, "user disconnected the band — skipping this run")
            return Result.success()
        }

        val driver = try {
            DriverHolder.ensureConnected(CONNECT_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.i(TAG, "band not reachable, skipping this run: ${t.message}")
            PowerLog.onSyncFinished("band not reachable: ${t.message}")
            // ensureConnected re-arms the passive reconnect itself; this covers BT_OFF /
            // an attempt that never started.
            DriverHolder.armReconnect("periodic worker")
            return Result.success()
        }

        val files = try {
            ActivitySync.run(driver) { phase, progress ->
                Log.d(TAG, "sync $phase ${(progress * 100).toInt()}%")
            }
        } catch (e: CancellationException) {
            PowerLog.onSyncFinished("background sync cancelled")
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "activity sync failed", t)
            PowerLog.onSyncFinished("background sync failed: ${t.message ?: t.javaClass.simpleName}")
            null
        }
        if (files != null) {
            Log.i(TAG, "background sync parsed $files activity files")
            PowerLog.onSyncFinished(null)
            PowerLog.event(PowerLog.SYNC, "background sync: $files file(s)")
        }

        try {
            val today = LocalDate.now()
            val written = HealthConnectExporter.exportRange(applicationContext, today.minusDays(1), today)
            if (written > 0) Log.i(TAG, "exported $written records to Health Connect")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // HC missing, permission revoked (SecurityException), provider busy…
            Log.w(TAG, "Health Connect export skipped", t)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "MB9A_PeriodicSync"
        private const val UNIQUE_NAME = "miband9active-periodic-sync"
        private const val CONNECT_TIMEOUT_MS = 30_000L
        const val MIN_INTERVAL_MINUTES = 30L

        private const val PREFS = "mb9a_periodic_sync"
        private const val K_ENABLED = "enabled"
        private const val K_INTERVAL = "intervalMinutes"

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)


        /** Persisted user setting; null when never set natively (pre-upgrade install). */
        fun setting(context: Context): Setting? {
            val p = prefs(context)
            if (!p.contains(K_ENABLED)) return null
            return Setting(
                p.getBoolean(K_ENABLED, false),
                p.getLong(K_INTERVAL, MIN_INTERVAL_MINUTES).coerceAtLeast(MIN_INTERVAL_MINUTES),
            )
        }

        private fun persist(context: Context, enabled: Boolean, intervalMinutes: Long) {
            prefs(context).edit().putBoolean(K_ENABLED, enabled).putLong(K_INTERVAL, intervalMinutes).apply()
        }

        fun enable(context: Context, intervalMinutes: Long = MIN_INTERVAL_MINUTES) {
            val effective = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
            persist(context, true, effective)
            // UPDATE: an interval change keeps the job id and its run history.
            enqueue(context, effective, ExistingPeriodicWorkPolicy.UPDATE)
            workStateCache = null
        }

        fun disable(context: Context) {
            val interval = setting(context)?.intervalMinutes ?: MIN_INTERVAL_MINUTES
            persist(context, false, interval)
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
            workStateCache = null
        }

        private fun enqueue(context: Context, minutes: Long, policy: ExistingPeriodicWorkPolicy) {
            val request = PeriodicWorkRequestBuilder<MiBand9PeriodicSyncWorker>(
                minutes, TimeUnit.MINUTES,
            ).setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, policy, request)
        }

        /**
         * Process start: make the WorkManager state match the persisted
         * setting. KEEP never resets an existing schedule, so this is free
         * when nothing changed. Pre-upgrade installs (no native setting)
         * adopt whatever WorkManager already has.
         */
        fun reconcile(context: Context) {
            if (BandStore.load() == null) return
            val s = setting(context)
            val wm = WorkManager.getInstance(context)
            if (s != null) {
                if (s.enabled) enqueue(context, s.intervalMinutes, ExistingPeriodicWorkPolicy.KEEP)
                else wm.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            val info = try {
                readWorkInfos(context, 2_000L)?.firstOrNull { !it.state.isFinished }
            } catch (t: Throwable) {
                Log.w(TAG, "could not read WorkManager state", t)
                return
            }
            if (info != null) {
                val minutes = info.periodicityInfo?.repeatIntervalMillis?.let { TimeUnit.MILLISECONDS.toMinutes(it) }
                    ?: MIN_INTERVAL_MINUTES
                persist(context, true, minutes.coerceAtLeast(MIN_INTERVAL_MINUTES))
                Log.i(TAG, "adopted existing periodic sync (${minutes}m)")
            } else {
                persist(context, false, MIN_INTERVAL_MINUTES)
            }
        }

        /**
         * WorkInfos of the unique job, or null on timeout. Uses the Flow API
         * (the ListenableFuture one needs Guava on the compile classpath).
         */
        private fun readWorkInfos(context: Context, timeoutMs: Long): List<WorkInfo>? = runBlocking {
            withTimeoutOrNull(timeoutMs) {
                WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).first()
            }
        }

        @Volatile private var workStateCache: Pair<Long, WorkState>? = null // (readAt, state)

        /**
         * The real WorkManager state: enabled = a non-finished unique job
         * exists; interval = its periodicity; next run =
         * WorkInfo.nextScheduleTimeMillis. Cached for 30 s; a miss blocks at
         * most 250 ms on WorkManager's DB, then falls back to the persisted
         * setting (next run unknown).
         */
        fun workState(context: Context): WorkState {
            val now = System.currentTimeMillis()
            workStateCache?.let { (at, v) -> if (now - at < 30_000L) return v }
            val fresh = try {
                val infos = readWorkInfos(context, 250L) ?: throw IllegalStateException("WorkManager read timed out")
                val info = infos.firstOrNull { !it.state.isFinished }
                if (info == null) {
                    WorkState(false, setting(context)?.intervalMinutes ?: MIN_INTERVAL_MINUTES, null)
                } else {
                    val minutes = info.periodicityInfo?.repeatIntervalMillis
                        ?.let { TimeUnit.MILLISECONDS.toMinutes(it) }
                        ?: setting(context)?.intervalMinutes
                        ?: MIN_INTERVAL_MINUTES
                    val next = info.nextScheduleTimeMillis.takeIf {
                        (info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED) &&
                            it in 1 until Long.MAX_VALUE
                    }
                    WorkState(true, minutes, next)
                }
            } catch (t: Throwable) {
                workStateCache?.second?.let { return it }
                val st = setting(context)
                return WorkState(st?.enabled ?: false, st?.intervalMinutes ?: MIN_INTERVAL_MINUTES, null)
            }
            workStateCache = now to fresh
            return fresh
        }
    }
}
