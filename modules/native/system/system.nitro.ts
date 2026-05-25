/*
 * mi-band-9-active — Nitro HybridObject spec for system-level band actions.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported semantically from Gadgetbridge (AGPL-3.0):
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiSystemService
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiPreferences
 *
 * Note: Mi Band 9 Active does NOT support `findDevice` (phone -> band ring).
 * `findPhone` (band -> phone ring) is supported.
 */

import type { HybridObject } from 'react-native-nitro-modules';

export type DisplayLanguage = 'zh-Hant' | 'en';
export type HeartRateInterval = 'off' | '1m' | '10m' | '30m';

export interface BandPreferenceSnapshot {
  readonly language: DisplayLanguage;
  readonly use24HourClock: boolean;
  readonly heartRateRealtime: boolean;
  readonly heartRateInterval: HeartRateInterval;
  readonly stepGoal: number;
}

export interface HybridSystemControl
  extends HybridObject<{ android: 'kotlin' }> {
  /** Triggers the phone to ring even on silent. Called by the band. */
  ringPhone(): void;
  /** Stop ringing. */
  silencePhone(): void;

  /** Push the host phone time/timezone to the band. */
  syncClock(): Promise<void>;

  getPreferences(): BandPreferenceSnapshot;
  setPreferences(prefs: BandPreferenceSnapshot): Promise<BandPreferenceSnapshot>;
}
