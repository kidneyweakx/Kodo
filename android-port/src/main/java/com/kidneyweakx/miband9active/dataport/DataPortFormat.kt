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
 * Pure, dependency-free helpers for the backup / import layer (no Android,
 * no org.json) so the non-trivial rules are reviewable and JVM-testable:
 * manifest validation, secret detection, file naming, file-type sniffing,
 * Gadgetbridge unit conversion and raw-summary repair.
 */
package com.kidneyweakx.miband9active.dataport

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.CRC32

/** Error codes surfaced to JS as `CODE: message`. */
class DataPortException(val code: String, message: String, cause: Throwable? = null) :
    Exception("$code: $message", cause) {
    companion object {
        const val BUSY = "BUSY"
        const val BAD_FILE = "BAD_FILE"
        const val NOT_KODO = "NOT_KODO"
        const val NOT_GADGETBRIDGE = "NOT_GADGETBRIDGE"
        const val NEWER_FORMAT = "NEWER_FORMAT"
        const val DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND"
        const val IO = "IO"
    }
}

object KodoBackupFormat {
    const val FORMAT = "kodo-backup"
    const val FORMAT_VERSION = 1

    const val MANIFEST = "manifest.json"
    const val APP_PREFS = "app/prefs.json"
    const val PREFS_DIR = "prefs/"
    const val SAMPLES_DIR = "samples/"
    const val SAMPLES_EXT = ".jsonl"

    /** Tables never exported / restored: bookkeeping of the sync engine or SQLite/Android internals. */
    private val SKIPPED_TABLES = setOf("android_metadata", "file_log")

    fun isBackedUpTable(name: String): Boolean =
        name !in SKIPPED_TABLES && !name.startsWith("sqlite_") && isSafeIdentifier(name)

    /** Table / column names we are willing to splice into SQL (always double-quoted as well). */
    fun isSafeIdentifier(name: String): Boolean =
        name.isNotEmpty() && name.length <= 64 && name.all { it.isLetterOrDigit() || it == '_' }

    fun quote(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

    /** `kodo-backup-YYYYMMDD-HHmm.zip` in the phone's zone. */
    fun fileName(at: Instant, zone: ZoneId): String =
        "kodo-backup-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)
            .format(LocalDateTime.ofInstant(at, zone)) + ".zip"

    fun samplesEntry(table: String): String = SAMPLES_DIR + table + SAMPLES_EXT

    /** `samples/<table>.jsonl` → table, or null for anything else (incl. path tricks). */
    fun tableFromEntry(entryName: String): String? {
        if (!entryName.startsWith(SAMPLES_DIR) || !entryName.endsWith(SAMPLES_EXT)) return null
        val t = entryName.substring(SAMPLES_DIR.length, entryName.length - SAMPLES_EXT.length)
        return t.takeIf { isSafeIdentifier(it) }
    }

    /** `prefs/<name>.json` → SharedPreferences file name, or null. */
    fun prefsNameFromEntry(entryName: String): String? {
        if (!entryName.startsWith(PREFS_DIR) || !entryName.endsWith(".json")) return null
        val n = entryName.substring(PREFS_DIR.length, entryName.length - ".json".length)
        return n.takeIf { isOwnPrefsName(it) }
    }

    /**
     * SharedPreferences files that belong to the native engine. Every store in
     * the app is named `mb9a_*` or `miband9active_*` (BandStore, FeatureStore,
     * NotificationPrefs, OwmWeather, …); the pre-SQLite sample store is dead
     * weight and skipped. Anything else in shared_prefs/ belongs to libraries
     * (WorkManager, Expo, RN dev settings) and must not travel between phones.
     */
    fun isOwnPrefsName(name: String): Boolean =
        (name.startsWith("mb9a_") || name.startsWith("miband9active_")) &&
            name != LEGACY_SAMPLES_PREFS &&
            isSafeIdentifier(name.replace('.', '_'))

    const val LEGACY_SAMPLES_PREFS = "miband9active_samples"
    const val BAND_STORE_PREFS = "mb9a_band_store"
    const val BAND_KEY = "authKeyHex"

    private val SECRET_KEY = Regex("(?i)(auth.?key|api.?key|secret|token|password|passwd)")

    /** Pref keys that must not leave the phone unless the user opted in. */
    fun isSecretKey(key: String): Boolean = SECRET_KEY.containsMatchIn(key)

    /** Manifest fields relevant for validation, parsed by the caller from JSON. */
    data class Manifest(
        val format: String?,
        val formatVersion: Int?,
        val createdAt: String?,
        val appVersion: String?,
        val storeSchemaVersion: Int?,
        val includesAuthKey: Boolean,
        val bandName: String?,
        val rowCount: Long,
    )

    /** Throws [DataPortException] when [m] is not a restorable Kodō manifest. */
    fun validate(m: Manifest) {
        if (m.format != FORMAT) throw DataPortException(DataPortException.NOT_KODO, "manifest format is '${m.format}'")
        val v = m.formatVersion
            ?: throw DataPortException(DataPortException.BAD_FILE, "manifest has no formatVersion")
        if (v < 1) throw DataPortException(DataPortException.BAD_FILE, "invalid formatVersion $v")
        if (v > FORMAT_VERSION) {
            throw DataPortException(
                DataPortException.NEWER_FORMAT,
                "backup format $v is newer than this app supports ($FORMAT_VERSION) — update Kodō first",
            )
        }
    }
}

/** First bytes of an input, used to tell a zip from a raw SQLite file. */
object FileSniffer {
    enum class Kind { ZIP, SQLITE, OTHER }

    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun kind(head: ByteArray, len: Int = head.size): Kind = when {
        len >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2] == 3.toByte() && head[3] == 4.toByte() -> Kind.ZIP
        len >= SQLITE_MAGIC.size && SQLITE_MAGIC.indices.all { head[it] == SQLITE_MAGIC[it] } -> Kind.SQLITE
        else -> Kind.OTHER
    }

