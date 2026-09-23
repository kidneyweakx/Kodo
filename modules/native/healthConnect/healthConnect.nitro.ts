/*
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * Nitro HybridObject spec for Health Connect export. Ported semantically
 * from Gadgetbridge util/healthconnect (HealthConnectClientProvider,
 * HealthConnectUtils, syncers/*). Fitbit, Samsung Health and Google Fit read
 * FROM Health Connect; we are only a writer.
 */

import type { HybridObject } from 'react-native-nitro-modules';

export type HealthConnectKind =
  | 'steps'
  | 'heartRate'
  | 'spo2'
  | 'sleep'
  | 'activeCalories'
  | 'distance'
  | 'restingHeartRate'
  | 'exercise';

/**
 * `available` — HC usable (built into Android 14+, or the HC app on 13-).
 * `updateRequired` — Android 13-: HC app missing/outdated; requestPermissions()
 *   opens the Play Store page instead.
 * `unavailable` — not supported on this device.
 */
export type HealthConnectAvailability = 'available' | 'updateRequired' | 'unavailable';

export interface HealthConnectStatus {
  readonly availability: HealthConnectAvailability;
  /** Same as `availability === 'available'` (kept for older callers). */
  readonly installed: boolean;
  /** Kinds whose WRITE permission is currently granted. */
  readonly grantedKinds: readonly HealthConnectKind[];
}

export interface HybridHealthConnect extends HybridObject<{ android: 'kotlin' }> {
  status(): Promise<HealthConnectStatus>;

  /**
   * Shows the Health Connect permission sheet for the WRITE permissions of
   * `kinds` (needs a foreground Activity) and resolves with the resulting
   * status. When HC must be installed/updated, opens the Play Store instead
   * and resolves with the current status.
   */
  requestPermissions(kinds: readonly HealthConnectKind[]): Promise<HealthConnectStatus>;

  /** Push one local day (`YYYY-MM-DD`). Idempotent (clientRecordId upserts). Resolves with records written. */
  exportDay(dateIso: string): Promise<number>;

  /** Push every local day in [fromIso, toIso]. Idempotent. Resolves with records written. */
  exportRange(fromIso: string, toIso: string): Promise<number>;

  /** Deletes every record this app wrote, then revokes our HC permissions. Used on un-pair / forget. */
  revokeAndClear(): Promise<void>;
}
