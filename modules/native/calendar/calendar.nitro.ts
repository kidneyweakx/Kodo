/*
 * mi-band-9-active — Nitro HybridObject spec for calendar sync.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiCalendarService
 *     (type 12, subtype 1 CMD_CALENDAR_SET, max 50 events)
 *   - nodomain.freeyourgadget.gadgetbridge.externalevents.CalendarReceiver / CalendarManager
 *     (CalendarContract.Instances window, reminders -> notifyMinutesBefore)
 *
 * Native sync reads CalendarContract (needs READ_CALENDAR) and pushes on every
 * connect, on syncNow(), and from a >= 6 h WorkManager job while enabled.
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type { CalendarEventPush } from '../types';

export interface CalendarSyncSettings {
  readonly enabled: boolean;
  /** Days ahead to sync, 1..30 (upstream default 7). */
  readonly lookaheadDays: number;
  readonly includeAllDay: boolean;
}

export interface HybridCalendarBridge extends HybridObject<{ android: 'kotlin' }> {
  /** Push a JS-provided event list (bypasses CalendarContract). */
  pushEvents(events: readonly CalendarEventPush[]): Promise<void>;
  /** Tell the band calendar sync is disabled (clears its list). */
  clearEvents(): Promise<void>;

  hasCalendarPermission(): boolean;
  getSyncSettings(): CalendarSyncSettings;
  /** Persists; if enabled and connected, syncs right away. Resolves event count sent, -1 if not sent. */
  setSyncSettings(settings: CalendarSyncSettings): Promise<number>;
  /** Read CalendarContract and push. -1 when disabled, no permission or not connected. */
  syncNow(): Promise<number>;
  /** Epoch ms of last successful push, undefined if never. */
  getLastSyncedAt(): number | undefined;
}
