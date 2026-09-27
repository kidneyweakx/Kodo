/*  Copyright (C) 2024 José Rebelo                                           (Gadgetbridge)
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
 * Reads a Gadgetbridge export and imports one Xiaomi device's history.
 *
 * Input formats (upstream master 75f9239):
 *  - "Export zip" (util/backup/ZipBackupExportJob + AbstractZipBackupJob):
 *      gadgetbridge.json          ZipBackupMetadata {appId, appVersionName,
 *                                 appVersionCode, backupVersion, backupDate
 *                                 ("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", GsonUtcDateAdapter)}
 *                                 — written LAST
 *      database/Gadgetbridge      the closed SQLite database file
 *      preferences/global.json    JsonBackupPreferences
 *      preferences/device_<IDENTIFIER>.json   per-device prefs; "authkey" is the
 *                                 Xiaomi secret (XiaomiAuthService.getSecretKey,
 *                                 optional "0x" prefix)
 *      files/…                    external files (GPX, raw activity dumps) — ignored
 *  - "Export database": the raw SQLite file (GBDatabase.exportDB).
 *
 * Schema (GBDaoGenerator, GreenDAO → UPPER_SNAKE columns):
 *  DEVICE(_id, NAME, MANUFACTURER, IDENTIFIER = MAC, TYPE (deprecated int),
 *         TYPE_NAME (DeviceType enum name, since schema 62), MODEL, ALIAS, PARENT_FOLDER)
 *  XIAOMI_ACTIVITY_SAMPLE(TIMESTAMP int SECONDS, DEVICE_ID, USER_ID, RAW_INTENSITY,
 *         STEPS, RAW_KIND, HEART_RATE, STRESS?, SPO2?, DISTANCE_CM, ACTIVE_CALORIES, ENERGY)
 *  XIAOMI_SLEEP_TIME_SAMPLE(TIMESTAMP ms, DEVICE_ID, USER_ID, WAKEUP_TIME ms, IS_AWAKE,
 *         TOTAL_DURATION, DEEP_SLEEP_DURATION, LIGHT_SLEEP_DURATION, REM_SLEEP_DURATION,
 *         AWAKE_DURATION — minutes)
 *  XIAOMI_SLEEP_STAGE_SAMPLE(TIMESTAMP ms, DEVICE_ID, USER_ID, STAGE)
 *  XIAOMI_MANUAL_SAMPLE(TIMESTAMP ms, DEVICE_ID, USER_ID, TYPE, VALUE)
 *  XIAOMI_DAILY_SUMMARY_SAMPLE(TIMESTAMP ms = file id time, DEVICE_ID, USER_ID, TIMEZONE,
 *         STEPS, HR_RESTING, HR_MAX, HR_MAX_TS (s), …, ACTIVE_CALORIES, RECOVERY_HOURS)
 *  BASE_ACTIVITY_SUMMARY(_id, NAME, START_TIME ms, END_TIME ms, ACTIVITY_KIND, …,
 *         DEVICE_ID, USER_ID, SUMMARY_DATA, RAW_SUMMARY_DATA = the raw Xiaomi
 *         SPORTS/SUMMARY file, re-parsed here with our WorkoutSummaryParser)
 *
 * Column lookups are loose (case- and underscore-insensitive) and every
 * table / column is optional, so older Gadgetbridge databases import what
 * they have. All writes go through SampleStore's typed upserts in batches.
 */
package com.kidneyweakx.miband9active.dataport

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.kidneyweakx.miband9active.SampleStore
import com.kidneyweakx.miband9active.xiaomi.activity.ActivityFileContent
import com.kidneyweakx.miband9active.xiaomi.activity.DailySummarySample
import com.kidneyweakx.miband9active.xiaomi.activity.ManualSample
import com.kidneyweakx.miband9active.xiaomi.activity.NOT_MEASURED
import com.kidneyweakx.miband9active.xiaomi.activity.SleepStageSample
import com.kidneyweakx.miband9active.xiaomi.activity.SleepSummary
import com.kidneyweakx.miband9active.xiaomi.activity.WorkoutFields
import com.kidneyweakx.miband9active.xiaomi.activity.WorkoutSummaryParser
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileId
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivitySample
import com.kidneyweakx.miband9active.xiaomi.auth.XiaomiCrypto
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.zip.ZipFile
import org.json.JSONObject

