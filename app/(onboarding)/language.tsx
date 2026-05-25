/*
 * mi-band-9-active — onboarding step 2/8: language pick.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { router } from 'expo-router';
import { useState } from 'react';
import { Pressable, View } from 'react-native';
import Animated, { FadeInRight } from 'react-native-reanimated';

import { Radius, Spacing } from '@/constants/DesignSystem';
import { ThemedButton, ThemedText, readableTextOn } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { useTheme } from '@/context/ThemeContext';
import { cache, cacheKeys } from '@/libs/services/cache';
import { getLocale, setLocale, t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';
import type { SupportedLocale } from '@/libs/services/i18n';

const LANGUAGES: ReadonlyArray<{ code: SupportedLocale; labelKey: string }> = [
  { code: 'zh-Hant', labelKey: 'onboarding.language.zhHant' },
  { code: 'en', labelKey: 'onboarding.language.en' },
];

export default function LanguageScreen() {
  const { theme } = useTheme();
  const [picked, setPicked] = useState<SupportedLocale>(getLocale());

  const onPick = (code: SupportedLocale) => {
    void hapticsBridge.fire('selection');
    setLocale(code);
    setPicked(code);
  };

  const onContinue = () => {
    cache.set(cacheKeys.language, picked);
    router.push('/(onboarding)/bluetooth');
  };

  return (
    <OnboardingScaffold
      stepIndex={1}
      totalSteps={7}
      eyebrow="STEP 2"
      title={t('onboarding.language.title')}
      body={t('onboarding.language.body')}
      footer={<ThemedButton label={t('common.continue')} size="lg" fullWidth onPress={onContinue} />}
    >
      <View style={{ gap: Spacing.md, marginTop: Spacing.lg }}>
        {LANGUAGES.map((lang, idx) => {
          const selected = lang.code === picked;
          return (
            <Animated.View key={lang.code} entering={FadeInRight.delay(120 + idx * 80).duration(360)}>
              <Pressable
                onPress={() => onPick(lang.code)}
                accessibilityRole="radio"
                accessibilityState={{ selected }}
                style={{
                  borderRadius: Radius.lg,
                  paddingVertical: Spacing.lg,
                  paddingHorizontal: Spacing.xl,
                  borderWidth: 1.5,
                  borderColor: selected ? theme.accent : theme.glassBorder,
                  backgroundColor: selected ? theme.accent : theme.background.secondary,
                }}
              >
                <ThemedText
                  variant="titleLarge"
                  style={{ color: selected ? readableTextOn(theme.accent) : theme.text.primary }}
                >
                  {t(lang.labelKey)}
                </ThemedText>
              </Pressable>
            </Animated.View>
          );
        })}
      </View>
    </OnboardingScaffold>
  );
}