    /**
     * SQLite header bytes 18/19 are the read/write format versions; 2 = WAL.
     * A copy of a *closed* database (which is what Gadgetbridge exports, see
     * GBDatabase.getClosedDBPath) has no pending WAL frames, so flipping them
     * to 1 (legacy rollback journal) lets us open it read-only without a
     * -shm/-wal pair. Returns true when [header] was changed.
     */
    fun downgradeWalHeader(header: ByteArray): Boolean {
        if (header.size < 20) return false
        if (header[18] != 2.toByte() && header[19] != 2.toByte()) return false
        header[18] = 1
        header[19] = 1
        return true
    }
}

/** Gadgetbridge (GreenDAO) → Kodō store unit conversion. */
object GbUnits {
    /** ActivitySample.NOT_MEASURED upstream. */
    const val GB_NOT_MEASURED = -1

    /**
     * Xiaomi protobuf devices (DeviceType names whose coordinator extends
     * XiaomiCoordinator, upstream master 75f9239). Stored in DEVICE.TYPE_NAME.
     */
    val XIAOMI_TYPES = setOf(
        "MIBAND10", "MIBAND10PRO", "MIBAND4C", "MIBAND7PRO", "MIBAND8", "MIBAND8ACTIVE", "MIBAND8PRO",
        "MIBAND9", "MIBAND9ACTIVE", "MIBAND9PRO", "MIWATCHCOLORSPORT", "MIWATCHLITE",
        "REDMISMARTBAND2", "REDMISMARTBAND3", "REDMISMARTBANDPRO", "REDMIWATCH2", "REDMIWATCH2LITE",
        "REDMIWATCH3", "REDMIWATCH3ACTIVE", "REDMIWATCH4", "REDMIWATCH5", "REDMIWATCH5ACTIVE",
        "REDMIWATCH5LITE", "REDMIWATCH6", "REDMIWATCHMOVE", "XIAOMI_WATCH_5", "XIAOMI_WATCH_S1",
        "XIAOMI_WATCH_S1_ACTIVE", "XIAOMI_WATCH_S1_PRO", "XIAOMI_WATCH_S3", "XIAOMI_WATCH_S4",
    )
    const val BAND_9_ACTIVE = "MIBAND9ACTIVE"

    /** AbstractTimeSample.timestamp / wakeupTime / Date columns are epoch milliseconds. */
    fun msToSec(ms: Long): Long = Math.floorDiv(ms, 1000L)

    /**
     * Nullable GB int → our Int with [notMeasured] as "absent". Upstream uses
     * -1 (NOT_MEASURED) for distance/calories/energy/steps and NULL for
     * stress/spo2.
     */
    fun measured(v: Long?, notMeasured: Int): Int =
        if (v == null || v < 0 || v > Int.MAX_VALUE) notMeasured else v.toInt()