internal object GadgetbridgeImport {
    private const val TAG = "MB9A_GbImport"
    private const val BATCH = 1000
    private const val WORKOUT_BATCH = 50
    private const val SNAPSHOT_TTL_MS = 15 * 60 * 1000L

    const val METADATA = "gadgetbridge.json"
    const val DATABASE = "database/Gadgetbridge"
    private const val DEVICE_PREFS_PREFIX = "preferences/device_"

    data class Meta(val backupVersion: Int?, val backupDate: String?, val appVersionName: String?)

    data class Device(
        val id: Long,
        val address: String,
        val name: String,
        val type: String,
        val authKey: String?,
        val firstSampleSec: Long?,
        val lastSampleSec: Long?,
        val sampleCount: Long,
    )

    data class Overview(val meta: Meta?, val devices: List<Device>, val xiaomiRows: Long)

    // ------------------------------------------------------------- snapshot

    /**
     * The input copied / extracted into the temp dir: the database file plus
     * what we need from the zip. Kept for [SNAPSHOT_TTL_MS] so the usual
     * inspect → inspectGadgetbridge → import sequence copies a large export
     * only once.
     */
    private class Snapshot(
        val uri: String,
        val db: File,
        val meta: Meta?,
        /** IDENTIFIER (upper case) → normalized 32-hex auth key. */
        val authKeys: Map<String, String>,
        val temps: List<File>,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        fun delete() = temps.forEach { it.delete(); File(it.path + "-journal").delete() }
    }

    @Volatile private var cached: Snapshot? = null

    @Synchronized
    fun release() {
        cached?.delete()
        cached = null
    }

    @Synchronized
    private fun snapshot(uri: String, progress: ProgressGate): Snapshot {
        cached?.let { s ->
            if (s.uri == uri && System.currentTimeMillis() - s.createdAt < SNAPSHOT_TTL_MS && s.db.isFile) return s
            s.delete()
            cached = null
        }
        DataPortFiles.sweepTemp()
        val snap = load(uri, progress)
        cached = snap
        return snap
    }

    private fun load(uri: String, progress: ProgressGate): Snapshot {
        val total = DataPortFiles.size(uri)
        progress.report("reading", 0.0)
        val (input, inputIsTemp) = DataPortFiles.materialize(uri) { n ->
            if (total != null && total > 0) progress.report("reading", (n.toDouble() / total * 0.5).coerceAtMost(0.5))
        }
        val temps = ArrayList<File>()
        if (inputIsTemp) temps += input
        try {
            val head = ByteArray(100)
            val headLen = input.inputStream().use { it.read(head) }.coerceAtLeast(0)
            return when (FileSniffer.kind(head, headLen)) {
                FileSniffer.Kind.ZIP -> fromZip(uri, input, inputIsTemp, temps, progress)
                FileSniffer.Kind.SQLITE -> {
                    val db = if (FileSniffer.downgradeWalHeader(head.copyOf(100))) {
                        // WAL flag set: work on a private copy so the user's file is never modified.
                        val copy = if (inputIsTemp) input else DataPortFiles.newTemp("gb-", ".db").also { c ->
                            temps += c
                            input.inputStream().use { i -> c.outputStream().use { o -> DataPortFiles.copy(i, o) } }
                        }
                        patchWal(copy)
                        copy
                    } else {
                        input
                    }
                    Snapshot(uri, db, null, emptyMap(), temps)
                }
                FileSniffer.Kind.OTHER -> throw DataPortException(
                    DataPortException.NOT_GADGETBRIDGE,
                    "not a Gadgetbridge export zip or database file",
                )
            }
        } catch (e: Throwable) {
            temps.forEach { it.delete() }
            throw e
        }
    }

