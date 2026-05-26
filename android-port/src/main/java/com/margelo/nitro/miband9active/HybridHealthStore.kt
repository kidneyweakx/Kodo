/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.SampleStore
import com.kidneyweakx.miband9active.xiaomi.activity.NOT_MEASURED
import com.kidneyweakx.miband9active.xiaomi.activity.SleepStageSample
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileId
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivitySample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class HybridHealthStore : HybridHybridHealthStoreSpec() {

    override fun getDailySummary(dateIso: String): Variant_NullType_HealthDailySummary {
        val samples = SampleStore.loadActivity(dateIso)
        val (sleepSummary, _) = SampleStore.loadSleep(dateIso)
        if (samples.isEmpty() && sleepSummary == null) return Defaults.SUMMARY
        return Variant_NullType_HealthDailySummary.create(buildSummary(dateIso, samples, sleepSummary))
    }

    override fun getDailySummariesRange(fromIso: String, toIso: String): Array<HealthDailySummary> {
        val from = LocalDate.parse(fromIso, ISO_DATE)
        val to = LocalDate.parse(toIso, ISO_DATE)
        val out = mutableListOf<HealthDailySummary>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            val dayIso = cursor.format(ISO_DATE)
            val samples = SampleStore.loadActivity(dayIso)
            val (sleep, _) = SampleStore.loadSleep(dayIso)
            if (samples.isNotEmpty() || sleep != null) {
                out += buildSummary(dayIso, samples, sleep)
            }
            cursor = cursor.plusDays(1)
        }
        return out.toTypedArray()
    }

    override fun getHeartRateSeries(dateIso: String): Array<HeartRateSample> =
        SampleStore.loadActivity(dateIso)
            .filter { it.heartRate != NOT_MEASURED && it.heartRate in 20..250 }
            .map { HeartRateSample(takenAt = isoOf(it.timestampSec), bpm = it.heartRate.toDouble()) }
            .toTypedArray()

    override fun getStressSeries(dateIso: String): Array<StressSample> =
        SampleStore.loadActivity(dateIso)
            .filter { it.stress != NOT_MEASURED && it.stress in 0..100 }
            .map {
                StressSample(
                    takenAt = isoOf(it.timestampSec),
                    score = it.stress.toDouble(),
                    bucket = when {
                        it.stress <= 25 -> StressBucket.RELAXED
                        it.stress <= 50 -> StressBucket.MILD
                        it.stress <= 80 -> StressBucket.MODERATE
                        else -> StressBucket.HIGH
                    },
                )
            }
            .toTypedArray()

    override fun getSleepSegments(dateIso: String): Array<SleepSegment> {
        val (_, stages) = SampleStore.loadSleep(dateIso)
        // Zip neighbouring samples to form [a→b] segments.
        return stages.zipWithNext { a, b ->
            SleepSegment(
                startedAt = isoOf(a.timestampSec),
                endedAt = isoOf(b.timestampSec),
                stage = when (a.kind) {
                    SleepStageSample.Kind.AWAKE -> SleepStage.AWAKE
                    SleepStageSample.Kind.LIGHT -> SleepStage.LIGHT
                    SleepStageSample.Kind.DEEP -> SleepStage.DEEP
                    SleepStageSample.Kind.REM -> SleepStage.REM
                    SleepStageSample.Kind.UNKNOWN -> SleepStage.AWAKE
                },
            )
        }.toTypedArray()
    }

    override fun getRecentWorkouts(limit: Double): Array<WorkoutSummary> {
        val n = limit.toInt().coerceAtLeast(1).coerceAtMost(500)
        val rows = SampleStore.loadRecentWorkouts(n)
        return rows.mapNotNull { o ->
            val startSec = o.optLong("startSec", 0L)
            if (startSec == 0L) return@mapNotNull null
            val endSec = if (o.has("endSec")) o.optLong("endSec") else null
            val durationSec = (o.optInt("activeSec", 0).takeIf { it > 0 }
                ?: (endSec?.let { (it - startSec).toInt().coerceAtLeast(0) })
                ?: 0).toDouble()
            val subtypeCode = o.optInt("subtype", -1)
            val kind = workoutKindFor(subtypeCode)
            WorkoutSummary(
                id = o.optString("id"),
                kind = kind,
                startedAt = Instant.ofEpochSecond(startSec).toString(),
                endedAt = endSec?.let {
                    Variant_NullType_String.create(Instant.ofEpochSecond(it).toString())
                },
                durationSeconds = durationSec,
                kcal = if (o.has("kcal")) Variant_NullType_Double.create(o.optDouble("kcal")) else null,
                distanceMeters = if (o.has("distM")) Variant_NullType_Double.create(o.optDouble("distM")) else null,
                hrAvg = if (o.has("hrAvg")) Variant_NullType_Double.create(o.optDouble("hrAvg")) else null,
                hrMax = if (o.has("hrMax")) Variant_NullType_Double.create(o.optDouble("hrMax")) else null,
                hrMin = if (o.has("hrMin")) Variant_NullType_Double.create(o.optDouble("hrMin")) else null,
                steps = if (o.has("steps")) Variant_NullType_Double.create(o.optDouble("steps")) else null,
            )
        }.toTypedArray()
    }

    private fun workoutKindFor(subtypeCode: Int): WorkoutKind {
        // Map XiaomiActivityFileId.Subtype.code → WorkoutKind. See
        // XiaomiActivityFileId.kt for the canonical code table.
        return when (subtypeCode) {
            0x01 -> WorkoutKind.RUNNING          // SPORTS_OUTDOOR_RUNNING
            0x02 -> WorkoutKind.WALKING          // SPORTS_OUTDOOR_WALKING_V1
            0x16 -> WorkoutKind.WALKING          // SPORTS_OUTDOOR_WALKING_V2
            0x03 -> WorkoutKind.TREADMILL        // SPORTS_TREADMILL
            0x06 -> WorkoutKind.OUTDOOR_CYCLING  // SPORTS_OUTDOOR_CYCLING_V2
            0x17 -> WorkoutKind.OUTDOOR_CYCLING  // SPORTS_OUTDOOR_CYCLING
            0x07 -> WorkoutKind.INDOOR_CYCLING   // SPORTS_INDOOR_CYCLING
            0x08 -> WorkoutKind.FREESTYLE        // SPORTS_FREESTYLE
            0x09 -> WorkoutKind.POOL_SWIMMING    // SPORTS_POOL_SWIMMING
            0x10 -> WorkoutKind.HIIT             // SPORTS_HIIT
            0x0B -> WorkoutKind.ELLIPTICAL       // SPORTS_ELLIPTICAL
            0x0D -> WorkoutKind.ROWING           // SPORTS_ROWING
            0x0E -> WorkoutKind.JUMP_ROPE        // SPORTS_JUMP_ROPING
            else -> WorkoutKind.OTHER
        }
    }

    override fun clearAll() = SampleStore.clearAll()

    // ----------------------------------------------------------------- internals

    private fun buildSummary(
        dateIso: String,
        samples: List<XiaomiActivitySample>,
        sleep: com.kidneyweakx.miband9active.xiaomi.activity.SleepSummary?,
    ): HealthDailySummary {
        val steps = samples.sumOf { if (it.steps == NOT_MEASURED) 0 else it.steps }
        val distance = samples.sumOf { if (it.distanceCm == NOT_MEASURED) 0 else it.distanceCm } / 100.0
        val kcal = samples.sumOf { if (it.activeCalories == NOT_MEASURED) 0 else it.activeCalories }.toDouble()
        val hrValues = samples.mapNotNull { if (it.heartRate == NOT_MEASURED) null else it.heartRate }
        val stressValues = samples.mapNotNull { if (it.stress == NOT_MEASURED) null else it.stress }
        val spo2Values = samples.mapNotNull { if (it.spo2 == NOT_MEASURED) null else it.spo2 }
        return HealthDailySummary(
            date = dateIso,
            steps = steps.toDouble(),
            distanceMeters = distance,
            activeCalories = kcal,
            restingHeartRate = hrValues.minOrNull()?.toDouble()?.let { Variant_NullType_Double.create(it) },
            averageHeartRate = if (hrValues.isEmpty()) null else Variant_NullType_Double.create(hrValues.average()),
            sleepMinutes = sleep?.totalMinutes?.toDouble()?.let { Variant_NullType_Double.create(it) },
            stressAverage = if (stressValues.isEmpty()) null else Variant_NullType_Double.create(stressValues.average()),
            spo2Average = if (spo2Values.isEmpty()) null else Variant_NullType_Double.create(spo2Values.average()),
            paiScore = null,
        )
    }

    private fun isoOf(epochSec: Long): String =
        Instant.ofEpochSecond(epochSec).toString()

    companion object {
        private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
