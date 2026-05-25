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
import { LogoLockup } from '@/components/brand/LogoLockup';
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
      <Animated.View
        entering={FadeIn.duration(560).delay(120)}
        style={{ flex: 1, justifyContent: 'center', gap: Spacing.xxl }}
      >
        <LogoLockup tagline="一支手環,一個專屬伴侶。" size={132} />

        <ThemedSurface variant="elevated" padded="xl" style={{ gap: Spacing.lg }}>
          {[
            { color: theme.accent, label: '本機儲存 · 不上雲 / Local first' },
            { color: theme.success, label: '省電優化 · 不常駐 / Battery-aware' },
            { color: theme.secondary, label: '只支援 Mi Band 9 Active · 專一' },
          ].map((row) => (
            <View
              key={row.label}
              style={{ flexDirection: 'row', gap: Spacing.lg, alignItems: 'center' }}
            >
              <View style={{ width: 8, height: 8, borderRadius: 999, backgroundColor: row.color }} />
              <ThemedText variant="titleMedium">{row.label}</ThemedText>
            </View>
          ))}
        </ThemedSurface>
      </Animated.View>
    </OnboardingScaffold>
  );
}