    private fun fromZip(uri: String, input: File, inputIsTemp: Boolean, temps: ArrayList<File>, progress: ProgressGate): Snapshot {
        val zip = try {
            ZipFile(input)
        } catch (e: Exception) {
            throw DataPortException(DataPortException.BAD_FILE, "unreadable zip", e)
        }
        zip.use {
            if (it.getEntry(KodoBackupFormat.MANIFEST) != null && it.getEntry(DATABASE) == null) {
                throw DataPortException(DataPortException.NOT_GADGETBRIDGE, "this is a Kodō backup — use restore instead")
            }
            val dbEntry = it.getEntry(DATABASE)
                ?: throw DataPortException(DataPortException.NOT_GADGETBRIDGE, "zip has no $DATABASE")
            val meta = it.getEntry(METADATA)?.let { e ->
                runCatching { parseMeta(JSONObject(it.getInputStream(e).use { s -> s.readBytes() }.toString(Charsets.UTF_8))) }.getOrNull()
            }
            if (meta?.backupVersion != null && meta.backupVersion > 1) {
                Log.w(TAG, "Gadgetbridge backupVersion ${meta.backupVersion} is newer than 1; trying anyway")
            }
            val keys = HashMap<String, String>()
            for (e in it.entries()) {
                if (!e.name.startsWith(DEVICE_PREFS_PREFIX) || !e.name.endsWith(".json") || e.size > 4 * 1024 * 1024) continue
                val ident = e.name.substring(DEVICE_PREFS_PREFIX.length, e.name.length - ".json".length)
                val json = runCatching { JSONObject(it.getInputStream(e).use { s -> s.readBytes() }.toString(Charsets.UTF_8)) }.getOrNull()
                    ?: continue
                val raw = PrefsDump.string(json, "authkey") ?: continue
                XiaomiCrypto.normalizeAuthKeyHex(raw)?.let { k -> keys[ident.uppercase(Locale.ROOT)] = k }
            }
            val db = DataPortFiles.newTemp("gb-", ".db")
            temps += db
            val size = dbEntry.size.takeIf { s -> s > 0 }
            it.getInputStream(dbEntry).use { i ->
                db.outputStream().use { o ->
                    DataPortFiles.copy(i, o) { n ->
                        if (size != null) progress.report("reading", 0.5 + (n.toDouble() / size * 0.5).coerceAtMost(0.5))
                    }
                }
            }
            val head = db.inputStream().use { s -> ByteArray(100).also { b -> s.read(b) } }
            if (FileSniffer.kind(head) != FileSniffer.Kind.SQLITE) {
                throw DataPortException(DataPortException.BAD_FILE, "$DATABASE is not an SQLite database")
            }
            if (FileSniffer.downgradeWalHeader(head)) patchWal(db)
            // The zip copy is no longer needed once the database is out.
            if (inputIsTemp) {
                input.delete()
                temps.remove(input)
            }
            return Snapshot(uri, db, meta, keys, temps)
        }
    }

    private fun patchWal(f: File) {
        RandomAccessFile(f, "rw").use { raf ->
            raf.seek(18)
            raf.write(byteArrayOf(1, 1))
        }
    }

    private fun parseMeta(o: JSONObject) = Meta(
        backupVersion = if (o.has("backupVersion")) o.optInt("backupVersion") else null,
        backupDate = o.optString("backupDate").takeIf { o.has("backupDate") && !o.isNull("backupDate") },
        appVersionName = o.optString("appVersionName").takeIf { o.has("appVersionName") && !o.isNull("appVersionName") },
    )

    private fun <T> withDb(snap: Snapshot, block: (SQLiteDatabase) -> T): T {
        val db = try {
            SQLiteDatabase.openDatabase(
                snap.db.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
        } catch (e: Exception) {
            throw DataPortException(DataPortException.BAD_FILE, "cannot open the Gadgetbridge database: ${e.message}", e)
        }
        return db.use(block)
    }

    // ------------------------------------------------------------- schema

    /** Loose table / column resolution over whatever schema the export has. */
    private class Schema(private val db: SQLiteDatabase) {
        private val tables: Map<String, String> =
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                val m = HashMap<String, String>()
                while (c.moveToNext()) c.getString(0).let { m[GbUnits.looseName(it)] = it }
                m
            }
        private val cols = HashMap<String, Map<String, String>>()

        fun table(name: String): String? = tables[GbUnits.looseName(name)]

        fun columns(table: String): Map<String, String> = cols.getOrPut(table) {
            db.rawQuery("PRAGMA table_info(${KodoBackupFormat.quote(table)})", null).use { c ->
                val idx = c.getColumnIndexOrThrow("name")
                val m = HashMap<String, String>()
                while (c.moveToNext()) c.getString(idx).let { m[GbUnits.looseName(it)] = it }
                m
            }
        }

        fun column(table: String, name: String): String? = columns(table)[GbUnits.looseName(name)]
    }

