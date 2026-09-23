/*
 * mi-band-9-active — a slim Mi Band 9 Active companion app
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
 * JS facade over HybridSchedule (alarms, reminders, sleep mode). The cached
 * lists are the band's last answer (persisted natively) — use them for the
 * first frame, then `fetch*()` to revalidate silently.
 */

import { NativeSchedule } from '@/modules/native';
import { safeAsync, safeCall } from '@/modules/native/safe';
import type {
  AlarmDraft,
  AlarmList,
  ReminderDraft,
  ReminderList,
  SleepModeConfig,
} from '@/modules/native';

/** Gadgetbridge Alarm weekday bits. */
export const AlarmDays = {
  MON: 1,
  TUE: 2,
  WED: 4,
  THU: 8,
  FRI: 16,
  SAT: 32,
  SUN: 64,
  ONCE: 0,
  DAILY: 127,
} as const;

export const schedule = {
  getCachedAlarms(): AlarmList | undefined {
    return safeCall(() => NativeSchedule().getCachedAlarms(), undefined);
  },
  fetchAlarms(): Promise<AlarmList> {
    return NativeSchedule().fetchAlarms();
  },
  createAlarm(draft: AlarmDraft): Promise<AlarmList> {
    return NativeSchedule().createAlarm(draft);
  },
  updateAlarm(id: number, draft: AlarmDraft): Promise<AlarmList> {
    return NativeSchedule().updateAlarm(id, draft);
  },
  deleteAlarms(ids: readonly number[]): Promise<AlarmList> {
    return NativeSchedule().deleteAlarms(ids);
  },

  getCachedReminders(): ReminderList | undefined {
    return safeCall(() => NativeSchedule().getCachedReminders(), undefined);
  },
  fetchReminders(): Promise<ReminderList> {
    return NativeSchedule().fetchReminders();
  },
  createReminder(draft: ReminderDraft): Promise<ReminderList> {
    return NativeSchedule().createReminder(draft);
  },
  updateReminder(id: number, draft: ReminderDraft): Promise<ReminderList> {
    return NativeSchedule().updateReminder(id, draft);
  },
  deleteReminders(ids: readonly number[]): Promise<ReminderList> {
    return NativeSchedule().deleteReminders(ids);
  },

  getSleepMode(): SleepModeConfig | undefined {
    return safeCall(() => NativeSchedule().getSleepMode(), undefined);
  },
  refreshSleepMode(): Promise<SleepModeConfig | undefined> {
    return safeAsync(() => NativeSchedule().refreshSleepMode(), undefined);
  },
  setSleepMode(config: SleepModeConfig): Promise<SleepModeConfig> {
    return NativeSchedule().setSleepMode(config);
  },
};
