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
 * Parser dispatch by (type, subtype, detailType), translated from
 * XiaomiActivityParser.create(). Version checks live in each parser, as
 * upstream. Not ported: WorkoutDetailsParser (SPORTS / DETAILS — per-second
 * workout HR/pace streams, used upstream only for FIT export).
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import android.util.Log
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileId.DetailType
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileId.Subtype
import com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileId.Type

object XiaomiActivityParser {
    private const val TAG = "MB9A_ActivityParser"

    /** True when a parser exists for this file id (regardless of version). */
    fun hasParserFor(fileId: XiaomiActivityFileId): Boolean = when (fileId.type) {
        Type.ACTIVITY -> when (fileId.subtype) {
            Subtype.ACTIVITY_DAILY ->
                fileId.detailType == DetailType.DETAILS || fileId.detailType == DetailType.SUMMARY
            Subtype.ACTIVITY_SLEEP_STAGES, Subtype.ACTIVITY_MANUAL_SAMPLES ->
                fileId.detailType == DetailType.DETAILS
            Subtype.ACTIVITY_SLEEP -> true
            else -> false
        }
        Type.SPORTS -> fileId.detailType == DetailType.SUMMARY || fileId.detailType == DetailType.GPS_TRACK
        Type.UNKNOWN -> false
    }

    /**
     * Decode one complete, CRC-checked activity file ([bytes] still includes
     * the 7-byte id, the padding byte and the trailing CRC, exactly as
     * upstream parsers receive it). Returns null when no parser handles this
     * file / version or decoding failed.
     */
    fun parse(fileId: XiaomiActivityFileId, bytes: ByteArray): ActivityFileContent? = try {
        when (fileId.type) {
            Type.ACTIVITY -> when (fileId.subtype) {
                Subtype.ACTIVITY_DAILY -> when (fileId.detailType) {
                    DetailType.DETAILS -> DailyDetailsParser.parse(fileId, bytes)
                        ?.let { ActivityFileContent.DailyDetails(fileId, it) }
                    DetailType.SUMMARY -> DailySummaryParser.parse(fileId, bytes)
                    else -> null
                }
                Subtype.ACTIVITY_SLEEP_STAGES ->
                    if (fileId.detailType == DetailType.DETAILS) SleepStagesParser.parse(fileId, bytes) else null
                Subtype.ACTIVITY_MANUAL_SAMPLES ->
                    if (fileId.detailType == DetailType.DETAILS) ManualSamplesParser.parse(fileId, bytes) else null
                // Comes both as DETAILS (v2) and SUMMARY (v4, v5).
                Subtype.ACTIVITY_SLEEP -> SleepDetailsParser.parse(fileId, bytes)
                else -> null
            }
            Type.SPORTS -> when (fileId.detailType) {
                DetailType.SUMMARY -> WorkoutSummaryParser.parse(fileId, bytes)
                DetailType.GPS_TRACK -> WorkoutGpsParser.parse(fileId, bytes)
                else -> null
            }
            Type.UNKNOWN -> null
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Exception while parsing $fileId", t)
        null
    }

    /** Bit i of an MSB-first validity bitmap (XiaomiActivityParser.validData). */
    fun validData(header: ByteArray, i: Int): Boolean {
        val idx = i / 8
        if (idx >= header.size) return false
        return (header[idx].toInt() and (1 shl (7 - (i % 8)))) != 0
    }
}
