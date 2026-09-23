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

/** The main sleep session that ended on a given day (Gadgetbridge XiaomiSleepTimeSample). */
export interface SleepSessionSummary {
  readonly bedAt: string;
  readonly wakeAt: string;
  /** Minutes asleep as reported by the band; null when the file did not carry it. */
  readonly totalMinutes: number | null;
  readonly deepMinutes: number | null;
  readonly lightMinutes: number | null;
  readonly remMinutes: number | null;
  readonly awakeMinutes: number | null;
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

  /** The longest sleep session that ended (woke up) on `dateIso`, or null. */
  getSleepSession(dateIso: string): SleepSessionSummary | null;
  /** Stage segments of the sleep that ended on `dateIso`. Unknown / n-a stages are omitted. */
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
