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
 * Translated from SleepStagesParser.java (`ACTIVITY_SLEEP_STAGES / DETAILS`,
 * version 2). Returns data; SampleStore persists.
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SleepStagesParser {
    private const val TAG = "MB9A_SleepStages"

    fun parse(fileId: XiaomiActivityFileId, bytes: ByteArray): ActivityFileContent? {
        if (fileId.version != 2) {
            Log.w(TAG, "Unknown sleep stages version ${fileId.version}")
            return null
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Upstream does not trim the CRC here and relies on the stage loop
        // throwing on the 4 trailing bytes; we trim it so the loop ends cleanly.
        buf.limit(buf.limit() - 4)
        buf.get(ByteArray(7)) // fileId
        val padding = buf.get()
        if (padding.toInt() != 0) Log.w(TAG, "Expected 0 padding after fileId, got $padding")

        buf.get(ByteArray(7)) // unk1

        val sleepDuration = buf.short.toInt() and 0xFFFF
        val bedTime = buf.int.toLong() and 0xFFFFFFFFL
        val wakeupTime = buf.int.toLong() and 0xFFFFFFFFL

        buf.get(ByteArray(3)) // unk2

        val deep = buf.short.toInt() and 0xFFFF
        val light = buf.short.toInt() and 0xFFFF
        val rem = buf.short.toInt() and 0xFFFF
        val awake = buf.short.toInt() and 0xFFFF

        if (bedTime == 0L || wakeupTime == 0L || sleepDuration == 0) {
            // Upstream: "Ignoring sleep stages sample with no data" and returns true.
            return ActivityFileContent.Empty(fileId, "sleep stages without bedtime/wakeup")
        }

        val session = SleepSummary(
            bedTimeSec = bedTime,
            wakeupTimeSec = wakeupTime,
            totalMinutes = sleepDuration,
            deepMinutes = deep,
            lightMinutes = light,
            remMinutes = rem,
            awakeMinutes = awake,
            isAwake = false,
        )

        buf.get() // unk3
        val stages = ArrayList<SleepStageSample>()
        while (buf.remaining() >= 5) {
            val ts = buf.int.toLong() and 0xFFFFFFFFL
            val phase = buf.get().toInt() and 0xFF
            stages += SleepStageSample(ts, phase)
        }

        return ActivityFileContent.Sleep(fileId, listOf(session), stages)
    }
}
