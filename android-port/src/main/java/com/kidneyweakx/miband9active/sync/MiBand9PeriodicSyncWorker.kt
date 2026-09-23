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
 */
package com.kidneyweakx.miband9active.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.healthconnect.HealthConnectExporter
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class MiBand9PeriodicSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Log.i(POWER_TAG, "periodic sync wake (attempt $runAttemptCount)")

        val driver = try {
            DriverHolder.ensureConnected(CONNECT_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.i(TAG, "band not reachable, skipping this run: ${t.message}")
            return Result.success()
        }

        val files = try {
            ActivitySync.run(driver) { phase, progress ->
                Log.d(TAG, "sync $phase ${(progress * 100).toInt()}%")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "activity sync failed", t)
            0
        }
        Log.i(TAG, "background sync parsed $files activity files")

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
        private const val POWER_TAG = "MB9A_POWER"
        private const val UNIQUE_NAME = "miband9active-periodic-sync"
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val MIN_INTERVAL_MINUTES = 30L

        fun enable(context: Context, intervalMinutes: Long = MIN_INTERVAL_MINUTES) {
            val effective = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
            val request = PeriodicWorkRequestBuilder<MiBand9PeriodicSyncWorker>(
                effective, TimeUnit.MINUTES,
            ).setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
            )
        }

        fun disable(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
