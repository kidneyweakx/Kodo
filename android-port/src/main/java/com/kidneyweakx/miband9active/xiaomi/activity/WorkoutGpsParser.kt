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
 * Translated from WorkoutGpsParser.java (`SPORTS / GPS_TRACK`, v1–v3,
 * Gadgetbridge 75f923904f). Sample layouts:
 *   v1 (12 B): ts i32, lon f32, lat f32
 *   v2 (18 B): v1 + accuracy f32 (m) + speed u16 (hi 12 bits = 0.1 m/s)
 *   v3 (26 B): v2 + altitude f32 + hdop f32
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WorkoutGpsParser {
    private const val TAG = "MB9A_WorkoutGps"

    fun parse(fileId: XiaomiActivityFileId, bytes: ByteArray): ActivityFileContent? {
        val version = fileId.version
        val headerSize = 1
        val sampleSize = when (version) {
            1 -> 12
            2 -> 18
            3 -> 26
            else -> {
                Log.w(TAG, "Unable to parse workout gps version $version")
                return null
            }
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.limit(buf.limit() - 4) // crc
        buf.get(ByteArray(7))
        val padding = buf.get()
        if (padding.toInt() != 0) Log.w(TAG, "Expected 0 padding after fileId, got $padding")
        buf.position(buf.position() + headerSize)

        if (buf.remaining() % sampleSize != 0) {
            Log.w(TAG, "Remaining ${buf.remaining()} bytes not a multiple of $sampleSize")
        }

        val points = ArrayList<WorkoutGpsPoint>()
        while (buf.remaining() >= sampleSize) {
            val ts = buf.int.toLong() and 0xFFFFFFFFL
            val lon = buf.float.toDouble()
            val lat = buf.float.toDouble()
            var accuracy: Double? = null
            var speed: Double? = null
            var altitude: Double? = null
            if (version >= 2) {
                val acc = buf.float.toDouble()
                val speedRaw = buf.short.toInt() and 0xFFFF
                speed = ((speedRaw and 0xFFF0) shr 4) / 10.0
                accuracy = acc
            }
            if (version >= 3) {
                altitude = buf.float.toDouble()
                accuracy = buf.float.toDouble() // true HDOP on v3
            }
            points += WorkoutGpsPoint(ts, lat, lon, altitude, accuracy, speed)
        }

        // Upstream (XiaomiActivityTrackProvider.hasAnyNonNullIslandLocation): a
        // 13-byte placeholder or an all-(0,0) track means "no fix", not data.
        val usable = points.filter { it.latitude != 0.0 || it.longitude != 0.0 }
        if (usable.isEmpty()) {
            return ActivityFileContent.Empty(fileId, "GPS track without a fix (${bytes.size} bytes)")
        }
        return ActivityFileContent.WorkoutGps(fileId, usable)
    }
}
