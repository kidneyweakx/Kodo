/*  Copyright (C) 2023-2024 José Rebelo                  (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                        (Kotlin port)
 *
 *  Translated from DailyDetailsParser.java. The original wrote samples into
 *  GreenDAO; our port returns the list and lets the caller persist.
 *
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package gg.solidarity.miband9active.xiaomi.activity

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
            get() // skip padding byte
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
