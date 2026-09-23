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
 * Translated from DailyDetailsParser.java (`ACTIVITY_DAILY / DETAILS`,
 * versions 1-4). The original wrote samples into GreenDAO; this returns the
 * list and SampleStore persists it (upsert by timestamp).
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object DailyDetailsParser {

    /**
     * @return one [XiaomiActivitySample] per minute of the file, in order,
     *         or null when the file version is unsupported.
     */
    fun parse(fileId: XiaomiActivityFileId, bytes: ByteArray): List<XiaomiActivitySample>? {
        val headerSize = when (fileId.version) {
            1, 2 -> 4
            3 -> 5
            4 -> 6
            else -> return null
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            limit(limit() - 4) // drop trailing CRC
            get(ByteArray(7)) // skip 7-byte fileId header
        }
        val padding = buf.get()
        if (padding.toInt() != 0) {
            Log.w("MB9A_DailyDetails", "Expected 0 padding after fileId, got $padding - parsing might fail")
        }

        val header = ByteArray(headerSize).also { buf.get(it) }
        val complex = XiaomiComplexActivityParser(header, buf)

        val samples = ArrayList<XiaomiActivitySample>()
        var minute = 0L

        while (buf.position() < buf.limit()) {
            complex.reset()

            var steps = NOT_MEASURED
            var activeCalories = NOT_MEASURED
            var distanceCm = NOT_MEASURED
            var heartRate = NOT_MEASURED
            var energy = NOT_MEASURED
            var spo2 = NOT_MEASURED
            var stress = NOT_MEASURED
            var includeExtraEntry = 0

            // Group 0 — 16 bits — activity bitfield + steps
            if (complex.nextGroup(16)) {
                if (complex.hasSecond()) includeExtraEntry = complex.get(1, 1)
                if (complex.hasThird()) steps = complex.get(2, 14)
            }
            // Group 1 — 8 bits — active calories
            if (complex.nextGroup(8) && complex.hasSecond()) {
                activeCalories = complex.get(2, 6)
            }
            // Group 2 — reserved
            complex.nextGroup(8)
            // Group 3 — 16 bits — distance (m, multiply by 100 for cm)
            if (complex.nextGroup(16) && complex.hasFirst()) {
                distanceCm = complex.get(0, 16) * 100
            }
            // Group 4 — 8 bits — heart rate
            if (complex.nextGroup(8) && complex.hasFirst()) {
                heartRate = complex.get(0, 8)
            }
            // Group 5 — 8 bits — body energy (vitality)
            if (complex.nextGroup(8) && complex.hasFirst()) {
                energy = complex.get(0, 8)
            }
            // Group 6 — reserved
            complex.nextGroup(16)

            if (fileId.version >= 3) {
                if (complex.nextGroup(8) && complex.hasFirst()) {
                    spo2 = complex.get(0, 8)
                }
                if (complex.nextGroup(8) && complex.hasFirst()) {
                    val s = complex.get(0, 8)
                    if (s != 255) stress = s
                }
            }
            if (includeExtraEntry == 1 && buf.hasRemaining()) {
                buf.get()
            }
            if (fileId.version >= 4) {
                complex.nextGroup(16) // light value (TODO)
                complex.nextGroup(16) // body momentum (TODO)
            }

            val ts = fileId.timestamp.time / 1000L + minute * 60L
            samples += XiaomiActivitySample(
                timestampSec = ts,
                steps = steps,
                heartRate = heartRate,
                spo2 = spo2,
                stress = stress,
                activeCalories = activeCalories,
                distanceCm = distanceCm,
                energy = energy,
            )
            minute++
        }

        return samples
    }
}
