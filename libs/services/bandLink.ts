/*
 * mi-band-9-active — JS facade over HybridBandLink.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { useEffect, useState } from 'react';

import { cache, cacheKeys } from '@/libs/services/cache';
import { NativeBandLink } from '@/modules/native';
import type { ConnectionState, DiscoveredBand, PairedBand } from '@/modules/native';

export const bandLink = {
  getPairedSync(): PairedBand | null {
    return cache.getSync<PairedBand>(cacheKeys.pairedBand);
  },

  async scan(durationMs = 12_000): Promise<readonly DiscoveredBand[]> {
    return NativeBandLink().scan({ durationMs });
  },

  stopScan(): void {
    NativeBandLink().stopScan();
  },

  async pair(deviceId: string, authKey: string): Promise<PairedBand> {
    const paired = await NativeBandLink().pair(deviceId, { authKey });
    cache.set(cacheKeys.pairedBand, paired);
    return paired;
  },

  async forget(): Promise<void> {
    await NativeBandLink().forget();
    cache.remove(cacheKeys.pairedBand);
  },

  async connect(): Promise<void> {
    await NativeBandLink().connect();
  },

  disconnect(): void {
    NativeBandLink().disconnect();
  },

  async syncSince(sinceIso: string): Promise<number> {
    const count = await NativeBandLink().syncSince(sinceIso);
    cache.set(cacheKeys.lastSyncAt, new Date().toISOString());
    return count;
  },
};

export function useConnectionState(): ConnectionState {
  const [state, setState] = useState<ConnectionState>(() => NativeBandLink().connectionState);
  useEffect(() => NativeBandLink().onConnectionStateChange(setState), []);
  return state;
}

export function usePairedBand(): PairedBand | null {
  const [band, setBand] = useState<PairedBand | null>(() => bandLink.getPairedSync());
  useEffect(() => {
    const unsub = NativeBandLink().onConnectionStateChange(() => {
      setBand(NativeBandLink().currentBand);
    });
    return unsub;
  }, []);
  return band;
}
