/*  Copyright (C) 2025 LLan, Gideon Zenz; 2026 José Rebelo                    (Gadgetbridge util/healthconnect)
 *
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
 * Health Connect writer. Record mapping follows Gadgetbridge's syncers:
 *   StepsSyncer / ActiveCaloriesSyncer / DistanceSyncer — one record per
 *     minute sample with a positive value (kcal → Energy.kilocalories,
 *     cm → Length.meters(cm / 100)).
 *   HeartRateSyncer — samples 20..250 bpm grouped into HeartRateRecords.
 *   Spo2Syncer — one OxygenSaturationRecord per reading, 0 < v <= 100;
 *     manual readings use activelyRecorded metadata.
 *   RestingHeartRateSyncer — daily summary resting HR, 20..250.
 *   SleepSyncer — one SleepSessionRecord per session with stage segments
 *     (deep/light/REM/awake; unknown stages skipped).
 *   RecordedWorkoutSyncer — ExerciseSessionRecord per workout.
 * Zone offsets come from the phone's zone at each instant
 * (offset.rules.getOffset(instant), as upstream).
 *
 * Deviations (documented):
 *   - Idempotency: upstream advances a per-type "last synced" cursor; we
 *     instead stamp every record with a deterministic clientRecordId and a
 *     monotonic clientRecordVersion, so re-exporting a day upserts instead
 *     of duplicating, and a grown "today" file updates the same records.
 *   - Minute records cover [ts, ts+60): Xiaomi daily-detail timestamps are
 *     the start of the minute (upstream's generic syncer uses [ts-60, ts]).
 *   - HR records are grouped per UTC hour (stable ids) instead of by
 *     15-min gaps.
 *   - Sessions without stage data are written without stages rather than
 *     as all-light sleep.
 */
package com.kidneyweakx.miband9active.healthconnect

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import com.kidneyweakx.miband9active.SampleStore
import com.kidneyweakx.miband9active.xiaomi.activity.NOT_MEASURED
import com.kidneyweakx.miband9active.xiaomi.activity.SleepStageSample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.reflect.KClass

enum class HealthConnectKind {
    STEPS, HEART_RATE, SPO2, SLEEP, ACTIVE_CALORIES, DISTANCE, RESTING_HEART_RATE, EXERCISE,
}

enum class HealthConnectAvailability { AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }

data class HealthConnectStatus(
    val availability: HealthConnectAvailability,
    val granted: Set<HealthConnectKind>,
) {
    val installed: Boolean get() = availability == HealthConnectAvailability.AVAILABLE
}

object HealthConnectExporter {
    private const val TAG = "MB9A_HealthConnect"

    /** HealthConnectUtils.CHUNK_SIZE */
    private const val CHUNK_SIZE = 200

    const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

    private val DEVICE = Device(
        manufacturer = "Xiaomi",
        model = "Smart Band 9 Active",
        type = Device.TYPE_FITNESS_BAND,
    )

    fun recordClass(kind: HealthConnectKind): KClass<out Record> = when (kind) {
        HealthConnectKind.STEPS -> StepsRecord::class
        HealthConnectKind.HEART_RATE -> HeartRateRecord::class
        HealthConnectKind.SPO2 -> OxygenSaturationRecord::class
        HealthConnectKind.SLEEP -> SleepSessionRecord::class
        HealthConnectKind.ACTIVE_CALORIES -> ActiveCaloriesBurnedRecord::class
        HealthConnectKind.DISTANCE -> DistanceRecord::class
        HealthConnectKind.RESTING_HEART_RATE -> RestingHeartRateRecord::class
        HealthConnectKind.EXERCISE -> ExerciseSessionRecord::class
    }

    fun writePermission(kind: HealthConnectKind): String = HealthPermission.getWritePermission(recordClass(kind))

    fun permissionsFor(kinds: Collection<HealthConnectKind>): Set<String> = kinds.map { writePermission(it) }.toSet()

