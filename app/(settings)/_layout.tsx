/*
 * mi-band-9-active — settings sub-pages stack (pushed over the tab bar).
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { Stack } from 'expo-router';

import { useTheme } from '@/context/ThemeContext';

export default function SettingsLayout() {
  const { theme } = useTheme();
  return (
    <Stack
      screenOptions={{
        headerShown: false,
        animation: 'slide_from_right',
        contentStyle: { backgroundColor: theme.background.primary },
      }}
    />
  );
}
