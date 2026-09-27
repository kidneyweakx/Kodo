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
 * Nitro facade over SampleStore. Every value returned comes from a sample
 * the band sent; absent data is null / empty (CLAUDE.md rule 8).
 */
package com.margelo.nitro.miband9active

import android.util.Log
import com.kidneyweakx.miband9active.SampleStore
import com.kidneyweakx.miband9active.xiaomi.activity.SleepNightAssembler
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileId
import com.margelo.nitro.core.NullType
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

class HybridHealthStore : HybridHybridHealthStoreSpec() {

    override val lastSampleAt: Variant_NullType_String
        get() {
            val sec = safe(null) { SampleStore.lastSampleAtSec() }
            return if (sec == null) {
                Variant_NullType_String.create(NullType.NULL)
            } else {
                Variant_NullType_String.create(iso(sec))
            }
        }

    override fun getDailySummary(dateIso: String): Variant_NullType_HealthDailySummary {
        val date = parseDate(dateIso) ?: return Defaults.SUMMARY
        val summary = safe(null) { buildSummary(date) } ?: return Defaults.SUMMARY
        return Variant_NullType_HealthDailySummary.create(summary)
    }

    override fun getDailySummariesRange(fromIso: String, toIso: String): Array<HealthDailySummary> {
        val from = parseDate(fromIso) ?: return emptyArray()
        val to = parseDate(toIso) ?: return emptyArray()
        if (to.isBefore(from) || from.plusDays(400).isBefore(to)) return emptyArray()
        val out = ArrayList<HealthDailySummary>()
        var d = from
        while (!d.isAfter(to)) {
            safe(null) { buildSummary(d) }?.let { out += it }
            d = d.plusDays(1)
        }
        return out.toTypedArray()
    }

    override fun getHeartRateSeries(dateIso: String): Array<HeartRateSample> {
        val date = parseDate(dateIso) ?: return emptyArray()
        val (from, to) = SampleStore.dayRange(date)
        return safe(emptyList()) { SampleStore.heartRate(from, to) }
            .map { HeartRateSample(takenAt = iso(it.timestampSec), bpm = it.bpm.toDouble()) }
            .toTypedArray()
    }

    override fun getStressSeries(dateIso: String): Array<StressSample> {
        val date = parseDate(dateIso) ?: return emptyArray()
        val (from, to) = SampleStore.dayRange(date)
        return safe(emptyList()) { SampleStore.stress(from, to) }
            .map {
                StressSample(
                    takenAt = iso(it.timestampSec),
                    score = it.value.toDouble(),
                    // XiaomiCoordinator.getStressRanges(): 1 / 26 / 51 / 81
                    bucket = when {
                        it.value <= 25 -> StressBucket.RELAXED
                        it.value <= 50 -> StressBucket.MILD
                        it.value <= 80 -> StressBucket.MODERATE
                        else -> StressBucket.HIGH
                    },
                )
            }
            .toTypedArray()
    }

    override fun getSpo2Series(dateIso: String): Array<Spo2Sample> {
        val date = parseDate(dateIso) ?: return emptyArray()
        val (from, to) = SampleStore.dayRange(date)
        return safe(emptyList()) { SampleStore.spo2(from, to) }
            .map { Spo2Sample(takenAt = iso(it.timestampSec), percent = it.value.toDouble(), manual = it.manual) }
            .toTypedArray()
    }

    override fun getSleepNight(dateIso: String): Variant_NullType_SleepNight {
        val date = parseDate(dateIso) ?: return Variant_NullType_SleepNight.create(NullType.NULL)
        val night = safe(null) { SampleStore.sleepNight(date)?.let(::toBridge) }
            ?: return Variant_NullType_SleepNight.create(NullType.NULL)
        return Variant_NullType_SleepNight.create(night)
    }

    override fun getSleepNights(fromIso: String, toIso: String): Array<SleepNight> {
        val from = parseDate(fromIso) ?: return emptyArray()
        val to = parseDate(toIso) ?: return emptyArray()
        if (to.isBefore(from) || from.plusDays(400).isBefore(to)) return emptyArray()
        return safe(emptyList()) { SampleStore.sleepNights(from, to).map(::toBridge) }.toTypedArray()
    }

