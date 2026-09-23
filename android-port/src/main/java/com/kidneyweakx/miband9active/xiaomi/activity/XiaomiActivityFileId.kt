/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo                  (Gadgetbridge)
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
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 7-byte file-id header used by the Xiaomi activity protocol.
 *
 * Wire layout (little endian):
 *   [0..3] uint32 — unix timestamp in seconds
 *   [4]    int8   — timezone in 15-minute blocks
 *   [5]    uint8  — version
 *   [6]    uint8  — packed flags: type(1b) << 7 | subtype(5b) << 2 | detail(2b)
 */
data class XiaomiActivityFileId(
    val timestamp: Date,
    val timezone: Int,
    val typeCode: Int,
    val subtypeCode: Int,
    val detailTypeCode: Int,
    val version: Int,
) : Comparable<XiaomiActivityFileId> {

    val type: Type get() = Type.fromCode(typeCode)
    val subtype: Subtype get() = Subtype.fromCode(type, subtypeCode)
    val detailType: DetailType get() = DetailType.fromCode(detailTypeCode)

    fun toBytes(): ByteArray = ByteBuffer.allocate(7)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt((timestamp.time / 1000L).toInt())
        .put(timezone.toByte())
        .put(version.toByte())
        .put(((typeCode shl 7) or (subtypeCode shl 2) or detailTypeCode).toByte())
        .array()

    override fun compareTo(other: XiaomiActivityFileId): Int {
        var c = timestamp.compareTo(other.timestamp)
        if (c != 0) return c
        c = timezone.compareTo(other.timezone)
        if (c != 0) return c
        c = typeCode.compareTo(other.typeCode)
        if (c != 0) return c
        c = subtypeCode.compareTo(other.subtypeCode)
        if (c != 0) return c
        c = detailType.fetchOrder.compareTo(other.detailType.fetchOrder)
        if (c != 0) return c
        return version.compareTo(other.version)
    }

    fun outputFile(targetDir: File): File {
        val sdfYear = SimpleDateFormat("yyyy", Locale.ROOT)
        val sdfFull = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val typeName = type
        val subtypeName = subtype
        val detailName = detailType
        val typePart = if (typeName == Type.UNKNOWN) "UNKNOWN_%02X".format(typeCode) else typeName.name
        val subtypePart = if (subtypeName == Subtype.UNKNOWN) "UNKNOWN_%02X".format(subtypeCode) else subtypeName.name
        val detailPart = if (detailName == DetailType.UNKNOWN) "UNKNOWN_%02X".format(detailTypeCode) else detailName.name
        val filename = "%s_%02X_%02X_%02X_v%d.bin".format(
            Locale.ROOT,
            sdfFull.format(timestamp),
            typeCode,
            subtypeCode,
            detailTypeCode,
            version,
        )
        return File(
            targetDir,
            sdfYear.format(timestamp) + File.separator +
                typePart + File.separator +
                subtypePart + File.separator +
                detailPart + File.separator +
                filename,
        )
    }

    companion object {
        fun from(bytes: ByteArray): XiaomiActivityFileId {
            require(bytes.size >= 7) { "Need at least 7 bytes for file id" }
            return from(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
        }

        fun from(buf: ByteBuffer): XiaomiActivityFileId {
            val ts = buf.int
            val tz = buf.get().toInt()
            val version = buf.get().toInt() and 0xFF
            val flags = buf.get().toInt() and 0xFF
            val type = (flags shr 7) and 1
            val subtype = (flags and 0x7F) shr 2
            val detail = flags and 0x03
            return XiaomiActivityFileId(Date(ts * 1000L), tz, type, subtype, detail, version)
        }
    }

    enum class Type(val code: Int) {
        UNKNOWN(-1),
        ACTIVITY(0),
        SPORTS(1);
        companion object {
            fun fromCode(code: Int): Type = values().firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    enum class Subtype(val type: Type, val code: Int) {
        UNKNOWN(Type.UNKNOWN, -1),
        ACTIVITY_DAILY(Type.ACTIVITY, 0x00),
        ACTIVITY_SLEEP_STAGES(Type.ACTIVITY, 0x03),
        ACTIVITY_MANUAL_SAMPLES(Type.ACTIVITY, 0x06),
        ACTIVITY_SLEEP(Type.ACTIVITY, 0x08),
        SPORTS_OUTDOOR_RUNNING(Type.SPORTS, 0x01),
        SPORTS_OUTDOOR_WALKING_V1(Type.SPORTS, 0x02),
        SPORTS_TREADMILL(Type.SPORTS, 0x03),
        SPORTS_OUTDOOR_CYCLING_V2(Type.SPORTS, 0x06),
        SPORTS_INDOOR_CYCLING(Type.SPORTS, 0x07),
        SPORTS_FREESTYLE(Type.SPORTS, 0x08),
        SPORTS_POOL_SWIMMING(Type.SPORTS, 0x09),
        SPORTS_HIIT(Type.SPORTS, 0x10),
        SPORTS_ELLIPTICAL(Type.SPORTS, 0x0B),
        SPORTS_ROWING(Type.SPORTS, 0x0D),
        SPORTS_JUMP_ROPING(Type.SPORTS, 0x0E),
        SPORTS_OUTDOOR_WALKING_V2(Type.SPORTS, 0x16),
        SPORTS_OUTDOOR_CYCLING(Type.SPORTS, 0x17);
        companion object {
            fun fromCode(type: Type, code: Int): Subtype =
                values().firstOrNull { it.type == type && it.code == code } ?: UNKNOWN
        }
    }

    enum class DetailType(val code: Int, val fetchOrder: Int) {
        UNKNOWN(-1, 3),
        // Fetch summary first so workout track parsing has summary context.
        DETAILS(0x00, 1),
        SUMMARY(0x01, 0),
        GPS_TRACK(0x02, 2);
        companion object {
            fun fromCode(code: Int): DetailType = values().firstOrNull { it.code == code } ?: UNKNOWN
        }
    }
}
