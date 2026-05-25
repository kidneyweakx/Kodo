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
} from '@/modules/native/types';

export interface BandLinkScanOptions {
  /** ms, default 12000. Long scans cost battery; we keep it under 15s. */
  readonly durationMs?: number;
}

export interface BandLinkPairOptions {
  /** 32-byte hex string. If empty, the native side tries the plaintext numeric path. */
  readonly authKey: string;
}

export interface HybridBandLink
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  // ----- state -----
  readonly connectionState: ConnectionState;
  readonly currentBand: PairedBand | null;
  readonly battery: BatteryInfo | null;

  // ----- discovery + pairing -----
  scan(options: BandLinkScanOptions): Promise<readonly DiscoveredBand[]>;
  stopScan(): void;
  pair(deviceId: string, options: BandLinkPairOptions): Promise<PairedBand>;
  forget(): Promise<void>;

  // ----- session lifecycle -----
  connect(): Promise<void>;
  disconnect(): void;

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
}
