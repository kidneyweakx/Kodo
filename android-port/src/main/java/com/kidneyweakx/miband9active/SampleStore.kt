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
 * Native sample store (plain android.database.sqlite, no extra deps).
 *
 * Every table is keyed by the band's own timestamp(s), and every write is an
 * upsert, so re-fetching the same (or a grown "today") activity file never
 * double counts — the same property Gadgetbridge gets from GreenDAO
 * `insertOrReplace` keyed by (timestamp, device).
 *
 * Read-side validity filters mirror Gadgetbridge's providers:
 *   HR 20..250 (HealthConnect HeartRateSyncer), stress 1..100
 *   (XiaomiStressSampleProvider drops 0), SpO2 1..100 (XiaomiSpo2SampleProvider).
 *
 * Days are local calendar days in the phone's current zone, except daily
 * summary files, which are bucketed by the timezone the band stamped into
 * the file id.
 */
package com.kidneyweakx.miband9active

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.kidneyweakx.miband9active.xiaomi.activity.DailySummarySample
import com.kidneyweakx.miband9active.xiaomi.activity.ManualSample
import com.kidneyweakx.miband9active.xiaomi.activity.NOT_MEASURED
import com.kidneyweakx.miband9active.xiaomi.activity.SleepStageSample
import com.kidneyweakx.miband9active.xiaomi.activity.SleepSummary
import com.kidneyweakx.miband9active.xiaomi.activity.WorkoutFields
import com.kidneyweakx.miband9active.xiaomi.activity.WorkoutGpsPoint
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileId
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivitySample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

object SampleStore {

    // ------------------------------------------------------------ read models

    data class HrPoint(val timestampSec: Long, val bpm: Int, val manual: Boolean)
    data class ValuePoint(val timestampSec: Long, val value: Int, val manual: Boolean)

    data class StoredWorkout(
        /** File-id timestamp (upstream BaseActivitySummary.startTime key). */
        val startSec: Long,
        val fileSubtype: Int,
        val fileVersion: Int,
        val workoutType: Int?,
        val endSec: Long?,
        val activeSeconds: Int?,
        val kcal: Int?,
        val kcalTotal: Int?,
        val distanceMeters: Int?,
        val hrAvg: Int?,
        val hrMax: Int?,
        val hrMin: Int?,
        val steps: Int?,
        val hasGps: Boolean,
    )

    /** Aggregate for one local day. Null fields = the band gave us nothing for them. */
    data class DayAggregate(
        val date: LocalDate,
        val steps: Int?,
        val distanceMeters: Double?,
        val activeCalories: Double?,
        val restingHeartRate: Int?,
        val averageHeartRate: Double?,
        val stressAverage: Double?,
        val spo2Average: Double?,
        val sleepMinutes: Int?,
    )

    // ------------------------------------------------------------------ schema

