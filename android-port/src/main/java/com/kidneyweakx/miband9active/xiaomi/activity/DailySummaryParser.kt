/*  Copyright (C) 2023-2026 José Rebelo, Dany Mestas                         (Gadgetbridge)
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
 * Translated from DailySummaryParser.java (Gadgetbridge 75f923904f,
 * including fec57bcf83 "parse daily-summary validity bitmap" and 4cf2b29896
 * "Mi Band 9 Active: Fix activity summary parsing" — the Band 9 Active emits
 * version 4, laid out like the Smart Band 8 Active's version 3).
 *
 * Every slot always consumes its bytes; the value is only kept when the
 * slot's bit in the validity bitmap (MSB-first) is set.
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object DailySummaryParser {
    private const val TAG = "MB9A_DailySummary"

    private fun u8(b: ByteBuffer) = b.get().toInt() and 0xFF
    private fun u16(b: ByteBuffer) = b.short.toInt() and 0xFFFF
    private fun s32(b: ByteBuffer) = b.int
    private fun u32(b: ByteBuffer) = b.int.toLong() and 0xFFFFFFFFL

    fun parse(fileId: XiaomiActivityFileId, bytes: ByteArray): ActivityFileContent? {
        val (headerSize, slotCount) = when (fileId.version) {
            3, 4 -> 3 to 21 // Smart Band 8 Active, Mi Band 9 Active
            5 -> 4 to 32    // Mi Band 10 and later
            else -> {
                Log.w(TAG, "Unable to parse daily summary version ${fileId.version}")
                return null
            }
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.get(ByteArray(7)) // fileId
        val padding = buf.get()
        if (padding.toInt() != 0) Log.w(TAG, "Expected 0 padding after fileId, got $padding")

        val header = ByteArray(headerSize).also { buf.get(it) }
        fun valid(i: Int) = XiaomiActivityParser.validData(header, i)

        var s = DailySummarySample(
            timestampSec = fileId.timestamp.time / 1000L,
            timezone = fileId.timezone,
        )

        for (i in 0 until slotCount) {
            val v = valid(i)
            s = when (i) {
                0 -> s32(buf).let { if (v) s.copy(steps = it) else s }
                1 -> u16(buf).let { if (v) s.copy(activeCalories = it) else s }
                2 -> { buf.get(); s }
                3 -> u8(buf).let { if (v) s.copy(hrResting = it) else s }
                4 -> u8(buf).let { if (v) s.copy(hrMax = it) else s }
                5 -> u32(buf).let { if (v) s.copy(hrMaxTs = it) else s }
                6 -> u8(buf).let { if (v) s.copy(hrMin = it) else s }
                7 -> u32(buf).let { if (v) s.copy(hrMinTs = it) else s }
                8 -> u8(buf).let { if (v) s.copy(hrAvg = it) else s }
                9 -> u8(buf).let { if (v) s.copy(stressAvg = it) else s }
                10 -> u8(buf).let { if (v) s.copy(stressMax = it) else s }
                11 -> u8(buf).let { if (v) s.copy(stressMin = it) else s }
                12 -> {
                    val sb = ByteArray(3).also { buf.get(it) }
                    val packed = (sb[0].toInt() and 0xFF) or
                        ((sb[1].toInt() and 0xFF) shl 8) or
                        ((sb[2].toInt() and 0xFF) shl 16)
                    if (v) s.copy(standing = packed) else s
                }
                13 -> u16(buf).let { if (v) s.copy(calories = it) else s }
                14 -> u16(buf).let { if (v) s.copy(recoveryHours = it) else s }
                15 -> { buf.get(); s }
                16 -> u8(buf).let { if (v) s.copy(spo2Max = it) else s }
                17 -> u32(buf).let { if (v) s.copy(spo2MaxTs = it) else s }
                18 -> u8(buf).let { if (v) s.copy(spo2Min = it) else s }
                19 -> u32(buf).let { if (v) s.copy(spo2MinTs = it) else s }
                20 -> u8(buf).let { if (v) s.copy(spo2Avg = it) else s }
                21 -> u16(buf).let { if (v) s.copy(trainingLoadDay = it) else s }
                22 -> u16(buf).let { if (v) s.copy(trainingLoadWeek = it) else s }
                23 -> u8(buf).let { if (v) s.copy(trainingLoadLevel = it) else s }
                24 -> u8(buf).let { if (v) s.copy(vitalityIncreaseLight = it) else s }
                25 -> u8(buf).let { if (v) s.copy(vitalityIncreaseModerate = it) else s }
                26 -> u8(buf).let { if (v) s.copy(vitalityIncreaseHigh = it) else s }
                27 -> u16(buf).let { if (v) s.copy(vitalityCurrent = it) else s }
                28, 29 -> { buf.get(); s }
                30, 31 -> { buf.short; s }
                else -> s
            }
        }

        return ActivityFileContent.DailySummary(fileId, s)
    }
}
