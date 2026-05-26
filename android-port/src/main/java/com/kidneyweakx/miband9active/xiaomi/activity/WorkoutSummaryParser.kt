/*  Copyright (C) 2024 José Rebelo                                            (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                                             (Kotlin port)
 *
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Per-sport-type activity summary parsers for Mi Band 9 Active SPORTS files.
 *  Semantic port of `WorkoutSummaryParser.java` covering the seven highest-
 *  traffic sports on this device (running, walking V1/V2, treadmill, indoor
 *  cycling, freestyle, HIIT). Unknown sports / unsupported versions fall back
 *  to a generic 3-int header read so the workout still appears in the list
 *  with at minimum start/end/duration — better than dropping the file.
 *
 *  File layout (matches XiaomiActivityFileFetcher output):
 *    [0..6]   XiaomiActivityFileId   (7 bytes)
 *    [7]      padding                (1 byte)
 *    [8..]    per-sport buffer       (variable; starts with sport's header bitmap)
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WorkoutSummaryParser {

    /**
     * Parse a SPORTS activity file. Returns null only if the buffer is too short
     * to even read the universal fields; otherwise always returns a Fields
     * object that may have nulls where the per-sport parser couldn't fill in.
     */
    fun parse(fileId: XiaomiActivityFileId, data: ByteArray): WorkoutFields? {
        if (data.size < 8 + 4) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        // Skip fileId (7) + 1-byte padding.
        buf.position(8)
        val parser = parserFor(fileId) ?: return parseGenericFallback(buf)
        return parser.parse(buf)
    }

    private fun parserFor(fileId: XiaomiActivityFileId): XiaomiSimpleActivityParser? {
        val v = fileId.version
        return when (fileId.subtype) {
            // Universal V1 outdoor (walking/running).
            XiaomiActivityFileId.Subtype.SPORTS_OUTDOOR_WALKING_V1,
            XiaomiActivityFileId.Subtype.SPORTS_OUTDOOR_RUNNING ->
                if (v == 4) outdoorWalkingV1(headerSize = 4) else null

            XiaomiActivityFileId.Subtype.SPORTS_OUTDOOR_WALKING_V2 -> {
                val hs = when (v) {
                    1 -> 5; 4 -> 7; 5, 6 -> 9; 9 -> 13
                    else -> return null
                }
                outdoorWalkingV2(headerSize = hs, version = v)
            }

            XiaomiActivityFileId.Subtype.SPORTS_TREADMILL -> {
                val hs = when (v) { 5 -> 4; 10 -> 8; 11 -> 9; else -> return null }
                treadmill(headerSize = hs, version = v)
            }

            XiaomiActivityFileId.Subtype.SPORTS_INDOOR_CYCLING -> {
                val hs = when (v) { 8 -> 7; 9 -> 8; else -> return null }
                indoorCycling(headerSize = hs)
            }

            XiaomiActivityFileId.Subtype.SPORTS_FREESTYLE -> {
                val hs = when (v) { 5 -> 3; 7 -> 5; 8, 9 -> 6; else -> return null }
                freestyle(headerSize = hs, version = v)
            }

            XiaomiActivityFileId.Subtype.SPORTS_HIIT -> {
                val hs = when (v) { 5 -> 4; else -> return null }
                hiit(headerSize = hs)
            }

            else -> null
        }
    }

    /**
     * Last-resort parser for unsupported sports or versions. Just reads the
     * first three ints (start, end, active seconds) after a guessed minimal
     * header. Calories/distance/HR stay null — better an honest blank than a
     * wrong number.
     */
    private fun parseGenericFallback(buf: ByteBuffer): WorkoutFields? {
        if (buf.remaining() < 12) return null
        val fields = WorkoutFields()
        try {
            fields.timeStartEpochSec = buf.int
            fields.timeEndEpochSec = buf.int
            fields.activeSeconds = buf.int
        } catch (_: Throwable) {
            // ignore — return what we have
        }
        return fields
    }

    // ----------------------------------------------------- per-sport builders

    private fun outdoorWalkingV1(headerSize: Int) = XiaomiSimpleActivityParser.Builder()
        .setHeaderSize(headerSize)
        .addInt(XiaomiSimpleField.TIME_START)
        .addInt(XiaomiSimpleField.TIME_END)
        .addInt(XiaomiSimpleField.ACTIVE_SECONDS)
        .addInt(XiaomiSimpleField.DISTANCE_METERS)
        .addInt(XiaomiSimpleField.CALORIES)
        .addUnknown(8) // pace max + pace min
        .addUnknown(4)
        .addInt(XiaomiSimpleField.STEPS)
        .addUnknown(2)
        .addByte(XiaomiSimpleField.HR_AVG)
        .addByte(XiaomiSimpleField.HR_MAX)
        .addByte(XiaomiSimpleField.HR_MIN)
        .build()

    private fun outdoorWalkingV2(headerSize: Int, version: Int) =
        XiaomiSimpleActivityParser.Builder()
            .setHeaderSize(headerSize)
            .addShort(XiaomiSimpleField.WORKOUT_TYPE)
            .addInt(XiaomiSimpleField.TIME_START)
            .addInt(XiaomiSimpleField.TIME_END)
            .addInt(XiaomiSimpleField.ACTIVE_SECONDS)
            .addUnknown(4)
            .addInt(XiaomiSimpleField.DISTANCE_METERS)
            .addShort(XiaomiSimpleField.UNKNOWN) // calories total
            .addShort(XiaomiSimpleField.CALORIES) // calories burnt
            .apply {
                if (version >= 5) addUnknown(4) // pace avg
                addUnknown(8) // pace max + pace min
                if (version >= 5) addUnknown(4) // speed avg
                addUnknown(4) // speed max
                addInt(XiaomiSimpleField.STEPS)
                if (version >= 5) addUnknown(4) // step length avg + step rate avg
                addUnknown(2) // step rate max
                addByte(XiaomiSimpleField.HR_AVG)
                addByte(XiaomiSimpleField.HR_MAX)
                addByte(XiaomiSimpleField.HR_MIN)
            }
            .build()

    private fun treadmill(headerSize: Int, version: Int) =
        XiaomiSimpleActivityParser.Builder()
            .setHeaderSize(headerSize)
            .addInt(XiaomiSimpleField.TIME_START)
            .addInt(XiaomiSimpleField.TIME_END)
            .addInt(XiaomiSimpleField.ACTIVE_SECONDS)
            .addInt(XiaomiSimpleField.DISTANCE_METERS)
            .addShort(XiaomiSimpleField.CALORIES)
            .apply {
                if (version >= 10) addUnknown(4) // pace avg
                addUnknown(8) // pace max + pace min
                addInt(XiaomiSimpleField.STEPS)
                if (version >= 10) {
                    addUnknown(2)
                    addUnknown(2) // cadence avg
                }
                addUnknown(2) // cadence max
                addByte(XiaomiSimpleField.HR_AVG)
                addByte(XiaomiSimpleField.HR_MAX)
                addByte(XiaomiSimpleField.HR_MIN)
            }
            .build()

    private fun indoorCycling(headerSize: Int) = XiaomiSimpleActivityParser.Builder()
        .setHeaderSize(headerSize)
        .addInt(XiaomiSimpleField.TIME_START)
        .addInt(XiaomiSimpleField.TIME_END)
        .addInt(XiaomiSimpleField.ACTIVE_SECONDS)
        .addUnknown(4)
        .addShort(XiaomiSimpleField.CALORIES)
        .addUnknown(4)
        .addByte(XiaomiSimpleField.HR_AVG)
        .addByte(XiaomiSimpleField.HR_MAX)
        .addByte(XiaomiSimpleField.HR_MIN)
        .build()

    private fun freestyle(headerSize: Int, version: Int) =
        XiaomiSimpleActivityParser.Builder()
            .setHeaderSize(headerSize)
            .addInt(XiaomiSimpleField.TIME_START)
            .addInt(XiaomiSimpleField.TIME_END)
            .addInt(XiaomiSimpleField.ACTIVE_SECONDS)
            .addShort(XiaomiSimpleField.CALORIES)
            .addByte(XiaomiSimpleField.HR_AVG)
            .addByte(XiaomiSimpleField.HR_MAX)
            .addByte(XiaomiSimpleField.HR_MIN)
            .apply {
                if (version == 5) {
                    addUnknown(6)
                }
                addUnknown(4) // training effect aerobic float
                if (version == 5) addUnknown(1)
                addUnknown(1)
                addUnknown(2) // recovery time short
            }
            .build()

    private fun hiit(headerSize: Int) = XiaomiSimpleActivityParser.Builder()
        .setHeaderSize(headerSize)
        .addInt(XiaomiSimpleField.TIME_START)
        .addInt(XiaomiSimpleField.TIME_END)
        .addInt(XiaomiSimpleField.ACTIVE_SECONDS)
        .addShort(XiaomiSimpleField.CALORIES)
        .addByte(XiaomiSimpleField.HR_AVG)
        .addByte(XiaomiSimpleField.HR_MAX)
        .addByte(XiaomiSimpleField.HR_MIN)
        .build()
}
