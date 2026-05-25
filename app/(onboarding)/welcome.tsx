/*
 * mi-band-9-active — onboarding step 1/8: welcome.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { router } from 'expo-router';
import { View } from 'react-native';
import Animated, { FadeIn } from 'react-native-reanimated';

import { ThemedButton, ThemedSurface, ThemedText } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { AppLogo } from '@/components/brand/AppLogo';
import { Spacing } from '@/constants/DesignSystem';
import { useTheme } from '@/context/ThemeContext';
import { t } from '@/libs/services/i18n';

export default function WelcomeScreen() {
  const { theme } = useTheme();

  return (
    <OnboardingScaffold
      stepIndex={0}
      totalSteps={7}
      eyebrow={t('app.name')}
      title={t('onboarding.welcome.title')}
      body={t('onboarding.welcome.body')}
      footer={
        <ThemedButton
          label={t('onboarding.welcome.cta')}
          size="lg"
          fullWidth
          onPress={() => router.push('/(onboarding)/language')}
        />
      }
    >
      <Animated.View entering={FadeIn.duration(560).delay(180)} style={{ flex: 1, justifyContent: 'center', gap: Spacing.xl }}>
        <View style={{ alignItems: 'center' }}>
          <AppLogo size={140} />
        </View>
        <ThemedSurface variant="elevated" padded="xl" style={{ gap: Spacing.lg }}>
          <View style={{ flexDirection: 'row', gap: Spacing.lg, alignItems: 'center' }}>
            <View style={{ width: 8, height: 8, borderRadius: 999, backgroundColor: theme.accent }} />
            <ThemedText variant="titleMedium">本機儲存 · 不上雲 / Local first</ThemedText>
          </View>
          <View style={{ flexDirection: 'row', gap: Spacing.lg, alignItems: 'center' }}>
            <View style={{ width: 8, height: 8, borderRadius: 999, backgroundColor: theme.success }} />
            <ThemedText variant="titleMedium">省電優化 · 不常駐 / Battery-aware sync</ThemedText>
          </View>
          <View style={{ flexDirection: 'row', gap: Spacing.lg, alignItems: 'center' }}>
            <View style={{ width: 8, height: 8, borderRadius: 999, backgroundColor: theme.secondary }} />
            <ThemedText variant="titleMedium">只支援 Mi Band 9 Active · 專一</ThemedText>
          </View>
        </ThemedSurface>
      </Animated.View>
    </OnboardingScaffold>
  );
}
