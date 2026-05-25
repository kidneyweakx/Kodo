/*
 * mi-band-9-active — onboarding step 8/8: paired, jump to dashboard.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { router } from 'expo-router';

import { ThemedButton } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { PermissionHero } from '@/components/onboarding/PermissionHero';
import { t } from '@/libs/services/i18n';

export default function DoneScreen() {
  return (
    <OnboardingScaffold
      stepIndex={6}
      totalSteps={7}
      eyebrow="DONE"
      title={t('onboarding.done.title')}
      body={t('onboarding.done.body')}
      footer={
        <ThemedButton
          label={t('onboarding.done.cta')}
          size="lg"
          fullWidth
          onPress={() => router.replace('/(tabs)')}
          haptic="success"
        />
      }
    >
      <PermissionHero icon="band" />
    </OnboardingScaffold>
  );
}
