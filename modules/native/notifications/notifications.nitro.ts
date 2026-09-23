/*
 * mi-band-9-active — Nitro HybridObject spec for notification forwarding.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported semantically from Gadgetbridge (AGPL-3.0):
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiNotificationService
 *   - nodomain.freeyourgadget.gadgetbridge.externalevents.NotificationListener (Android NLS bridge)
 *   - nodomain.freeyourgadget.gadgetbridge.externalevents.PhoneCallReceiver (incoming calls)
 *
 * Forwarding itself is fully native: the NotificationListenerService filters,
 * coalesces and sends to the band on its own, even before JS is loaded. This
 * object only exposes settings + a manual push for testing.
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type { NotificationPushRequest } from '../types';

export interface NotificationFilter {
  readonly sourceId: string;
  readonly appName: string;
  readonly enabled: boolean;
}

export interface HybridNotificationBridge
  extends HybridObject<{ android: 'kotlin' }> {
  readonly notificationAccessGranted: boolean;

  /** Synchronous check used by onboarding (same value as `notificationAccessGranted`). */
  isNotificationAccessGranted(): boolean;

  /** Opens system settings screen for notification listener access. */
  requestAccess(): void;

  /**
   * Manually push a notification (debug / test). Dropped when the band is not
   * connected — the listener never starts a connection.
   */
  push(request: NotificationPushRequest): Promise<void>;

  /**
   * Allow-list. Returns every app that is allowed (`enabled: true`) plus every
   * app that posted a notification since install but is not allowed
   * (`enabled: false`). Apps not enabled are dropped before touching BLE.
   */
  getFilters(): readonly NotificationFilter[];
  setFilter(filter: NotificationFilter): void;
  removeFilter(sourceId: string): void;

  /** Drop notifications (and call alerts) that the phone's Do Not Disturb suppresses. */
  setMuteWhenDnd(enabled: boolean): void;
  readonly muteWhenDnd: boolean;

  /**
   * Incoming-call alerts on the band (ringing → band shows caller, band
   * reject → end call / silence ringer). Default: true.
   */
  setCallAlertsEnabled(enabled: boolean): void;
  readonly callAlertsEnabled: boolean;
}
