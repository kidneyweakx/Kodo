/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.SampleStore
import com.kidneyweakx.miband9active.xiaomi.activity.NOT_MEASURED
import com.kidneyweakx.miband9active.xiaomi.activity.SleepStageSample
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
