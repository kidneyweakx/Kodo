/*
 * mi-band-9-active — boot redirect.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Render-path rule: read sync cache, redirect synchronously. No skeleton, no
 * await. CLAUDE.md rule 10.
 */

import { Redirect } from 'expo-router';

import { cache, cacheKeys } from '@/libs/services/cache';

export default function Index() {
  const onboarded = cache.getSync<boolean>(cacheKeys.onboardingDone) === true;
  return onboarded ? <Redirect href="/(tabs)" /> : <Redirect href="/(onboarding)/welcome" />;
}
