/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo         (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                                  (Kotlin port, slimmed)
 *
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Accumulates `XiaomiChannel.ACTIVITY` chunks coming off MiBand9BleDriver
 *  into whole activity files, parses them, and emits decoded samples.
 *  Stateless besides the in-flight buffer.
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import java.io.ByteArrayOutputStream

sealed class ParsedActivityFile {
    data class DailySamples(val fileId: XiaomiActivityFileId, val samples: List<XiaomiActivitySample>) : ParsedActivityFile()
    data class Sleep(val fileId: XiaomiActivityFileId, val sleep: SleepFile) : ParsedActivityFile()
    data class Unknown(val fileId: XiaomiActivityFileId, val raw: ByteArray) : ParsedActivityFile()
}

class XiaomiActivityFileFetcher {

    private val buffer = ByteArrayOutputStream()
    private var expectedSize: Int = -1
    private var fileId: XiaomiActivityFileId? = null

    fun addChunk(payload: ByteArray) {
        if (fileId == null) {
            // First chunk carries the 7-byte file id (+1 byte pad).
            if (payload.size < 8) return
            fileId = XiaomiActivityFileId.from(payload)
        }
        buffer.write(payload)
    }

    /** Call once the band signals the file is complete. Resets internal state. */
    fun finalizeFile(): ParsedActivityFile? {
        val id = fileId ?: return null
        val bytes = buffer.toByteArray()
        buffer.reset()
        fileId = null

        return when (id.subtype) {
            XiaomiActivityFileId.Subtype.ACTIVITY_DAILY ->
                DailyDetailsParser.parse(id, bytes)
                    ?.let { ParsedActivityFile.DailySamples(id, it) }
                    ?: ParsedActivityFile.Unknown(id, bytes)
            XiaomiActivityFileId.Subtype.ACTIVITY_SLEEP_STAGES ->
                SleepStagesParser.parse(id, bytes)
                    ?.let { ParsedActivityFile.Sleep(id, it) }
                    ?: ParsedActivityFile.Unknown(id, bytes)
            else -> ParsedActivityFile.Unknown(id, bytes)
        }
    }
}
