/*
 * mi-band-9-active — Nitro HybridObject spec for notification forwarding.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported semantically from Gadgetbridge (AGPL-3.0):
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiNotificationService
 *   - nodomain.freeyourgadget.gadgetbridge.service.NotificationListener (Android NLS bridge)
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type { NotificationPushRequest } from '@/modules/native/types';

export interface NotificationFilter {
  readonly sourceId: string;
  readonly appName: string;
  readonly enabled: boolean;
}

export interface HybridNotificationBridge
  extends HybridObject<{ android: 'kotlin' }> {
  readonly notificationAccessGranted: boolean;

  /** Opens system settings screen for notification listener access. */
  requestAccess(): void;

  /** Manually push a notification (used for testing + by the on-device NLS). */
  push(request: NotificationPushRequest): Promise<void>;

  /** Active filter list. Apps not in this list are dropped before send. */
  getFilters(): readonly NotificationFilter[];
  setFilter(filter: NotificationFilter): void;
  removeFilter(sourceId: string): void;

  /** Mirrors the phone's DnD state to the band. */
  setMuteWhenDnd(enabled: boolean): void;
  readonly muteWhenDnd: boolean;
}
