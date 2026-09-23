/*
 * mi-band-9-active — JS facade over HybridBandLink.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * `pair()` runs the REAL GATT connect + Xiaomi V2 encrypted handshake on the
 * native side and only resolves once the band is authenticated. Native
 * persists the band (BandStore) so it survives restarts; we mirror it into
 * MMKV purely for first-paint chrome (Rule 10).
 *
 * Every native rejection carries a code prefix (`AUTH_REJECTED: …`), mapped
 * by `pairErrorCode()` for the onboarding UI.
 */

import { useEffect, useState } from 'react';

import { cache, cacheKeys } from '@/libs/services/cache';
import { syncStatus } from '@/libs/services/syncStatus';
import { NativeBandLink, NativeHealthConnect } from '@/modules/native';
import { safeCall, safeUnsubscribe, safeAsync } from '@/modules/native/safe';
import type { BatteryInfo, ConnectionState, DiscoveredBand, PairedBand } from '@/modules/native';

export type BandLinkErrorCode =
  | 'AUTH_KEY_INVALID'
  | 'AUTH_REJECTED'
  | 'BT_OFF'
  | 'PERMISSION'
  | 'TIMEOUT'
  | 'GATT'
  | 'NOT_PAIRED'
  | 'UNKNOWN';

const KNOWN_CODES: readonly Exclude<BandLinkErrorCode, 'UNKNOWN'>[] = [
  'AUTH_KEY_INVALID',
  'AUTH_REJECTED',
  'BT_OFF',
  'PERMISSION',
  'TIMEOUT',
  'GATT',
  'NOT_PAIRED',
];

const CODE_RE = new RegExp(`\\b(${KNOWN_CODES.join('|')}):`);

/**
 * Normalise a user-pasted auth key: strips `0x`, whitespace, `:` and `-`,
 * lower-cases. Returns 32 lowercase hex chars, or null when invalid.
 * Mirrors XiaomiCrypto.normalizeAuthKeyHex on the native side.
 */
export function normalizeAuthKey(raw: string): string | null {
  let s = raw.trim();
  if (s.startsWith('0x') || s.startsWith('0X')) s = s.slice(2);
  s = s.replace(/[\s:-]/g, '').toLowerCase();
  return /^[0-9a-f]{32}$/.test(s) ? s : null;
}

/** Map a native rejection to its code. Native messages start with `CODE:`. */
export function pairErrorCode(e: unknown): BandLinkErrorCode {
  const message =
    e instanceof Error ? e.message : typeof e === 'string' ? e : String((e as { message?: unknown })?.message ?? e);
  const m = CODE_RE.exec(message);
  if (m) return m[1] as BandLinkErrorCode;
  if (/SecurityException|permission/i.test(message)) return 'PERMISSION';
  return 'UNKNOWN';
}

const currentNativeState = (): ConnectionState =>
  safeCall(() => NativeBandLink().connectionState, 'disconnected');

const nativeBand = (): PairedBand | null => safeCall(() => NativeBandLink().currentBand, null);

// In-flight guards so a tap-tap-tap doesn't start overlapping handshakes.
let sessionPromise: Promise<void> | null = null;
let syncPromise: Promise<number> | null = null;

