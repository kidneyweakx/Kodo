/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo         (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                                   (Kotlin port, slimmed)
 *
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Accumulates [total uint16 LE][current uint16 LE][payload] chunks coming
 *  off the band's ACTIVITY channel and emits parsed files via [onFile] when
 *  `current == total` and the payload's trailing CRC-32 is valid.
 *
 *  Translated from `XiaomiActivityFileFetcher.java`, dropping the GBDevice /
 *  per-device priority-queue scaffolding (we sync one band at a time).
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

sealed class ParsedActivityFile {
    data class DailySamples(val fileId: XiaomiActivityFileId, val samples: List<XiaomiActivitySample>) : ParsedActivityFile()
    data class Sleep(val fileId: XiaomiActivityFileId, val sleep: SleepFile) : ParsedActivityFile()
    data class Workout(val fileId: XiaomiActivityFileId, val fields: WorkoutFields) : ParsedActivityFile()
    data class Unknown(val fileId: XiaomiActivityFileId, val raw: ByteArray) : ParsedActivityFile()

    fun fileId(): XiaomiActivityFileId = when (this) {
        is DailySamples -> fileId
        is Sleep -> fileId
        is Workout -> fileId
        is Unknown -> fileId
    }
}

class XiaomiActivityFileFetcher {

    private var buffer = ByteArrayOutputStream()
    /** Fired exactly once per fully-received, CRC-validated activity file. */
    var onFile: ((ParsedActivityFile) -> Unit)? = null

    fun addChunk(chunk: ByteArray) {
        if (chunk.size < 4) return
        val header = ByteBuffer.wrap(chunk, 0, 4).order(ByteOrder.LITTLE_ENDIAN)
        val total = header.short.toInt() and 0xFFFF
        val current = header.short.toInt() and 0xFFFF

        if (current == 1) {
            buffer = ByteArrayOutputStream()
        }
        buffer.write(chunk, 4, chunk.size - 4)

        if (current != total) return

        // File payload is now in buffer. Validate CRC-32, parse, emit.
        val data = buffer.toByteArray()
        buffer = ByteArrayOutputStream()
        if (data.size < 13) return

        val expectedCrc = ByteBuffer.wrap(data, data.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val actualCrc = CRC32().run { update(data, 0, data.size - 4); value }.toInt()
        if (expectedCrc != actualCrc) return

        val fileId = XiaomiActivityFileId.from(data.copyOfRange(0, 7))
        val parsed: ParsedActivityFile = when {
            fileId.subtype == XiaomiActivityFileId.Subtype.ACTIVITY_DAILY ->
                DailyDetailsParser.parse(fileId, data)
                    ?.let { ParsedActivityFile.DailySamples(fileId, it) }
                    ?: ParsedActivityFile.Unknown(fileId, data)
            fileId.subtype == XiaomiActivityFileId.Subtype.ACTIVITY_SLEEP_STAGES ->
                SleepStagesParser.parse(fileId, data)
                    ?.let { ParsedActivityFile.Sleep(fileId, it) }
                    ?: ParsedActivityFile.Unknown(fileId, data)
            fileId.type == XiaomiActivityFileId.Type.SPORTS ->
                WorkoutSummaryParser.parse(fileId, data)
                    ?.let { ParsedActivityFile.Workout(fileId, it) }
                    ?: ParsedActivityFile.Unknown(fileId, data)
            else -> ParsedActivityFile.Unknown(fileId, data)
        }
        onFile?.invoke(parsed)
    }

    fun reset() {
        buffer = ByteArrayOutputStream()
    }
}
