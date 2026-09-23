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
 * Builder-style binary deserializer for the Xiaomi workout summary files.
 * Semantic port of `XiaomiSimpleActivityParser.java` + `XiaomiSimpleDataEntry`:
 * each sport/version declares an ordered list of typed reads; fields we do
 * not surface are read as `addUnknown(size)` so the byte offsets of later
 * fields stay identical to upstream.
 *
 * Like upstream, the validity bitmap header is read but NOT applied (upstream
 * FIXME: field lengths for unknown fields aren't all known, so the bitmap
 * would go out of sync).
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/** Field identifiers we surface. */
enum class XiaomiSimpleField {
    UNKNOWN,
    TIME_START,
    TIME_END,
    ACTIVE_SECONDS,
    CALORIES,
    CALORIES_TOTAL,
    DISTANCE_METERS,
    HR_AVG,
    HR_MAX,
    HR_MIN,
    STEPS,
    WORKOUT_TYPE,
}

private sealed class Reader(val key: XiaomiSimpleField) {
    abstract fun read(buf: ByteBuffer): Number?

    class ByteR(key: XiaomiSimpleField) : Reader(key) {
        override fun read(buf: ByteBuffer): Number = buf.get().toInt() and 0xFF
    }

    /** Upstream reads shorts signed; every field we keep is < 32768 in practice, read unsigned. */
    class ShortR(key: XiaomiSimpleField) : Reader(key) {
        override fun read(buf: ByteBuffer): Number = buf.short.toInt() and 0xFFFF
    }

    class IntR(key: XiaomiSimpleField) : Reader(key) {
        override fun read(buf: ByteBuffer): Number = buf.int
    }

    class FloatR(key: XiaomiSimpleField) : Reader(key) {
        override fun read(buf: ByteBuffer): Number = buf.float
    }

    class Skip(private val n: Int) : Reader(XiaomiSimpleField.UNKNOWN) {
        override fun read(buf: ByteBuffer): Number? {
            buf.position(buf.position() + n)
            return null
        }
    }
}

/** Output of a parse pass — only the fields we surface. Null = not decoded. */
data class WorkoutFields(
    var timeStartEpochSec: Int? = null,
    var timeEndEpochSec: Int? = null,
    var activeSeconds: Int? = null,
    /** Active ("burnt") kcal. */
    var calories: Int? = null,
    /** Active + basal kcal where the file carries it. */
    var caloriesTotal: Int? = null,
    var distanceMeters: Int? = null,
    var hrAvg: Int? = null,
    var hrMax: Int? = null,
    var hrMin: Int? = null,
    var steps: Int? = null,
    /** Xiaomi workout type code (XiaomiWorkoutType), when the file carries one. */
    var workoutType: Int? = null,
    /** True when every declared field was read without running out of bytes. */
    var complete: Boolean = false,
)

class XiaomiSimpleActivityParser private constructor(
    private val headerSize: Int,
    private val readers: List<Reader>,
) {
    fun parse(buf: ByteBuffer): WorkoutFields {
        val fields = WorkoutFields()
        try {
            buf.position(buf.position() + headerSize) // validity bitmap (not applied, see header)
            for (r in readers) {
                val value = r.read(buf) ?: continue
                when (r.key) {
                    XiaomiSimpleField.TIME_START -> fields.timeStartEpochSec = value.toInt()
                    XiaomiSimpleField.TIME_END -> fields.timeEndEpochSec = value.toInt()
                    XiaomiSimpleField.ACTIVE_SECONDS -> fields.activeSeconds = value.toInt()
                    XiaomiSimpleField.CALORIES -> fields.calories = value.toInt()
                    XiaomiSimpleField.CALORIES_TOTAL -> fields.caloriesTotal = value.toInt()
                    XiaomiSimpleField.DISTANCE_METERS -> fields.distanceMeters = value.toInt()
                    XiaomiSimpleField.HR_AVG -> fields.hrAvg = value.toInt()
                    XiaomiSimpleField.HR_MAX -> fields.hrMax = value.toInt()
                    XiaomiSimpleField.HR_MIN -> fields.hrMin = value.toInt()
                    XiaomiSimpleField.STEPS -> fields.steps = value.toInt()
                    XiaomiSimpleField.WORKOUT_TYPE -> fields.workoutType = value.toInt()
                    XiaomiSimpleField.UNKNOWN -> Unit
                }
            }
            fields.complete = true
        } catch (_: BufferUnderflowException) {
            // Keep what was decoded; caller decides.
        } catch (_: IllegalArgumentException) {
            // position() past limit on Skip
        }
        return fields
    }

    class Builder {
        private var headerSize: Int = 0
        private val readers = mutableListOf<Reader>()

        fun setHeaderSize(n: Int) = apply { headerSize = n }
        fun addByte(key: XiaomiSimpleField = XiaomiSimpleField.UNKNOWN) = apply { readers += Reader.ByteR(key) }
        fun addShort(key: XiaomiSimpleField = XiaomiSimpleField.UNKNOWN) = apply { readers += Reader.ShortR(key) }
        fun addInt(key: XiaomiSimpleField = XiaomiSimpleField.UNKNOWN) = apply { readers += Reader.IntR(key) }
        fun addFloat(key: XiaomiSimpleField = XiaomiSimpleField.UNKNOWN) = apply { readers += Reader.FloatR(key) }
        fun addUnknown(n: Int) = apply { readers += Reader.Skip(n) }

        fun build() = XiaomiSimpleActivityParser(headerSize, readers.toList())
    }
}
