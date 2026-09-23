/*
 * mi-band-9-active — Nitro HybridObject spec for band alarms, reminders and sleep mode.
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * Ported semantically from Gadgetbridge:
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiScheduleService
 *     (command type 17: alarms 0/1/2/4, sleep mode 8/9, reminders 14/15/17/18)
 *
 * The band is the source of truth for alarms and reminders: every mutating
 * call re-reads the list from the band and resolves with what the band
 * reports. Nothing is invented locally. Calls reject when the band is not
 * connected.
 */

import type { HybridObject } from 'react-native-nitro-modules';

export interface TimeOfDay {
  /** 0..23 */
  readonly hour: number;
  /** 0..59 */
  readonly minute: number;
}

export interface BandAlarm {
  /** Band-assigned id (starts at 1). Pass back to update/delete. */
  readonly id: number;
  readonly time: TimeOfDay;
  readonly enabled: boolean;
  readonly smartWakeup: boolean;
  /**
   * Weekday bitmask, Gadgetbridge `Alarm` semantics:
   * Mon=1 Tue=2 Wed=4 Thu=8 Fri=16 Sat=32 Sun=64. 0 = once, 127 = daily.
   */
  readonly repeatDays: number;
}

export interface AlarmDraft {
  readonly time: TimeOfDay;
  readonly enabled: boolean;
  readonly smartWakeup: boolean;
  readonly repeatDays: number;
}

export interface AlarmList {
  /** Slot count reported by the band (`Alarms.maxAlarms`). */
  readonly maxAlarms: number;
  readonly alarms: readonly BandAlarm[];
  /** Epoch ms when the band returned this list. */
  readonly fetchedAt: number;
}

export type ReminderRepeat = 'once' | 'daily' | 'weekly' | 'monthly' | 'yearly';

export interface BandReminder {
  readonly id: number;
  /** Max 20 characters on the band; longer titles are truncated. */
  readonly title: string;
  /** Epoch ms. */
  readonly at: number;
  readonly repeat: ReminderRepeat;
}

export interface ReminderDraft {
  readonly title: string;
  readonly at: number;
  readonly repeat: ReminderRepeat;
}

export interface ReminderList {
  readonly maxReminders: number;
  readonly reminders: readonly BandReminder[];
  readonly fetchedAt: number;
}

export interface SleepModeConfig {
  readonly enabled: boolean;
  readonly start: TimeOfDay;
  readonly end: TimeOfDay;
}

export interface HybridSchedule extends HybridObject<{ android: 'kotlin' }> {
  /** Last list the band returned (persisted), or undefined if never fetched. */
  getCachedAlarms(): AlarmList | undefined;
  fetchAlarms(): Promise<AlarmList>;
  createAlarm(draft: AlarmDraft): Promise<AlarmList>;
  updateAlarm(id: number, draft: AlarmDraft): Promise<AlarmList>;
  deleteAlarms(ids: readonly number[]): Promise<AlarmList>;

  getCachedReminders(): ReminderList | undefined;
  fetchReminders(): Promise<ReminderList>;
  createReminder(draft: ReminderDraft): Promise<ReminderList>;
  updateReminder(id: number, draft: ReminderDraft): Promise<ReminderList>;
  deleteReminders(ids: readonly number[]): Promise<ReminderList>;

  /** Persisted sleep-mode schedule (band-reported or user-set), undefined if unknown. */
  getSleepMode(): SleepModeConfig | undefined;
  /** Re-reads the schedule from the band. Resolves undefined when not connected. */
  refreshSleepMode(): Promise<SleepModeConfig | undefined>;
  /** Persists and pushes; if disconnected the value is pushed on next connect. */
  setSleepMode(config: SleepModeConfig): Promise<SleepModeConfig>;
}