    /** A projection of the columns that exist, addressed by upstream property name. */
    private class Projection(schema: Schema, val table: String, wanted: List<String>) {
        val present: Map<String, String> = wanted.mapNotNull { w -> schema.column(table, w)?.let { w to it } }.toMap()
        val sql: String = present.values.joinToString(",") { KodoBackupFormat.quote(it) }
        private var index: Map<String, Int> = emptyMap()

        fun bind(c: Cursor) {
            index = present.mapValues { (_, col) -> c.getColumnIndex(col) }.filterValues { it >= 0 }
        }

        fun long(c: Cursor, prop: String): Long? = index[prop]?.let { if (c.isNull(it)) null else c.getLong(it) }
        fun blob(c: Cursor, prop: String): ByteArray? = index[prop]?.let { if (c.isNull(it)) null else runCatching { c.getBlob(it) }.getOrNull() }
        fun has(prop: String) = prop in present
    }

    // ------------------------------------------------------------- inspect

    fun overview(uri: String, progress: ProgressGate): Overview {
        val snap = snapshot(uri, progress)
        return withDb(snap) { db ->
            val schema = Schema(db)
            val deviceTable = schema.table("DEVICE")
                ?: throw DataPortException(DataPortException.NOT_GADGETBRIDGE, "database has no DEVICE table")
            val devProj = Projection(schema, deviceTable, listOf("_id", "NAME", "IDENTIFIER", "TYPE", "TYPE_NAME", "ALIAS"))
            if (!devProj.has("_id") || !devProj.has("IDENTIFIER")) {
                throw DataPortException(DataPortException.NOT_GADGETBRIDGE, "DEVICE table has an unknown layout")
            }

            // Per-device activity stats.
            data class Stats(val count: Long, val min: Long?, val max: Long?)
            val stats = HashMap<Long, Stats>()
            schema.table("XIAOMI_ACTIVITY_SAMPLE")?.let { t ->
                val dev = schema.column(t, "DEVICE_ID")
                val ts = schema.column(t, "TIMESTAMP")
                if (dev != null && ts != null) {
                    db.rawQuery(
                        "SELECT ${q(dev)}, COUNT(*), MIN(${q(ts)}), MAX(${q(ts)}) FROM ${q(t)} GROUP BY ${q(dev)}",
                        null,
                    ).use { c ->
                        while (c.moveToNext()) {
                            stats[c.getLong(0)] = Stats(
                                c.getLong(1),
                                if (c.isNull(2)) null else c.getLong(2),
                                if (c.isNull(3)) null else c.getLong(3),
                            )
                        }
                    }
                }
            }
            // Devices with any Xiaomi-table rows (covers unknown / legacy TYPE_NAME).
            val xiaomiDeviceIds = HashSet<Long>(stats.keys)
            var xiaomiRows = 0L
            for (name in XIAOMI_TABLES) {
                val t = schema.table(name) ?: continue
                val dev = schema.column(t, "DEVICE_ID") ?: continue
                db.rawQuery("SELECT ${q(dev)}, COUNT(*) FROM ${q(t)} GROUP BY ${q(dev)}", null).use { c ->
                    while (c.moveToNext()) {
                        xiaomiDeviceIds += c.getLong(0)
                        xiaomiRows += c.getLong(1)
                    }
                }
            }

            val devices = ArrayList<Device>()
            db.rawQuery("SELECT ${devProj.sql} FROM ${q(deviceTable)}", null).use { c ->
                devProj.bind(c)
                while (c.moveToNext()) {
                    val id = devProj.long(c, "_id") ?: continue
                    val ident = str(c, devProj, "IDENTIFIER") ?: continue
                    val typeName = str(c, devProj, "TYPE_NAME")?.takeIf { it.isNotBlank() }
                    val isXiaomi = (typeName != null && typeName in GbUnits.XIAOMI_TYPES) || id in xiaomiDeviceIds
                    if (!isXiaomi) continue
                    val alias = str(c, devProj, "ALIAS")?.takeIf { it.isNotBlank() }
                    val s = stats[id]
                    devices += Device(
                        id = id,
                        address = ident,
                        name = alias ?: str(c, devProj, "NAME") ?: ident,
                        type = typeName ?: devProj.long(c, "TYPE")?.let { "legacy:$it" } ?: "unknown",
                        authKey = snap.authKeys[ident.uppercase(Locale.ROOT)],
                        firstSampleSec = s?.min,
                        lastSampleSec = s?.max,
                        sampleCount = s?.count ?: 0L,
                    )
                }
            }
            devices.sortWith(
                compareByDescending<Device> { it.type == GbUnits.BAND_9_ACTIVE }.thenByDescending { it.sampleCount },
            )
            progress.report("done", 1.0)
            Overview(snap.meta, devices, xiaomiRows)
        }
    }

