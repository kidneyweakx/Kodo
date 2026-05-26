/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  WorkManager job that runs every 30 minutes (when battery isn't low) and
 *  pulls the day's activity files from the band, parses them into the
 *  SampleStore, and writes them to Health Connect so Fitbit / Samsung
 *  Health / Google Fit see them too.
 *
 *  Per `docs/POWER.md`:
 *    - 30 min minimum interval (Android's minimum for PeriodicWorkRequest)
 *    - `requiresBatteryNotLow = true`
 *    - no network constraint (we talk BLE, not WiFi)
 *    - the band may be out of range; we tolerate failures silently.
 */
package com.kidneyweakx.miband9active.sync

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.SampleStore
import com.kidneyweakx.miband9active.healthconnect.HealthConnectExporter
import com.kidneyweakx.miband9active.xiaomi.activity.ParsedActivityFile
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileFetcher
import com.kidneyweakx.miband9active.xiaomi.auth.XiaomiCrypto
import com.kidneyweakx.miband9active.xiaomi.protocol.MiBand9BleDriver
import com.kidneyweakx.miband9active.xiaomi.services.HealthCommands
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

class MiBand9PeriodicSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val drv = DriverHolder.current ?: return Result.success()

        // Mirror the syncSince logic from HybridBandLink but with a tighter
        // 12 s grace window for background runs.
        val fetcher = XiaomiActivityFileFetcher()
        val collector: Job
        run {
            collector = collectorJob(drv, fetcher)
        }

        drv.sendCommand(
            XiaomiProto.Command.newBuilder()
                .setType(HealthCommands.COMMAND_TYPE)
                .setSubtype(2)
                .setHealth(
                    XiaomiProto.Health.newBuilder().setActivitySyncRequestToday(
                        XiaomiProto.ActivitySyncRequestToday.newBuilder().setUnknown1(0).build(),
                    ),
                )
                .build(),
        )

        // Wait briefly. The collector parses any files it sees.
        kotlinx.coroutines.delay(12_000)
        collector.cancel()

        // Export today's samples to Health Connect (Fitbit reads here).
        val today = java.time.LocalDate.now().toString()
        val samples = SampleStore.loadActivity(today)
        val (summary, stages) = SampleStore.loadSleep(today)
        runCatching {
            HealthConnectExporter.exportDay(
                context = applicationContext,
                bandName = "Mi Band 9 Active",
                bandSerial = today,
                samples = samples,
                sleepStages = stages,
                sleepSummary = summary,
            )
        }
        return Result.success()
    }

    private fun collectorJob(drv: MiBand9BleDriver, fetcher: XiaomiActivityFileFetcher): Job {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        fetcher.onFile = { parsed ->
            when (parsed) {
                is ParsedActivityFile.DailySamples -> SampleStore.persistActivity(parsed.samples)
                is ParsedActivityFile.Sleep -> {
                    val dayIso = parsed.fileId.timestamp.toInstant().toString().substring(0, 10)
                    SampleStore.persistSleep(dayIso, parsed.sleep.summary, parsed.sleep.stages)
                }
                is ParsedActivityFile.Workout -> SampleStore.persistWorkout(parsed.fileId, parsed.fields)
                is ParsedActivityFile.Unknown -> Unit
            }
        }
        return scope.launch {
            drv.activityChunks.collect { chunk -> fetcher.addChunk(chunk) }
        }
    }

    companion object {
        private const val UNIQUE_NAME = "miband9active-periodic-sync"

        fun enable(context: Context, intervalMinutes: Long = 30) {
            val effective = intervalMinutes.coerceAtLeast(30)
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