    override fun getSleepSession(dateIso: String): Variant_NullType_SleepSessionSummary {
        val date = parseDate(dateIso) ?: return Variant_NullType_SleepSessionSummary.create(NullType.NULL)
        val s = safe(null) { SampleStore.sleepNight(date)?.main }
            ?: return Variant_NullType_SleepSessionSummary.create(NullType.NULL)
        return Variant_NullType_SleepSessionSummary.create(
            SleepSessionSummary(
                bedAt = iso(s.startSec),
                wakeAt = iso(s.endSec),
                totalMinutes = nd(s.totalMinutes),
                deepMinutes = nd(s.deepMinutes),
                lightMinutes = nd(s.lightMinutes),
                remMinutes = nd(s.remMinutes),
                awakeMinutes = nd(s.awakeMinutes),
            ),
        )
    }

    override fun getSleepSegments(dateIso: String): Array<SleepSegment> {
        val date = parseDate(dateIso) ?: return emptyArray()
        val s = safe(null) { SampleStore.sleepNight(date)?.main } ?: return emptyArray()
        return segments(s)
    }

    override fun getRecentWorkouts(limit: Double): Array<WorkoutSummary> {
        val n = limit.toInt().coerceIn(1, 500)
        return safe(emptyList()) { SampleStore.recentWorkouts(n * 2) }
            .mapNotNull { w ->
                val end = w.endSec?.takeIf { it > w.startSec }
                val duration = w.activeSeconds?.takeIf { it > 0 }?.toLong() ?: end?.let { it - w.startSec }
                // Undecoded layout (unknown sport/version): nothing real to show yet.
                if (duration == null) return@mapNotNull null
                WorkoutSummary(
                    id = w.startSec.toString(),
                    kind = workoutKind(w.workoutType, w.fileSubtype),
                    startedAt = iso(w.startSec),
                    endedAt = end?.let { Variant_NullType_String.create(iso(it)) },
                    durationSeconds = duration.toDouble(),
                    kcal = w.kcal?.let { Variant_NullType_Double.create(it.toDouble()) },
                    distanceMeters = w.distanceMeters?.takeIf { it > 0 }?.let { Variant_NullType_Double.create(it.toDouble()) },
                    hrAvg = w.hrAvg?.takeIf { it in SampleStore.HR_VALID }?.let { Variant_NullType_Double.create(it.toDouble()) },
                    hrMax = w.hrMax?.takeIf { it in SampleStore.HR_VALID }?.let { Variant_NullType_Double.create(it.toDouble()) },
                    hrMin = w.hrMin?.takeIf { it in SampleStore.HR_VALID }?.let { Variant_NullType_Double.create(it.toDouble()) },
                    steps = w.steps?.let { Variant_NullType_Double.create(it.toDouble()) },
                )
            }
            .take(n)
            .toTypedArray()
    }

    override fun clearAll() {
        safe(Unit) { SampleStore.clearAll() }
    }

    // ----------------------------------------------------------------- internals

    private fun buildSummary(date: LocalDate): HealthDailySummary? {
        val agg = SampleStore.dayAggregate(date) ?: return null
        // HealthDailySummary.steps is non-nullable: without step data the day
        // has no summary (render "—"), never a 0 pretending to be data.
        val steps = agg.steps ?: return null
        return HealthDailySummary(
            date = date.toString(),
            steps = steps.toDouble(),
            distanceMeters = agg.distanceMeters ?: 0.0,
            activeCalories = agg.activeCalories ?: 0.0,
            restingHeartRate = nd(agg.restingHeartRate),
            averageHeartRate = ndD(agg.averageHeartRate),
            sleepMinutes = nd(agg.sleepMinutes),
            stressAverage = ndD(agg.stressAverage),
            spo2Average = ndD(agg.spo2Average),
            paiScore = Variant_NullType_Double.create(NullType.NULL),
        )
    }

