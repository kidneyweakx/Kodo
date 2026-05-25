/*
 * mi-band-9-active — onboarding step 5/8: battery optimization whitelist.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Doze whitelist is needed for *occasional* WorkManager wake-ups, not a
 * permanent foreground service. We still default to "off" and let the user
 * decide — fewer privileges == happier OEM battery scores.
 */

import { router } from 'expo-router';
import { View } from 'react-native';

import { Spacing } from '@/constants/DesignSystem';
import { ThemedButton, ThemedSurface, ThemedText } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { PermissionHero } from '@/components/onboarding/PermissionHero';
import { t } from '@/libs/services/i18n';

export default function BatteryScreen() {
  const onContinue = () => router.push('/(onboarding)/scan');

  return (
    <OnboardingScaffold
      stepIndex={4}
      totalSteps={7}
      eyebrow="STEP 5"
      title={t('onboarding.battery.title')}
      body={t('onboarding.battery.body')}
      footer={
        <View style={{ flexDirection: 'row', gap: Spacing.md }}>
          <View style={{ flex: 1 }}>
            <ThemedButton variant="ghost" label={t('common.skip')} size="lg" fullWidth onPress={onContinue} />
          </View>
          <View style={{ flex: 1 }}>
            <ThemedButton label={t('onboarding.battery.cta')} size="lg" fullWidth onPress={onContinue} />
          </View>
        </View>
      }
    >
      <View style={{ flex: 1, justifyContent: 'space-between', paddingBottom: Spacing.lg }}>
        <PermissionHero icon="battery" />
        <ThemedSurface variant="elevated" padded="lg" style={{ gap: Spacing.sm }}>
          <ThemedText variant="caption" tone="accent">
            POWER PROFILE
          </ThemedText>
          <ThemedText variant="titleMedium">省電預設 / Battery defaults</ThemedText>
          <ThemedText variant="bodyMedium" tone="secondary">
            背景同步用 WorkManager + Doze-aware,每 30 分鐘最多一次。即時心率預設關閉。
          </ThemedText>
          <ThemedText variant="bodyMedium" tone="secondary">
            Background sync uses WorkManager with Doze awareness; ≤ once per 30 min. Realtime HR is off by default.
          </ThemedText>
        </ThemedSurface>
      </View>
    </OnboardingScaffold>
  );
}
