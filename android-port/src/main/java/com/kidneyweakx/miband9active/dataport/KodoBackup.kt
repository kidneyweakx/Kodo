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
 * Kodō's own backup format (formatVersion 1), a zip of:
 *
 *   manifest.json        always the FIRST entry — {format:"kodo-backup",
 *                        formatVersion, createdAt, appVersion, appVersionCode,
 *                        storeSchemaVersion, includesAuthKey, bandName,
 *                        rowCount, tables:[{name,rows,columns}], prefs:[…],
 *                        strippedKeys:["<prefs>/<key>"], hasAppPrefs}
 *   app/prefs.json       the JS MMKV blob, verbatim
 *   prefs/<name>.json    native SharedPreferences (JsonBackupPreferences shape)
 *   samples/<table>.jsonl one JSON object per row; BLOBs as {"b64": "…"}
 *
 * Tables are enumerated from sqlite_master (the schema grows with the app),
 * so a backup restores into any later schema: only columns that exist now are
 * inserted, with INSERT OR REPLACE (a restore merges; it never deletes).
 * Dump and restore each run inside ONE SampleStore transaction.
 */
package com.kidneyweakx.miband9active.dataport

import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteStatement
import android.os.Build
import android.util.Base64
import android.util.Log
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.BandStore
import com.kidneyweakx.miband9active.SampleStore
import com.kidneyweakx.miband9active.xiaomi.auth.XiaomiCrypto
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

internal object KodoBackup {
    private const val TAG = "MB9A_Backup"
    private const val PAGE = 2000
    private const val MAX_APP_PREFS_BYTES = 8 * 1024 * 1024

    data class Exported(
        val file: File,
        val createdAt: Instant,
        val rowCount: Long,
        val includesAuthKey: Boolean,
    )

    data class Info(
        val createdAt: String?,
        val appVersion: String?,
        val rowCount: Long,
        val includesAuthKey: Boolean,
        val bandName: String?,
        val formatVersion: Int?,
    )

    data class Restored(
        val counts: ImportCounts,
        val appPrefsJson: String?,
        val restoredBand: Boolean,
    )

    // ------------------------------------------------------------------ export