    /** HealthConnectClientProvider.healthConnectInit semantics. */
    fun availability(context: Context): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UPDATE_REQUIRED
            else -> HealthConnectAvailability.UNAVAILABLE
        }

    fun client(context: Context): HealthConnectClient? =
        if (availability(context) == HealthConnectAvailability.AVAILABLE) {
            HealthConnectClient.getOrCreate(context, PROVIDER_PACKAGE)
        } else {
            null
        }

    suspend fun status(context: Context): HealthConnectStatus {
        val availability = availability(context)
        val client = client(context) ?: return HealthConnectStatus(availability, emptySet())
        val granted = client.permissionController.getGrantedPermissions()
        return HealthConnectStatus(
            availability,
            HealthConnectKind.entries.filter { writePermission(it) in granted }.toSet(),
        )
    }

    /** Export one local day. Returns records written (upserted). */
    suspend fun exportDay(context: Context, date: LocalDate): Int = exportRange(context, date, date)

    /**
     * Export every local day in [from]..[to] (inclusive). Only kinds with a
     * granted WRITE permission are written. Returns records written; 0 when
     * HC is unavailable or nothing is granted.
     */
    suspend fun exportRange(context: Context, from: LocalDate, to: LocalDate): Int {
        val client = client(context) ?: return 0
        val granted = client.permissionController.getGrantedPermissions()
        val kinds = HealthConnectKind.entries.filter { writePermission(it) in granted }.toSet()
        if (kinds.isEmpty()) return 0

        val zone = ZoneId.systemDefault()
        fun off(sec: Long): ZoneOffset = zone.rules.getOffset(Instant.ofEpochSecond(sec))
        val nowSec = System.currentTimeMillis() / 1000L
        val fromSec = SampleStore.dayRange(from).first
        val toSec = minOf(SampleStore.dayRange(to).second, nowSec + 1)

        val records = ArrayList<Record>()
        val minutes = SampleStore.loadActivity(fromSec, toSec)

        // Per-minute cumulative records.
        for (s in minutes) {
            val start = s.timestampSec
            val end = start + 60
            if (end > nowSec) continue // minute not finished yet
            val startI = Instant.ofEpochSecond(start)
            val endI = Instant.ofEpochSecond(end)
            if (HealthConnectKind.STEPS in kinds && s.steps != NOT_MEASURED && s.steps > 0) {
                records += StepsRecord(
                    startTime = startI,
                    startZoneOffset = off(start),
                    endTime = endI,
                    endZoneOffset = off(end),
                    count = s.steps.toLong(),
                    metadata = auto("steps-$start", s.steps.toLong()),
                )
            }
            if (HealthConnectKind.ACTIVE_CALORIES in kinds && s.activeCalories != NOT_MEASURED && s.activeCalories > 0) {
                records += ActiveCaloriesBurnedRecord(
                    startTime = startI,
                    startZoneOffset = off(start),
                    endTime = endI,
                    endZoneOffset = off(end),
                    energy = Energy.kilocalories(s.activeCalories.toDouble()),
                    metadata = auto("kcal-$start", s.activeCalories.toLong()),
                )
            }
            if (HealthConnectKind.DISTANCE in kinds && s.distanceCm != NOT_MEASURED && s.distanceCm > 0) {
                records += DistanceRecord(
                    startTime = startI,
                    startZoneOffset = off(start),
                    endTime = endI,
                    endZoneOffset = off(end),
                    distance = Length.meters(s.distanceCm / 100.0),
                    metadata = auto("dist-$start", s.distanceCm.toLong()),
                )
            }
        }

        if (HealthConnectKind.HEART_RATE in kinds) {
            SampleStore.heartRate(fromSec, toSec)
                .groupBy { it.timestampSec / 3600 }
                .forEach { (hour, points) ->
                    val sorted = points.sortedBy { it.timestampSec }.distinctBy { it.timestampSec }
                    val first = sorted.first().timestampSec
                    var last = sorted.last().timestampSec
                    if (last == first) last += 1 // HC needs a positive duration
                    records += HeartRateRecord(
                        startTime = Instant.ofEpochSecond(first),
                        startZoneOffset = off(first),
                        endTime = Instant.ofEpochSecond(last),
                        endZoneOffset = off(last),
                        samples = sorted.map { HeartRateRecord.Sample(Instant.ofEpochSecond(it.timestampSec), it.bpm.toLong()) },
                        metadata = auto("hr-${hour * 3600}", sorted.size.toLong()),
                    )
                }
        }

        if (HealthConnectKind.SPO2 in kinds) {
            for (p in SampleStore.spo2(fromSec, toSec)) {
                records += OxygenSaturationRecord(
                    time = Instant.ofEpochSecond(p.timestampSec),
                    zoneOffset = off(p.timestampSec),
                    percentage = Percentage(p.value.toDouble()),
                    metadata = if (p.manual) {
                        active("spo2m-${p.timestampSec}", 1)
                    } else {
                        auto("spo2-${p.timestampSec}", 1)
                    },
                )
            }
        }

        if (HealthConnectKind.RESTING_HEART_RATE in kinds) {
            var d = from
            while (!d.isAfter(to)) {
                val summary = SampleStore.loadDailySummary(d)
                val rhr = summary?.hrResting
                if (summary != null && rhr != null && rhr in SampleStore.HR_VALID && summary.timestampSec <= nowSec) {
                    records += RestingHeartRateRecord(
                        time = Instant.ofEpochSecond(summary.timestampSec),
                        zoneOffset = off(summary.timestampSec),
                        beatsPerMinute = rhr.toLong(),
                        metadata = auto("rhr-${summary.timestampSec}", rhr.toLong()),
                    )
                }
                d = d.plusDays(1)
            }
        }

        if (HealthConnectKind.SLEEP in kinds) {
            for (session in SampleStore.sleepSessionsWakingBetween(fromSec, toSec)) {
                val bed = session.bedTimeSec
                val wake = session.wakeupTimeSec
                if (wake <= bed) continue
                val raw = SampleStore.sleepStages(bed, wake)
                val stages = ArrayList<SleepSessionRecord.Stage>()
                for ((i, st) in raw.withIndex()) {
                    val type = when (st.kind) {
                        SleepStageSample.Kind.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
                        SleepStageSample.Kind.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
                        SleepStageSample.Kind.REM -> SleepSessionRecord.STAGE_TYPE_REM
                        SleepStageSample.Kind.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
                        SleepStageSample.Kind.UNKNOWN -> null
                    } ?: continue
                    val start = st.timestampSec.coerceIn(bed, wake)
                    val end = (if (i + 1 < raw.size) raw[i + 1].timestampSec else wake).coerceIn(bed, wake)
                    if (end > start) {
                        stages += SleepSessionRecord.Stage(Instant.ofEpochSecond(start), Instant.ofEpochSecond(end), type)
                    }
                }
                records += SleepSessionRecord(
                    startTime = Instant.ofEpochSecond(bed),
                    startZoneOffset = off(bed),
                    endTime = Instant.ofEpochSecond(wake),
                    endZoneOffset = off(wake),
                    title = "Xiaomi Smart Band 9 Active",
                    stages = stages,
                    // Grows as the band extends the session or adds stages.
                    metadata = auto("sleep-$bed", wake * 1000L + stages.size.coerceAtMost(999)),
                )
            }
        }

        if (HealthConnectKind.EXERCISE in kinds) {
            for (w in SampleStore.workoutsStartingBetween(fromSec, toSec)) {
                val end = w.endSec ?: continue
                if (end <= w.startSec || end > nowSec) continue
                records += ExerciseSessionRecord(
                    startTime = Instant.ofEpochSecond(w.startSec),
                    startZoneOffset = off(w.startSec),
                    endTime = Instant.ofEpochSecond(end),
                    endZoneOffset = off(end),
                    exerciseType = exerciseType(w.workoutType, w.fileSubtype),
                    title = "Xiaomi Smart Band 9 Active",
                    metadata = active("workout-${w.startSec}", end),
                )
            }
        }

        if (records.isEmpty()) return 0
        var written = 0
        for (chunk in records.chunked(CHUNK_SIZE)) {
            // SecurityException (permission revoked mid-export) propagates.
            client.insertRecords(chunk)
            written += chunk.size
        }
        Log.i(TAG, "exported $written records for $from..$to")
        return written
    }

    /** Delete everything this app wrote (HC only lets an app delete its own data). */
    suspend fun deleteAllWritten(context: Context): Int {
        val client = client(context) ?: return 0
        val granted = client.permissionController.getGrantedPermissions()
        var types = 0
        for (kind in HealthConnectKind.entries) {
            if (writePermission(kind) !in granted) continue
            runCatching {
                client.deleteRecords(recordClass(kind), TimeRangeFilter.after(Instant.EPOCH))
                types++
            }.onFailure { Log.w(TAG, "failed to delete $kind records", it) }
        }
        return types
    }

    suspend fun revokeAll(context: Context) {
        client(context)?.permissionController?.revokeAllPermissions()
    }

    private fun auto(id: String, version: Long): Metadata = Metadata.autoRecorded(
        clientRecordId = "mb9a-$id",
        clientRecordVersion = version,
        device = DEVICE,
    )

    private fun active(id: String, version: Long): Metadata = Metadata.activelyRecorded(
        clientRecordId = "mb9a-$id",
        clientRecordVersion = version,
        device = DEVICE,
    )

    /** WorkoutSyncerUtils activity-kind → exercise-type mapping, keyed by Xiaomi codes. */
    private fun exerciseType(xiaomiType: Int?, subtypeCode: Int): Int {
        when (xiaomiType) {
            1, 5 -> return ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            2, 15 -> return ExerciseSessionRecord.EXERCISE_TYPE_WALKING
            3, 4 -> return ExerciseSessionRecord.EXERCISE_TYPE_HIKING
            6 -> return ExerciseSessionRecord.EXERCISE_TYPE_BIKING
            7 -> return ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
            9 -> return ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
            10 -> return ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER
            11 -> return ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
            12 -> return ExerciseSessionRecord.EXERCISE_TYPE_YOGA
            13 -> return ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
            14 -> return ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS
            16 -> return ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
        }
        return when (subtypeCode) {
            0x01 -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            0x02, 0x16 -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
            0x03 -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
            0x06, 0x17 -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
            0x07 -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
            0x09 -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
            0x10 -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
            0x0B -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
            0x0D -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
            0x0E -> ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS
            else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        }
    }
}
