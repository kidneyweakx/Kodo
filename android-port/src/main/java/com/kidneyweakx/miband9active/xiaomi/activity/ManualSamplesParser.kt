/*  Copyright (C) 2024 José Rebelo                                            (Gadgetbridge)
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
 * Translated from ManualSamplesParser.java (`ACTIVITY_MANUAL_SAMPLES /
 * DETAILS`), including 38b622494d "support v1 manual SpO2 and stress
 * samples" — the Mi Band 9 Active emits version 1.
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ManualSamplesParser {
    private const val TAG = "MB9A_ManualSamples"

    fun parse(fileId: XiaomiActivityFileId, bytes: ByteArray): ActivityFileContent? = when (fileId.version) {
        1 -> decodeVersion1(fileId, bytes)?.let { ActivityFileContent.ManualSamples(fileId, it) }
        2 -> decodeVersion2(fileId, bytes)?.let { ActivityFileContent.ManualSamples(fileId, it) }
        else -> {
            Log.w(TAG, "Unknown manual samples version ${fileId.version}")
            null
        }
    }

    private fun decodeVersion2(fileId: XiaomiActivityFileId, bytes: ByteArray): List<ManualSample>? {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.limit(buf.limit() - 4) // discard crc
        buf.get(ByteArray(7)) // fileId
        val padding = buf.get()
        if (padding.toInt() != 0) Log.w(TAG, "Expected 0 padding after fileId, got $padding")

        // No header; samples start right away: [ts u32][type u8][value…]
        val samples = ArrayList<ManualSample>()
        while (buf.position() < buf.limit()) {
            val ts = buf.int.toLong() and 0xFFFFFFFFL
            val type = buf.get().toInt() and 0xFF
            val value = when (type) {
                ManualSample.TYPE_HR, ManualSample.TYPE_SPO2, ManualSample.TYPE_STRESS ->
                    buf.get().toInt() and 0xFF
                // FIXME upstream: actually 2 × 2-byte values
                ManualSample.TYPE_TEMPERATURE -> buf.int
                else -> {
                    // Unknown sample size — must abort (upstream returns false).
                    Log.w(TAG, "Unknown sample type $type in ${fileId}")
                    return null
                }
            }
            if (value == 0) continue
            samples += ManualSample(ts, type, value)
        }
        return samples
    }

    /**
     * Mi Band 9 Active: 7 file-id bytes, one 0x00 padding byte, a 0xFF header,
     * one or more 6-byte samples [ts u32][type u8][value u8], 4-byte CRC.
     */
    private fun decodeVersion1(fileId: XiaomiActivityFileId, bytes: ByteArray): List<ManualSample>? {
        if (bytes.size < 19) {
            Log.w(TAG, "Manual samples v1 packet too short: ${bytes.size}")
            return null
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.limit(buf.limit() - 4)
        buf.get(ByteArray(7))

        val padding = buf.get()
        if (padding.toInt() != 0) {
            Log.w(TAG, "Expected 0 padding after v1 fileId, got $padding")
            return null
        }
        val header = buf.get().toInt() and 0xFF
        if (header != 0xFF) {
            Log.w(TAG, "Unexpected manual samples v1 header $header")
            return null
        }
        if (buf.remaining() == 0 || buf.remaining() % 6 != 0) {
            Log.w(TAG, "Unexpected manual samples v1 payload length ${buf.remaining()}")
            return null
        }

        val samples = ArrayList<ManualSample>()
        while (buf.hasRemaining()) {
            val ts = buf.int.toLong() and 0xFFFFFFFFL
            val typeV1 = buf.get().toInt() and 0xFF
            val value = buf.get().toInt() and 0xFF
            val type = when (typeV1) {
                0x02 -> ManualSample.TYPE_SPO2
                0x03 -> ManualSample.TYPE_STRESS
                else -> {
                    Log.w(TAG, "Unknown manual samples v1 type $typeV1 in $fileId")
                    return null
                }
            }
            if (value == 0 || value > 100) {
                Log.w(TAG, "Invalid manual samples v1 value $value for type $typeV1")
                return null
            }
            samples += ManualSample(ts, type, value)
        }
        return samples
    }
}
