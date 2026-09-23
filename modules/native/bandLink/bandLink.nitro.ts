/*
 * mi-band-9-active — Nitro HybridObject spec for BLE link, scan, pair, sync.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Semantics ported from Gadgetbridge classes (AGPL-3.0):
 *   - nodomain.freeyourgadget.gadgetbridge.devices.xiaomi.XiaomiCoordinator
 *   - nodomain.freeyourgadget.gadgetbridge.devices.xiaomi.watches.MiBand9ActiveCoordinator
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiAuthService
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiBleSupport
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiBleProtocolV1
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiBleProtocolV2
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type {
  BatteryInfo,
  ConnectionState,
  DiscoveredBand,
  HealthDailySummary,
  PairedBand,
} from '../types';

export interface BandLinkScanOptions {
  /** ms, default 12000. Long scans cost battery; we keep it under 15s. */
  readonly durationMs?: number;
}

export interface BandLinkPairOptions {
  /**
   * 16-byte auth key as 32 hex chars. Native normalises `0x` prefix, spaces,
   * colons, dashes and upper case before validating.
   */
  readonly authKey: string;
}

/**
 * Error-message prefixes used by `pair()` / `connect()` rejections. The
 * rejected Error's `message` STARTS WITH one of these followed by `:`.
 *   AUTH_KEY_INVALID  key is not 32 hex chars after normalising
 *   AUTH_REJECTED     band's HMAC didn't match / band refused auth (wrong key)
 *   BT_OFF            Bluetooth adapter missing or disabled
 *   PERMISSION        SecurityException (BLUETOOTH_CONNECT/SCAN not granted)
 *   TIMEOUT           band never answered (out of range / bonded to another app)
 *   GATT              any other GATT failure (incl. V2 service missing)
 *   NOT_PAIRED        connect() with no stored band
 */
export type BandLinkErrorCode =
  | 'AUTH_KEY_INVALID'
  | 'AUTH_REJECTED'
  | 'BT_OFF'
  | 'PERMISSION'
  | 'TIMEOUT'
  | 'GATT'
  | 'NOT_PAIRED';

export interface HybridBandLink
  extends HybridObject<{ android: 'kotlin' }> {
  // ----- state -----
  readonly connectionState: ConnectionState;
  readonly currentBand: PairedBand | null;
  readonly battery: BatteryInfo | null;

  // ----- discovery + pairing -----
  scan(options: BandLinkScanOptions): Promise<readonly DiscoveredBand[]>;
  /** Stops an in-flight scan; the pending `scan()` promise resolves early with results so far. */
  stopScan(): void;
  /**
   * Real GATT connect + Xiaomi V2 encrypted auth. Resolves only once the band
   * is authenticated, then persists the band natively (survives restart).
   * Rejects with an Error whose message starts with a `BandLinkErrorCode:`.
   * On failure nothing is persisted and the GATT client is closed.
   */
  pair(deviceId: string, options: BandLinkPairOptions): Promise<PairedBand>;
  /** Clears the stored band, disables periodic sync, disconnects, removes the system bond. */
  forget(): Promise<void>;

  // ----- session lifecycle -----
  /**
   * Connects to the natively stored band; resolves when authenticated.
   * Rejects with `NOT_PAIRED:` when no band is stored, else same codes as pair().
   */
  connect(): Promise<void>;
  /** User-initiated disconnect. Also disarms passive auto-reconnect. */
  disconnect(): void;

  // ----- background + band housekeeping -----
  /** Enable/disable WorkManager periodic sync. intervalMinutes is clamped to >= 30. */
  setPeriodicSync(enabled: boolean, intervalMinutes: number): void;
  /** Ask the band for a fresh battery reading (no-op when not connected). */
  requestBattery(): void;

  // ----- sync -----
  /**
   * Pulls daily samples since `sinceIso`. Resolves with the count of days
   * persisted to the native store. Caller reads results via HybridHealthStore.
   */
  syncSince(sinceIso: string): Promise<number>;
  fetchTodaySummary(): Promise<HealthDailySummary | null>;

  // ----- event subscriptions (native -> JS) -----
  /** Returns an unsubscribe function. */
  onConnectionStateChange(listener: (state: ConnectionState) => void): () => void;
  onBatteryChange(listener: (battery: BatteryInfo) => void): () => void;
  onScanResult(listener: (band: DiscoveredBand) => void): () => void;

  /**
   * Live sync progress. `phase` is human-displayable ("Heart rate",
   * "Sleep", ...) and `progress` is 0..1. Used to drive the dashboard's
   * live sync banner. Fires on the same dispatch as native sync I/O.
   */
  onSyncProgress(listener: (event: SyncProgress) => void): () => void;
}

export interface SyncProgress {
  readonly phase: string;
  readonly progress: number;
  readonly startedAt: number;
}
