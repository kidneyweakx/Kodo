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
};

export const cacheKeys = {
  pairedBand: 'paired-band',
  onboardingDone: 'onboarding-done',
  language: 'language',
  themeId: 'theme-id',
  themeMode: 'theme-mode',
  notificationFilters: 'notification-filters',
  muteWhenDnd: 'mute-when-dnd',
  dashboardSummary: (dateIso: string) => `dashboard-summary:${dateIso}`,
  lastSyncAt: 'last-sync-at',
} as const;
