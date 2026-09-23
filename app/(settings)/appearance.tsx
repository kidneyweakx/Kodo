/*
 * mi-band-9-active — Settings › Appearance & language.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { useState } from 'react';
import { Pressable, View } from 'react-native';

import { Radius, Spacing } from '@/constants/DesignSystem';
import { ThemedText } from '@/components/themed';
import { SettingsPage } from '@/components/settings/SettingsPage';
import {
  SettingsChoice,
  SettingsDivider,
  SettingsItem,
  SettingsSectionCard,
} from '@/components/settings/SettingsKit';
import { themeSwatch, useTheme } from '@/context/ThemeContext';
import type { ThemeId, ThemeMode, TintIntensity } from '@/context/ThemeContext';
import { cache, cacheKeys } from '@/libs/services/cache';
import { getLocale, setLocale, t } from '@/libs/services/i18n';
import type { SupportedLocale } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';

const THEMES: readonly { id: ThemeId; label: string }[] = [
  { id: 'aurora', label: 'Aurora' },
  { id: 'graphite', label: 'Graphite' },
  { id: 'ember', label: 'Ember' },
  { id: 'lagoon', label: 'Lagoon' },
];

function ThemeSwatches({ value, onChange }: { readonly value: ThemeId; readonly onChange: (id: ThemeId) => void }) {
  const { theme, resolvedMode } = useTheme();
  return (
    <View style={{ flexDirection: 'row', gap: Spacing.sm }}>
      {THEMES.map((opt) => {
        const selected = opt.id === value;
        const sw = themeSwatch(opt.id, resolvedMode);
        return (
          <Pressable
            key={opt.id}
            onPress={() => {
              void hapticsBridge.fire('selection');
              onChange(opt.id);
            }}
            accessibilityRole="radio"
            accessibilityState={{ selected }}
            accessibilityLabel={opt.label}
            style={{ flex: 1, alignItems: 'center', gap: Spacing.xs }}
          >
            <View
              style={{
                width: '100%',
                aspectRatio: 1,
                borderRadius: Radius.lg,
                backgroundColor: sw.background,
                borderWidth: 2,
                borderColor: selected ? theme.accent : 'transparent',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <View style={{ width: 28, height: 28, borderRadius: 14, backgroundColor: sw.accent }} />
            </View>
            <ThemedText variant="caption" tone={selected ? 'accent' : 'secondary'}>
              {opt.label}
            </ThemedText>
          </Pressable>
        );
      })}
    </View>
  );
}

export default function AppearanceSettings() {
  const { themeId, themeMode, tintIntensity, increaseContrast, setTheme, setThemeMode, setTintIntensity, setIncreaseContrast } =
    useTheme();
  const [lang, setLang] = useState<SupportedLocale>(getLocale());

  const onLang = (code: SupportedLocale) => {
    setLocale(code);
    cache.set(cacheKeys.language, code);
    setLang(code);
  };

  const modes: readonly { value: ThemeMode; label: string }[] = [
    { value: 'auto', label: t('settings.appearance.modes.auto') },
    { value: 'light', label: t('settings.appearance.modes.light') },
    { value: 'dark', label: t('settings.appearance.modes.dark') },
  ];
  const tints: readonly { value: TintIntensity; label: string }[] = [
    { value: 'subtle', label: t('settings.appearance.tints.subtle') },
    { value: 'balanced', label: t('settings.appearance.tints.balanced') },
    { value: 'vivid', label: t('settings.appearance.tints.vivid') },
  ];

  return (
    <SettingsPage key={lang} title={t('settings.nav.appearance.title')}>
      <SettingsSectionCard title={t('settings.appearance.theme')} icon="palette">
        <View style={{ paddingHorizontal: Spacing.sm, paddingBottom: Spacing.md }}>
          <ThemeSwatches value={themeId} onChange={setTheme} />
        </View>
        <SettingsDivider inset={false} />
        <SettingsItem
          icon="moon"
          title={t('settings.appearance.mode')}
          subtitle={t('settings.appearance.modeBody')}
          below={<SettingsChoice options={modes} value={themeMode} onChange={setThemeMode} />}
        />
        <SettingsDivider />
        <SettingsItem
          icon="palette"
          title={t('settings.appearance.tint')}
          subtitle={t('settings.appearance.tintBody')}
          below={<SettingsChoice options={tints} value={tintIntensity} onChange={setTintIntensity} />}
        />
        <SettingsDivider />
        <SettingsItem
          icon="shield"
          title={t('settings.appearance.contrast')}
          subtitle={t('settings.appearance.contrastBody')}
          trailing={{ kind: 'switch', value: increaseContrast, onChange: setIncreaseContrast }}
        />
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.appearance.language')} icon="globe">
        <SettingsItem
          icon="globe"
          title={t('settings.appearance.language')}
          subtitle={t('settings.appearance.languageBody')}
          below={
            <SettingsChoice
              options={[
                { value: 'zh-Hant' as const, label: '繁體中文' },
                { value: 'en' as const, label: 'English' },
              ]}
              value={lang}
              onChange={onLang}
            />
          }
        />
      </SettingsSectionCard>
    </SettingsPage>
  );
}
