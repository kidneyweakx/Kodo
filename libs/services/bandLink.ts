/*
 * mi-band-9-active — JS facade over HybridBandLink (safe-mode).
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { useEffect, useState } from 'react';

import { cache, cacheKeys } from '@/libs/services/cache';
import { NativeBandLink } from '@/modules/native';
import { safeAsync, safeCall, safeUnsubscribe } from '@/modules/native/safe';
import type { ConnectionState, DiscoveredBand, PairedBand } from '@/modules/native';

export const bandLink = {
  getPairedSync(): PairedBand | null {
    return cache.getSync<PairedBand>(cacheKeys.pairedBand);
  },

  scan(durationMs = 12_000): Promise<readonly DiscoveredBand[]> {
    return safeAsync(() => NativeBandLink().scan({ durationMs }), []);
  },

  stopScan(): void {
    safeCall(() => NativeBandLink().stopScan(), undefined);
  },

  async pair(deviceId: string, authKey: string): Promise<PairedBand> {
    const paired = await NativeBandLink().pair(deviceId, { authKey });
    cache.set(cacheKeys.pairedBand, paired);
    return paired;
  },

  async forget(): Promise<void> {
    await safeAsync(() => NativeBandLink().forget(), undefined);
    cache.remove(cacheKeys.pairedBand);
  },

  connect(): Promise<void> {
    return safeAsync(() => NativeBandLink().connect(), undefined);
  },

  disconnect(): void {
    safeCall(() => NativeBandLink().disconnect(), undefined);
  },

  async syncSince(sinceIso: string): Promise<number> {
    const count = await safeAsync(() => NativeBandLink().syncSince(sinceIso), 0);
    cache.set(cacheKeys.lastSyncAt, new Date().toISOString());
    return count;
  },
};

const initialConnectionState = (): ConnectionState =>
  safeCall(() => NativeBandLink().connectionState, 'disconnected');

export function useConnectionState(): ConnectionState {
  const [state, setState] = useState<ConnectionState>(initialConnectionState);
  useEffect(() => safeUnsubscribe(() => NativeBandLink().onConnectionStateChange(setState)), []);
  return state;
}

export function usePairedBand(): PairedBand | null {
  const [band, setBand] = useState<PairedBand | null>(() => bandLink.getPairedSync());
  useEffect(
    () =>
      safeUnsubscribe(() =>
        NativeBandLink().onConnectionStateChange(() => {
          setBand(safeCall(() => NativeBandLink().currentBand, bandLink.getPairedSync()));
        }),
      ),
    [],
  );
  return band;
}
