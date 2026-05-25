/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. Inspired by Gadgetbridge's util.healthconnect package (AGPL-3.0).
 *
 *  Slim Health Connect writer for Mi Band 9 Active samples. Fitbit, Samsung
 *  Health, and Google Fit all read FROM Health Connect on Android 14+ — we
 *  are the writer.
 *
 *  See FEATURES.md §13 for the source → HC record mapping.
 */
package com.kidneyweakx.miband9active.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.kidneyweakx.miband9active.xiaomi.activity.NOT_MEASURED
import com.kidneyweakx.miband9active.xiaomi.activity.SleepStageSample
import com.kidneyweakx.miband9active.xiaomi.activity.SleepSummary
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivitySample
import androidx.health.connect.client.units.Percentage
import java.time.Instant
import java.time.ZoneOffset

enum class HealthConnectKind { STEPS, HEART_RATE, SPO2, SLEEP, ACTIVE_CALORIES, DISTANCE }

data class HealthConnectStatus(
    val installed: Boolean,
    val granted: Set<HealthConnectKind>,
)

object HealthConnectExporter {

    fun client(context: Context): HealthConnectClient? = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectClient.getOrCreate(context)
        else -> null
    }

    fun permissionsFor(kinds: Collection<HealthConnectKind>): Set<String> =
        kinds.flatMap { kind ->
            when (kind) {
                HealthConnectKind.STEPS -> listOf(HealthPermission.getWritePermission(StepsRecord::class))
                HealthConnectKind.HEART_RATE -> listOf(HealthPermission.getWritePermission(HeartRateRecord::class))
                HealthConnectKind.SPO2 -> listOf(HealthPermission.getWritePermission(OxygenSaturationRecord::class))
                HealthConnectKind.SLEEP -> listOf(HealthPermission.getWritePermission(SleepSessionRecord::class))
                HealthConnectKind.ACTIVE_CALORIES -> listOf(HealthPermission.getWritePermission(StepsRecord::class))
                HealthConnectKind.DISTANCE -> listOf(HealthPermission.getWritePermission(StepsRecord::class))
            }
        }.toSet()

    suspend fun status(context: Context, kinds: Collection<HealthConnectKind>): HealthConnectStatus {
        val client = client(context) ?: return HealthConnectStatus(installed = false, granted = emptySet())
        val granted = client.permissionController.getGrantedPermissions()
        val grantedKinds = kinds.filter { kind ->
            permissionsFor(listOf(kind)).all { it in granted }
        }.toSet()
        return HealthConnectStatus(installed = true, granted = grantedKinds)
    }

    /** Returns the number of HC records written. */
    suspend fun exportDay(
        context: Context,
        bandName: String,
        bandSerial: String,
        samples: List<XiaomiActivitySample>,
        sleepStages: List<SleepStageSample>,
        sleepSummary: SleepSummary?,
    ): Int {
        val client = client(context) ?: return 0
        val granted = client.permissionController.getGrantedPermissions()

        val metadata = Metadata.manualEntry()

        val records = buildList {
            // Heart rate samples (minute resolution) — one HeartRateRecord with N samples.
            val hrSamples = samples
                .filter { it.heartRate in 20..250 }
                .sortedBy { it.timestampSec }
                .map { HeartRateRecord.Sample(Instant.ofEpochSecond(it.timestampSec), it.heartRate.toLong()) }
            if (hrSamples.isNotEmpty() &&
                HealthPermission.getWritePermission(HeartRateRecord::class) in granted
            ) {
                add(
                    HeartRateRecord(
                        startTime = hrSamples.first().time,
                        startZoneOffset = ZoneOffset.UTC,
                        endTime = hrSamples.last().time,
                        endZoneOffset = ZoneOffset.UTC,
                        samples = hrSamples,
                        metadata = metadata,
                    ),
                )
            }

            // SpO2 — one record per non-zero sample.
            if (HealthPermission.getWritePermission(OxygenSaturationRecord::class) in granted) {
                samples
                    .filter { it.spo2 != NOT_MEASURED && it.spo2 in 70..100 }
                    .forEach { s ->
                        val t = Instant.ofEpochSecond(s.timestampSec)
                        add(
                            OxygenSaturationRecord(
                                time = t,
                                zoneOffset = ZoneOffset.UTC,
                                percentage = Percentage(s.spo2.toDouble()),
                                metadata = metadata,
                            ),
                        )
                    }
            }

            // Steps — coalesce same-day samples into a single steps record per
            // minute slice. Health Connect rejects overlapping ranges from the
            // same source so we keep slices disjoint.
            if (HealthPermission.getWritePermission(StepsRecord::class) in granted) {
                samples
                    .filter { it.steps != NOT_MEASURED && it.steps > 0 }
                    .forEach { s ->
                        val start = Instant.ofEpochSecond(s.timestampSec)
                        val end = start.plusSeconds(60)
                        add(
                            StepsRecord(
                                startTime = start,
                                startZoneOffset = ZoneOffset.UTC,
                                endTime = end,
                                endZoneOffset = ZoneOffset.UTC,
                                count = s.steps.toLong(),
                                metadata = metadata,
                            ),
                        )
                    }
            }

            // Sleep — one session covering the night, with stage segments.
            val summary = sleepSummary
            if (summary != null && HealthPermission.getWritePermission(SleepSessionRecord::class) in granted) {
                val start = Instant.ofEpochSecond(summary.bedTimeSec)
                val end = Instant.ofEpochSecond(summary.wakeupTimeSec)
                val stages = sleepStages.zipWithNext { a, b ->
                    SleepSessionRecord.Stage(
                        startTime = Instant.ofEpochSecond(a.timestampSec),
                        endTime = Instant.ofEpochSecond(b.timestampSec),
                        stage = when (a.kind) {
                            SleepStageSample.Kind.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
                            SleepStageSample.Kind.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
                            SleepStageSample.Kind.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
                            SleepStageSample.Kind.REM -> SleepSessionRecord.STAGE_TYPE_REM
                            SleepStageSample.Kind.UNKNOWN -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
                        },
                    )
                }
                add(
                    SleepSessionRecord(
                        startTime = start,
                        startZoneOffset = ZoneOffset.UTC,
                        endTime = end,
                        endZoneOffset = ZoneOffset.UTC,
                        title = "$bandName ($bandSerial)",
                        stages = stages,
                        metadata = metadata,
                    ),
                )
            }
        }

        if (records.isEmpty()) return 0
        client.insertRecords(records)
        return records.size
    }
}
