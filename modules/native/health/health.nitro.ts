/*
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
 * Nitro HybridObject spec for the native health sample store (SQLite,
 * filled by activity sync). Semantics follow Gadgetbridge's
 * XiaomiSampleProvider, XiaomiSleepStageSampleProvider,
 * XiaomiStressSampleProvider, XiaomiSpo2SampleProvider and
 * XiaomiDailySummarySampleProvider.
 *
 * Every read is a synchronous local SQLite query (a day is ≤ 1440 rows), so
 * screens can call them from `useState(() => …)` on the first frame.
 * `dateIso` is a LOCAL calendar date `YYYY-MM-DD`; timestamps returned are
 * ISO-8601 UTC instants. Nothing here ever synthesises a value: no data ⇒
 * `null` / empty array.
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type {
  HealthDailySummary,
  HeartRateSample,
  SleepSegment,
  StressSample,
  WorkoutSummary,
} from '../types';

/** One SpO₂ reading (automatic all-day sample or on-band spot check). */
export interface Spo2Sample {
  readonly takenAt: string;
  /** Percent, 1–100. */
  readonly percent: number;
  /** True for a spot check started on the band. */
  readonly manual: boolean;
}

/** The main sleep of the night that ended on a given day (see `SleepNight`). */
export interface SleepSessionSummary {
  readonly bedAt: string;
  readonly wakeAt: string;
  /** Minutes asleep (awake excluded). Never null for an existing session; kept nullable for API stability. */
  readonly totalMinutes: number | null;
  readonly deepMinutes: number | null;
  readonly lightMinutes: number | null;
  readonly remMinutes: number | null;
  readonly awakeMinutes: number | null;
}

/**
 * A shorter sleep session that also ended on the night's date but is not
 * its main sleep (afternoon nap, a separate evening doze, …).
 */
export interface SleepNap {
  readonly bedAt: string;
  readonly wakeAt: string;
  /** Asleep minutes (awake time excluded). */
  readonly totalMinutes: number;
}

/**
 * One night of sleep, assembled natively from the band's sleep-session rows
 * (Gadgetbridge XiaomiSleepTimeSample) and sleep-stage rows
 * (XiaomiSleepStageSample). Every number comes from the band; anything it
 * did not send is `null` (CLAUDE.md rule 8).
 *
 * Assembly rules (native `SleepNightAssembler`, mirrors Gadgetbridge
 * XiaomiSampleProvider.overlaySleep + SleepAnalysis):
 *
 * 1. Timeline. Each band session contributes "asleep" from its bedtime and
 *    "not sleeping" from its wake-up time; stage rows in between set the
 *    stage (codes: 2 deep, 3 light, 4 REM, 5 awake; 0 "not sleeping" and
 *    1 "n/a" count as not sleeping). A stage is in effect until the next
 *    stage row or the session's wake-up. Stage rows more than 60 min before
 *    any session's bedtime (or after its wake-up) are ignored as orphans.
 *    A wake-up / bedtime that falls strictly inside another overlapping
 *    session (the band re-emits a growing night with a new bedtime) does
 *    not interrupt it.
 * 2. Merging. Sleep spans separated by a not-sleeping gap of ≤ 60 min with
 *    no steps recorded in the gap belong to the same session; the gap is
 *    counted as awake (Gadgetbridge SleepAnalysis.MAX_WAKE_PHASE_LENGTH).
 *    A longer gap, or any step in it, starts a new session. Sessions of
 *    5 min or less are dropped (SleepAnalysis.MIN_SESSION_LENGTH).
 * 3. Date. A session belongs to the LOCAL calendar date on which it ended
 *    (the wake-up date), so a night 23:10 → 07:05 is `date` = the morning.
 * 4. Main vs naps. Among the sessions that ended on `date`, the one with
 *    the most asleep minutes (ties: the later one) is the main sleep; all
 *    others are `naps`, chronological.
 * 5. Minutes. With stage data: minutes are summed from the stage timeline
 *    (awake = band "awake" stages + merged gaps); `awakeCount` counts awake
 *    runs that have sleep both before and after them (lying awake before
 *    falling asleep or after the final wake-up is not an episode). Without
 *    stage data: the band's own per-session totals are used when present,
 *    otherwise `totalMinutes` is the bedtime → wake-up span; stage minutes
 *    and `awakeCount` are then null and `segments` is empty.
 * 6. `bedAt` / `wakeAt` are the first / last instant of the session's sleep
 *    timeline (a leading "awake in bed" stage is included, as upstream).
 * 7. A night whose sleep is still in progress on the band shows what has
 *    been synced so far; it grows (and may move to the next date if it
 *    crosses midnight) on later syncs.
 */
