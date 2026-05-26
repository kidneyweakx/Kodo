/*  Copyright (C) 2024 José Rebelo                                           (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                                            (Kotlin port)
 *
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Builder-style binary deserializer for the Xiaomi V2 activity summary
 *  files. Each sport-type-specific parser declares a sequence of typed
 *  reads (byte/short/int/float/unknown); the parser then walks the buffer
 *  field-by-field, keyed by `XiaomiSimpleField`. Faithful semantic port of
 *  `XiaomiSimpleActivityParser.java`, but stripped of GBApplication / DB
 *  scaffolding — we emit our own [WorkoutFields] DTO instead.
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import java.nio.ByteBuffer

/** Field identifiers we care about for the JS-facing summary. */
enum class XiaomiSimpleField {
    UNKNOWN,
    TIME_START,
    TIME_END,
    ACTIVE_SECONDS,
    CALORIES,
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
    class ShortR(key: XiaomiSimpleField, val mult: Double = 1.0) : Reader(key) {
        override fun read(buf: ByteBuffer): Number = (buf.short.toInt() and 0xFFFF) * mult
    }
    class IntR(key: XiaomiSimpleField) : Reader(key) {
        override fun read(buf: ByteBuffer): Number = buf.int
    }
    class FloatR(key: XiaomiSimpleField) : Reader(key) {
        override fun read(buf: ByteBuffer): Number = buf.float
    }
    class Skip(val n: Int) : Reader(XiaomiSimpleField.UNKNOWN) {
        override fun read(buf: ByteBuffer): Number? {
            // Burn N bytes
            val tmp = ByteArray(n)
            buf.get(tmp)
            return null
        }
    }
}

/** Output of a parse pass — only the fields we surface to JS. */
data class WorkoutFields(
    var timeStartEpochSec: Int? = null,
    var timeEndEpochSec: Int? = null,
    var activeSeconds: Int? = null,
    var calories: Int? = null,
    var distanceMeters: Int? = null,
    var hrAvg: Int? = null,
    var hrMax: Int? = null,
    var hrMin: Int? = null,
    var steps: Int? = null,
    /** Xiaomi-defined workout type id; non-null when a `setWorkoutType` was emitted. */
    var workoutType: Int? = null,
)

class XiaomiSimpleActivityParser private constructor(
    private val headerSize: Int,
    private val readers: List<Reader>,
) {
    fun parse(buf: ByteBuffer): WorkoutFields {
        val fields = WorkoutFields()
        // Burn the per-version header (validity bitmap).
        if (headerSize > 0) buf.get(ByteArray(headerSize))

        for (r in readers) {
            val value = try { r.read(buf) } catch (_: Throwable) { return fields }
            if (value == null) continue
            when (r.key) {
                XiaomiSimpleField.TIME_START -> fields.timeStartEpochSec = value.toInt()
                XiaomiSimpleField.TIME_END -> fields.timeEndEpochSec = value.toInt()
                XiaomiSimpleField.ACTIVE_SECONDS -> fields.activeSeconds = value.toInt()
                XiaomiSimpleField.CALORIES -> fields.calories = value.toInt()
                XiaomiSimpleField.DISTANCE_METERS -> fields.distanceMeters = value.toInt()
                XiaomiSimpleField.HR_AVG -> fields.hrAvg = value.toInt()
                XiaomiSimpleField.HR_MAX -> fields.hrMax = value.toInt()
                XiaomiSimpleField.HR_MIN -> fields.hrMin = value.toInt()
                XiaomiSimpleField.STEPS -> fields.steps = value.toInt()
                XiaomiSimpleField.WORKOUT_TYPE -> fields.workoutType = value.toInt()
                XiaomiSimpleField.UNKNOWN -> Unit
            }
        }
        return fields
    }

    class Builder {
        private var headerSize: Int = 0
        private val readers = mutableListOf<Reader>()

        fun setHeaderSize(n: Int) = apply { headerSize = n }
        fun addByte(key: XiaomiSimpleField) = apply { readers += Reader.ByteR(key) }
        fun addShort(key: XiaomiSimpleField) = apply { readers += Reader.ShortR(key) }
        fun addShort(key: XiaomiSimpleField, mult: Double) = apply { readers += Reader.ShortR(key, mult) }
        fun addInt(key: XiaomiSimpleField) = apply { readers += Reader.IntR(key) }
        fun addFloat(key: XiaomiSimpleField) = apply { readers += Reader.FloatR(key) }
        fun addUnknown(n: Int) = apply { readers += Reader.Skip(n) }

        fun build() = XiaomiSimpleActivityParser(headerSize, readers.toList())
    }
}