    /**
     * XiaomiActivitySample.heartRate is NOT NULL upstream and stays 0 when the
     * minute carried no HR group (DailyDetailsParser), so 0 means "absent".
     */
    fun heartRate(v: Long?, notMeasured: Int): Int =
        if (v == null || v <= 0 || v > Int.MAX_VALUE) notMeasured else v.toInt()

    fun intOrNull(v: Long?): Int? = v?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    /** GreenDAO column name for a property ("heartRate" → "HEART_RATE"), compared loosely. */
    fun looseName(name: String): String = name.filter { it != '_' }.lowercase(Locale.ROOT)

    /**
     * XiaomiActivityParser.fixAndWrap: older Gadgetbridge builds stored raw
     * activity files without the padding byte after the 7-byte file id and
     * without the trailing CRC32. When the CRC over [data] doesn't match,
     * rebuild the file the same way upstream does.
     */
    fun fixRawActivityFile(data: ByteArray): ByteArray {
        if (data.size >= 12) {
            val crc = CRC32().run { update(data, 0, data.size - 4); value.toInt() }
            val stored = (data[data.size - 4].toInt() and 0xFF) or
                ((data[data.size - 3].toInt() and 0xFF) shl 8) or
                ((data[data.size - 2].toInt() and 0xFF) shl 16) or
                ((data[data.size - 1].toInt() and 0xFF) shl 24)
            if (crc == stored) return data
        }
        if (data.size < 7) return data
        val out = ByteArray(data.size + 1 + 4)
        System.arraycopy(data, 0, out, 0, 7)
        out[7] = 0
        System.arraycopy(data, 7, out, 8, data.size - 7)
        val crc = CRC32().run { update(out, 0, out.size - 4); value.toInt() }
        out[out.size - 4] = crc.toByte()
        out[out.size - 3] = (crc ushr 8).toByte()
        out[out.size - 2] = (crc ushr 16).toByte()
        out[out.size - 1] = (crc ushr 24).toByte()
        return out
    }
}

/** Running min/max of epoch seconds → first/last local day. */
class DayRange {
    var minSec: Long? = null
        private set
    var maxSec: Long? = null
        private set

    fun add(sec: Long) {
        if (sec <= 0) return
        minSec = minSec?.let { minOf(it, sec) } ?: sec
        maxSec = maxSec?.let { maxOf(it, sec) } ?: sec
    }

    fun firstDay(zone: ZoneId): String? = minSec?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate().toString() }
    fun lastDay(zone: ZoneId): String? = maxSec?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate().toString() }
}

/** Counts reported back as ImportSummary. */
data class ImportCounts(
    var activitySamples: Long = 0,
    var sleepSessions: Long = 0,
    var sleepStages: Long = 0,
    var dailySummaries: Long = 0,
    var manualSamples: Long = 0,
    var workouts: Long = 0,
    val days: DayRange = DayRange(),
) {
    /** Kodō table → counter, for the generic restore. Unknown tables aren't counted. */
    fun addRestored(table: String, rows: Long) {
        when (table) {
            "activity_sample" -> activitySamples += rows
            "sleep_session" -> sleepSessions += rows
            "sleep_stage" -> sleepStages += rows
            "daily_summary" -> dailySummaries += rows
            "manual_sample" -> manualSamples += rows
            "workout" -> workouts += rows
        }
    }

    companion object {
        /** Kodō table → the epoch-seconds column that places a row on a day (for firstDay/lastDay). */
        fun dayColumn(table: String): String? = when (table) {
            "activity_sample", "daily_summary", "sleep_stage", "manual_sample" -> "ts"
            "sleep_session" -> "wake_ts"
            "workout" -> "start_ts"
            else -> null
        }
    }
}

/** Throttles progress callbacks to ≥ 1 % steps or ≥ 150 ms, always passing 0 and 1. */
class ProgressGate(private val emit: (String, Double) -> Unit) {
    private var lastPhase: String? = null
    private var lastValue = -1.0
    private var lastAt = 0L

    fun report(phase: String, value: Double, nowMs: Long = System.currentTimeMillis()) {
        val v = value.coerceIn(0.0, 1.0)
        val force = phase != lastPhase || v == 0.0 || v == 1.0
        if (!force && v - lastValue < 0.01 && nowMs - lastAt < 150) return
        if (!force && v == lastValue) return
        lastPhase = phase
        lastValue = v
        lastAt = nowMs
        emit(phase, v)
    }
}
