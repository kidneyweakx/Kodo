/*
 * mi-band-9-active — onboarding step 3/8: bluetooth permission.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * The actual runtime permission request runs through the Nitro SystemControl
 * bridge once that's implemented. For now we record the user intent and move
 * on so the JS-side flow is testable.
 */

import { router } from 'expo-router';

import { ThemedButton } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { t } from '@/libs/services/i18n';
import { PermissionHero } from '@/components/onboarding/PermissionHero';

export default function BluetoothScreen() {
  return (
    <OnboardingScaffold
      stepIndex={2}
      totalSteps={7}
      eyebrow="STEP 3"
      title={t('onboarding.bluetooth.title')}
      body={t('onboarding.bluetooth.body')}
      footer={
        <ThemedButton
          label={t('onboarding.bluetooth.cta')}
          size="lg"
          fullWidth
          onPress={() => router.push('/(onboarding)/notifications')}
        />
      }
    >
      <PermissionHero icon="bluetooth" />
    </OnboardingScaffold>
  );
}
