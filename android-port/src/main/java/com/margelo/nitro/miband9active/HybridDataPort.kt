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
 * JS facade over dataport/DataPort (Kodō backup / restore, Gadgetbridge
 * import). Type conversion only; all work runs on Dispatchers.IO inside
 * DataPort, one job at a time.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.dataport.DataPort
import com.kidneyweakx.miband9active.dataport.GadgetbridgeImport
import com.kidneyweakx.miband9active.dataport.ImportCounts
import com.margelo.nitro.core.NullType
import com.margelo.nitro.core.Promise
import java.time.ZoneId

class HybridDataPort : HybridHybridDataPortSpec() {

    override fun exportBackup(includeAuthKey: Boolean, appPrefsJson: String): Promise<BackupSummary> = Promise.async {
        val r = DataPort.exportBackup(includeAuthKey, appPrefsJson)
        BackupSummary(
            uri = "file://" + r.file.absolutePath,
            fileName = r.file.name,
            sizeBytes = r.file.length().toDouble(),
            createdAt = r.createdAt.toString(),
            rowCount = r.rowCount.toDouble(),
            includesAuthKey = r.includesAuthKey,
        )
    }

    override fun inspect(uri: String): Promise<BackupInfo> = Promise.async {
        val i = DataPort.inspect(uri)
        BackupInfo(
            format = when (i.format) {
                DataPort.Format.KODO -> BackupFormat.KODO
                DataPort.Format.GADGETBRIDGE -> BackupFormat.GADGETBRIDGE
                DataPort.Format.UNKNOWN -> BackupFormat.UNKNOWN
            },
            createdAt = str(i.createdAt),
            appVersion = str(i.appVersion),
            rowCount = i.rowCount.toDouble(),
            includesAuthKey = i.includesAuthKey,
            bandName = str(i.bandName),
            formatVersion = i.formatVersion?.let { Variant_NullType_Double.create(it.toDouble()) }
                ?: Variant_NullType_Double.create(NullType.NULL),
        )
    }

    override fun restoreBackup(uri: String): Promise<RestoreResult> = Promise.async {
        val r = DataPort.restoreBackup(uri)
        RestoreResult(
            imported = r.counts.toNitro(),
            appPrefsJson = str(r.appPrefsJson),
            restoredBand = r.restoredBand,
        )
    }

    override fun inspectGadgetbridge(uri: String): Promise<Array<GadgetbridgeDevice>> = Promise.async {
        DataPort.inspectGadgetbridge(uri).map { d ->
            GadgetbridgeDevice(
                address = d.address,
                name = d.name,
                type = d.type,
                authKey = str(d.authKey),
                // XIAOMI_ACTIVITY_SAMPLE.TIMESTAMP is epoch seconds.
                firstSampleAt = str(GadgetbridgeImport.isoOrNull(d.firstSampleSec)),
                lastSampleAt = str(GadgetbridgeImport.isoOrNull(d.lastSampleSec)),
                sampleCount = d.sampleCount.toDouble(),
            )
        }.toTypedArray()
    }

    override fun importGadgetbridge(uri: String, deviceAddress: String): Promise<ImportSummary> = Promise.async {
        DataPort.importGadgetbridge(uri, deviceAddress).toNitro()
    }

    override fun onProgress(listener: (phase: String, progress: Double) -> Unit): () -> Unit =
        DataPort.addProgressListener(listener)

    private fun str(s: String?): Variant_NullType_String =
        if (s == null) Variant_NullType_String.create(NullType.NULL) else Variant_NullType_String.create(s)

    private fun ImportCounts.toNitro(): ImportSummary {
        val zone = ZoneId.systemDefault()
        return ImportSummary(
            activitySamples = activitySamples.toDouble(),
            sleepSessions = sleepSessions.toDouble(),
            sleepStages = sleepStages.toDouble(),
            dailySummaries = dailySummaries.toDouble(),
            manualSamples = manualSamples.toDouble(),
            workouts = workouts.toDouble(),
            firstDay = str(days.firstDay(zone)),
            lastDay = str(days.lastDay(zone)),
        )
    }
}
