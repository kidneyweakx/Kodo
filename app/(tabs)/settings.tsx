/*
 * mi-band-9-active — settings tab (theme, language, paired band, reset).
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { useState } from 'react';
import { Pressable, ScrollView, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { router } from 'expo-router';

import { Radius, Spacing } from '@/constants/DesignSystem';
import { ThemedButton, ThemedSurface, ThemedText, readableTextOn } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';
import { bandLink, usePairedBand } from '@/libs/services/bandLink';
import { cache, cacheKeys } from '@/libs/services/cache';
import { getLocale, setLocale, t } from '@/libs/services/i18n';
import type { ThemeId } from '@/context/ThemeContext';
import type { SupportedLocale } from '@/libs/services/i18n';

const THEME_IDS: readonly ThemeId[] = ['aurora', 'graphite', 'ember', 'lagoon'];
const LANGS: readonly { code: SupportedLocale; label: string }[] = [
  { code: 'zh-Hant', label: '繁體中文' },
  { code: 'en', label: 'English' },
];

export default function SettingsTab() {
  const { theme, themeId, setTheme } = useTheme();
  const paired = usePairedBand();
  const [lang, setLang] = useState<SupportedLocale>(getLocale());

  const onLang = (code: SupportedLocale) => {
    setLocale(code);
    cache.set(cacheKeys.language, code);
    setLang(code);
  };

  const onUnpair = async () => {
    await bandLink.forget();
    cache.remove(cacheKeys.onboardingDone);
    router.replace('/(onboarding)/welcome');
  };

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: theme.background.primary }} edges={['top']}>
      <ScrollView contentContainerStyle={{ padding: Spacing.xl, gap: Spacing.lg, paddingBottom: Spacing.xxxl }}>
        <View>
          <ThemedText variant="caption" tone="accent">
            SETTINGS
          </ThemedText>
          <ThemedText variant="headlineLarge" style={{ marginTop: Spacing.xs }}>
            設定 / Settings
          </ThemedText>
        </View>

        <ThemedSurface variant="card" padded="lg">
          <ThemedText variant="caption" tone="tertiary">
            PAIRED BAND
          </ThemedText>
          <ThemedText variant="titleLarge" style={{ marginTop: Spacing.xs }}>
            {paired?.name ?? '—'}
          </ThemedText>
          <ThemedText variant="caption" tone="secondary" style={{ marginTop: Spacing.xs }}>
            {paired?.id ?? t('common.unsynced')}
          </ThemedText>
        </ThemedSurface>

        <View style={{ gap: Spacing.sm }}>
          <ThemedText variant="titleMedium">主題 / Theme</ThemedText>
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.sm }}>
            {THEME_IDS.map((id) => {
              const selected = id === themeId;
              return (
                <Pressable
                  key={id}
                  onPress={() => setTheme(id)}
                  accessibilityRole="radio"
                  accessibilityState={{ selected }}
                  style={{
                    paddingVertical: Spacing.sm,
                    paddingHorizontal: Spacing.lg,
                    borderRadius: Radius.pill,
                    borderWidth: 1.5,
                    borderColor: selected ? theme.accent : theme.glassBorder,
                    backgroundColor: selected ? theme.accent : theme.background.secondary,
                  }}
                >
                  <ThemedText
                    variant="bodyMedium"
                    weight="600"
                    style={{ color: selected ? readableTextOn(theme.accent) : theme.text.primary }}
                  >
                    {id}
                  </ThemedText>
                </Pressable>
              );
            })}
          </View>
        </View>

        <View style={{ gap: Spacing.sm }}>
          <ThemedText variant="titleMedium">語言 / Language</ThemedText>
          <View style={{ flexDirection: 'row', gap: Spacing.sm }}>
            {LANGS.map((l) => {
              const selected = l.code === lang;
              return (
                <Pressable
                  key={l.code}
                  onPress={() => onLang(l.code)}
                  accessibilityRole="radio"
                  accessibilityState={{ selected }}
                  style={{
                    flex: 1,
                    paddingVertical: Spacing.md,
                    paddingHorizontal: Spacing.lg,
                    borderRadius: Radius.lg,
                    borderWidth: 1.5,
                    borderColor: selected ? theme.accent : theme.glassBorder,
                    backgroundColor: selected ? theme.accent : theme.background.secondary,
                  }}
                >
                  <ThemedText
                    variant="titleMedium"
                    style={{ color: selected ? readableTextOn(theme.accent) : theme.text.primary, textAlign: 'center' }}
                  >
                    {l.label}
                  </ThemedText>
                </Pressable>
              );
            })}
          </View>
        </View>

        <ThemedButton variant="destructive" label="解除配對 / Unpair" size="lg" fullWidth onPress={onUnpair} />
      </ScrollView>
    </SafeAreaView>
  );
}