    private val XIAOMI_TABLES = listOf(
        "XIAOMI_ACTIVITY_SAMPLE", "XIAOMI_SLEEP_TIME_SAMPLE", "XIAOMI_SLEEP_STAGE_SAMPLE",
        "XIAOMI_DAILY_SUMMARY_SAMPLE", "XIAOMI_MANUAL_SAMPLE",
    )

    private fun str(c: Cursor, p: Projection, prop: String): String? =
        p.present[prop]?.let { col -> c.getColumnIndex(col).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) } }

    private fun q(id: String) = KodoBackupFormat.quote(id)

    // ------------------------------------------------------------- import

    fun import(uri: String, address: String, progress: ProgressGate): ImportCounts {
        val snap = snapshot(uri, progress)
        val counts = ImportCounts()
        try {
            withDb(snap) { db ->
                val schema = Schema(db)
                val deviceTable = schema.table("DEVICE")
                    ?: throw DataPortException(DataPortException.NOT_GADGETBRIDGE, "database has no DEVICE table")
                val idCol = schema.column(deviceTable, "_id")
                val identCol = schema.column(deviceTable, "IDENTIFIER")
                if (idCol == null || identCol == null) {
                    throw DataPortException(DataPortException.NOT_GADGETBRIDGE, "DEVICE table has an unknown layout")
                }
                val deviceId = db.rawQuery(
                    "SELECT ${q(idCol)} FROM ${q(deviceTable)} WHERE UPPER(${q(identCol)}) = UPPER(?)",
                    arrayOf(address),
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                    ?: throw DataPortException(DataPortException.DEVICE_NOT_FOUND, "no device $address in this export")

                val totals = XIAOMI_TABLES.plus("BASE_ACTIVITY_SUMMARY").sumOf { countFor(db, schema, it, deviceId) }
                    .coerceAtLeast(1L)
                val tracker = Tracker(progress, totals)
                progress.report("importing", 0.0)

                importActivity(db, schema, deviceId, counts, tracker)
                importSleepTimes(db, schema, deviceId, counts, tracker)
                importSleepStages(db, schema, deviceId, counts, tracker)
                importDailySummaries(db, schema, deviceId, counts, tracker)
                importManual(db, schema, deviceId, counts, tracker)
                importWorkouts(db, schema, deviceId, counts, tracker)
            }
        } finally {
            release()
        }
        progress.report("done", 1.0)
        return counts
    }

    private class Tracker(private val progress: ProgressGate, private val total: Long) {
        private var done = 0L
        fun add(n: Int) {
            done += n
            progress.report("importing", (done.toDouble() / total).coerceAtMost(0.99))
        }
    }

    private fun countFor(db: SQLiteDatabase, schema: Schema, table: String, deviceId: Long): Long {
        val t = schema.table(table) ?: return 0L
        val dev = schema.column(t, "DEVICE_ID") ?: return 0L
        return db.rawQuery("SELECT COUNT(*) FROM ${q(t)} WHERE ${q(dev)} = ?", arrayOf(deviceId.toString())).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    /**
     * Keyset pagination over (DEVICE_ID, [keyProp]) — every Xiaomi sample table
     * is keyed by (TIMESTAMP, DEVICE_ID) upstream, BASE_ACTIVITY_SUMMARY by _id —
     * so a table with millions of rows is read in [pageSize] slices without
     * cursor-window refills.
     */
    private fun pages(
        db: SQLiteDatabase,
        schema: Schema,
        tableName: String,
        deviceId: Long,
        keyProp: String,
        props: List<String>,
        pageSize: Int,
        onPage: (Projection, Cursor) -> Int,
    ) {
        val t = schema.table(tableName) ?: return
        val dev = schema.column(t, "DEVICE_ID") ?: return
        val key = schema.column(t, keyProp) ?: return
        val proj = Projection(schema, t, (props + keyProp).distinct())
        var last = Long.MIN_VALUE
        while (true) {
            val n = db.rawQuery(
                "SELECT ${proj.sql} FROM ${q(t)} WHERE ${q(dev)} = ? AND ${q(key)} > ? ORDER BY ${q(key)} LIMIT $pageSize",
                arrayOf(deviceId.toString(), last.toString()),
            ).use { c ->
                proj.bind(c)
                var rows = 0
                var maxKey = last
                while (c.moveToNext()) {
                    proj.long(c, keyProp)?.let { if (it > maxKey) maxKey = it }
                    rows++
                }
                c.moveToPosition(-1)
                if (rows > 0) onPage(proj, c)
                val advanced = maxKey > last
                last = maxKey
                if (advanced) rows else 0
            }
            if (n < pageSize) break
        }
    }

    private fun importActivity(db: SQLiteDatabase, schema: Schema, deviceId: Long, counts: ImportCounts, tr: Tracker) {
        val props = listOf("TIMESTAMP", "STEPS", "HEART_RATE", "SPO2", "STRESS", "ACTIVE_CALORIES", "DISTANCE_CM", "ENERGY")
        pages(db, schema, "XIAOMI_ACTIVITY_SAMPLE", deviceId, "TIMESTAMP", props, BATCH) { p, c ->
            val batch = ArrayList<XiaomiActivitySample>(BATCH)
            while (c.moveToNext()) {
                val ts = p.long(c, "TIMESTAMP") ?: continue // seconds (AbstractActivitySample)
                batch += XiaomiActivitySample(
                    timestampSec = ts,
                    steps = GbUnits.measured(p.long(c, "STEPS"), NOT_MEASURED),
                    heartRate = GbUnits.heartRate(p.long(c, "HEART_RATE"), NOT_MEASURED),
                    spo2 = GbUnits.measured(p.long(c, "SPO2"), NOT_MEASURED),
                    stress = GbUnits.measured(p.long(c, "STRESS"), NOT_MEASURED),
                    activeCalories = GbUnits.measured(p.long(c, "ACTIVE_CALORIES"), NOT_MEASURED),
                    distanceCm = GbUnits.measured(p.long(c, "DISTANCE_CM"), NOT_MEASURED),
                    energy = GbUnits.measured(p.long(c, "ENERGY"), NOT_MEASURED),
                )
                counts.days.add(ts)
            }
            SampleStore.upsertActivitySamples(batch)
            counts.activitySamples += batch.size
            tr.add(batch.size)
            batch.size
        }
    }

    private fun importSleepTimes(db: SQLiteDatabase, schema: Schema, deviceId: Long, counts: ImportCounts, tr: Tracker) {
        val props = listOf(
            "TIMESTAMP", "WAKEUP_TIME", "IS_AWAKE", "TOTAL_DURATION", "DEEP_SLEEP_DURATION",
            "LIGHT_SLEEP_DURATION", "REM_SLEEP_DURATION", "AWAKE_DURATION",
        )
        pages(db, schema, "XIAOMI_SLEEP_TIME_SAMPLE", deviceId, "TIMESTAMP", props, BATCH) { p, c ->
            val batch = ArrayList<SleepSummary>()
            var seen = 0
            while (c.moveToNext()) {
                seen++
                val bed = p.long(c, "TIMESTAMP")?.let(GbUnits::msToSec) ?: continue
                val wake = p.long(c, "WAKEUP_TIME")?.let(GbUnits::msToSec)?.takeIf { it > bed } ?: continue
                batch += SleepSummary(
                    bedTimeSec = bed,
                    wakeupTimeSec = wake,
                    totalMinutes = GbUnits.intOrNull(p.long(c, "TOTAL_DURATION")),
                    deepMinutes = GbUnits.intOrNull(p.long(c, "DEEP_SLEEP_DURATION")),
                    lightMinutes = GbUnits.intOrNull(p.long(c, "LIGHT_SLEEP_DURATION")),
                    remMinutes = GbUnits.intOrNull(p.long(c, "REM_SLEEP_DURATION")),
                    awakeMinutes = GbUnits.intOrNull(p.long(c, "AWAKE_DURATION")),
                    isAwake = (p.long(c, "IS_AWAKE") ?: 0L) != 0L,
                )
                counts.days.add(wake)
            }
            if (batch.isNotEmpty()) SampleStore.upsertSleep(batch, emptyList())
            counts.sleepSessions += batch.size
            tr.add(seen)
            seen
        }
    }

    private fun importSleepStages(db: SQLiteDatabase, schema: Schema, deviceId: Long, counts: ImportCounts, tr: Tracker) {
        pages(db, schema, "XIAOMI_SLEEP_STAGE_SAMPLE", deviceId, "TIMESTAMP", listOf("TIMESTAMP", "STAGE"), BATCH) { p, c ->
            val batch = ArrayList<SleepStageSample>()
            var seen = 0
            while (c.moveToNext()) {
                seen++
                val ts = p.long(c, "TIMESTAMP")?.let(GbUnits::msToSec) ?: continue
                val stage = GbUnits.intOrNull(p.long(c, "STAGE")) ?: continue
                batch += SleepStageSample(ts, stage)
            }
            if (batch.isNotEmpty()) SampleStore.upsertSleep(emptyList(), batch)
            counts.sleepStages += batch.size
            tr.add(seen)
            seen
        }
    }

    private fun importDailySummaries(db: SQLiteDatabase, schema: Schema, deviceId: Long, counts: ImportCounts, tr: Tracker) {
        val props = listOf(
            "TIMESTAMP", "TIMEZONE", "STEPS", "ACTIVE_CALORIES", "HR_RESTING", "HR_MAX", "HR_MAX_TS", "HR_MIN",
            "HR_MIN_TS", "HR_AVG", "STRESS_AVG", "STRESS_MAX", "STRESS_MIN", "STANDING", "CALORIES",
            "RECOVERY_HOURS", "SPO2_MAX", "SPO2_MAX_TS", "SPO2_MIN", "SPO2_MIN_TS", "SPO2_AVG",
            "TRAINING_LOAD_DAY", "TRAINING_LOAD_WEEK", "TRAINING_LOAD_LEVEL", "VITALITY_INCREASE_LIGHT",
            "VITALITY_INCREASE_MODERATE", "VITALITY_INCREASE_HIGH", "VITALITY_CURRENT",
        )
        pages(db, schema, "XIAOMI_DAILY_SUMMARY_SAMPLE", deviceId, "TIMESTAMP", props, BATCH) { p, c ->
            val batch = ArrayList<DailySummarySample>()
            var seen = 0
            while (c.moveToNext()) {
                seen++
                val ts = p.long(c, "TIMESTAMP")?.let(GbUnits::msToSec) ?: continue
                // Always set upstream (DailySummaryParser: fileId timezone); without
                // it we can't place the summary on a day, so the row is skipped.
                val tz = GbUnits.intOrNull(p.long(c, "TIMEZONE")) ?: continue
                fun i(prop: String) = GbUnits.intOrNull(p.long(c, prop))
                fun l(prop: String) = p.long(c, prop)
                batch += DailySummarySample(
                    timestampSec = ts,
                    timezone = tz,
                    steps = i("STEPS"),
                    activeCalories = i("ACTIVE_CALORIES"),
                    hrResting = i("HR_RESTING"),
                    hrMax = i("HR_MAX"),
                    hrMaxTs = l("HR_MAX_TS"),
                    hrMin = i("HR_MIN"),
                    hrMinTs = l("HR_MIN_TS"),
                    hrAvg = i("HR_AVG"),
                    stressAvg = i("STRESS_AVG"),
                    stressMax = i("STRESS_MAX"),
                    stressMin = i("STRESS_MIN"),
                    standing = i("STANDING"),
                    calories = i("CALORIES"),
                    recoveryHours = i("RECOVERY_HOURS"),
                    spo2Max = i("SPO2_MAX"),
                    spo2MaxTs = l("SPO2_MAX_TS"),
                    spo2Min = i("SPO2_MIN"),
                    spo2MinTs = l("SPO2_MIN_TS"),
                    spo2Avg = i("SPO2_AVG"),
                    trainingLoadDay = i("TRAINING_LOAD_DAY"),
                    trainingLoadWeek = i("TRAINING_LOAD_WEEK"),
                    trainingLoadLevel = i("TRAINING_LOAD_LEVEL"),
                    vitalityIncreaseLight = i("VITALITY_INCREASE_LIGHT"),
                    vitalityIncreaseModerate = i("VITALITY_INCREASE_MODERATE"),
                    vitalityIncreaseHigh = i("VITALITY_INCREASE_HIGH"),
                    vitalityCurrent = i("VITALITY_CURRENT"),
                )
                counts.days.add(ts)
            }
            if (batch.isNotEmpty()) SampleStore.withDatabase { batch.forEach { SampleStore.upsertDailySummary(it) } }
            counts.dailySummaries += batch.size
            tr.add(seen)
            seen
        }
    }

    private fun importManual(db: SQLiteDatabase, schema: Schema, deviceId: Long, counts: ImportCounts, tr: Tracker) {
        pages(db, schema, "XIAOMI_MANUAL_SAMPLE", deviceId, "TIMESTAMP", listOf("TIMESTAMP", "TYPE", "VALUE"), BATCH) { p, c ->
            val batch = ArrayList<ManualSample>()
            var seen = 0
            while (c.moveToNext()) {
                seen++
                val ts = p.long(c, "TIMESTAMP")?.let(GbUnits::msToSec) ?: continue
                val type = GbUnits.intOrNull(p.long(c, "TYPE")) ?: continue
                val value = GbUnits.intOrNull(p.long(c, "VALUE")) ?: continue
                batch += ManualSample(ts, type, value)
                counts.days.add(ts)
            }
            SampleStore.upsertManualSamples(batch)
            counts.manualSamples += batch.size
            tr.add(seen)
            seen
        }
    }

    /**
     * Workouts are re-derived from RAW_SUMMARY_DATA (the band's own
     * SPORTS/SUMMARY file, repaired like XiaomiActivityParser.fixAndWrap), so
     * the stored row is exactly what a live sync of that file produces. Rows
     * without raw data (non-Xiaomi parsers, or GPX-only summaries) are skipped:
     * our workout table is keyed by the band file id, which only the raw file
     * carries.
     */
    private fun importWorkouts(db: SQLiteDatabase, schema: Schema, deviceId: Long, counts: ImportCounts, tr: Tracker) {
        val props = listOf("_id", "START_TIME", "END_TIME", "RAW_SUMMARY_DATA")
        pages(db, schema, "BASE_ACTIVITY_SUMMARY", deviceId, "_id", props, WORKOUT_BATCH) { p, c ->
            var seen = 0
            while (c.moveToNext()) {
                seen++
                val raw = p.blob(c, "RAW_SUMMARY_DATA")?.takeIf { it.size >= 8 } ?: continue
                val fixed = GbUnits.fixRawActivityFile(raw)
                val fileId = runCatching { XiaomiActivityFileId.from(fixed) }.getOrNull() ?: continue
                if (fileId.type != XiaomiActivityFileId.Type.SPORTS) continue
                val parsed = runCatching { WorkoutSummaryParser.parse(fileId, fixed) }.getOrNull()
                val fields = (parsed as? ActivityFileContent.WorkoutSummary)?.fields
                    ?: WorkoutFields(
                        // Unparseable layout: keep the raw file (a later parser can
                        // re-decode it) plus the end time Gadgetbridge derived.
                        timeEndEpochSec = p.long(c, "END_TIME")?.let(GbUnits::msToSec)
                            ?.takeIf { it > fileId.timestamp.time / 1000L && it <= Int.MAX_VALUE }?.toInt(),
                    )
                runCatching { SampleStore.upsertWorkout(fileId, fields, fixed) }
                    .onSuccess {
                        counts.workouts++
                        counts.days.add(fileId.timestamp.time / 1000L)
                    }
                    .onFailure { Log.w(TAG, "workout ${fileId} not imported", it) }
            }
            tr.add(seen)
            seen
        }
    }

    // ------------------------------------------------------------- helpers for the hybrid

    fun isoOrNull(sec: Long?): String? = sec?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() }

    fun zone(): ZoneId = ZoneId.systemDefault()
}