export interface SleepNight {
  /** Local wake-up date `YYYY-MM-DD`. */
  readonly date: string;
  /** ISO instant, start of the main sleep. */
  readonly bedAt: string;
  /** ISO instant, end of the main sleep. */
  readonly wakeAt: string;
  /** Asleep minutes of the main sleep (awake excluded). */
  readonly totalMinutes: number;
  readonly deepMinutes: number | null;
  readonly lightMinutes: number | null;
  readonly remMinutes: number | null;
  /** Awake minutes inside the main sleep (incl. merged ≤ 60 min gaps). */
  readonly awakeMinutes: number | null;
  /** Awake episodes inside the main sleep; null without stage data. */
  readonly awakeCount: number | null;
  /**
   * Chronological stage spans of the main sleep (adjacent equal stages
   * merged; merged gaps appear as 'awake'). `[]` when the band sent only a
   * summary.
   */
  readonly segments: readonly SleepSegment[];
  /**
   * Band sleep score. Always null for now: the Band 9 Active's sleep-details
   * header carries a "sleep quality" byte (v4+), but Gadgetbridge reads it
   * without ever persisting or displaying it, so its meaning is unverified.
   */
  readonly score: number | null;
  /** Mean of the band's automatic minute HR (20–250) inside [bedAt, wakeAt). */
  readonly avgHeartRate: number | null;
  readonly lowestHeartRate: number | null;
  /** Mean of the band's automatic minute SpO₂ (1–100) inside [bedAt, wakeAt). */
  readonly avgSpo2: number | null;
  readonly lowestSpo2: number | null;
  /** Other sessions that ended on this date, chronological. */
  readonly naps: readonly SleepNap[];
}

export interface HybridHealthStore extends HybridObject<{ android: 'kotlin' }> {
  /**
   * Day totals. Null when the band has given us no step data for that day
   * (neither minute samples nor its daily-summary file) — render "—".
   */
  getDailySummary(dateIso: string): HealthDailySummary | null;
  /** Days in [fromIso, toIso] that have data (days without data are omitted). */
  getDailySummariesRange(fromIso: string, toIso: string): readonly HealthDailySummary[];

  /** Minute HR samples + spot checks, sorted. Only 20–250 bpm. */
  getHeartRateSeries(dateIso: string): readonly HeartRateSample[];
  /** Stress 1–100 with Gadgetbridge's Xiaomi buckets (1-25/26-50/51-80/81-100). */
  getStressSeries(dateIso: string): readonly StressSample[];
  /** SpO₂ 1–100 %, automatic and manual, sorted. */
  getSpo2Series(dateIso: string): readonly Spo2Sample[];

  /**
   * The night that ended (woke up) on `dateIso`, or null when the band sent
   * no sleep ending that day. See `SleepNight` for the assembly rules.
   */
  getSleepNight(dateIso: string): SleepNight | null;
  /** Nights in [fromIso, toIso] (inclusive, ≤ 400 days) that have data, ascending by date. */
  getSleepNights(fromIso: string, toIso: string): readonly SleepNight[];

  /** The main sleep of `getSleepNight(dateIso)` as a summary, or null. */
  getSleepSession(dateIso: string): SleepSessionSummary | null;
  /** `getSleepNight(dateIso).segments` (empty when there is no night / no stage data). */
  getSleepSegments(dateIso: string): readonly SleepSegment[];

  /**
   * Recent workouts, newest first. Workouts whose summary layout we can't
   * decode yet (unknown sport/version — raw bytes are kept) are omitted.
   */
  getRecentWorkouts(limit: number): readonly WorkoutSummary[];

  /** ISO instant of the newest sample stored from the band; null when the store is empty. */
  readonly lastSampleAt: string | null;

  /** Drops all stored samples. Used by the 'forget device' flow. */
  clearAll(): void;
}
