/*
 * mi-band-9-active — Settings hub.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Band hero on top, then N-Zik-style section cards of category entries that
 * push into app/(settings)/*. Nothing here awaits before first paint: the
 * hero reads the paired band + last sync from MMKV and the battery from the
 * native snapshot.
 */

import { router, useFocusEffect } from 'expo-router';
import type { Href } from 'expo-router';
import { useCallback, useState } from 'react';
import { Alert, ScrollView, View } from 'react-native';
import Animated, { FadeInDown } from 'react-native-reanimated';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Radius, Spacing, tabularNums } from '@/constants/DesignSystem';
import { ThemedButton, ThemedText } from '@/components/themed';
import { IconTile, SettingsDivider, SettingsItem, SettingsSectionCard } from '@/components/settings/SettingsKit';
import type { SettingsGlyphName } from '@/components/settings/SettingsGlyph';
import { useTheme } from '@/context/ThemeContext';
import { bandLink, useBatteryInfo, useConnectionState, usePairedBand } from '@/libs/services/bandLink';
import { cache, cacheKeys } from '@/libs/services/cache';
import { t } from '@/libs/services/i18n';
import { useSyncStatus } from '@/libs/services/syncStatus';
import type { ConnectionState } from '@/modules/native';

const relative = (iso: string | null): string => {
  if (!iso) return t('settings.hero.never');
  const delta = Date.now() - new Date(iso).getTime();
  if (delta < 60_000) return '< 1m';
  if (delta < 3_600_000) return `${Math.floor(delta / 60_000)}m`;
  if (delta < 86_400_000) return `${Math.floor(delta / 3_600_000)}h`;
  return new Date(iso).toLocaleDateString();
};

const stateLabel = (s: ConnectionState): string =>
  s === 'connected' ? t('settings.state.connected')
    : s === 'connecting' || s === 'authenticating' ? t('settings.state.connecting')
      : s === 'scanning' ? t('settings.state.scanning')
        : s === 'error' ? t('settings.state.error')
          : t('settings.state.disconnected');

function BandHero() {
  const { theme } = useTheme();
  const band = usePairedBand();
  const battery = useBatteryInfo();
  const connection = useConnectionState();
  const sync = useSyncStatus();
  const busy = sync.phase !== 'idle' && sync.phase !== 'done' && sync.phase !== 'error';

  const dot =
    connection === 'connected' ? theme.success : connection === 'error' ? theme.danger : theme.text.tertiary;

  if (!band) {
    return (
      <View style={{ borderRadius: Radius.xl, backgroundColor: theme.background.secondary, padding: Spacing.lg, gap: Spacing.md }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: Spacing.md }}>
          <IconTile name="band" tone="muted" />
          <ThemedText variant="titleLarge">{t('settings.hero.notPaired')}</ThemedText>
        </View>
        <ThemedButton label={t('settings.hero.pair')} fullWidth onPress={() => router.push('/(onboarding)/connect')} />
      </View>
    );
  }

  const onSync = () => {
    void bandLink
      .syncSince(new Date(Date.now() - 86_400_000).toISOString())
      .catch((e: unknown) => Alert.alert(t('common.syncFailed'), e instanceof Error ? e.message : String(e)));
  };

  return (
    <View
      style={{
        borderRadius: Radius.xl,
        backgroundColor: theme.background.secondary,
        borderWidth: 1,
        borderColor: theme.border,
        padding: Spacing.lg,
        gap: Spacing.lg,
      }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'flex-start', gap: Spacing.md }}>
        <View style={{ flex: 1, gap: Spacing.xs }}>
          <ThemedText variant="titleLarge" numberOfLines={1}>
            {band.name}
          </ThemedText>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            <View style={{ width: 7, height: 7, borderRadius: 4, backgroundColor: dot }} />
            <ThemedText variant="caption" style={{ color: dot }}>
              {stateLabel(connection)}
            </ThemedText>
          </View>
          <ThemedText variant="caption" tone="tertiary" style={tabularNums}>
            {t('settings.hero.lastSync', { when: relative(sync.lastSyncedAt ?? cache.getSync<string>(cacheKeys.lastSyncAt)) })}
          </ThemedText>
        </View>
        <View style={{ alignItems: 'flex-end' }}>
          <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: 2 }}>
            <ThemedText variant="displayMedium" style={tabularNums}>
              {battery ? Math.round(battery.percent) : '—'}
            </ThemedText>
            {battery ? (
              <ThemedText variant="titleMedium" tone="secondary">
                %
              </ThemedText>
            ) : null}
          </View>
          <ThemedText variant="micro" tone={battery?.charging ? 'accent' : 'tertiary'}>
            {battery?.charging ? t('settings.hero.charging') : t('settings.hero.battery')}
          </ThemedText>
        </View>
      </View>
      <ThemedButton
        variant="secondary"
        label={busy ? t('settings.hero.syncing') : t('settings.hero.syncNow')}
        loading={busy}
        fullWidth
        onPress={onSync}
      />
    </View>
  );
}

