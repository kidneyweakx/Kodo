/*
 * mi-band-9-active — JS facade over HybridBandLink.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * `pair()` is intentionally a *local-only* bind: it caches deviceId + authKey
 * to MMKV and returns immediately. The real GATT connect + Xiaomi V2 auth
 * handshake happens lazily on the first `syncSince()` (or any other call
 * that requires the encrypted channel), via `ensureNativeSession()`.
 *
 * Why split it: the handshake is several BLE round-trips with the band, which
 * can stall silently if the band is bonded to another app or out of range.
 * Letting the user reach the dashboard with a valid key cached, and triggering
 * the handshake from the sync button (with visible state), is a much better
 * UX than a 30-second spinner on the onboarding screen.
 */

import { useEffect, useState } from 'react';

import { cache, cacheKeys } from '@/libs/services/cache';
import { syncStatus } from '@/libs/services/syncStatus';
import { NativeBandLink, NativeHealthConnect } from '@/modules/native';
import { safeCall, safeUnsubscribe, safeAsync } from '@/modules/native/safe';
import type { BatteryInfo, ConnectionState, DiscoveredBand, PairedBand } from '@/modules/native';

const currentNativeState = (): ConnectionState =>
  safeCall(() => NativeBandLink().connectionState, 'disconnected');

// In-flight guards so a button tap-tap-tap doesn't tear down a connection
// attempt half-way through. Both ensureNativeSession and syncSince serialize.
let sessionPromise: Promise<void> | null = null;
let syncPromise: Promise<number> | null = null;

export const bandLink = {
  getPairedSync(): PairedBand | null {
    return cache.getSync<PairedBand>(cacheKeys.pairedBand);
  },

  /**
   * Returns `[]` if no bands seen. Throws `NativeNotImplemented` if the Nitro
   * Kotlin HybridBandLink isn't registered yet, so the UI can surface an
   * actionable message instead of pretending the scan ran with 0 hits.
   */
  async scan(durationMs = 12_000): Promise<readonly DiscoveredBand[]> {
    try {
      return await NativeBandLink().scan({ durationMs });
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      if (/createHybridObject|HybridObject|registered|not.*found/i.test(message)) {
        const err = new Error('NativeNotImplemented: HybridBandLink Kotlin impl missing');
        (err as Error & { code?: string }).code = 'NATIVE_NOT_IMPLEMENTED';
        throw err;
      }
      throw e;
    }
  },

  stopScan(): void {
    safeCall(() => NativeBandLink().stopScan(), undefined);
  },

  /**
   * Local-only bind. Saves the band info + authKey to MMKV and resolves
   * immediately. Does NOT touch BluetoothGatt or run any Xiaomi handshake.
   * The first sync (or call to ensureNativeSession()) will do that.
   */
  async pair(deviceId: string, authKey: string, name?: string): Promise<PairedBand> {
    const paired: PairedBand = {
      id: deviceId,
      name: name ?? 'Mi Band 9 Active',
      authKey,
      pairedAt: new Date().toISOString(),
    };
    cache.set(cacheKeys.pairedBand, paired);
    return paired;
  },

  async forget(): Promise<void> {
    await safeAsync(() => NativeBandLink().forget(), undefined);
    cache.remove(cacheKeys.pairedBand);
  },

  /**
   * Idempotent + serialized: if a connect attempt is already in flight, the
   * second caller awaits the same promise. If the driver is already connected,
   * returns immediately. Otherwise pulls deviceId+authKey from MMKV and runs
   * the real GATT pair+auth.
   */
  async ensureNativeSession(): Promise<void> {
    if (currentNativeState() === 'connected') return;
    if (sessionPromise) {
      console.log('[bandLink] ensureNativeSession reusing in-flight pair');
      return sessionPromise;
    }
    const band = bandLink.getPairedSync();
    if (!band) throw new Error('No paired band — finish onboarding first.');
    console.log('[bandLink] ensureNativeSession starting pair', band.id);
    sessionPromise = (async () => {
      try {
        await NativeBandLink().pair(band.id, { authKey: band.authKey });
      } finally {
        sessionPromise = null;
      }
    })();
    return sessionPromise;
  },

  disconnect(): void {
    safeCall(() => NativeBandLink().disconnect(), undefined);
  },

  async syncSince(sinceIso: string): Promise<number> {
    if (syncPromise) {
      console.log('[bandLink] syncSince already in flight, awaiting existing');
      return syncPromise;
    }
    const startedAt = Date.now();
    syncStatus.setPhase('connecting', 0.05, startedAt);
    syncPromise = (async () => {
      try {
        await bandLink.ensureNativeSession();
        syncStatus.setPhase('health', 0.4, startedAt);
        const count = await safeAsync(() => NativeBandLink().syncSince(sinceIso), 0);
        // Best-effort Health Connect export of *today* so Google Fit / Sleep
        // as Android can pick it up. Swallow errors — if the user hasn't
        // granted HC perms yet, the band-side sync still succeeded.
        try {
          const today = new Date().toISOString().slice(0, 10);
          await NativeHealthConnect().exportDay(today);
        } catch (e) {
          console.log('[bandLink] HealthConnect export skipped:', (e as Error)?.message ?? e);
        }
        const finishedAt = new Date().toISOString();
        cache.set(cacheKeys.lastSyncAt, finishedAt);
        syncStatus.setDone(finishedAt);
        return count;
      } catch (e) {
        syncStatus.setError(e instanceof Error ? e.message : String(e));
        throw e;
      } finally {
        syncPromise = null;
      }
    })();
    return syncPromise;
  },
};

export function useConnectionState(): ConnectionState {
  const [state, setState] = useState<ConnectionState>(currentNativeState);
  useEffect(() => safeUnsubscribe(() => NativeBandLink().onConnectionStateChange(setState)), []);
  return state;
}

/** Live battery info. Seeds from native state, then listens for updates. */
export function useBatteryInfo(): BatteryInfo | null {
  const [info, setInfo] = useState<BatteryInfo | null>(() =>
    safeCall(() => {
      const v = NativeBandLink().battery;
      return v ?? null;
    }, null),
  );
  useEffect(
    () =>
      safeUnsubscribe(() => NativeBandLink().onBatteryChange(setInfo)),
    [],
  );
  return info;
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