    private const val DB_NAME = "mb9a_samples.db"
    private const val DB_VERSION = 1

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE activity_sample(
                    ts INTEGER PRIMARY KEY, steps INTEGER, hr INTEGER, spo2 INTEGER,
                    stress INTEGER, active_kcal INTEGER, distance_cm INTEGER, energy INTEGER)""",
            )
            db.execSQL(
                """CREATE TABLE daily_summary(
                    ts INTEGER PRIMARY KEY, tz INTEGER NOT NULL, day TEXT NOT NULL,
                    steps INTEGER, active_kcal INTEGER, hr_resting INTEGER, hr_max INTEGER,
                    hr_max_ts INTEGER, hr_min INTEGER, hr_min_ts INTEGER, hr_avg INTEGER,
                    stress_avg INTEGER, stress_max INTEGER, stress_min INTEGER, standing INTEGER,
                    calories INTEGER, recovery_hours INTEGER, spo2_max INTEGER, spo2_max_ts INTEGER,
                    spo2_min INTEGER, spo2_min_ts INTEGER, spo2_avg INTEGER,
                    training_load_day INTEGER, training_load_week INTEGER, training_load_level INTEGER,
                    vitality_light INTEGER, vitality_moderate INTEGER, vitality_high INTEGER,
                    vitality_current INTEGER)""",
            )
            db.execSQL("CREATE INDEX daily_summary_day ON daily_summary(day)")
            db.execSQL(
                """CREATE TABLE sleep_session(
                    bed_ts INTEGER PRIMARY KEY, wake_ts INTEGER NOT NULL, is_awake INTEGER NOT NULL,
                    total_min INTEGER, deep_min INTEGER, light_min INTEGER, rem_min INTEGER,
                    awake_min INTEGER)""",
            )
            db.execSQL("CREATE INDEX sleep_session_wake ON sleep_session(wake_ts)")
            db.execSQL("CREATE TABLE sleep_stage(ts INTEGER PRIMARY KEY, stage INTEGER NOT NULL)")
            db.execSQL(
                """CREATE TABLE manual_sample(
                    ts INTEGER NOT NULL, type INTEGER NOT NULL, value INTEGER NOT NULL,
                    PRIMARY KEY(ts, type))""",
            )
            db.execSQL(
                """CREATE TABLE workout(
                    start_ts INTEGER PRIMARY KEY, file_subtype INTEGER NOT NULL,
                    file_version INTEGER NOT NULL, workout_type INTEGER, end_ts INTEGER,
                    active_sec INTEGER, kcal INTEGER, kcal_total INTEGER, distance_m INTEGER,
                    hr_avg INTEGER, hr_max INTEGER, hr_min INTEGER, steps INTEGER,
                    has_gps INTEGER NOT NULL DEFAULT 0, raw BLOB)""",
            )
            db.execSQL(
                """CREATE TABLE workout_gps(
                    workout_ts INTEGER NOT NULL, ts INTEGER NOT NULL, lat REAL NOT NULL,
                    lon REAL NOT NULL, alt REAL, accuracy REAL, speed REAL,
                    PRIMARY KEY(workout_ts, ts))""",
            )
            db.execSQL(
                """CREATE TABLE file_log(
                    file_id TEXT PRIMARY KEY, status TEXT NOT NULL,
                    failures INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)""",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private val db: SQLiteDatabase by lazy { Helper(AppContext.context).writableDatabase }

    private inline fun <T> tx(block: SQLiteDatabase.() -> T): T {
        db.beginTransaction()
        try {
            val r = db.block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    private fun ContentValues.putOpt(key: String, v: Int?) = if (v == null) putNull(key) else put(key, v)
    private fun ContentValues.putOpt(key: String, v: Long?) = if (v == null) putNull(key) else put(key, v)
    private fun ContentValues.putMeasured(key: String, v: Int) =
        if (v == NOT_MEASURED) putNull(key) else put(key, v)

    private fun Cursor.intOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

    private fun zone(): ZoneId = ZoneId.systemDefault()

    /** [start, end) epoch seconds of a local day. */
    fun dayRange(date: LocalDate): Pair<Long, Long> {
        val z = zone()
        return date.atStartOfDay(z).toEpochSecond() to date.plusDays(1).atStartOfDay(z).toEpochSecond()
    }

    // ------------------------------------------------------------------ writes

    fun upsertActivitySamples(samples: List<XiaomiActivitySample>) {
        if (samples.isEmpty()) return
        tx {
            val cv = ContentValues()
            for (s in samples) {
                cv.clear()
                cv.put("ts", s.timestampSec)
                cv.putMeasured("steps", s.steps)
                cv.putMeasured("hr", s.heartRate)
                cv.putMeasured("spo2", s.spo2)
                cv.putMeasured("stress", s.stress)
                cv.putMeasured("active_kcal", s.activeCalories)
                cv.putMeasured("distance_cm", s.distanceCm)
                cv.putMeasured("energy", s.energy)
                insertWithOnConflict("activity_sample", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun upsertDailySummary(s: DailySummarySample) {
        val day = Instant.ofEpochSecond(s.timestampSec)
            .atOffset(ZoneOffset.ofTotalSeconds((s.timezone * 15 * 60).coerceIn(-18 * 3600, 18 * 3600)))
            .toLocalDate()
            .toString()
        val cv = ContentValues().apply {
            put("ts", s.timestampSec)
            put("tz", s.timezone)
            put("day", day)
            putOpt("steps", s.steps)
            putOpt("active_kcal", s.activeCalories)
            putOpt("hr_resting", s.hrResting)
            putOpt("hr_max", s.hrMax)
            putOpt("hr_max_ts", s.hrMaxTs)
            putOpt("hr_min", s.hrMin)
            putOpt("hr_min_ts", s.hrMinTs)
            putOpt("hr_avg", s.hrAvg)
            putOpt("stress_avg", s.stressAvg)
            putOpt("stress_max", s.stressMax)
            putOpt("stress_min", s.stressMin)
            putOpt("standing", s.standing)
            putOpt("calories", s.calories)
            putOpt("recovery_hours", s.recoveryHours)
            putOpt("spo2_max", s.spo2Max)
            putOpt("spo2_max_ts", s.spo2MaxTs)
            putOpt("spo2_min", s.spo2Min)
            putOpt("spo2_min_ts", s.spo2MinTs)
            putOpt("spo2_avg", s.spo2Avg)
            putOpt("training_load_day", s.trainingLoadDay)
            putOpt("training_load_week", s.trainingLoadWeek)
            putOpt("training_load_level", s.trainingLoadLevel)
            putOpt("vitality_light", s.vitalityIncreaseLight)
            putOpt("vitality_moderate", s.vitalityIncreaseModerate)
            putOpt("vitality_high", s.vitalityIncreaseHigh)
            putOpt("vitality_current", s.vitalityCurrent)
        }
        db.insertWithOnConflict("daily_summary", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Sleep sessions follow SleepStagesParser/SleepDetailsParser upstream: the
     * bedtime is the key, and a session is ignored when an existing one with
     * the same bedtime already has a later wakeup. When the wakeup is equal,
     * durations the new file doesn't carry are kept from the stored row.
     */
    fun upsertSleep(sessions: List<SleepSummary>, stages: List<SleepStageSample>) {
        tx {
            for (s in sessions) {
                var existing: SleepSummary? = null
                rawQuery(
                    "SELECT wake_ts, is_awake, total_min, deep_min, light_min, rem_min, awake_min FROM sleep_session WHERE bed_ts = ?",
                    arrayOf(s.bedTimeSec.toString()),
                ).use { c ->
                    if (c.moveToFirst()) {
                        existing = SleepSummary(
                            bedTimeSec = s.bedTimeSec,
                            wakeupTimeSec = c.getLong(0),
                            isAwake = c.getInt(1) != 0,
                            totalMinutes = c.intOrNull(2),
                            deepMinutes = c.intOrNull(3),
                            lightMinutes = c.intOrNull(4),
                            remMinutes = c.intOrNull(5),
                            awakeMinutes = c.intOrNull(6),
                        )
                    }
                }
                val old = existing
                if (old != null && old.wakeupTimeSec > s.wakeupTimeSec) continue
                val merged = if (old != null && old.wakeupTimeSec == s.wakeupTimeSec) {
                    s.copy(
                        totalMinutes = s.totalMinutes ?: old.totalMinutes,
                        deepMinutes = s.deepMinutes ?: old.deepMinutes,
                        lightMinutes = s.lightMinutes ?: old.lightMinutes,
                        remMinutes = s.remMinutes ?: old.remMinutes,
                        awakeMinutes = s.awakeMinutes ?: old.awakeMinutes,
                    )
                } else {
                    s
                }
                val cv = ContentValues().apply {
                    put("bed_ts", merged.bedTimeSec)
                    put("wake_ts", merged.wakeupTimeSec)
                    put("is_awake", if (merged.isAwake) 1 else 0)
                    putOpt("total_min", merged.totalMinutes)
                    putOpt("deep_min", merged.deepMinutes)
                    putOpt("light_min", merged.lightMinutes)
                    putOpt("rem_min", merged.remMinutes)
                    putOpt("awake_min", merged.awakeMinutes)
                }
                insertWithOnConflict("sleep_session", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val cv = ContentValues()
            for (st in stages) {
                cv.clear()
                cv.put("ts", st.timestampSec)
                cv.put("stage", st.stage)
                insertWithOnConflict("sleep_stage", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun upsertManualSamples(samples: List<ManualSample>) {
        if (samples.isEmpty()) return
        tx {
            val cv = ContentValues()
            for (s in samples) {
                cv.clear()
                cv.put("ts", s.timestampSec)
                cv.put("type", s.type)
                cv.put("value", s.value)
                insertWithOnConflict("manual_sample", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun upsertWorkout(fileId: XiaomiActivityFileId, fields: WorkoutFields, raw: ByteArray) {
        val startSec = fileId.timestamp.time / 1000L
        tx {
            var hasGps = false
            rawQuery("SELECT has_gps FROM workout WHERE start_ts = ?", arrayOf(startSec.toString())).use { c ->
                if (c.moveToFirst()) hasGps = c.getInt(0) != 0
            }
            val cv = ContentValues().apply {
                put("start_ts", startSec)
                put("file_subtype", fileId.subtypeCode)
                put("file_version", fileId.version)
                putOpt("workout_type", fields.workoutType)
                putOpt("end_ts", fields.timeEndEpochSec?.toLong()?.and(0xFFFFFFFFL))
                putOpt("active_sec", fields.activeSeconds)
                putOpt("kcal", fields.calories)
                putOpt("kcal_total", fields.caloriesTotal)
                putOpt("distance_m", fields.distanceMeters)
                putOpt("hr_avg", fields.hrAvg)
                putOpt("hr_max", fields.hrMax)
                putOpt("hr_min", fields.hrMin)
                putOpt("steps", fields.steps)
                put("has_gps", if (hasGps) 1 else 0)
                put("raw", raw)
            }
            insertWithOnConflict("workout", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun upsertWorkoutGps(fileId: XiaomiActivityFileId, points: List<WorkoutGpsPoint>) {
        val workoutTs = fileId.timestamp.time / 1000L
        tx {
            val cv = ContentValues()
            for (p in points) {
                cv.clear()
                cv.put("workout_ts", workoutTs)
                cv.put("ts", p.timestampSec)
                cv.put("lat", p.latitude)
                cv.put("lon", p.longitude)
                if (p.altitudeMeters == null) cv.putNull("alt") else cv.put("alt", p.altitudeMeters)
                if (p.accuracy == null) cv.putNull("accuracy") else cv.put("accuracy", p.accuracy)
                if (p.speedMetersPerSecond == null) cv.putNull("speed") else cv.put("speed", p.speedMetersPerSecond)
                insertWithOnConflict("workout_gps", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            // Summary may arrive in the same sync (fetched first, see DetailType.fetchOrder).
            execSQL("UPDATE workout SET has_gps = 1 WHERE start_ts = ?", arrayOf<Any>(workoutTs))
        }
    }

    // ------------------------------------------------------------- file log

    private fun fileKey(fileId: XiaomiActivityFileId) =
        fileId.toBytes().joinToString("") { "%02x".format(it) }

    /** Parse failures so far for this file (0 when never seen or parsed OK). */
    fun parseFailures(fileId: XiaomiActivityFileId): Int =
        db.rawQuery("SELECT failures FROM file_log WHERE file_id = ?", arrayOf(fileKey(fileId))).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun recordFileParsed(fileId: XiaomiActivityFileId) {
        val cv = ContentValues().apply {
            put("file_id", fileKey(fileId))
            put("status", "parsed")
            put("failures", 0)
            put("updated_at", System.currentTimeMillis() / 1000L)
        }
        db.insertWithOnConflict("file_log", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun recordFileParseFailure(fileId: XiaomiActivityFileId) {
        val key = fileKey(fileId)
        tx {
            execSQL(
                "INSERT OR IGNORE INTO file_log(file_id, status, failures, updated_at) VALUES(?, 'failed', 0, ?)",
                arrayOf<Any>(key, System.currentTimeMillis() / 1000L),
            )
            execSQL(
                "UPDATE file_log SET status = 'failed', failures = failures + 1, updated_at = ? WHERE file_id = ?",
                arrayOf<Any>(System.currentTimeMillis() / 1000L, key),
            )
        }
    }

    // ------------------------------------------------------------------- reads

    fun loadActivity(fromSec: Long, toSec: Long): List<XiaomiActivitySample> =
        db.rawQuery(
            "SELECT ts, steps, hr, spo2, stress, active_kcal, distance_cm, energy FROM activity_sample WHERE ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(fromSec.toString(), toSec.toString()),
        ).use { c ->
            val out = ArrayList<XiaomiActivitySample>(c.count)
            while (c.moveToNext()) {
                out += XiaomiActivitySample(
                    timestampSec = c.getLong(0),
                    steps = c.intOrNull(1) ?: NOT_MEASURED,
                    heartRate = c.intOrNull(2) ?: NOT_MEASURED,
                    spo2 = c.intOrNull(3) ?: NOT_MEASURED,
                    stress = c.intOrNull(4) ?: NOT_MEASURED,
                    activeCalories = c.intOrNull(5) ?: NOT_MEASURED,
                    distanceCm = c.intOrNull(6) ?: NOT_MEASURED,
                    energy = c.intOrNull(7) ?: NOT_MEASURED,
                )
            }
            out
        }

    fun loadActivity(date: LocalDate): List<XiaomiActivitySample> {
        val (from, to) = dayRange(date)
        return loadActivity(from, to)
    }

    fun loadManual(fromSec: Long, toSec: Long, type: Int): List<ManualSample> =
        db.rawQuery(
            "SELECT ts, value FROM manual_sample WHERE type = ? AND ts >= ? AND ts < ? ORDER BY ts",
            arrayOf(type.toString(), fromSec.toString(), toSec.toString()),
        ).use { c ->
            val out = ArrayList<ManualSample>()
            while (c.moveToNext()) out += ManualSample(c.getLong(0), type, c.getInt(1))
            out
        }

    fun loadDailySummary(date: LocalDate): DailySummarySample? =
        db.rawQuery(
            """SELECT ts, tz, steps, active_kcal, hr_resting, hr_max, hr_max_ts, hr_min, hr_min_ts,
                hr_avg, stress_avg, stress_max, stress_min, standing, calories, recovery_hours,
                spo2_max, spo2_max_ts, spo2_min, spo2_min_ts, spo2_avg, training_load_day,
                training_load_week, training_load_level, vitality_light, vitality_moderate,
                vitality_high, vitality_current
               FROM daily_summary WHERE day = ? ORDER BY ts DESC LIMIT 1""",
            arrayOf(date.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            DailySummarySample(
                timestampSec = c.getLong(0),
                timezone = c.getInt(1),
                steps = c.intOrNull(2),
                activeCalories = c.intOrNull(3),
                hrResting = c.intOrNull(4),
                hrMax = c.intOrNull(5),
                hrMaxTs = c.longOrNull(6),
                hrMin = c.intOrNull(7),
                hrMinTs = c.longOrNull(8),
                hrAvg = c.intOrNull(9),
                stressAvg = c.intOrNull(10),
                stressMax = c.intOrNull(11),
                stressMin = c.intOrNull(12),
                standing = c.intOrNull(13),
                calories = c.intOrNull(14),
                recoveryHours = c.intOrNull(15),
                spo2Max = c.intOrNull(16),
                spo2MaxTs = c.longOrNull(17),
                spo2Min = c.intOrNull(18),
                spo2MinTs = c.longOrNull(19),
                spo2Avg = c.intOrNull(20),
                trainingLoadDay = c.intOrNull(21),
                trainingLoadWeek = c.intOrNull(22),
                trainingLoadLevel = c.intOrNull(23),
                vitalityIncreaseLight = c.intOrNull(24),
                vitalityIncreaseModerate = c.intOrNull(25),
                vitalityIncreaseHigh = c.intOrNull(26),
                vitalityCurrent = c.intOrNull(27),
            )
        }

    /** Heart rate for [from, to): automatic minute samples + manual spot checks, sorted. */
    fun heartRate(fromSec: Long, toSec: Long): List<HrPoint> {
        val auto = loadActivity(fromSec, toSec)
            .filter { it.heartRate in HR_VALID }
            .map { HrPoint(it.timestampSec, it.heartRate, manual = false) }
        val manual = loadManual(fromSec, toSec, ManualSample.TYPE_HR)
            .filter { it.value in HR_VALID }
            .map { HrPoint(it.timestampSec, it.value, manual = true) }
        return (auto + manual).sortedBy { it.timestampSec }
    }

    fun stress(fromSec: Long, toSec: Long): List<ValuePoint> {
        val auto = loadActivity(fromSec, toSec)
            .filter { it.stress in STRESS_VALID }
            .map { ValuePoint(it.timestampSec, it.stress, manual = false) }
        val manual = loadManual(fromSec, toSec, ManualSample.TYPE_STRESS)
            .filter { it.value in STRESS_VALID }
            .map { ValuePoint(it.timestampSec, it.value, manual = true) }
        return (auto + manual).sortedBy { it.timestampSec }
    }

    fun spo2(fromSec: Long, toSec: Long): List<ValuePoint> {
        val auto = loadActivity(fromSec, toSec)
            .filter { it.spo2 in SPO2_VALID }
            .map { ValuePoint(it.timestampSec, it.spo2, manual = false) }
        val manual = loadManual(fromSec, toSec, ManualSample.TYPE_SPO2)
            .filter { it.value in SPO2_VALID }
            .map { ValuePoint(it.timestampSec, it.value, manual = true) }
        return (auto + manual).sortedBy { it.timestampSec }
    }

    /**
     * Sleep sessions that ended (woke up) during [date]. Overlapping sessions
     * for the same night (the band re-emits a growing session with a new
     * bedtime) are collapsed, keeping the longer one.
     */
    fun sleepSessionsEndingOn(date: LocalDate): List<SleepSummary> {
        val (from, to) = dayRange(date)
        return sleepSessionsWakingBetween(from, to)
    }

    fun sleepSessionsWakingBetween(fromSec: Long, toSec: Long): List<SleepSummary> {
        val all = db.rawQuery(
            """SELECT bed_ts, wake_ts, is_awake, total_min, deep_min, light_min, rem_min, awake_min
               FROM sleep_session WHERE wake_ts >= ? AND wake_ts < ? ORDER BY bed_ts""",
            arrayOf(fromSec.toString(), toSec.toString()),
        ).use { c ->
            val out = ArrayList<SleepSummary>()
            while (c.moveToNext()) {
                out += SleepSummary(
                    bedTimeSec = c.getLong(0),
                    wakeupTimeSec = c.getLong(1),
                    isAwake = c.getInt(2) != 0,
                    totalMinutes = c.intOrNull(3),
                    deepMinutes = c.intOrNull(4),
                    lightMinutes = c.intOrNull(5),
                    remMinutes = c.intOrNull(6),
                    awakeMinutes = c.intOrNull(7),
                )
            }
            out
        }
        val kept = ArrayList<SleepSummary>()
        for (s in all) {
            val last = kept.lastOrNull()
            if (last != null && s.bedTimeSec < last.wakeupTimeSec) {
                val lastLen = last.wakeupTimeSec - last.bedTimeSec
                val sLen = s.wakeupTimeSec - s.bedTimeSec
                if (sLen > lastLen) kept[kept.size - 1] = s
            } else {
                kept += s
            }
        }
        return kept
    }

    /** Stage changes within [fromSec, toSec], sorted. */
    fun sleepStages(fromSec: Long, toSec: Long): List<SleepStageSample> =
        db.rawQuery(
            "SELECT ts, stage FROM sleep_stage WHERE ts >= ? AND ts <= ? ORDER BY ts",
            arrayOf(fromSec.toString(), toSec.toString()),
        ).use { c ->
            val out = ArrayList<SleepStageSample>()
            while (c.moveToNext()) out += SleepStageSample(c.getLong(0), c.getInt(1))
            out
        }

    fun recentWorkouts(limit: Int): List<StoredWorkout> = workoutsQuery(
        "SELECT $WORKOUT_COLS FROM workout ORDER BY start_ts DESC LIMIT ?",
        arrayOf(limit.toString()),
    )

    fun workoutsStartingBetween(fromSec: Long, toSec: Long): List<StoredWorkout> = workoutsQuery(
        "SELECT $WORKOUT_COLS FROM workout WHERE start_ts >= ? AND start_ts < ? ORDER BY start_ts",
        arrayOf(fromSec.toString(), toSec.toString()),
    )

    private const val WORKOUT_COLS =
        "start_ts, file_subtype, file_version, workout_type, end_ts, active_sec, kcal, kcal_total, " +
            "distance_m, hr_avg, hr_max, hr_min, steps, has_gps"

    private fun workoutsQuery(sql: String, args: Array<String>): List<StoredWorkout> =
        db.rawQuery(sql, args).use { c ->
            val out = ArrayList<StoredWorkout>()
            while (c.moveToNext()) {
                out += StoredWorkout(
                    startSec = c.getLong(0),
                    fileSubtype = c.getInt(1),
                    fileVersion = c.getInt(2),
                    workoutType = c.intOrNull(3),
                    endSec = c.longOrNull(4),
                    activeSeconds = c.intOrNull(5),
                    kcal = c.intOrNull(6),
                    kcalTotal = c.intOrNull(7),
                    distanceMeters = c.intOrNull(8),
                    hrAvg = c.intOrNull(9),
                    hrMax = c.intOrNull(10),
                    hrMin = c.intOrNull(11),
                    steps = c.intOrNull(12),
                    hasGps = c.getInt(13) != 0,
                )
            }
            out
        }

    fun workoutTrack(workoutStartSec: Long): List<WorkoutGpsPoint> =
        db.rawQuery(
            "SELECT ts, lat, lon, alt, accuracy, speed FROM workout_gps WHERE workout_ts = ? ORDER BY ts",
            arrayOf(workoutStartSec.toString()),
        ).use { c ->
            val out = ArrayList<WorkoutGpsPoint>()
            while (c.moveToNext()) {
                out += WorkoutGpsPoint(
                    timestampSec = c.getLong(0),
                    latitude = c.getDouble(1),
                    longitude = c.getDouble(2),
                    altitudeMeters = if (c.isNull(3)) null else c.getDouble(3),
                    accuracy = if (c.isNull(4)) null else c.getDouble(4),
                    speedMetersPerSecond = if (c.isNull(5)) null else c.getDouble(5),
                )
            }
            out
        }

    /** Newest timestamp of any stored band sample, or null if the store is empty. */
    fun lastSampleAtSec(): Long? =
        db.rawQuery(
            """SELECT MAX(t) FROM (
                 SELECT MAX(ts) AS t FROM activity_sample
                   WHERE steps IS NOT NULL OR hr IS NOT NULL OR spo2 IS NOT NULL OR stress IS NOT NULL
                 UNION ALL SELECT MAX(ts) FROM manual_sample
                 UNION ALL SELECT MAX(wake_ts) FROM sleep_session
                 UNION ALL SELECT MAX(COALESCE(end_ts, start_ts)) FROM workout)""",
            null,
        ).use { c -> if (c.moveToFirst()) c.longOrNull(0) else null }

    /**
     * Day aggregate from real samples. Returns null when the day has neither
     * minute samples nor a band daily-summary file — callers must render
     * "no data", not zeros. Band-computed totals (daily summary file, validity
     * bit set) win over sums of minute samples when both exist.
     */
    fun dayAggregate(date: LocalDate): DayAggregate? {
        val samples = loadActivity(date)
        val summary = loadDailySummary(date)
        val sleep = sleepSessionsEndingOn(date)
        if (samples.isEmpty() && summary == null && sleep.isEmpty()) return null

        val (from, to) = dayRange(date)
        val stepValues = samples.mapNotNull { s -> s.steps.takeIf { it != NOT_MEASURED && it >= 0 } }
        val distValues = samples.mapNotNull { s -> s.distanceCm.takeIf { it != NOT_MEASURED && it >= 0 } }
        val kcalValues = samples.mapNotNull { s -> s.activeCalories.takeIf { it != NOT_MEASURED && it >= 0 } }
        val hr = heartRate(from, to)
        val stress = stress(from, to)
        val spo2 = spo2(from, to)

        val sleepMinutes = sleep.mapNotNull { it.totalMinutes }.takeIf { it.isNotEmpty() }?.sum()

        return DayAggregate(
            date = date,
            steps = summary?.steps ?: stepValues.takeIf { it.isNotEmpty() }?.sum(),
            distanceMeters = distValues.takeIf { it.isNotEmpty() }?.sum()?.let { it / 100.0 },
            activeCalories = (summary?.activeCalories ?: kcalValues.takeIf { it.isNotEmpty() }?.sum())?.toDouble(),
            restingHeartRate = summary?.hrResting?.takeIf { it in HR_VALID },
            averageHeartRate = summary?.hrAvg?.takeIf { it in HR_VALID }?.toDouble()
                ?: hr.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average(),
            stressAverage = summary?.stressAvg?.takeIf { it in STRESS_VALID }?.toDouble()
                ?: stress.takeIf { it.isNotEmpty() }?.map { it.value }?.average(),
            spo2Average = summary?.spo2Avg?.takeIf { it in SPO2_VALID }?.toDouble()
                ?: spo2.takeIf { it.isNotEmpty() }?.map { it.value }?.average(),
            sleepMinutes = sleepMinutes,
        )
    }

    fun clearAll() {
        tx {
            for (t in listOf(
                "activity_sample", "daily_summary", "sleep_session", "sleep_stage",
                "manual_sample", "workout", "workout_gps", "file_log",
            )) {
                delete(t, null, null)
            }
        }
        // Pre-SQLite JSON store (never read any more).
        runCatching {
            AppContext.context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    val HR_VALID = 20..250
    val STRESS_VALID = 1..100
    val SPO2_VALID = 1..100

    private const val LEGACY_PREFS = "miband9active_samples"
}
