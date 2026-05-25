/*
 * mi-band-9-active — Nitro HybridObject spec for Health Connect export.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported semantically from Gadgetbridge (AGPL-3.0):
 *   - util/healthconnect/HealthConnectClientProvider.kt
 *   - util/healthconnect/GadgetbridgeDataExporter.kt
 *   - util/healthconnect/syncers/{HeartRateSync,Spo2Syncer,WeightSyncer,...}.kt
 *
 * Fitbit, Samsung Health, and Google Fit all read FROM Health Connect on
 * Android 14+. We are the writer.
 */

import type { HybridObject } from 'react-native-nitro-modules';

export type HealthConnectKind =
  | 'steps'
  | 'heartRate'
  | 'spo2'
  | 'sleep'
  | 'activeCalories'
  | 'distance';

export interface HealthConnectStatus {
  readonly installed: boolean;
  readonly grantedKinds: readonly HealthConnectKind[];
}

export interface HybridHealthConnect
  extends HybridObject<{ android: 'kotlin' }> {
  status(): HealthConnectStatus;
  /** Opens the Health Connect permission grant screen. */
  requestPermissions(kinds: readonly HealthConnectKind[]): Promise<HealthConnectStatus>;

  /** Push the day's samples for the given date. */
  exportDay(dateIso: string): Promise<number>;

  /** Wipes any data we previously wrote. Used on un-pair / forget. */
  revokeAndClear(): Promise<void>;
}
