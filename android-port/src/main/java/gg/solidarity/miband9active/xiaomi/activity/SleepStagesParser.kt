/*  Copyright (C) 2023-2024 José Rebelo            (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                  (Kotlin port)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Translated from SleepStagesParser.java. Returns a pure data result; the
 *  caller persists into our MMKV/Room layer.
 */
package gg.solidarity.miband9active.xiaomi.activity

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SleepStageSample(val timestampSec: Long, val stage: Int) {
    val kind: Kind get() = Kind.fromCode(stage)
    enum class Kind { AWAKE, LIGHT, DEEP, REM, UNKNOWN;
        companion object {
            fun fromCode(c: Int): Kind = when (c) {
                // Xiaomi codes — observed in the wild via Gadgetbridge tests.
                2 -> LIGHT
                3 -> DEEP
                4 -> REM
                5 -> AWAKE
                else -> UNKNOWN
            }
        }
    }
}

data class SleepSummary(
    val bedTimeSec: Long,
    val wakeupTimeSec: Long,
    val totalMinutes: Int,
    val deepMinutes: Int,
    val lightMinutes: Int,
    val remMinutes: Int,
    val awakeMinutes: Int,
)

data class SleepFile(val summary: SleepSummary, val stages: List<SleepStageSample>)

object SleepStagesParser {

    fun parse(fileId: XiaomiActivityFileId, bytes: ByteArray): SleepFile? {
        if (fileId.version != 2) return null

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            get(ByteArray(7)) // fileId
            get()             // padding
            get(ByteArray(7)) // unk1
        }

        val sleepDuration = buf.short.toInt() and 0xFFFF
        val bedTime = buf.int.toLong() and 0xFFFFFFFFL
        val wakeupTime = buf.int.toLong() and 0xFFFFFFFFL

        buf.get(ByteArray(3)) // unk2

        val deep = buf.short.toInt() and 0xFFFF
        val light = buf.short.toInt() and 0xFFFF
        val rem = buf.short.toInt() and 0xFFFF
        val awake = buf.short.toInt() and 0xFFFF

        if (bedTime == 0L || wakeupTime == 0L || sleepDuration == 0) return null

        buf.get() // unk3 stage marker

        val stages = ArrayList<SleepStageSample>()
        while (buf.position() < buf.limit()) {
            val ts = buf.int.toLong() and 0xFFFFFFFFL
            val phase = buf.get().toInt() and 0xFF
            stages += SleepStageSample(ts, phase)
        }

        return SleepFile(
            summary = SleepSummary(
                bedTimeSec = bedTime,
                wakeupTimeSec = wakeupTime,
                totalMinutes = sleepDuration,
                deepMinutes = deep,
                lightMinutes = light,
                remMinutes = rem,
                awakeMinutes = awake,
            ),
            stages = stages,
        )
    }
}
