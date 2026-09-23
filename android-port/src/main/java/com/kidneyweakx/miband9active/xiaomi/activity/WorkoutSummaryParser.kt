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
 * Translated from WorkoutSummaryParser.java (Gadgetbridge 75f923904f) for
 * every sport subtype/version upstream knows. Each builder is copied
 * field-for-field up to the last field we surface (HR min, or the Xiaomi
 * workout type for freestyle); fields we don't surface are `addUnknown(size)`
 * with the upstream field width so offsets are identical.
 *
 * Like upstream, an unsupported (subtype, version) still yields a summary
 * row keyed by the file-id timestamp with the raw bytes kept, so a future
 * parser can re-decode it. No values are guessed for it.
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import android.util.Log
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileId.Subtype
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiSimpleField as F
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WorkoutSummaryParser {
    private const val TAG = "MB9A_WorkoutSummary"

    fun parse(fileId: XiaomiActivityFileId, data: ByteArray): ActivityFileContent? {
        if (data.size < 8) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.get(ByteArray(7)) // fileId
        val padding = buf.get()
        if (padding.toInt() != 0) Log.w(TAG, "Expected 0 padding after fileId, got $padding")

        val parser = parserFor(fileId)
        if (parser == null) {
            Log.w(TAG, "No workout summary parser for subtype=${fileId.subtypeCode} v${fileId.version}; keeping raw bytes")
            return ActivityFileContent.WorkoutSummary(fileId, WorkoutFields(), data)
        }
        val fields = parser.parse(buf)
        if (!fields.complete) {
            // Upstream throws (BufferUnderflow) and reports failure.
            Log.w(TAG, "Workout summary $fileId shorter than its layout")
            return null
        }
        return ActivityFileContent.WorkoutSummary(fileId, fields, data)
    }

    private fun parserFor(fileId: XiaomiActivityFileId): XiaomiSimpleActivityParser? {
        val v = fileId.version
        return when (fileId.subtype) {
            Subtype.SPORTS_OUTDOOR_WALKING_V1,
            Subtype.SPORTS_OUTDOOR_RUNNING -> outdoorWalkingV1(v)
            Subtype.SPORTS_INDOOR_CYCLING -> indoorCycling(v)
            Subtype.SPORTS_FREESTYLE -> freestyle(v)
            Subtype.SPORTS_POOL_SWIMMING -> poolSwimming(v)
            Subtype.SPORTS_HIIT -> hiit(v)
            Subtype.SPORTS_ELLIPTICAL -> elliptical(v)
            Subtype.SPORTS_OUTDOOR_WALKING_V2 -> outdoorWalkingV2(v)
            Subtype.SPORTS_OUTDOOR_CYCLING_V2 -> outdoorCyclingV2(v)
            Subtype.SPORTS_OUTDOOR_CYCLING -> outdoorCycling(v)
            Subtype.SPORTS_TREADMILL -> treadmill(v)
            Subtype.SPORTS_ROWING -> rowing(v)
            Subtype.SPORTS_JUMP_ROPING -> jumpRoping(v)
            else -> null
        }
    }

    private fun b(headerSize: Int) = XiaomiSimpleActivityParser.Builder().setHeaderSize(headerSize)

    private fun XiaomiSimpleActivityParser.Builder.hrTriplet() = this
        .addByte(F.HR_AVG)
        .addByte(F.HR_MAX)
        .addByte(F.HR_MIN)

    private fun XiaomiSimpleActivityParser.Builder.times() = this
        .addInt(F.TIME_START)
        .addInt(F.TIME_END)
        .addInt(F.ACTIVE_SECONDS)

    private fun freestyle(v: Int): XiaomiSimpleActivityParser? {
        val hs = when (v) {
            5 -> 3
            7 -> 5
            8, 9, 10 -> 6
            else -> return null
        }
        val bld = b(hs).times()
            .addShort(F.CALORIES)
            .hrTriplet()
        if (v > 5) bld.addUnknown(6)
        bld.addFloat() // TRAINING_EFFECT_AEROBIC
        if (v > 6) bld.addUnknown(1)
        bld.addUnknown(1)
        bld.addShort() // RECOVERY_TIME
        repeat(5) { bld.addInt() } // HR zones extreme..warm-up
        if (v == 5) {
            bld.addUnknown(10)
            bld.addShort(F.WORKOUT_TYPE)
        } else {
            bld.addShort(F.CALORIES_TOTAL)
            bld.addUnknown(4)
            bld.addFloat() // TRAINING_EFFECT_ANAEROBIC
            bld.addUnknown(1)
            bld.addShort(F.WORKOUT_TYPE)
        }
        return bld.build()
    }

    private fun indoorCycling(v: Int): XiaomiSimpleActivityParser? {
        val hs = when (v) {
            8 -> 7
            9 -> 8
            else -> return null
        }
        return b(hs).times()
            .addUnknown(4)
            .addShort(F.CALORIES)
            .addUnknown(4)
            .hrTriplet()
            .build()
    }

    private fun outdoorWalkingV1(v: Int): XiaomiSimpleActivityParser? {
        if (v != 4) return null
        return b(4).times()
            .addInt(F.DISTANCE_METERS)
            .addInt(F.CALORIES)
            .addInt() // PACE_MAX
            .addInt() // PACE_MIN
            .addUnknown(4)
            .addInt(F.STEPS)
            .addUnknown(2)
            .hrTriplet()
            .build()
    }

    private fun outdoorWalkingV2(v: Int): XiaomiSimpleActivityParser? {
        val hs = when (v) {
            1 -> 5 // Smart Band 8 Active
            2 -> 6 // Redmi Watch 3
            4 -> 7
            5, 6 -> 9
            9 -> 13
            else -> return null
        }
        val bld = b(hs)
            .addShort(F.WORKOUT_TYPE)
            .times()
            .addUnknown(4) // validDuration
            .addInt(F.DISTANCE_METERS)
            .addShort(F.CALORIES_TOTAL)
            .addShort(F.CALORIES)
        if (v >= 5) bld.addInt() // PACE_AVG
        bld.addInt() // PACE_MAX
        bld.addInt() // PACE_MIN
        if (v >= 5) bld.addFloat() // SPEED_AVG
        bld.addFloat() // SPEED_MAX
        bld.addInt(F.STEPS)
        if (v >= 5) {
            bld.addShort() // STEP_LENGTH_AVG
            bld.addShort() // STEP_RATE_AVG
        }
        bld.addShort() // STEP_RATE_MAX
        bld.hrTriplet()
        return bld.build()
    }

    private fun outdoorCycling(v: Int): XiaomiSimpleActivityParser? {
        val hs = when (v) {
            4, 5 -> 6
            6 -> 7
            else -> return null
        }
        val bld = b(hs)
            .addShort(F.WORKOUT_TYPE)
            .times()
            .addUnknown(4)
            .addInt(F.DISTANCE_METERS)
            .addUnknown(2)
            .addShort(F.CALORIES)
            .addUnknown(4)
            .addUnknown(4)
        if (v >= 5) bld.addFloat() // SPEED_AVG
        bld.addFloat() // SPEED_MAX
        bld.hrTriplet()
        return bld.build()
    }

    private fun hiit(v: Int): XiaomiSimpleActivityParser? {
        if (v != 5) return null
        return b(4).times()
            .addShort(F.CALORIES)
            .hrTriplet()
            .build()
    }

    private fun poolSwimming(v: Int): XiaomiSimpleActivityParser? {
        val hs = when (v) {
            6 -> 4
            7 -> 5
            8 -> 8
            else -> return null
        }
        // No HR in pool-swim summaries upstream; stop after calories.
        return b(hs).times()
            .addInt(F.DISTANCE_METERS)
            .addShort(F.CALORIES)
            .build()
    }

    private fun elliptical(v: Int): XiaomiSimpleActivityParser? {
        if (v !in 3..6) return null
        val bld = b(4).times()
            .addShort(F.CALORIES)
            .addInt(F.STEPS)
        if (v >= 6) bld.addShort() // CADENCE_AVG
        bld.addShort() // CADENCE_MAX
        bld.hrTriplet()
        return bld.build()
    }

    private fun rowing(v: Int): XiaomiSimpleActivityParser? {
        val hs = when (v) {
            4 -> 4
            6, 7 -> 5
            8 -> 6
            else -> return null
        }
        return b(hs).times()
            .addShort(F.CALORIES)
            .hrTriplet()
            .build()
    }

    private fun treadmill(v: Int): XiaomiSimpleActivityParser? {
        val hs = when (v) {
            5 -> 4
            9 -> 6
            10 -> 8
            11 -> 9
            else -> return null
        }
        val bld = b(hs).times()
            .addInt(F.DISTANCE_METERS)
            .addShort(F.CALORIES)
        if (v >= 10) bld.addInt() // PACE_AVG
        bld.addInt() // PACE_MAX
        bld.addInt() // PACE_MIN
        bld.addInt(F.STEPS)
        if (v >= 10) {
            bld.addShort() // STEP_LENGTH_AVG
            bld.addShort() // CADENCE_AVG
        }
        bld.addShort() // CADENCE_MAX
        bld.hrTriplet()
        return bld.build()
    }

    private fun outdoorCyclingV2(v: Int): XiaomiSimpleActivityParser? {
        if (v != 4) return null
        return b(5).times()
            .addInt(F.DISTANCE_METERS)
            .addShort(F.CALORIES)
            .addUnknown(8)
            .addFloat() // SPEED_MAX
            .hrTriplet()
            .build()
    }

    private fun jumpRoping(v: Int): XiaomiSimpleActivityParser? {
        if (v != 3 && v != 5) return null
        return b(5).times()
            .addShort(F.CALORIES)
            .hrTriplet()
            .build()
    }
}