interface NavEntry {
  readonly icon: SettingsGlyphName;
  readonly key: string;
  readonly href: Href;
}

const BAND_ENTRIES: readonly NavEntry[] = [
  { icon: 'band', key: 'device', href: '/(settings)/device' },
  { icon: 'heart', key: 'health', href: '/(settings)/health' },
  { icon: 'alarm', key: 'alarms', href: '/(settings)/alarms' },
  { icon: 'bell', key: 'notifications', href: '/(tabs)/notifications' },
  { icon: 'cloud', key: 'weather', href: '/(settings)/weather' },
  { icon: 'watchface', key: 'watchface', href: '/(settings)/watchface' },
];

const APP_ENTRIES: readonly NavEntry[] = [
  { icon: 'sync', key: 'sync', href: '/(settings)/sync' },
  { icon: 'palette', key: 'appearance', href: '/(settings)/appearance' },
  { icon: 'info', key: 'about', href: '/(settings)/about' },
];

function EntryList({ entries, disabled }: { readonly entries: readonly NavEntry[]; readonly disabled?: boolean }) {
  return (
    <>
      {entries.map((e, i) => (
        <View key={e.key}>
          {i > 0 ? <SettingsDivider /> : null}
          <SettingsItem
            icon={e.icon}
            title={t(`settings.nav.${e.key}.title`)}
            subtitle={t(`settings.nav.${e.key}.body`)}
            trailing={{ kind: 'chevron' }}
            disabled={disabled}
            onPress={() => router.push(e.href)}
          />
        </View>
      ))}
    </>
  );
}

export default function SettingsTab() {
  const { theme } = useTheme();
  const band = usePairedBand();
  // Tabs stay mounted; re-render on focus so a language change made in
  // Appearance shows up here immediately.
  const [, setTick] = useState(0);
  useFocusEffect(useCallback(() => setTick((n) => n + 1), []));

  const confirm = (title: string, body: string, onYes: () => void) =>
    Alert.alert(title, body, [
      { text: t('settings.danger.cancel'), style: 'cancel' },
      { text: title, style: 'destructive', onPress: onYes },
    ]);

  const onUnpair = () =>
    confirm(t('settings.danger.unpair'), t('settings.danger.unpairBody'), async () => {
      await bandLink.forget();
      // Keep theme + language; drop everything tied to this band.
      [cacheKeys.pairedBand, cacheKeys.onboardingDone, cacheKeys.lastSyncAt].forEach(cache.remove);
      router.replace('/(onboarding)/welcome');
    });

  const onErase = () =>
    confirm(t('settings.danger.clear'), t('settings.danger.clearBody'), async () => {
      await bandLink.forget();
      cache.clear();
      router.replace('/(onboarding)/welcome');
    });

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: theme.background.primary }} edges={['top']}>
      <ScrollView
        contentContainerStyle={{
          paddingHorizontal: Spacing.lg,
          paddingTop: Spacing.lg,
          paddingBottom: Spacing.xxxl + Spacing.xxl,
          gap: Spacing.lg,
        }}
        showsVerticalScrollIndicator={false}
      >
        <Animated.View entering={FadeInDown.duration(320)}>
          <ThemedText variant="headlineLarge">{t('settings.title')}</ThemedText>
        </Animated.View>

        <BandHero />

        <SettingsSectionCard title={t('settings.hub.band')} icon="band">
          <EntryList entries={BAND_ENTRIES} disabled={!band} />
        </SettingsSectionCard>

        <SettingsSectionCard title={t('settings.hub.app')} icon="shield">
          <EntryList entries={APP_ENTRIES} />
        </SettingsSectionCard>

        {band ? (
          <SettingsSectionCard title={t('settings.danger.title')} icon="trash" tone="danger">
            <SettingsItem
              icon="unlink"
              tone="danger"
              title={t('settings.danger.unpair')}
              subtitle={t('settings.danger.unpairBody')}
              onPress={onUnpair}
            />
            <SettingsDivider />
            <SettingsItem
              icon="trash"
              tone="danger"
              title={t('settings.danger.clear')}
              subtitle={t('settings.danger.clearBody')}
              onPress={onErase}
            />
          </SettingsSectionCard>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}
