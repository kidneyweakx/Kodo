/*
 * mi-band-9-active — onboarding stack: welcome → connect → key → extras.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { Stack } from 'expo-router';

export default function OnboardingLayout() {
  return (
    <Stack
      screenOptions={{
        headerShown: false,
        animation: 'slide_from_right',
        gestureEnabled: true,
      }}
    >
      <Stack.Screen name="welcome" />
      <Stack.Screen name="connect" />
      <Stack.Screen name="key" />
      {/* Band is paired by now — no swiping back into the key step. */}
      <Stack.Screen name="extras" options={{ gestureEnabled: false }} />
    </Stack>
  );
}