    private fun toBridge(n: SleepNightAssembler.Night): SleepNight {
        val m = n.main
        val v = SampleStore.vitals(m.startSec, m.endSec)
        return SleepNight(
            date = n.date.toString(),
            bedAt = iso(m.startSec),
            wakeAt = iso(m.endSec),
            totalMinutes = m.totalMinutes.toDouble(),
            deepMinutes = nd(m.deepMinutes),
            lightMinutes = nd(m.lightMinutes),
            remMinutes = nd(m.remMinutes),
            awakeMinutes = nd(m.awakeMinutes),
            awakeCount = nd(m.awakeCount),
            segments = segments(m),
            // The v4+ "sleep quality" byte is unverified upstream (read, never
            // stored or shown by Gadgetbridge) — not a score we can vouch for.
            score = nd(null),
            avgHeartRate = ndD(v.avgHr?.let { Math.round(it).toDouble() }),
            lowestHeartRate = nd(v.minHr),
            avgSpo2 = ndD(v.avgSpo2?.let { Math.round(it).toDouble() }),
            lowestSpo2 = nd(v.minSpo2),
            naps = n.naps.map {
                SleepNap(bedAt = iso(it.startSec), wakeAt = iso(it.endSec), totalMinutes = it.totalMinutes.toDouble())
            }.toTypedArray(),
        )
    }

    private fun segments(s: SleepNightAssembler.Session): Array<SleepSegment> =
        s.displaySegments().mapNotNull { sp ->
            val stage = when (sp.kind) {
                SleepNightAssembler.Kind.AWAKE -> SleepStage.AWAKE
                SleepNightAssembler.Kind.LIGHT -> SleepStage.LIGHT
                SleepNightAssembler.Kind.DEEP -> SleepStage.DEEP
                SleepNightAssembler.Kind.REM -> SleepStage.REM
                else -> null
            } ?: return@mapNotNull null
            SleepSegment(startedAt = iso(sp.startSec), endedAt = iso(sp.endSec), stage = stage)
        }.toTypedArray()

    private fun workoutKind(xiaomiType: Int?, subtypeCode: Int): WorkoutKind {
        if (xiaomiType != null) {
            // XiaomiWorkoutType.fromCode() codes.
            when (xiaomiType) {
                1, 5 -> return WorkoutKind.RUNNING       // outdoor running, trail run
                2, 15 -> return WorkoutKind.WALKING      // walking, outdoor walking
                6 -> return WorkoutKind.OUTDOOR_CYCLING
                7 -> return WorkoutKind.INDOOR_CYCLING
                8 -> return WorkoutKind.FREESTYLE
                9 -> return WorkoutKind.POOL_SWIMMING
                11 -> return WorkoutKind.ELLIPTICAL
                13 -> return WorkoutKind.ROWING
                14 -> return WorkoutKind.JUMP_ROPE
                16 -> return WorkoutKind.HIIT
            }
            // Known sport outside our kinds (yoga, hiking, …) — fall through to
            // the file subtype only if it is more specific than "freestyle".
            if (subtypeCode == XiaomiActivityFileId.Subtype.SPORTS_FREESTYLE.code ||
                subtypeCode == XiaomiActivityFileId.Subtype.SPORTS_OUTDOOR_WALKING_V2.code ||
                subtypeCode == XiaomiActivityFileId.Subtype.SPORTS_OUTDOOR_CYCLING.code
            ) {
                return WorkoutKind.OTHER
            }
        }
        // WorkoutSummaryParser.updateSummaryFromData() activity kinds per subtype.
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

    private fun nd(v: Int?): Variant_NullType_Double =
        if (v == null) Variant_NullType_Double.create(NullType.NULL) else Variant_NullType_Double.create(v.toDouble())

    private fun ndD(v: Double?): Variant_NullType_Double =
        if (v == null) Variant_NullType_Double.create(NullType.NULL) else Variant_NullType_Double.create(v)

    private fun iso(epochSec: Long): String = Instant.ofEpochSecond(epochSec).toString()

    private fun parseDate(s: String): LocalDate? = try {
        LocalDate.parse(s.take(10))
    } catch (_: DateTimeParseException) {
        null
    }

    private inline fun <T> safe(fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        Log.e(TAG, "health store read failed", t)
        fallback
    }

    companion object {
        private const val TAG = "MB9A_HealthStore"
    }
}
