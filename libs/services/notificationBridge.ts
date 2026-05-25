/*
 * mi-band-9-active — JS facade over HybridNotificationBridge.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
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

export const notificationBridge = {
  isAccessGrantedSync(): boolean {
    return safeCall(() => NativeNotificationBridge().notificationAccessGranted, false);
  },

  requestAccess(): void {
    safeCall(() => NativeNotificationBridge().requestAccess(), undefined);
  },

  push(request: NotificationPushRequest): Promise<void> {
    return safeAsync(() => NativeNotificationBridge().push(request), undefined);
  },

  getFilters(): readonly NotificationFilter[] {
    const cached = cache.getSync<NotificationFilter[]>(cacheKeys.notificationFilters);
    if (cached) return cached;
    return safeCall<readonly NotificationFilter[]>(
      () => NativeNotificationBridge().getFilters(),
      [],
    );
  },

  setFilter(filter: NotificationFilter): void {
    safeCall(() => NativeNotificationBridge().setFilter(filter), undefined);
    const list = safeCall<readonly NotificationFilter[]>(
      () => NativeNotificationBridge().getFilters(),
      [],
    );
    cache.set(cacheKeys.notificationFilters, list);
  },

  removeFilter(sourceId: string): void {
    safeCall(() => NativeNotificationBridge().removeFilter(sourceId), undefined);
    const list = safeCall<readonly NotificationFilter[]>(
      () => NativeNotificationBridge().getFilters(),
      [],
    );
    cache.set(cacheKeys.notificationFilters, list);
  },

  setMuteWhenDnd(enabled: boolean): void {
    safeCall(() => NativeNotificationBridge().setMuteWhenDnd(enabled), undefined);
    cache.set(cacheKeys.muteWhenDnd, enabled);
  },

  getMuteWhenDndSync(): boolean {
    return cache.getSync<boolean>(cacheKeys.muteWhenDnd) ?? false;
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
