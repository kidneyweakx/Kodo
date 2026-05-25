/*
 * mi-band-9-active — JS facade over HybridHealthStore with sync-cache hydration.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Follows CLAUDE.md rule 10 — initial state seeds from the sync cache so the
 * dashboard never flashes a skeleton on a warm load.
 */

import { useEffect, useState } from 'react';

import { cache, cacheKeys } from '@/libs/services/cache';
import { NativeHealthStore } from '@/modules/native';
import type { HealthDailySummary } from '@/modules/native';

const todayIso = (): string => new Date().toISOString().slice(0, 10);

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

  refreshDashboard(dateIso = todayIso()): HealthDailySummary | null {
    const fresh = NativeHealthStore().getDailySummary(dateIso);
    if (fresh) cache.set(cacheKeys.dashboardSummary(dateIso), fresh);
    return fresh;
  },
};

export function useDashboardSummary(dateIso: string = todayIso()): DashboardReadModel {
  const [model, setModel] = useState<DashboardReadModel>(() => readDashboardSync(dateIso));

  useEffect(() => {
    let cancelled = false;
    const tick = () => {
      const fresh = healthStore.refreshDashboard(dateIso);
      if (!cancelled && fresh) {
        setModel({ summary: fresh, state: 'fresh', ageMs: 0 });
      }
    };
    tick();
    return () => {
      cancelled = true;
    };
  }, [dateIso]);

  return model;
}
