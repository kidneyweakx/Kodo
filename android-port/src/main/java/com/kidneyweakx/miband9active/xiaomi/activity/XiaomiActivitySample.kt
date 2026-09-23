/*  Copyright (C) 2023-2024 José Rebelo                                      (Gadgetbridge)
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
 * Pure-data rows produced by the activity parsers. Shapes mirror the
 * Gadgetbridge entities (XiaomiActivitySample, XiaomiDailySummarySample,
 * XiaomiSleepTimeSample, XiaomiSleepStageSample, XiaomiManualSample,
 * BaseActivitySummary, ActivityPoint) minus the GreenDAO bookkeeping —
 * persistence lives in SampleStore (SQLite).
 */
package com.kidneyweakx.miband9active.xiaomi.activity

/** Same sentinel as Gadgetbridge's `ActivitySample.NOT_MEASURED` semantics. */
const val NOT_MEASURED: Int = Int.MIN_VALUE

/**
 * One minute of `ACTIVITY_DAILY / DETAILS`. [timestampSec] is the start of the
 * minute (fileId timestamp + index minutes). Values are raw as decoded;
 * validity ranges are applied at read time (see SampleStore).
 *
 * [activeCalories] is kcal for that minute (Gadgetbridge stores the same raw
 * value and multiplies by 1000 to get calories in XiaomiSampleProvider).
 */
data class XiaomiActivitySample(
    val timestampSec: Long,
    val steps: Int = NOT_MEASURED,
    val heartRate: Int = NOT_MEASURED,
    val spo2: Int = NOT_MEASURED,
    val stress: Int = NOT_MEASURED,
    val activeCalories: Int = NOT_MEASURED,
    val distanceCm: Int = NOT_MEASURED,
    /** "Body energy" / "Vitality" reading. */
    val energy: Int = NOT_MEASURED,
)

/**
 * `ACTIVITY_DAILY / SUMMARY` — one per day. Every field is null unless the
 * file's validity bitmap flagged it (DailySummaryParser.java, fec57bcf83).
 */
data class DailySummarySample(
    val timestampSec: Long,
    /** 15-minute blocks, as carried by the file id. */
    val timezone: Int,
    val steps: Int? = null,
    val activeCalories: Int? = null,
    val hrResting: Int? = null,
    val hrMax: Int? = null,
    val hrMaxTs: Long? = null,
    val hrMin: Int? = null,
    val hrMinTs: Long? = null,
    val hrAvg: Int? = null,
    val stressAvg: Int? = null,
    val stressMax: Int? = null,
    val stressMin: Int? = null,
    /** 24-bit bitmap, bit n = user stood during hour n. */
    val standing: Int? = null,
    val calories: Int? = null,
    val recoveryHours: Int? = null,
    val spo2Max: Int? = null,
    val spo2MaxTs: Long? = null,
    val spo2Min: Int? = null,
    val spo2MinTs: Long? = null,
    val spo2Avg: Int? = null,
    val trainingLoadDay: Int? = null,
    val trainingLoadWeek: Int? = null,
    val trainingLoadLevel: Int? = null,
    val vitalityIncreaseLight: Int? = null,
    val vitalityIncreaseModerate: Int? = null,
    val vitalityIncreaseHigh: Int? = null,
    val vitalityCurrent: Int? = null,
)

/**
 * A sleep session (Gadgetbridge `XiaomiSleepTimeSample`). Durations are
 * minutes and are null when the file did not carry them (older sleep
 * details versions without a type-16 summary packet).
 */
data class SleepSummary(
    val bedTimeSec: Long,
    val wakeupTimeSec: Long,
    val totalMinutes: Int?,
    val deepMinutes: Int?,
    val lightMinutes: Int?,
    val remMinutes: Int?,
    val awakeMinutes: Int?,
    val isAwake: Boolean = false,
)

/**
 * A sleep phase change (Gadgetbridge `XiaomiSleepStageSample`). [stage] uses
 * the normalised codes XiaomiSampleProvider understands:
 *   0 = not sleeping, 1 = n/a, 2 = deep, 3 = light, 4 = REM, 5 = awake.
 */
data class SleepStageSample(val timestampSec: Long, val stage: Int) {
    val kind: Kind get() = Kind.fromCode(stage)

    enum class Kind {
        AWAKE, LIGHT, DEEP, REM, UNKNOWN;

        companion object {
            /** XiaomiSampleProvider.getActivityKindForSample(). */
            fun fromCode(c: Int): Kind = when (c) {
                2 -> DEEP
                3 -> LIGHT
                4 -> REM
                5 -> AWAKE
                else -> UNKNOWN
            }
        }
    }
}

/** Spot check taken on the band (Gadgetbridge `XiaomiManualSample`). */
data class ManualSample(val timestampSec: Long, val type: Int, val value: Int) {
    companion object {
        /** XiaomiManualSampleProvider.TYPE_* */
        const val TYPE_HR = 0x11
        const val TYPE_SPO2 = 0x12
        const val TYPE_STRESS = 0x13
        const val TYPE_TEMPERATURE = 0x44
    }
}

/** One GPS fix of a `SPORTS / GPS_TRACK` file (Gadgetbridge `ActivityPoint`). */
data class WorkoutGpsPoint(
    val timestampSec: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    /** v2: accuracy in metres; v3: dimensionless HDOP. */
    val accuracy: Double?,
    val speedMetersPerSecond: Double?,
)

/** Parsed content of one activity file, ready for SampleStore. */
sealed class ActivityFileContent {
    abstract val fileId: XiaomiActivityFileId

    data class DailyDetails(
        override val fileId: XiaomiActivityFileId,
        val samples: List<XiaomiActivitySample>,
    ) : ActivityFileContent()

    data class DailySummary(
        override val fileId: XiaomiActivityFileId,
        val summary: DailySummarySample,
    ) : ActivityFileContent()

    data class Sleep(
        override val fileId: XiaomiActivityFileId,
        val sessions: List<SleepSummary>,
        val stages: List<SleepStageSample>,
        /**
         * False when the stage packets hit a buffer underflow. Upstream then
         * persists the session but reports failure; we do the same and
         * leave the file un-acked.
         */
        val complete: Boolean = true,
    ) : ActivityFileContent()

    data class ManualSamples(
        override val fileId: XiaomiActivityFileId,
        val samples: List<ManualSample>,
    ) : ActivityFileContent()

    data class WorkoutSummary(
        override val fileId: XiaomiActivityFileId,
        val fields: WorkoutFields,
        /** Raw file bytes, kept so a future parser version can re-decode. */
        val raw: ByteArray,
    ) : ActivityFileContent()

    data class WorkoutGps(
        override val fileId: XiaomiActivityFileId,
        val points: List<WorkoutGpsPoint>,
    ) : ActivityFileContent()

    /**
     * The file was well-formed but, per upstream, carries nothing to store
     * (sleep-stages file with zero bedtime, 13-byte indoor GPS placeholder,
     * Null-Island-only track). Counts as parsed so it gets acked.
     */
    data class Empty(
        override val fileId: XiaomiActivityFileId,
        val reason: String,
    ) : ActivityFileContent()
}