    fun export(includeAuthKey: Boolean, appPrefsJson: String, progress: ProgressGate): Exported {
        DataPortFiles.sweepBackups()
        val createdAt = Instant.now()
        val name = KodoBackupFormat.fileName(createdAt, ZoneId.systemDefault())
        val target = File(DataPortFiles.backupDir(), name)
        val part = File(DataPortFiles.backupDir(), "$name.part")
        progress.report("prefs", 0.0)

        var rowCount = 0L
        var withKey = false
        try {
            BufferedOutputStream(part.outputStream(), 256 * 1024).use { bos ->
                ZipOutputStream(bos).use { zip ->
                    val out = NonClosing(zip)
                    // One transaction: the manifest's counts match the rows, and
                    // a concurrent sync can't interleave half a file.
                    SampleStore.withDatabase {
                        val tables = listTables(this)
                        val counts = tables.associateWith { countRows(this, it) }
                        rowCount = counts.values.sum()

                        val prefsNames = PrefsDump.ownPrefsNames()
                        val dumps = prefsNames.associateWith { PrefsDump.dump(it, includeSecrets = includeAuthKey) }
                        val band = BandStore.load()
                        withKey = includeAuthKey &&
                            dumps[KodoBackupFormat.BAND_STORE_PREFS]?.let { d ->
                                PrefsDump.string(d.json, KodoBackupFormat.BAND_KEY)
                                    ?.let { XiaomiCrypto.normalizeAuthKeyHex(it) } != null
                            } == true

                        val manifest = JSONObject()
                            .put("format", KodoBackupFormat.FORMAT)
                            .put("formatVersion", KodoBackupFormat.FORMAT_VERSION)
                            .put("createdAt", createdAt.toString())
                            .put("appVersion", appVersionName() ?: JSONObject.NULL)
                            .put("appVersionCode", appVersionCode() ?: JSONObject.NULL)
                            .put("storeSchemaVersion", SampleStore.schemaVersion)
                            .put("includesAuthKey", withKey)
                            .put("bandName", band?.name ?: JSONObject.NULL)
                            .put("rowCount", rowCount)
                            .put("hasAppPrefs", true)
                            .put(
                                "tables",
                                JSONArray().apply {
                                    for (t in tables) {
                                        put(
                                            JSONObject()
                                                .put("name", t)
                                                .put("rows", counts[t] ?: 0L)
                                                .put("columns", JSONArray(columns(this@withDatabase, t))),
                                        )
                                    }
                                },
                            )
                            .put("prefs", JSONArray(prefsNames))
                            .put(
                                "strippedKeys",
                                JSONArray(dumps.flatMap { (n, d) -> d.strippedKeys.map { "$n/$it" } }),
                            )
                        writeEntry(zip, KodoBackupFormat.MANIFEST, manifest.toString(2).toByteArray())
                        writeEntry(zip, KodoBackupFormat.APP_PREFS, appPrefsJson.toByteArray())
                        for ((n, d) in dumps) {
                            writeEntry(zip, KodoBackupFormat.PREFS_DIR + n + ".json", d.json.toString(2).toByteArray())
                        }
                        progress.report("samples", 0.0)

                        var done = 0L
                        for (t in tables) {
                            zip.putNextEntry(ZipEntry(KodoBackupFormat.samplesEntry(t)))
                            val w = OutputStreamWriter(out, Charsets.UTF_8)
                            dumpTable(this, t) { row ->
                                w.write(row.toString())
                                w.write('\n'.code)
                                done++
                                if (done % 500 == 0L) {
                                    progress.report("samples", if (rowCount == 0L) 1.0 else done.toDouble() / rowCount * 0.98)
                                }
                            }
                            w.flush()
                            zip.closeEntry()
                        }
                    }
                    progress.report("finishing", 0.99)
                    zip.finish()
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw DataPortException(DataPortException.IO, "could not finalise $name")
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        progress.report("done", 1.0)
        return Exported(target, createdAt, rowCount, withKey)
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    /** Lets an OutputStreamWriter flush into the zip without closing it. */
    private class NonClosing(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    internal fun listTables(db: SQLiteDatabase): List<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", null).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) {
                val n = c.getString(0)
                if (KodoBackupFormat.isBackedUpTable(n)) out += n
            }
            out
        }

    internal fun columns(db: SQLiteDatabase, table: String): List<String> =
        db.rawQuery("PRAGMA table_info(${KodoBackupFormat.quote(table)})", null).use { c ->
            val idx = c.getColumnIndexOrThrow("name")
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(idx)
            out
        }

    private fun countRows(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM ${KodoBackupFormat.quote(table)}", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    /**
     * Streams every row of [table] as a JSON object. Pages by rowid so a
     * large table never needs a cursor window re-fill from the start; falls
     * back to one plain cursor for WITHOUT ROWID tables.
     */
    private fun dumpTable(db: SQLiteDatabase, table: String, sink: (JSONObject) -> Unit) {
        val q = KodoBackupFormat.quote(table)
        val paged = try {
            var last = Long.MIN_VALUE
            while (true) {
                val n = db.rawQuery(
                    "SELECT rowid AS __kodo_rowid, * FROM $q WHERE rowid > ? ORDER BY rowid LIMIT $PAGE",
                    arrayOf(last.toString()),
                ).use { c ->
                    var n = 0
                    val rid = c.getColumnIndexOrThrow("__kodo_rowid")
                    while (c.moveToNext()) {
                        last = c.getLong(rid)
                        sink(rowJson(c, skip = rid))
                        n++
                    }
                    n
                }
                if (n < PAGE) break
            }
            true
        } catch (e: SQLiteException) {
            Log.i(TAG, "rowid paging unavailable for $table (${e.message}); plain cursor")
            false
        }
        if (!paged) {
            db.rawQuery("SELECT * FROM $q", null).use { c -> while (c.moveToNext()) sink(rowJson(c, skip = -1)) }
        }
    }

    private fun rowJson(c: Cursor, skip: Int): JSONObject {
        val o = JSONObject()
        for (i in 0 until c.columnCount) {
            if (i == skip) continue
            val name = c.getColumnName(i)
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> o.put(name, JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> o.put(name, c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).let { if (it.isFinite()) o.put(name, it) else o.put(name, JSONObject.NULL) }
                Cursor.FIELD_TYPE_STRING -> o.put(name, c.getString(i))
                Cursor.FIELD_TYPE_BLOB -> o.put(name, JSONObject().put("b64", Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP)))
            }
        }
        return o
    }

    // ----------------------------------------------------------------- inspect

    fun parseManifest(json: JSONObject): KodoBackupFormat.Manifest = KodoBackupFormat.Manifest(
        format = json.optString("format").takeIf { json.has("format") },
        formatVersion = if (json.has("formatVersion")) json.optInt("formatVersion", -1) else null,
        createdAt = json.optString("createdAt").takeIf { json.has("createdAt") && !json.isNull("createdAt") },
        appVersion = json.optString("appVersion").takeIf { json.has("appVersion") && !json.isNull("appVersion") },
        storeSchemaVersion = if (json.has("storeSchemaVersion")) json.optInt("storeSchemaVersion") else null,
        includesAuthKey = json.optBoolean("includesAuthKey", false),
        bandName = json.optString("bandName").takeIf { json.has("bandName") && !json.isNull("bandName") },
        rowCount = json.optLong("rowCount", 0L),
    )

    fun info(m: KodoBackupFormat.Manifest) = Info(
        createdAt = m.createdAt,
        appVersion = m.appVersion,
        rowCount = m.rowCount,
        includesAuthKey = m.includesAuthKey,
        bandName = m.bandName,
        formatVersion = m.formatVersion,
    )

    // ----------------------------------------------------------------- restore

    fun restore(zipFile: File, progress: ProgressGate): Restored {
        ZipFile(zipFile).use { zip ->
            val manifestEntry = zip.getEntry(KodoBackupFormat.MANIFEST)
                ?: throw DataPortException(DataPortException.NOT_KODO, "no manifest.json in this file")
            val manifestJson = try {
                JSONObject(zip.getInputStream(manifestEntry).use { it.readBytes() }.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                throw DataPortException(DataPortException.BAD_FILE, "manifest.json is not valid JSON", e)
            }
            val manifest = parseManifest(manifestJson)
            KodoBackupFormat.validate(manifest)

            val appPrefs = zip.getEntry(KodoBackupFormat.APP_PREFS)?.let { e ->
                if (e.size > MAX_APP_PREFS_BYTES) throw DataPortException(DataPortException.BAD_FILE, "app/prefs.json too large")
                zip.getInputStream(e).use { it.readBytes() }.toString(Charsets.UTF_8)
            }

            val stripped = HashMap<String, MutableSet<String>>()
            manifestJson.optJSONArray("strippedKeys")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i)
                    val slash = s.indexOf('/')
                    if (slash > 0) stripped.getOrPut(s.substring(0, slash)) { HashSet() } += s.substring(slash + 1)
                }
            }

            // ---- samples (atomic)
            progress.report("samples", 0.0)
            val counts = ImportCounts()
            val total = manifest.rowCount.coerceAtLeast(1L)
            val sampleEntries = zip.entries().asSequence()
                .mapNotNull { e -> KodoBackupFormat.tableFromEntry(e.name)?.let { it to e } }
                .toList()
            SampleStore.withDatabase {
                val current = listTables(this).toSet()
                var done = 0L
                for ((table, entry) in sampleEntries) {
                    if (table !in current) {
                        Log.w(TAG, "backup table '$table' does not exist in this schema; skipped")
                        continue
                    }
                    val cols = columns(this, table).toSet()
                    val dayCol = ImportCounts.dayColumn(table)
                    val stmts = HashMap<List<String>, SQLiteStatement>()
                    var rows = 0L
                    try {
                        BufferedReader(InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8), 256 * 1024).use { r ->
                            while (true) {
                                val line = r.readLine() ?: break
                                if (line.isBlank()) continue
                                val row = try {
                                    JSONObject(line)
                                } catch (e: Exception) {
                                    throw DataPortException(DataPortException.BAD_FILE, "corrupt row in ${entry.name}", e)
                                }
                                val keys = row.keys().asSequence().filter { it in cols }.sorted().toList()
                                if (keys.isEmpty()) continue
                                val st = stmts.getOrPut(keys) {
                                    compileStatement(
                                        "INSERT OR REPLACE INTO ${KodoBackupFormat.quote(table)} (" +
                                            keys.joinToString(",") { KodoBackupFormat.quote(it) } +
                                            ") VALUES (" + keys.joinToString(",") { "?" } + ")",
                                    )
                                }
                                st.clearBindings()
                                keys.forEachIndexed { i, k -> bind(st, i + 1, row.opt(k)) }
                                st.executeInsert()
                                rows++
                                if (dayCol != null && row.has(dayCol)) {
                                    val v = row.opt(dayCol)
                                    if (v is Number) counts.days.add(v.toLong())
                                }
                                done++
                                if (done % 500 == 0L) progress.report("samples", (done.toDouble() / total * 0.9).coerceAtMost(0.9))
                            }
                        }
                    } finally {
                        stmts.values.forEach { it.close() }
                    }
                    counts.addRestored(table, rows)
                }
            }

            // ---- native prefs (after the samples committed; BandStore last)
            progress.report("prefs", 0.92)
            val prefEntries = zip.entries().asSequence()
                .mapNotNull { e -> KodoBackupFormat.prefsNameFromEntry(e.name)?.let { it to e } }
                .toList()
            var bandJson: JSONObject? = null
            for ((name, entry) in prefEntries) {
                val json = try {
                    JSONObject(zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8))
                } catch (e: Exception) {
                    Log.w(TAG, "skipping unreadable prefs $name", e)
                    continue
                }
                if (name == KodoBackupFormat.BAND_STORE_PREFS) {
                    bandJson = json
                    continue
                }
                PrefsDump.restore(name, json, stripped[name].orEmpty())
                NativeStateHooks.afterPrefsRestored(name, json)
            }
            val restoredBand = bandJson?.let { restoreBand(it) } ?: false
            NativeStateHooks.afterRestore(restoredBand)
            progress.report("done", 1.0)
            return Restored(counts, appPrefs, restoredBand)
        }
    }

    /**
     * The paired band is only restored when the backup carries a valid key —
     * a key-less backup never clobbers the band paired on this phone.
     */
    private fun restoreBand(json: JSONObject): Boolean {
        val id = PrefsDump.string(json, "id")?.takeIf { it.isNotBlank() } ?: return false
        val key = PrefsDump.string(json, KodoBackupFormat.BAND_KEY)?.let { XiaomiCrypto.normalizeAuthKeyHex(it) }
            ?: return false
        BandStore.save(
            BandStore.StoredBand(
                id = id,
                name = PrefsDump.string(json, "name") ?: "Mi Band 9 Active",
                authKeyHex = key,
                pairedAtIso = PrefsDump.string(json, "pairedAtIso") ?: "",
            ),
        )
        return true
    }

    private fun bind(st: SQLiteStatement, i: Int, v: Any?) {
        when (v) {
            null, JSONObject.NULL -> st.bindNull(i)
            is Boolean -> st.bindLong(i, if (v) 1L else 0L)
            is Int -> st.bindLong(i, v.toLong())
            is Long -> st.bindLong(i, v)
            is Number -> st.bindDouble(i, v.toDouble())
            is String -> st.bindString(i, v)
            is JSONObject -> {
                val b64 = v.optString("b64", "")
                if (v.has("b64")) st.bindBlob(i, Base64.decode(b64, Base64.NO_WRAP)) else st.bindString(i, v.toString())
            }
            else -> st.bindString(i, v.toString())
        }
    }

    private fun appVersionName(): String? = runCatching {
        val ctx = AppContext.context
        packageInfo(ctx.packageManager, ctx.packageName).versionName
    }.getOrNull()

    private fun appVersionCode(): Long? = runCatching {
        val ctx = AppContext.context
        val pi = packageInfo(ctx.packageManager, ctx.packageName)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrNull()

    private fun packageInfo(pm: PackageManager, pkg: String) =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
        }
}
