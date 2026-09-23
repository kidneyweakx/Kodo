/*
 * mi-band-9-active — JS facade over HybridNotificationBridge.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Forwarding runs natively (NotificationListenerService → band) and never
 * needs JS. This facade only reads/writes settings.
 */

import { useEffect, useState } from 'react';

import { cache, cacheKeys } from '@/libs/services/cache';
import { NativeNotificationBridge } from '@/modules/native';
import { safeAsync, safeCall } from '@/modules/native/safe';
import type { NotificationPushRequest } from '@/modules/native';

export interface NotificationFilter {
  readonly sourceId: string;
  readonly appName: string;
  readonly enabled: boolean;
}

const readNativeFilters = (): readonly NotificationFilter[] | null =>
  safeCall<readonly NotificationFilter[] | null>(() => NativeNotificationBridge().getFilters(), null);

const refreshFilterCache = (): readonly NotificationFilter[] => {
  const list = readNativeFilters();
  if (list) cache.set(cacheKeys.notificationFilters, list);
  return list ?? cache.getSync<NotificationFilter[]>(cacheKeys.notificationFilters) ?? [];
};

export const notificationBridge = {
  /** Synchronous — safe on the render path (onboarding). */
  isAccessGrantedSync(): boolean {
    return safeCall(() => NativeNotificationBridge().isNotificationAccessGranted(), false);
  },

  requestAccess(): void {
    safeCall(() => NativeNotificationBridge().requestAccess(), undefined);
  },

  /** Debug/manual push. Dropped natively when the band is not connected. */
  push(request: NotificationPushRequest): Promise<void> {
    return safeAsync(() => NativeNotificationBridge().push(request), undefined);
  },

  /**
   * Allowed apps (`enabled: true`) plus apps that have posted notifications but
   * are not allowed (`enabled: false`). The native read is a synchronous
   * SharedPreferences lookup; the MMKV copy is only a fallback.
   */
  getFilters(): readonly NotificationFilter[] {
    return refreshFilterCache();
  },

  setFilter(filter: NotificationFilter): void {
    safeCall(() => NativeNotificationBridge().setFilter(filter), undefined);
    refreshFilterCache();
  },

  removeFilter(sourceId: string): void {
    safeCall(() => NativeNotificationBridge().removeFilter(sourceId), undefined);
    refreshFilterCache();
  },

  setMuteWhenDnd(enabled: boolean): void {
    safeCall(() => NativeNotificationBridge().setMuteWhenDnd(enabled), undefined);
    cache.set(cacheKeys.muteWhenDnd, enabled);
  },

  getMuteWhenDndSync(): boolean {
    return safeCall(
      () => NativeNotificationBridge().muteWhenDnd,
      cache.getSync<boolean>(cacheKeys.muteWhenDnd) ?? false,
    );
  },

  /** Band alerts for incoming calls (reject on band → end call / silence ringer). */
  setCallAlertsEnabled(enabled: boolean): void {
    safeCall(() => NativeNotificationBridge().setCallAlertsEnabled(enabled), undefined);
  },

  getCallAlertsEnabledSync(): boolean {
    return safeCall(() => NativeNotificationBridge().callAlertsEnabled, false);
  },

  /**
   * Call after the user grants READ_PHONE_STATE / ANSWER_PHONE_CALLS at runtime
   * so the native telephony listener registers without an app restart.
   */
  onCallPermissionsChanged(): void {
    safeCall(() => {
      const bridge = NativeNotificationBridge();
      bridge.setCallAlertsEnabled(bridge.callAlertsEnabled);
    }, undefined);
  },
};

export function useNotificationAccess(): boolean {
  const [granted, setGranted] = useState(notificationBridge.isAccessGrantedSync());
  useEffect(() => {
    const id = setInterval(() => {
      const next = notificationBridge.isAccessGrantedSync();
      setGranted((prev) => (prev === next ? prev : next));
    }, 2_000);
    return () => clearInterval(id);
  }, []);
  return granted;
}
