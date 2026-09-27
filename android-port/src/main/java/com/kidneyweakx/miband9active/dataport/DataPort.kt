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
 * Entry point of the backup / import engine: one job at a time, progress
 * fan-out, IO dispatcher, temp-file hygiene. HybridDataPort only converts
 * types; everything else lives here and in the dataport/ helpers.
 */
package com.kidneyweakx.miband9active.dataport

import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal object DataPort {
    private const val TAG = "MB9A_DataPort"

    enum class Format { KODO, GADGETBRIDGE, UNKNOWN }

    data class Info(
        val format: Format,
        val createdAt: String?,
        val appVersion: String?,
        val rowCount: Long,
        val includesAuthKey: Boolean,
        val bandName: String?,
        val formatVersion: Int?,
    )

    private val listeners = CopyOnWriteArrayList<(String, Double) -> Unit>()
    private val busy = AtomicBoolean(false)

    fun addProgressListener(l: (String, Double) -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun gate() = ProgressGate { phase, v -> listeners.forEach { runCatching { it(phase, v) } } }

    /** Serialises export / restore / import (inspect too: it may copy a large file). */
    private suspend fun <T> job(name: String, block: (ProgressGate) -> T): T {
        if (!busy.compareAndSet(false, true)) {
            throw DataPortException(DataPortException.BUSY, "another backup/import is running")
        }
        try {
            return withContext(Dispatchers.IO) {
                try {
                    block(gate())
                } catch (e: DataPortException) {
                    throw e
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "$name failed", e)
                    throw DataPortException(DataPortException.IO, e.message ?: e.javaClass.simpleName, e)
                } catch (e: android.database.SQLException) {
                    Log.w(TAG, "$name failed", e)
                    throw DataPortException(DataPortException.IO, "database error: ${e.message}", e)
                } catch (e: org.json.JSONException) {
                    throw DataPortException(DataPortException.BAD_FILE, "malformed JSON: ${e.message}", e)
                }
            }
        } finally {
            busy.set(false)
        }
    }

    suspend fun exportBackup(includeAuthKey: Boolean, appPrefsJson: String): KodoBackup.Exported =
        job("export") { KodoBackup.export(includeAuthKey, appPrefsJson, it) }

    suspend fun inspect(uri: String): Info = job("inspect") { p ->
        val head = DataPortFiles.head(uri)
        when (FileSniffer.kind(head)) {
            FileSniffer.Kind.ZIP -> inspectZip(uri, p)
            FileSniffer.Kind.SQLITE -> gbInfo(uri, p)
            FileSniffer.Kind.OTHER -> unknown()
        }
    }

    private fun unknown() = Info(Format.UNKNOWN, null, null, 0, false, null, null)

    /** Kodō writes manifest.json first, so its header is read without touching the rest. */
    private fun inspectZip(uri: String, p: ProgressGate): Info {
        val first = DataPortFiles.open(uri).use { input ->
            java.util.zip.ZipInputStream(input.buffered()).use { zin ->
                val e = zin.nextEntry ?: return@use null
                if (e.name == KodoBackupFormat.MANIFEST) {
                    runCatching { JSONObject(zin.readBytes().toString(Charsets.UTF_8)) }.getOrNull()
                } else {
                    null
                }
            }
        }
        if (first != null && first.optString("format") == KodoBackupFormat.FORMAT) {
            val m = KodoBackup.parseManifest(first)
            val i = KodoBackup.info(m)
            return Info(Format.KODO, i.createdAt, i.appVersion, i.rowCount, i.includesAuthKey, i.bandName, i.formatVersion)
        }
        // Anything else: a Gadgetbridge export zip (database/Gadgetbridge inside) or unknown.
        // The snapshot made here is reused by inspectGadgetbridge / importGadgetbridge.
        return gbInfo(uri, p)
    }

    private fun gbInfo(uri: String, p: ProgressGate): Info {
        val o = try {
            GadgetbridgeImport.overview(uri, p)
        } catch (e: DataPortException) {
            if (e.code == DataPortException.NOT_GADGETBRIDGE) return unknown()
            throw e
        }
        val first = o.devices.firstOrNull()
        return Info(
            format = Format.GADGETBRIDGE,
            createdAt = o.meta?.backupDate,
            appVersion = o.meta?.appVersionName,
            rowCount = o.xiaomiRows,
            includesAuthKey = o.devices.any { it.authKey != null },
            bandName = first?.name,
            formatVersion = o.meta?.backupVersion,
        )
    }

    suspend fun restoreBackup(uri: String): KodoBackup.Restored = job("restore") { p ->
        val kind = FileSniffer.kind(DataPortFiles.head(uri))
        if (kind != FileSniffer.Kind.ZIP) throw DataPortException(DataPortException.NOT_KODO, "not a Kodō backup (zip) file")
        p.report("reading", 0.0)
        val (file, isTemp) = DataPortFiles.materialize(uri)
        try {
            KodoBackup.restore(file, p)
        } catch (e: java.util.zip.ZipException) {
            throw DataPortException(DataPortException.BAD_FILE, "unreadable zip: ${e.message}", e)
        } finally {
            if (isTemp) file.delete()
            DataPortFiles.sweepTemp()
        }
    }

    suspend fun inspectGadgetbridge(uri: String): List<GadgetbridgeImport.Device> =
        job("inspectGadgetbridge") { GadgetbridgeImport.overview(uri, it).devices }

    suspend fun importGadgetbridge(uri: String, deviceAddress: String): ImportCounts =
        job("importGadgetbridge") { GadgetbridgeImport.import(uri, deviceAddress, it) }

    internal fun fileUri(f: File): String = "file://" + f.absolutePath
}