export const bandLink = {
  /** MMKV mirror — render-path safe (sync). */
  getPairedSync(): PairedBand | null {
    return cache.getSync<PairedBand>(cacheKeys.pairedBand);
  },

  /**
   * Returns `[]` if no bands seen. Throws `NativeNotImplemented` if the Nitro
   * Kotlin HybridBandLink isn't registered, so the UI can surface an
   * actionable message instead of pretending the scan ran with 0 hits.
   * Rejects with `BT_OFF:` / `PERMISSION:` coded errors.
   */
  async scan(durationMs = 12_000): Promise<readonly DiscoveredBand[]> {
    try {
      return await NativeBandLink().scan({ durationMs });
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      if (/createHybridObject|HybridObject|registered|not.*found/i.test(message) && !CODE_RE.test(message)) {
        const err = new Error('NativeNotImplemented: HybridBandLink Kotlin impl missing');
        (err as Error & { code?: string }).code = 'NATIVE_NOT_IMPLEMENTED';
        throw err;
      }
      throw e;
    }
  },

  /** Stops the in-flight scan; the pending scan() resolves early with results so far. */
  stopScan(): void {
    safeCall(() => NativeBandLink().stopScan(), undefined);
  },

  /**
   * Real pair: GATT connect + encrypted auth. Resolves once authenticated and
   * persisted natively; rejects with a coded Error (see `pairErrorCode`).
   */
  async pair(deviceId: string, authKey: string, name?: string): Promise<PairedBand> {
    const key = normalizeAuthKey(authKey);
    if (!key) throw new Error('AUTH_KEY_INVALID: auth key must be 32 hex characters');
    const native = await NativeBandLink().pair(deviceId, { authKey: key });
    const paired: PairedBand = { ...native, name: native.name || name || 'Mi Band 9 Active' };
    cache.set(cacheKeys.pairedBand, paired);
    return paired;
  },

  async forget(): Promise<void> {
    await safeAsync(() => NativeBandLink().forget(), undefined);
    cache.remove(cacheKeys.pairedBand);
  },

  /**
   * Idempotent + serialized. Connects to the natively stored band. Migration:
   * builds before native persistence only had the band in MMKV — if native
   * says NOT_PAIRED but MMKV has a band, run one native pair with that key.
   */
  async ensureNativeSession(): Promise<void> {
    if (currentNativeState() === 'connected') return;
    if (sessionPromise) return sessionPromise;
    sessionPromise = (async () => {
      try {
        try {
          await NativeBandLink().connect();
        } catch (e) {
          const cached = bandLink.getPairedSync();
          if (pairErrorCode(e) !== 'NOT_PAIRED' || !cached) throw e;
          console.log('[bandLink] migrating MMKV-only band to native store', cached.id);
          await bandLink.pair(cached.id, cached.authKey, cached.name);
        }
      } finally {
        sessionPromise = null;
      }
    })();
    return sessionPromise;
  },

  disconnect(): void {
    safeCall(() => NativeBandLink().disconnect(), undefined);
  },

  /** WorkManager periodic sync. Native clamps interval to >= 30 min (docs/POWER.md). */
  setPeriodicSync(enabled: boolean, intervalMinutes = 30): void {
    safeCall(() => NativeBandLink().setPeriodicSync(enabled, Math.max(30, intervalMinutes)), undefined);
  },

  /** Ask the band for a fresh battery reading (no-op when not connected). */
  requestBattery(): void {
    safeCall(() => NativeBandLink().requestBattery(), undefined);
  },

  async syncSince(sinceIso: string): Promise<number> {
    if (syncPromise) return syncPromise;
    const startedAt = Date.now();
    syncStatus.setPhase('connecting', 0.05, startedAt);
    syncPromise = (async () => {
      try {
        await bandLink.ensureNativeSession();
        syncStatus.setPhase('health', 0.4, startedAt);
        // Errors propagate: a failed sync must not look like "0 new samples".
        const count = await NativeBandLink().syncSince(sinceIso);
        // Best-effort Health Connect export of today; band sync already succeeded.
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
    safeCall(() => NativeBandLink().battery ?? null, null),
  );
  useEffect(() => safeUnsubscribe(() => NativeBandLink().onBatteryChange(setInfo)), []);
  return info;
}

/**
 * Paired band. Seeds from the MMKV mirror (first paint), then reconciles with
 * the native store (source of truth) whenever the connection state changes.
 */
export function usePairedBand(): PairedBand | null {
  const [band, setBand] = useState<PairedBand | null>(() => bandLink.getPairedSync());
  useEffect(() => {
    const sync = () => {
      const n = nativeBand();
      if (n) {
        cache.set(cacheKeys.pairedBand, n);
        setBand((prev) => (prev && prev.id === n.id && prev.authKey === n.authKey && prev.name === n.name ? prev : n));
        return;
      }
      // n === null: not paired natively yet (pre-migration build) or native
      // module unavailable — keep the MMKV mirror unless forget() cleared it.
      if (!bandLink.getPairedSync()) setBand(null);
    };
    sync();
    return safeUnsubscribe(() => NativeBandLink().onConnectionStateChange(sync));
  }, []);
  return band;
}
