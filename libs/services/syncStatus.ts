/*
 * mi-band-9-active — observable sync status singleton.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Bridges the native `onSyncProgress` event stream into a tiny in-memory
 * pub/sub so any screen can subscribe with a sync-cache-warm initial value
 * (CLAUDE.md rule 10).
 */

import { useEffect, useState } from 'react';

import { cache, cacheKeys } from '@/libs/services/cache';
import { NativeBandLink } from '@/modules/native';
import { safeUnsubscribe } from '@/modules/native/safe';

export type SyncPhase =
  | 'idle'
  | 'connecting'
  | 'health'
  | 'sleep'
  | 'workouts'
  | 'settings'
  | 'done'
  | 'error';

export interface SyncStatusSnapshot {
  readonly phase: SyncPhase;
  readonly progress: number;
  readonly label: string;
  readonly startedAt: number | null;
  readonly lastSyncedAt: string | null;
  readonly errorMessage: string | null;
}

const initial = (): SyncStatusSnapshot => ({
  phase: 'idle',
  progress: 0,
  label: '',
  startedAt: null,
  lastSyncedAt: cache.getSync<string>(cacheKeys.lastSyncAt),
  errorMessage: null,
});

let snapshot: SyncStatusSnapshot = initial();
const listeners = new Set<(s: SyncStatusSnapshot) => void>();

const emit = (next: SyncStatusSnapshot) => {
  snapshot = next;
  listeners.forEach((l) => l(next));
};

let nativeUnsub: (() => void) | null = null;

const ensureNativeSubscribed = () => {
  if (nativeUnsub) return;
  nativeUnsub = safeUnsubscribe(() =>
    NativeBandLink().onSyncProgress(({ phase, progress, startedAt }) => {
      const known: SyncPhase = (
        ['idle', 'connecting', 'health', 'sleep', 'workouts', 'settings', 'done', 'error'] as const
      ).includes(phase as SyncPhase)
        ? (phase as SyncPhase)
        : 'health';
      emit({
        ...snapshot,
        phase: known,
        progress: Math.max(0, Math.min(1, progress)),
        label: phase,
        startedAt,
        errorMessage: null,
      });
      if (known === 'done') {
        const finishedAt = new Date().toISOString();
        cache.set(cacheKeys.lastSyncAt, finishedAt);
        emit({ ...snapshot, phase: 'idle', progress: 0, label: '', lastSyncedAt: finishedAt });
      }
    }),
  );
};

export const syncStatus = {
  get(): SyncStatusSnapshot {
    return snapshot;
  },
  setPhase(phase: SyncPhase, progress: number, startedAt: number): void {
    emit({
      ...snapshot,
      phase,
      progress: Math.max(0, Math.min(1, progress)),
      label: phase,
      startedAt,
      errorMessage: null,
    });
  },
  setDone(finishedAt: string): void {
    emit({ ...snapshot, phase: 'idle', progress: 0, label: '', lastSyncedAt: finishedAt });
  },
  setError(message: string): void {
    emit({ ...snapshot, phase: 'error', errorMessage: message });
  },
  subscribe(listener: (s: SyncStatusSnapshot) => void): () => void {
    ensureNativeSubscribed();
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
};

export function useSyncStatus(): SyncStatusSnapshot {
  const [state, setState] = useState<SyncStatusSnapshot>(syncStatus.get);
  useEffect(() => syncStatus.subscribe(setState), []);
  return state;
}
