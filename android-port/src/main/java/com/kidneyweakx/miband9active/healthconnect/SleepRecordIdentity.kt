/*  Copyright (C) 2025 Gideon Zenz                                            (Gadgetbridge SleepSyncer)
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
 * Pure decision core of the Health Connect sleep export (no HC / Android
 * deps), translated from SleepSyncer.planSleepSessions / pruneSleepRows.
 *
 * A night is re-segmented as more of it syncs (an in-progress night grows,
 * fragments merge, its start moves earlier), so a clientRecordId derived
 * from the current bedtime would orphan the old record and duplicate the
 * night. Instead the id is frozen on first sight and reused for any later
 * detection that overlaps the stored span; the HC record then grows in place.
 *
 * Deviation: upstream only compares the span to decide "unchanged"; we also
 * compare a fingerprint of the stage list, so stage data that arrives for an
 * unchanged span (a later sleep-stages file) still updates the record.
 */
package com.kidneyweakx.miband9active.healthconnect

internal data class SleepIdentityRow(
    val clientRecordId: String,
    val startSec: Long,
    val endSec: Long,
    val fingerprint: String,
)

internal data class DetectedSleep(val startSec: Long, val endSec: Long, val fingerprint: String)

internal data class PlannedSleep(
    val detectedIndex: Int,
    val clientRecordId: String,
    val startSec: Long,
    val endSec: Long,
)

internal data class SleepIdentityPlan(val planned: List<PlannedSleep>, val rows: List<SleepIdentityRow>)

internal object SleepRecordIdentity {

    /** Records we minted: `mb9a-sleepnight-<first-seen start epoch s>`. */
    fun mintId(startSec: Long): String = "mb9a-sleepnight-$startSec"

    /**
     * Overlap-match each detection to a stored row (inclusive overlap, first
     * unused match wins), freeze/reuse its id, grow its span. Only new or
     * changed sessions are planned unless [force].
     */
    fun plan(
        existing: List<SleepIdentityRow>,
        detected: List<DetectedSleep>,
        force: Boolean,
    ): SleepIdentityPlan {
        val rows = existing.toMutableList()
        val used = HashSet<Int>()
        val planned = ArrayList<PlannedSleep>(detected.size)

        for ((i, d) in detected.withIndex()) {
            var match = -1
            for (idx in rows.indices) {
                if (idx in used) continue
                val r = rows[idx]
                if (d.startSec <= r.endSec && d.endSec >= r.startSec) {
                    match = idx
                    break
                }
            }
            if (match >= 0) {
                val r = rows[match]
                used += match
                // Upstream grows the span as a union so a shorter re-detection
                // never shrinks the record.
                val start = minOf(d.startSec, r.startSec)
                val end = maxOf(d.endSec, r.endSec)
                val changed = start != r.startSec || end != r.endSec || d.fingerprint != r.fingerprint
                if (changed || force) {
                    rows[match] = SleepIdentityRow(r.clientRecordId, start, end, d.fingerprint)
                    planned += PlannedSleep(i, r.clientRecordId, start, end)
                }
            } else {
                var id = mintId(d.startSec)
                var n = 1
                while (rows.any { it.clientRecordId == id }) id = mintId(d.startSec) + "-" + n++
                rows += SleepIdentityRow(id, d.startSec, d.endSec, d.fingerprint)
                used += rows.size - 1
                planned += PlannedSleep(i, id, d.startSec, d.endSec)
            }
        }
        return SleepIdentityPlan(planned, rows)
    }

    /** SleepSyncer.pruneSleepRows: drop rows that ended before [beforeSec]. */
    fun prune(rows: List<SleepIdentityRow>, beforeSec: Long): List<SleepIdentityRow> =
        rows.filter { it.endSec > beforeSec }
}
