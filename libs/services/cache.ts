/*
 * mi-band-9-active — MMKV-backed sync cache. See CLAUDE.md rule 10.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Render-path callers MUST use getSync(). Only background refresh callers
 * may await getAsync().
 */

import { createMMKV } from 'react-native-mmkv';

const storage = createMMKV({ id: 'mb9a-cache.v1' });

interface Envelope<T> {
  readonly v: 1;
  readonly t: number;
  readonly data: T;
}

const isEnvelope = <T>(value: unknown): value is Envelope<T> =>
  typeof value === 'object' &&
  value !== null &&
  (value as { v?: unknown }).v === 1 &&
  typeof (value as { t?: unknown }).t === 'number';

export const cache = {
  getSync<T>(key: string): T | null {
    const raw = storage.getString(key);
    if (!raw) return null;
    try {
      const parsed = JSON.parse(raw) as unknown;
      if (isEnvelope<T>(parsed)) return parsed.data;
      return null;
    } catch {
      return null;
    }
  },

  getWithMeta<T>(key: string): { readonly data: T; readonly ageMs: number } | null {
    const raw = storage.getString(key);
    if (!raw) return null;
    try {
      const parsed = JSON.parse(raw) as unknown;
      if (isEnvelope<T>(parsed)) return { data: parsed.data, ageMs: Date.now() - parsed.t };
      return null;
    } catch {
      return null;
    }
  },

  set<T>(key: string, data: T): void {
    const envelope: Envelope<T> = { v: 1, t: Date.now(), data };
    storage.set(key, JSON.stringify(envelope));
  },

  remove(key: string): void {
    storage.remove(key);
  },

  clear(): void {
    storage.clearAll();
  },

  /**
   * Every key's raw envelope as one JSON string — the JS half of a Kodō
   * backup. Derived per-day dashboard snapshots are skipped (the native store
   * rebuilds them).
   */
  exportSnapshot(): string {
    const out: Record<string, string> = {};
    for (const key of storage.getAllKeys()) {
      if (key.startsWith('dashboard-summary:')) continue;
      const raw = storage.getString(key);
      if (raw != null) out[key] = raw;
    }
    return JSON.stringify({ v: 1, entries: out });
  },

  /** Applies a snapshot from `exportSnapshot`. Unknown shapes are ignored. */
  importSnapshot(json: string): number {
    try {
      const parsed = JSON.parse(json) as { v?: unknown; entries?: Record<string, unknown> };
      if (parsed.v !== 1 || !parsed.entries) return 0;
      let n = 0;
      for (const [key, raw] of Object.entries(parsed.entries)) {
        if (typeof raw !== 'string') continue;
        storage.set(key, raw);
        n += 1;
      }
      return n;
    } catch {
      return 0;
    }
  },
};

export const cacheKeys = {
  pairedBand: 'paired-band',
  onboardingDone: 'onboarding-done',
  onboardingBatterySeen: 'onboarding-battery-seen',
  /** UI-side preferences mirrored to band where possible. */
  use24HourClock: 'pref:use24h',
  stepGoal: 'pref:step-goal',
  heartRateInterval: 'pref:hr-interval',
  autoSyncIntervalMin: 'pref:auto-sync-min',
  language: 'language',
  themeId: 'theme-id',
  themeMode: 'theme-mode',
  notificationFilters: 'notification-filters',
  muteWhenDnd: 'mute-when-dnd',
  dashboardSummary: (dateIso: string) => `dashboard-summary:${dateIso}`,
  lastSyncAt: 'last-sync-at',
} as const;
