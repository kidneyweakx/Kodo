/*  Copyright (C) 2019-2024 José Rebelo, Petr Vaněk, Q-er                     (Gadgetbridge SleepAnalysis)
 *  Copyright (C) 2023-2024 José Rebelo                                      (Gadgetbridge XiaomiSampleProvider)
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
 * Pure night assembly (no Android / DB dependencies — unit-testable on the
 * JVM). Turns stored sleep-session rows (XiaomiSleepTimeSample) and stage
 * rows (XiaomiSleepStageSample) into sessions and nights, following:
 *
 *   - XiaomiSampleProvider.overlaySleep(): a RangeMap (LOWER_BOUND) where a
 *     session's bedtime starts sleep, its wake-up ends it (UNKNOWN), and
 *     stage rows in between set the kind (2 deep, 3 light, 4 REM, 5 awake,
 *     anything else UNKNOWN). Wake-ups are re-applied last so they win over
 *     a stage row at the same instant.
 *   - SleepAnalysis.calculateSleepSessions(): sleep kinds (incl. awake) form
 *     a session; a non-sleep gap ends it when it exceeds
 *     MAX_WAKE_PHASE_LENGTH (1 h) or has steps, otherwise the gap is counted
 *     as awake. Sessions of MIN_SESSION_LENGTH (5 min) or less are dropped.
 *   - SleepDailyFragment / "sleep ending on day": a session belongs to the
 *     day on which it ended.
 *
 * Deviations (each keeps the output limited to what the band actually said):
 *   - Continuous time instead of per-minute activity samples, so spans end
 *     exactly at the band's wake-up instead of on the last minute sample.
 *   - Upstream paints a session without stage rows as LIGHT sleep; we mark
 *     it ASLEEP_UNSTAGED (asleep, stage unknown) and fall back to the band's
 *     own per-session totals for stage minutes.
 *   - A bedtime is only inserted where no stage row is already in effect,
 *     so it cannot interrupt a stage sequence that began before the band's
 *     "real" sleep start (SleepStagesParser: bedtime may be later than the
 *     first phase change).
 *   - A bedtime / wake-up strictly inside another overlapping session row
 *     (the band re-emits a growing night with a new bedtime) is ignored
 *     instead of cutting that session.
 *   - Stage rows further than 1 h before every session's bedtime or after
 *     its wake-up are ignored: without a closing wake-up they would extend
 *     to the next event and paint hours of fake sleep.
 *   - Main sleep vs naps is ours (upstream shows all sessions of a day):
 *     the session with the most asleep minutes is the main sleep.
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

object SleepNightAssembler {

    /** SleepAnalysis.MAX_WAKE_PHASE_LENGTH */
    const val MAX_WAKE_PHASE_SEC = 60L * 60L

    /** SleepAnalysis.MIN_SESSION_LENGTH */
    const val MIN_SESSION_SEC = 5L * 60L

    /** Stage rows this far before a bedtime still belong to that session. */
    const val PRE_BED_STAGE_SLACK_SEC = 60L * 60L

    enum class Kind {
        DEEP, LIGHT, REM, AWAKE,

        /** Asleep per the session row, but the band gave no stage for it. */
        ASLEEP_UNSTAGED,

        /** Not sleeping / n-a / outside any session. */
        NONE;

        val isSleep: Boolean get() = this != NONE
        val isAsleep: Boolean get() = this == DEEP || this == LIGHT || this == REM || this == ASLEEP_UNSTAGED
        val isStage: Boolean get() = this == DEEP || this == LIGHT || this == REM || this == AWAKE
    }

    /**
     * A piece of a session's timeline, [startSec, endSec).
     * [fromBand] is false only for a merged not-sleeping gap (≤ 1 h, no
     * steps) that is counted as awake, like SleepAnalysis does.
     */
    data class Span(val startSec: Long, val endSec: Long, val kind: Kind, val fromBand: Boolean = true) {
        val seconds: Long get() = endSec - startSec
    }

    data class Session(
        val startSec: Long,
        val endSec: Long,
        /** Chronological, contiguous from [startSec] to [endSec]. */
        val spans: List<Span>,
        /** True when at least one band stage row lies inside the session. */
        val hasStages: Boolean,
        /** Asleep minutes (awake excluded). */
        val totalMinutes: Int,
        val deepMinutes: Int?,
        val lightMinutes: Int?,
        val remMinutes: Int?,
        val awakeMinutes: Int?,
        /** Awake runs with sleep before and after them; null without stages. */
        val awakeCount: Int?,
    ) {
        /**
         * Stage spans for display: adjacent equal stages merged, merged gaps
         * shown as awake, unstaged asleep time omitted. Empty without stages.
         */
        fun displaySegments(): List<Span> =
            if (!hasStages) emptyList() else mergeAdjacent(spans.filter { it.kind.isStage })

        /**
         * Only what the band itself classified (Health Connect SleepSyncer:
         * UNKNOWN samples produce no stage, so merged gaps are omitted).
         */
        fun bandStageSpans(): List<Span> =
            mergeAdjacent(spans.filter { it.fromBand && it.kind.isStage })
    }

    data class Night(val date: LocalDate, val main: Session, val naps: List<Session>) {
        /** Main sleep and naps, chronological. */
        val sessions: List<Session> get() = (naps + main).sortedBy { it.startSec }
    }

    /** XiaomiSampleProvider.getActivityKindForSample() */
    fun kindOf(stage: Int): Kind = when (stage) {
        2 -> Kind.DEEP
        3 -> Kind.LIGHT
        4 -> Kind.REM
        5 -> Kind.AWAKE
        else -> Kind.NONE
    }

    /**
     * Piecewise-constant timeline (overlaySleep's RangeMap), as consecutive
     * spans. The open-ended tail after the last event is not returned.
     */
    fun timeline(fragments: List<SleepSummary>, stages: List<SleepStageSample>): List<Span> {
        val frags = fragments.filter { it.wakeupTimeSec > it.bedTimeSec }
        if (frags.isEmpty()) return emptyList()

        fun insideOther(ts: Long, self: Int): Boolean =
            frags.indices.any { j -> j != self && frags[j].bedTimeSec < ts && ts < frags[j].wakeupTimeSec }

        val events = TreeMap<Long, Kind>()
        val stageTs = HashSet<Long>()
        val beds = frags.map { it.bedTimeSec }.toHashSet()

        // 1. Wake-ups end sleep.
        for ((i, f) in frags.withIndex()) {
            if (!insideOther(f.wakeupTimeSec, i)) events[f.wakeupTimeSec] = Kind.NONE
        }
        // 2. Stage rows belonging to some session.
        for (s in stages) {
            val owned = frags.any { s.timestampSec >= it.bedTimeSec - PRE_BED_STAGE_SLACK_SEC && s.timestampSec <= it.wakeupTimeSec }
            if (!owned) continue
            events[s.timestampSec] = kindOf(s.stage)
            stageTs += s.timestampSec
        }
        // 3. Bedtimes start sleep, unless a stage row already governs that instant.
        for ((i, f) in frags.withIndex()) {
            val bed = f.bedTimeSec
            if (bed in stageTs || insideOther(bed, i)) continue
            val prev = events.floorEntry(bed)?.value
            if (prev == null || prev == Kind.NONE) events[bed] = Kind.ASLEEP_UNSTAGED
        }
        // 4. Wake-ups win over a stage row at the same instant (overlaySleep
        //    re-puts them last) — except where another session starts right there.
        for ((i, f) in frags.withIndex()) {
            val w = f.wakeupTimeSec
            if (w !in beds && !insideOther(w, i)) events[w] = Kind.NONE
        }

        val out = ArrayList<Span>(events.size)
        var prevTs: Long? = null
        var prevKind = Kind.NONE
        for ((ts, kind) in events) {
            val p = prevTs
            if (p != null && ts > p) out += Span(p, ts, prevKind)
            prevTs = ts
            prevKind = kind
        }
        return out
    }

    /**
     * SleepAnalysis.calculateSleepSessions() over [timeline].
     *
     * @param stepMinutes ascending start timestamps of activity minutes with
     *   steps > 0 (SleepAnalysis "hasActivity"); a gap containing one splits.
     */
    fun sessions(
        fragments: List<SleepSummary>,
        stages: List<SleepStageSample>,
        stepMinutes: LongArray = LongArray(0),
    ): List<Session> {
        val validFrags = fragments.filter { it.wakeupTimeSec > it.bedTimeSec }
        val out = ArrayList<Session>()
        var cur: MutableList<Span>? = null
        for (sp in timeline(validFrags, stages)) {
            if (sp.seconds <= 0 || !sp.kind.isSleep) continue
            val c = cur
            if (c == null) {
                cur = mutableListOf(sp)
                continue
            }
            val lastEnd = c.last().endSec
            val gap = sp.startSec - lastEnd
            if (gap > MAX_WAKE_PHASE_SEC || hasSteps(stepMinutes, lastEnd, sp.startSec)) {
                close(c, validFrags)?.let(out::add)
                cur = mutableListOf(sp)
            } else {
                if (gap > 0) c += Span(lastEnd, sp.startSec, Kind.AWAKE, fromBand = false)
                c += sp
            }
        }
        cur?.let { c -> close(c, validFrags)?.let(out::add) }
        return out
    }

    /**
     * Sessions grouped by the local date they ended on, ascending. Only
     * dates with at least one session are returned.
     */
    fun nights(
        fragments: List<SleepSummary>,
        stages: List<SleepStageSample>,
        stepMinutes: LongArray,
        zone: ZoneId,
    ): List<Night> =
        sessions(fragments, stages, stepMinutes)
            .groupBy { wakeDate(it, zone) }
            .map { (date, list) ->
                val main = list.maxWithOrNull(compareBy<Session>({ it.totalMinutes }, { it.endSec }))!!
                Night(date, main, list.filter { it !== main }.sortedBy { it.startSec })
            }
            .sortedBy { it.date }

    fun wakeDate(s: Session, zone: ZoneId): LocalDate =
        Instant.ofEpochSecond(s.endSec).atZone(zone).toLocalDate()

    // ----------------------------------------------------------------- internals

    private fun close(spans: List<Span>, frags: List<SleepSummary>): Session? {
        val start = spans.first().startSec
        val end = spans.last().endSec
        if (end - start <= MIN_SESSION_SEC) return null

        val hasStages = spans.any { it.fromBand && it.kind.isStage }
        val asleepSec = spans.filter { it.kind.isAsleep }.sumOf { it.seconds }

        if (hasStages) {
            fun sec(k: Kind) = spans.filter { it.kind == k }.sumOf { it.seconds }
            return Session(
                startSec = start,
                endSec = end,
                spans = spans,
                hasStages = true,
                totalMinutes = minutes(asleepSec),
                deepMinutes = minutes(sec(Kind.DEEP)),
                lightMinutes = minutes(sec(Kind.LIGHT)),
                remMinutes = minutes(sec(Kind.REM)),
                awakeMinutes = minutes(sec(Kind.AWAKE)),
                awakeCount = awakeEpisodes(spans),
            )
        }

        // No stage rows: use the band's own per-session totals when every
        // session row in here carries them. Overlapping rows are re-emits of
        // the same night — keep the longest so nothing is counted twice.
        val inside = frags
            .filter { it.bedTimeSec >= start && it.wakeupTimeSec <= end }
            .sortedBy { it.bedTimeSec }
        val distinct = ArrayList<SleepSummary>()
        for (f in inside) {
            val last = distinct.lastOrNull()
            if (last != null && f.bedTimeSec < last.wakeupTimeSec) {
                if (f.wakeupTimeSec - f.bedTimeSec > last.wakeupTimeSec - last.bedTimeSec) distinct[distinct.size - 1] = f
            } else {
                distinct += f
            }
        }
        fun sumAll(pick: (SleepSummary) -> Int?): Int? {
            if (distinct.isEmpty()) return null
            val values = distinct.map(pick)
            return if (values.any { it == null }) null else values.sumOf { it!! }
        }
        val gapMinutes = minutes(spans.filter { !it.fromBand }.sumOf { it.seconds })
        return Session(
            startSec = start,
            endSec = end,
            spans = spans,
            hasStages = false,
            totalMinutes = sumAll { it.totalMinutes } ?: minutes(asleepSec),
            deepMinutes = sumAll { it.deepMinutes },
            lightMinutes = sumAll { it.lightMinutes },
            remMinutes = sumAll { it.remMinutes },
            awakeMinutes = sumAll { it.awakeMinutes }?.plus(gapMinutes),
            awakeCount = null,
        )
    }

    /** Awake runs (band awake stage or merged gap) with asleep time on both sides. */
    private fun awakeEpisodes(spans: List<Span>): Int {
        var count = 0
        var seenAsleep = false
        var inAwake = false
        for (sp in spans) {
            when {
                sp.kind.isAsleep -> {
                    if (inAwake && seenAsleep) count++
                    inAwake = false
                    seenAsleep = true
                }
                sp.kind == Kind.AWAKE -> inAwake = true
                else -> Unit
            }
        }
        return count
    }

    private fun mergeAdjacent(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>(spans.size)
        for (sp in spans) {
            val last = out.lastOrNull()
            if (last != null && last.kind == sp.kind && last.endSec == sp.startSec) {
                out[out.size - 1] = last.copy(endSec = sp.endSec, fromBand = last.fromBand && sp.fromBand)
            } else {
                out += sp
            }
        }
        return out
    }

    /** Any step minute starting in [fromSec, toSec)? [sorted] ascending. */
    private fun hasSteps(sorted: LongArray, fromSec: Long, toSec: Long): Boolean {
        if (sorted.isEmpty() || toSec <= fromSec) return false
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < fromSec) lo = mid + 1 else hi = mid
        }
        return lo < sorted.size && sorted[lo] < toSec
    }

    private fun minutes(sec: Long): Int = ((sec + 30) / 60).toInt()
}
