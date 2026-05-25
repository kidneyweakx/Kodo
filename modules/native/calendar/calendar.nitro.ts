/*
 * mi-band-9-active — Nitro HybridObject spec for calendar sync.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiCalendarService
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type { CalendarEventPush } from '../types';

export interface HybridCalendarBridge
  extends HybridObject<{ android: 'kotlin' }> {
  pushEvents(events: readonly CalendarEventPush[]): Promise<void>;
  clearEvents(): Promise<void>;
}
