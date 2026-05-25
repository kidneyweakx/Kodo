/*
 * mi-band-9-active — JS facade over HybridNotificationBridge.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { useEffect, useState } from 'react';

import { cache, cacheKeys } from '@/libs/services/cache';
import { NativeNotificationBridge } from '@/modules/native';
import type { NotificationPushRequest } from '@/modules/native';

export interface NotificationFilter {
  readonly sourceId: string;
  readonly appName: string;
  readonly enabled: boolean;
}

export const notificationBridge = {
  isAccessGrantedSync(): boolean {
    try {
      return NativeNotificationBridge().notificationAccessGranted;
    } catch {
      return false;
    }
  },

  requestAccess(): void {
    NativeNotificationBridge().requestAccess();
  },

  push(request: NotificationPushRequest): Promise<void> {
    return NativeNotificationBridge().push(request);
  },

  getFilters(): readonly NotificationFilter[] {
    const cached = cache.getSync<NotificationFilter[]>(cacheKeys.notificationFilters);
    return cached ?? NativeNotificationBridge().getFilters();
  },

  setFilter(filter: NotificationFilter): void {
    NativeNotificationBridge().setFilter(filter);
    const list = NativeNotificationBridge().getFilters();
    cache.set(cacheKeys.notificationFilters, list);
  },

  removeFilter(sourceId: string): void {
    NativeNotificationBridge().removeFilter(sourceId);
    const list = NativeNotificationBridge().getFilters();
    cache.set(cacheKeys.notificationFilters, list);
  },

  setMuteWhenDnd(enabled: boolean): void {
    NativeNotificationBridge().setMuteWhenDnd(enabled);
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
