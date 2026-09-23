/*  Copyright (C) 2023-2024 José Rebelo, Yoran Vulker                         (Gadgetbridge)
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
 * Chunk reassembly half of `XiaomiActivityFileFetcher.java`. Each
 * ACTIVITY-channel payload is `[total u16 LE][num u16 LE][bytes…]`; `num == 1`
 * resets the buffer, `num == total` completes the file, whose last 4 bytes
 * are a CRC-32 (LE) over everything before them. The request queue / ack /
 * timeout half lives in `sync/ActivitySync.kt` (coroutines instead of a
 * main-looper Handler).
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class XiaomiActivityFileFetcher {

    sealed class ChunkResult {
        /** More chunks expected. */
        data class Partial(val num: Int, val total: Int) : ChunkResult()

        /** A complete file; [data] includes file id, padding, payload and CRC. */
        class Complete(val fileId: XiaomiActivityFileId, val data: ByteArray) : ChunkResult()

        /** The last chunk arrived but the file is unusable. */
        data class Invalid(val reason: String) : ChunkResult()
    }

    private var buffer = ByteArrayOutputStream()

    fun reset() {
        buffer = ByteArrayOutputStream()
    }

    /** Feed one raw ACTIVITY-channel payload. Mirrors addChunk() upstream. */
    fun assemble(chunk: ByteArray): ChunkResult {
        if (chunk.size < 4) return ChunkResult.Invalid("chunk of ${chunk.size} bytes has no header")
        val total = (chunk[0].toInt() and 0xFF) or ((chunk[1].toInt() and 0xFF) shl 8)
        val num = (chunk[2].toInt() and 0xFF) or ((chunk[3].toInt() and 0xFF) shl 8)

        if (num == 1) buffer = ByteArrayOutputStream()
        buffer.write(chunk, 4, chunk.size - 4)

        if (num != total) return ChunkResult.Partial(num, total)

        val data = buffer.toByteArray()
        buffer = ByteArrayOutputStream()

        if (data.size < 13) return ChunkResult.Invalid("activity data length ${data.size} too short")

        val expected = ByteBuffer.wrap(data, data.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val actual = CRC32().run { update(data, 0, data.size - 4); value.toInt() }
        if (expected != actual) {
            return ChunkResult.Invalid(
                "invalid checksum: got %08X, expected %08X".format(actual, expected),
            )
        }
        if (data[7].toInt() != 0) {
            Log.w(TAG, "Unexpected activity payload byte 0x%02X at position 7 - parsing might fail".format(data[7]))
        }
        val fileId = XiaomiActivityFileId.from(data.copyOfRange(0, 7))
        return ChunkResult.Complete(fileId, data)
    }

    companion object {
        private const val TAG = "MB9A_ActivityFetcher"
    }
}
