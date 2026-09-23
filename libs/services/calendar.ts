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
 * JS facade over HybridCalendarBridge. Sync reads CalendarContract natively
 * (READ_CALENDAR must be granted by the permissions flow first).
 */

import { NativeCalendarBridge } from '@/modules/native';
import { safeAsync, safeCall } from '@/modules/native/safe';
import type { CalendarSyncSettings } from '@/modules/native';

export const calendar = {
  hasPermission(): boolean {
    return safeCall(() => NativeCalendarBridge().hasCalendarPermission(), false);
  },
  getSyncSettings(): CalendarSyncSettings | undefined {
    return safeCall(() => NativeCalendarBridge().getSyncSettings(), undefined);
  },
  /** Resolves the number of events sent, -1 if not sent (disconnected / no permission). */
  setSyncSettings(settings: CalendarSyncSettings): Promise<number> {
    return NativeCalendarBridge().setSyncSettings(settings);
  },
  syncNow(): Promise<number> {
    return safeAsync(() => NativeCalendarBridge().syncNow(), -1);
  },
  getLastSyncedAt(): number | undefined {
    return safeCall(() => NativeCalendarBridge().getLastSyncedAt(), undefined);
  },
};
