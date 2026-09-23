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
  if (cache.getSync<boolean>(cacheKeys.onboardingDone) === true) return <Redirect href="/(tabs)" />;
  // Paired but the app was closed on the last (optional) step — resume there
  // instead of making the user scan and paste the key again.
  if (cache.getSync(cacheKeys.pairedBand)) return <Redirect href="/(onboarding)/extras" />;
  return <Redirect href="/(onboarding)/welcome" />;
}
