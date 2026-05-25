/*
 * mi-band-9-active — haptics bridge
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import * as Haptics from 'expo-haptics';

export type HapticKind = 'selection' | 'tap' | 'success' | 'warning' | 'error' | 'heavy' | 'none';

export const hapticsBridge = {
  fire(kind: HapticKind) {
    switch (kind) {
      case 'selection':
        return Haptics.selectionAsync();
      case 'tap':
        return Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
      case 'heavy':
        return Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Heavy);
      case 'success':
        return Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      case 'warning':
        return Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
      case 'error':
        return Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error);
      case 'none':
      default:
        return Promise.resolve();
    }
  },
};
