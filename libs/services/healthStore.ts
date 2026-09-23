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
 * JS facade over HybridHealthStore (native SQLite filled by activity sync).
 *
 * CLAUDE.md rule 10: every hook reads synchronously on the render path (a
 * local SQLite query through Nitro; the dashboard falls back to its MMKV
 * snapshot), so a warm screen never flashes a skeleton. Rule 8: `null`
 * / `[]` means "the band gave us nothing", never a placeholder.
 *
 * Dates are LOCAL calendar dates (`YYYY-MM-DD`), matching the native store.
 */

import { useEffect, useMemo, useState } from 'react';

import { cache, cacheKeys } from '@/libs/services/cache';
import { syncStatus, type SyncStatusSnapshot } from '@/libs/services/syncStatus';
import { NativeHealthStore } from '@/modules/native';
import type {
  HealthDailySummary,
  HeartRateSample,
  SleepSegment,
  StressSample,
  WorkoutSummary,
} from '@/modules/native';
import type { SleepSessionSummary, Spo2Sample } from '@/modules/native/health/health.nitro';
import { safeCall } from '@/modules/native/safe';

export type { SleepSessionSummary, Spo2Sample };

/** Local calendar date `YYYY-MM-DD` (NOT `toISOString()`, which is UTC). */
export const localDateIso = (d: Date = new Date()): string => {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
};

export interface DashboardReadModel {
  readonly summary: HealthDailySummary | null;
  readonly state: 'cold' | 'warm' | 'fresh';
  readonly ageMs: number;
}

const readDashboardSync = (dateIso: string): DashboardReadModel => {
  const cached = cache.getWithMeta<HealthDailySummary>(cacheKeys.dashboardSummary(dateIso));
  if (!cached) return { summary: null, state: 'cold', ageMs: 0 };
  if (cached.ageMs < 60_000) return { summary: cached.data, state: 'fresh', ageMs: cached.ageMs };
  return { summary: cached.data, state: 'warm', ageMs: cached.ageMs };
};

export const healthStore = {
  readDashboardSync,

  /** Native read of the day summary; mirrors it into MMKV for the next cold start. */
  refreshDashboard(dateIso: string = localDateIso()): HealthDailySummary | null {
    const fresh = safeCall<HealthDailySummary | null>(
      () => NativeHealthStore().getDailySummary(dateIso),
      null,
    );
    if (fresh) cache.set(cacheKeys.dashboardSummary(dateIso), fresh);
    return fresh;
  },

  getSummariesRange(fromIso: string, toIso: string): readonly HealthDailySummary[] {
    return safeCall(() => NativeHealthStore().getDailySummariesRange(fromIso, toIso), []);
  },
  getHeartRateSeries(dateIso: string): readonly HeartRateSample[] {
    return safeCall(() => NativeHealthStore().getHeartRateSeries(dateIso), []);
  },
  getStressSeries(dateIso: string): readonly StressSample[] {
    return safeCall(() => NativeHealthStore().getStressSeries(dateIso), []);
  },
  getSpo2Series(dateIso: string): readonly Spo2Sample[] {
    return safeCall(() => NativeHealthStore().getSpo2Series(dateIso), []);
  },
  getSleepSession(dateIso: string): SleepSessionSummary | null {
    return safeCall(() => NativeHealthStore().getSleepSession(dateIso), null);
  },
  getSleepSegments(dateIso: string): readonly SleepSegment[] {
    return safeCall(() => NativeHealthStore().getSleepSegments(dateIso), []);
  },
  getRecentWorkouts(limit: number): readonly WorkoutSummary[] {
    return safeCall(() => NativeHealthStore().getRecentWorkouts(limit), []);
  },
  /** ISO instant of the newest band sample in the store, or null. */
  getLastSampleAt(): string | null {
    return safeCall(() => NativeHealthStore().lastSampleAt, null);
  },
  clearAll(): void {
    safeCall(() => NativeHealthStore().clearAll(), undefined);
  },
};

/**
 * Bumps whenever a sync finishes or moves to a new phase (health → sleep →
 * workouts), i.e. whenever new rows may have landed in the store. Progress
 * ticks inside a phase are ignored to keep re-reads cheap.
 */
function useSyncVersion(): number {
  const [version, setVersion] = useState(0);
  useEffect(() => {
    let prev: SyncStatusSnapshot = syncStatus.get();
    return syncStatus.subscribe((s) => {
      const finished = s.lastSyncedAt !== prev.lastSyncedAt || s.phase === 'done';
      const phaseAdvanced =
        s.phase !== prev.phase && (s.phase === 'sleep' || s.phase === 'workouts');
      prev = s;
      if (finished || phaseAdvanced) setVersion((v) => v + 1);
    });
  }, []);
  return version;
}

/**
 * Day summary. Frame 1 reads the native store synchronously (local SQLite);
 * if native isn't available yet it falls back to the MMKV snapshot, so a warm
 * start never shows a skeleton. Re-reads after each sync phase.
 */
export function useDashboardSummary(dateIso: string = localDateIso()): DashboardReadModel {
  const version = useSyncVersion();
  const native = useMemo(
    () =>
      safeCall<HealthDailySummary | null>(() => NativeHealthStore().getDailySummary(dateIso), null),
    // `version` re-runs the read after a sync.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [dateIso, version],
  );

  useEffect(() => {
    if (native) cache.set(cacheKeys.dashboardSummary(dateIso), native);
  }, [native, dateIso]);

  return useMemo<DashboardReadModel>(
    () => (native ? { summary: native, state: 'fresh', ageMs: 0 } : readDashboardSync(dateIso)),
    [native, dateIso],
  );
}

/** Recent workout sessions read from the native sample store. */
export function useRecentWorkouts(limit = 10): readonly WorkoutSummary[] {
  const version = useSyncVersion();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  return useMemo(() => healthStore.getRecentWorkouts(limit), [limit, version]);
}

/** The sleep session that ended on `dateIso` (default: today), or null. */
export function useSleepSession(dateIso: string = localDateIso()): SleepSessionSummary | null {
  const version = useSyncVersion();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  return useMemo(() => healthStore.getSleepSession(dateIso), [dateIso, version]);
}

/** ISO instant of the newest band sample; null until the first successful sync. */
export function useLastSampleAt(): string | null {
  const version = useSyncVersion();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  return useMemo(() => healthStore.getLastSampleAt(), [version]);
}

/**
 * Generic day-series hook. Pass a stable reader, e.g.
 * `useDaySeries(healthStore.getHeartRateSeries, day)`.
 */
export function useDaySeries<T>(
  read: (dateIso: string) => readonly T[],
  dateIso: string = localDateIso(),
): readonly T[] {
  const version = useSyncVersion();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  return useMemo(() => read(dateIso), [read, dateIso, version]);
}
