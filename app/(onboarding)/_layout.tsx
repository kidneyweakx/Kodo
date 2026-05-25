/*
 * mi-band-9-active — onboarding stack layout.
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
      <Stack.Screen name="language" />
      <Stack.Screen name="bluetooth" />
      <Stack.Screen name="notifications" />
      <Stack.Screen name="battery" />
      <Stack.Screen name="scan" />
      <Stack.Screen name="auth" />
      <Stack.Screen name="done" />
    </Stack>
  );
}
