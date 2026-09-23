/*
 * mi-band-9-active — Nitro HybridObject spec for inactivity / sedentary reminders.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - XiaomiHealthService (CMD_CONFIG_STANDING_REMINDER_GET = 12 / _SET = 13,
 *     proto `Health.standingReminder`)
 *   - XiaomiCoordinator: `devicesettings_inactivity_dnd_no_threshold` — the band
 *     exposes NO interval/threshold setting, only an active window plus an
 *     optional "do not disturb" window.
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type { TimeOfDay } from '../schedule/schedule.nitro';

export interface SedentaryConfig {
  readonly enabled: boolean;
  /** Reminders are active between start and end (band local time). */
  readonly start: TimeOfDay;
  readonly end: TimeOfDay;
  /** Suppress reminders inside the DND window (e.g. lunch). */
  readonly dndEnabled: boolean;
  readonly dndStart: TimeOfDay;
  readonly dndEnd: TimeOfDay;
}

export interface HybridSedentary extends HybridObject<{ android: 'kotlin' }> {
  /**
   * Persisted config: the band-reported value after the last connect, or the
   * user's pending change. `undefined` until either exists (never a guess).
   */
  get(): SedentaryConfig | undefined;
  /** True when a local change has not reached the band yet. */
  readonly pendingPush: boolean;
  /** Re-read from the band. Resolves undefined when not connected / no reply. */
  refresh(): Promise<SedentaryConfig | undefined>;
  /** Persist + push (or queue for next connect). */
  set(config: SedentaryConfig): Promise<SedentaryConfig>;
}
