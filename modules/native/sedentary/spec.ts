/*
 * mi-band-9-active — Nitro HybridObject spec for inactivity / sedentary reminders.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - XiaomiPreferences.FEAT_INACTIVITY
 *   - XiaomiSettingsCustomizer (inactivity preference exposure)
 */

import type { HybridObject } from 'react-native-nitro-modules';

export interface SedentaryConfig {
  readonly enabled: boolean;
  /** 24h, inclusive. e.g. 9 = 09:00 */
  readonly startHour: number;
  /** 24h, exclusive. e.g. 21 = until 20:59 */
  readonly endHour: number;
  /** Minutes of inactivity before the band buzzes. */
  readonly intervalMinutes: number;
  /** Don't disturb during lunch hour. */
  readonly suppressDuringDnd: boolean;
}

export interface HybridSedentary
  extends HybridObject<{ android: 'kotlin' }> {
  get(): SedentaryConfig;
  set(config: SedentaryConfig): Promise<SedentaryConfig>;
}
