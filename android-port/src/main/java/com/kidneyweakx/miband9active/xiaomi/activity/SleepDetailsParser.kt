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
 * Translated from SleepDetailsParser.java (`ACTIVITY_SLEEP`, any detail
 * type, versions 1–5). The per-night HR / SpO2 / snore arrays are skipped
 * exactly like upstream; RR intervals (packet type 1) are consumed but not
 * stored (upstream writes them to HeartPulseSample, which we don't surface).
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import android.util.Log
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SleepDetailsParser {
    private const val TAG = "MB9A_SleepDetails"
    private const val STAGE_PACKET_MAGIC = 0xfffcfafb.toInt()

    fun parse(fileId: XiaomiActivityFileId, bytes: ByteArray): ActivityFileContent? {
        val version = fileId.version
        val headerSize = when (version) {
            1, 2, 3, 4 -> 1
            5 -> 2
            else -> {
                Log.w(TAG, "Unknown sleep details version $version")
                return null
            }
        }

        var headerIdx = 0
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.get(ByteArray(7)) // fileId
        val padding = buf.get()
        if (padding.toInt() != 0) Log.w(TAG, "Expected 0 padding after fileId, got $padding")

        val header = ByteArray(headerSize).also { buf.get(it) }

        val isAwake = buf.get().toInt() and 0xFF // 0/1 — more correctly !isSleepFinish
        headerIdx++
        val bedTime = buf.int.toLong() and 0xFFFFFFFFL
        headerIdx++
        val wakeupTime = buf.int.toLong() and 0xFFFFFFFFL
        headerIdx++

        if (version >= 4) {
            if (XiaomiActivityParser.validData(header, headerIdx)) {
                buf.get() // sleep quality (not surfaced upstream either)
            }
            headerIdx++
        }

        if (version >= 5) {
            buf.get(ByteArray(9))
            buf.int // bedTime2
            buf.int // wakeupTime2
            headerIdx += 5
        }

        // Heart rate samples
        if (XiaomiActivityParser.validData(header, headerIdx)) {
            skipSampleArray(buf, version, bytesPerSample = 1)
        }
        headerIdx++

        // SpO2 samples
        if (XiaomiActivityParser.validData(header, headerIdx)) {
            skipSampleArray(buf, version, bytesPerSample = 1)
        }
        headerIdx++

        // Snore samples (floats)
        if (version >= 3) {
            if (XiaomiActivityParser.validData(header, headerIdx)) {
                skipSampleArray(buf, version, bytesPerSample = 4)
            }
            headerIdx++
        }

        val base = SleepSummary(
            bedTimeSec = bedTime,
            wakeupTimeSec = wakeupTime,
            totalMinutes = null,
            deepMinutes = null,
            lightMinutes = null,
            remMinutes = null,
            awakeMinutes = null,
            isAwake = isAwake == 1,
        )

        val summaries = ArrayList<SleepSummary>()
        val stages = ArrayList<SleepStageSample>()
        var stagesParseFailed = false

        try {
            while (buf.remaining() >= 17) {
                if (!readStagePacketHeader(buf)) break

                buf.get() // header length, always 17
                // Seconds for message types 16 and 17.
                val ts = buf.long
                buf.get() // parity
                val type = buf.get().toInt() and 0xFF
                val dataLen = ((buf.get().toInt() and 0xFF) shl 8) or (buf.get().toInt() and 0xFF)

                if (type == 0x2 || type == 0x3 || type == 0x9 || type == 0xc ||
                    type == 0xd || type == 0xe || type == 0xf
                ) {
                    // The "length" bytes are flags for these; no payload follows.
                    continue
                }

                val data = ByteArray(dataLen).also { buf.get(it) }
                val dataBuf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

                when (type) {
                    16 -> {
                        dataBuf.get() // sleep index (hi nibble) / wake count (lo nibble)
                        val sleepDuration = dataBuf.short.toInt() and 0xFFFF
                        val wakeDuration = dataBuf.short.toInt() and 0xFFFF
                        val lightDuration = dataBuf.short.toInt() and 0xFFFF
                        val remDuration = dataBuf.short.toInt() and 0xFFFF
                        val deepDuration = dataBuf.short.toInt() and 0xFFFF
                        // Upstream keys the summary by bedTime/wakeupTime from the
                        // file header, not the packet timestamp.
                        summaries += base.copy(
                            totalMinutes = sleepDuration,
                            deepMinutes = deepDuration,
                            lightMinutes = lightDuration,
                            remMinutes = remDuration,
                            awakeMinutes = wakeDuration,
                        )
                    }
                    17 -> {
                        var currentTime = ts
                        for (i in 0 until dataLen / 2) {
                            val v = dataBuf.short.toInt() and 0xFFFF
                            val stage = v shr 12
                            val offsetMinutes = v and 0xFFF
                            stages += SleepStageSample(currentTime, decodeStage(stage))
                            currentTime += offsetMinutes * 60L
                        }
                    }
                    else -> Unit // type 1 = RR intervals; others unknown
                }
            }
        } catch (e: BufferUnderflowException) {
            Log.w(TAG, "Buffer underflow while parsing sleep stages", e)
            stagesParseFailed = true
        }

        // Upstream persists every type-16 summary but they share the same
        // bedtime primary key, so effectively the last one wins.
        val session = summaries.lastOrNull() ?: base
        return ActivityFileContent.Sleep(
            fileId = fileId,
            sessions = if (bedTime == 0L || wakeupTime == 0L) emptyList() else listOf(session),
            stages = if (stagesParseFailed) emptyList() else stages,
            complete = !stagesParseFailed,
        )
    }

    private fun skipSampleArray(buf: ByteBuffer, version: Int, bytesPerSample: Int) {
        buf.short // unit (sample rate)
        val count = buf.short.toInt()
        if (count > 0) {
            if (version >= 2) buf.int // firstRecordTime
            buf.position(buf.position() + count * bytesPerSample)
        }
    }

    private fun readStagePacketHeader(buffer: ByteBuffer): Boolean {
        while (buffer.remaining() >= 17) {
            if (buffer.int == STAGE_PACKET_MAGIC) return true
            // roll back to the second byte of the candidate header
            buffer.position(buffer.position() - 3)
        }
        return false
    }

    /** SleepDetailsParser.decodeStage — raw band stage → normalised stage code. */
    private fun decodeStage(rawStage: Int): Int = when (rawStage) {
        0 -> 5 // AWAKE
        1 -> 3 // LIGHT_SLEEP
        2 -> 2 // DEEP_SLEEP
        3 -> 4 // REM_SLEEP
        4 -> 0 // NOT_SLEEP
        else -> 1 // N/A
    }
}
