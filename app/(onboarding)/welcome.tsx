/*
 * mi-band-9-active — onboarding 1/4: welcome + inline language pick.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { router } from 'expo-router';
import { useState } from 'react';
import { View } from 'react-native';
import Animated, { FadeIn } from 'react-native-reanimated';

import { ThemedButton, ThemedSurface, ThemedText } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { LogoLockup } from '@/components/brand/LogoLockup';
import { SettingsChoice } from '@/components/settings/SettingsKit';
import { Spacing } from '@/constants/DesignSystem';
import { useTheme } from '@/context/ThemeContext';
import { cache, cacheKeys } from '@/libs/services/cache';
import { getLocale, setLocale, t } from '@/libs/services/i18n';
import type { SupportedLocale } from '@/libs/services/i18n';

const LANGS: readonly { value: SupportedLocale; label: string }[] = [
  { value: 'zh-Hant', label: '繁體中文' },
  { value: 'en', label: 'English' },
];

export default function WelcomeScreen() {
  const { theme } = useTheme();
  // Language defaults to the system locale; this state only exists so the
  // screen re-renders its strings when the user flips it.
  const [lang, setLang] = useState<SupportedLocale>(getLocale());

  const onLang = (code: SupportedLocale) => {
    setLocale(code);
    cache.set(cacheKeys.language, code);
    setLang(code);
  };

  return (
    <OnboardingScaffold
      key={lang}
      stepIndex={0}
      totalSteps={4}
      title={t('onboarding.welcome.title')}
      body={t('onboarding.welcome.body')}
      footer={
        <ThemedButton
          label={t('onboarding.welcome.cta')}
          size="lg"
          fullWidth
          onPress={() => router.push('/(onboarding)/connect')}
        />
      }
    >
      <Animated.View
        entering={FadeIn.duration(560).delay(120)}
        style={{ flex: 1, justifyContent: 'center', gap: Spacing.xl }}
      >
        <LogoLockup size={112} />

        <ThemedSurface variant="elevated" padded="lg" style={{ gap: Spacing.md }}>
          {[
            { color: theme.accent, label: t('onboarding.welcome.points.local') },
            { color: theme.success, label: t('onboarding.welcome.points.battery') },
            { color: theme.secondary, label: t('onboarding.welcome.points.single') },
          ].map((row) => (
            <View key={row.label} style={{ flexDirection: 'row', gap: Spacing.md, alignItems: 'center' }}>
              <View style={{ width: 8, height: 8, borderRadius: 999, backgroundColor: row.color }} />
              <ThemedText variant="bodyMedium">{row.label}</ThemedText>
            </View>
          ))}
        </ThemedSurface>

        <SettingsChoice options={LANGS} value={lang} onChange={onLang} />
      </Animated.View>
    </OnboardingScaffold>
  );
}
