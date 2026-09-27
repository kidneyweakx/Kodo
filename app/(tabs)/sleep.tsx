/*
 * mi-band-9-active — Sleep tab.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * One night at a time (keyed by local wake-up date, like Gadgetbridge's
 * "sleep ending on day"), plus a 7-night trend that doubles as the night
 * picker. Everything is read synchronously from the native store on the
 * render path; a night the band hasn't sent is an honest empty state.
 */

import { router } from 'expo-router';
import { useMemo, useState } from 'react';
import { ScrollView, View } from 'react-native';
import Animated, { FadeInDown } from 'react-native-reanimated';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Radius, Spacing, tabularNums } from '@/constants/DesignSystem';
import { ThemedButton, ThemedIconButton, ThemedSurface, ThemedText } from '@/components/themed';
import { SettingsItem, SettingsSectionCard } from '@/components/settings/SettingsKit';
import { Hypnogram, formatMinutes, hhmm } from '@/components/sleep/Hypnogram';
import { SleepTrend, StageBreakdown } from '@/components/sleep/SleepCharts';
import { useTheme } from '@/context/ThemeContext';
import { bandLink } from '@/libs/services/bandLink';
import { localDateIso, useSleepNight, useSleepNights } from '@/libs/services/healthStore';
import { getLocale, t } from '@/libs/services/i18n';
import { useSyncStatus } from '@/libs/services/syncStatus';
import type { SleepNight } from '@/modules/native/health/health.nitro';
import Svg, { Path } from 'react-native-svg';

const addDays = (dateIso: string, n: number): string => {
  const [y, m, d] = dateIso.split('-').map(Number);
  return localDateIso(new Date(y ?? 1970, (m ?? 1) - 1, (d ?? 1) + n));
};

const dateLabel = (dateIso: string, today: string): string => {
  if (dateIso === today) return t('sleep.lastNight');
  const [y, m, d] = dateIso.split('-').map(Number);
  const locale = getLocale() === 'zh-Hant' ? 'zh-TW' : 'en-US';
  return new Date(y ?? 1970, (m ?? 1) - 1, d ?? 1).toLocaleDateString(locale, {
    month: 'short',
    day: 'numeric',
    weekday: 'short',
  });
};

function Chevron({ dir, color }: { readonly dir: 'left' | 'right'; readonly color: string }) {
  return (
    <Svg width={20} height={20} viewBox="0 0 24 24" fill="none">
      <Path
        d={dir === 'left' ? 'M14.5 6l-6 6 6 6' : 'M9.5 6l6 6-6 6'}
        stroke={color}
        strokeWidth={2}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Svg>
  );
}

function Stat({ label, value }: { readonly label: string; readonly value: string }) {
  const { theme } = useTheme();
  return (
    <View
      style={{
        flexBasis: '47%',
        flexGrow: 1,
        padding: Spacing.md,
        borderRadius: Radius.lg,
        backgroundColor: theme.background.tertiary,
        gap: 2,
      }}
    >
      <ThemedText variant="caption" tone="secondary">
        {label}
      </ThemedText>
      <ThemedText variant="titleLarge" style={tabularNums}>
        {value}
      </ThemedText>
    </View>
  );
}

function NightDetail({ night }: { readonly night: SleepNight }) {
  const vitals = [
    night.avgHeartRate != null ? { label: t('sleep.avgHr'), value: `${Math.round(night.avgHeartRate)} bpm` } : null,
    night.lowestHeartRate != null ? { label: t('sleep.lowHr'), value: `${Math.round(night.lowestHeartRate)} bpm` } : null,
    night.avgSpo2 != null ? { label: t('sleep.avgSpo2'), value: `${Math.round(night.avgSpo2)}%` } : null,
    night.lowestSpo2 != null ? { label: t('sleep.lowSpo2'), value: `${Math.round(night.lowestSpo2)}%` } : null,
    night.awakeCount != null ? { label: t('sleep.awakeCount'), value: String(night.awakeCount) } : null,
  ].filter((v): v is { label: string; value: string } => v != null);

  return (
    <>
      <ThemedSurface variant="elevated" padded="lg" radius="xl" style={{ gap: Spacing.xs }}>
        <View style={{ flexDirection: 'row', alignItems: 'flex-end', justifyContent: 'space-between' }}>
          <ThemedText variant="displayMedium" style={tabularNums}>
            {formatMinutes(night.totalMinutes)}
          </ThemedText>
          {night.score != null ? (
            <View style={{ alignItems: 'flex-end' }}>
              <ThemedText variant="micro" tone="tertiary">
                {t('sleep.score')}
              </ThemedText>
              <ThemedText variant="headlineMedium" tone="accent" style={tabularNums}>
                {night.score}
              </ThemedText>
            </View>
          ) : null}
        </View>
        <ThemedText variant="bodyMedium" tone="secondary" style={tabularNums}>
          {t('sleep.bedWake', { bed: hhmm(night.bedAt), wake: hhmm(night.wakeAt) })}
        </ThemedText>
      </ThemedSurface>

      <SettingsSectionCard title={t('sleep.stagesTitle')} icon="moon">
        <View style={{ paddingHorizontal: Spacing.sm, paddingBottom: Spacing.sm, gap: Spacing.lg }}>
          {night.segments.length > 0 ? (
            <Hypnogram segments={night.segments} bedAt={night.bedAt} wakeAt={night.wakeAt} />
          ) : (
            <ThemedText variant="caption" tone="tertiary">
              {t('sleep.summaryOnly')}
            </ThemedText>
          )}
          <StageBreakdown night={night} />
        </View>
      </SettingsSectionCard>

      {vitals.length > 0 ? (
        <SettingsSectionCard title={t('sleep.vitals')} icon="heart">
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.sm, padding: Spacing.sm, paddingTop: 0 }}>
            {vitals.map((v) => (
              <Stat key={v.label} label={v.label} value={v.value} />
            ))}
          </View>
        </SettingsSectionCard>
      ) : null}

      {night.naps.length > 0 ? (
        <SettingsSectionCard title={t('sleep.naps')} icon="clock">
          {night.naps.map((nap) => (
            <SettingsItem
              key={nap.bedAt}
              icon="moon"
              title={formatMinutes(nap.totalMinutes)}
              subtitle={t('sleep.bedWake', { bed: hhmm(nap.bedAt), wake: hhmm(nap.wakeAt) })}
            />
          ))}
        </SettingsSectionCard>
      ) : null}
    </>
  );
}

export default function SleepTab() {
  const { theme } = useTheme();
  const today = localDateIso();
  const [selected, setSelected] = useState(today);
  const night = useSleepNight(selected);
  const sync = useSyncStatus();
  const syncing = sync.phase !== 'idle' && sync.phase !== 'done' && sync.phase !== 'error';

  // The trend window ends today unless the user paged further back.
  const windowEnd = selected > addDays(today, -6) ? today : selected;
  const dates = useMemo(() => Array.from({ length: 7 }, (_, i) => addDays(windowEnd, i - 6)), [windowEnd]);
  const nights = useSleepNights(dates[0] ?? windowEnd, windowEnd);

  const onSync = () => {
    void bandLink.syncSince(new Date(Date.now() - 2 * 86_400_000).toISOString()).catch(() => undefined);
  };

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
        <Animated.View entering={FadeInDown.duration(320)} style={{ gap: Spacing.sm }}>
          <ThemedText variant="headlineLarge">{t('sleep.title')}</ThemedText>
          <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
            <ThemedIconButton
              accessibilityLabel={t('sleep.prev')}
              variant="ghost"
              onPress={() => setSelected(addDays(selected, -1))}
              icon={(c) => <Chevron dir="left" color={c} />}
            />
            <ThemedText variant="titleMedium">{dateLabel(selected, today)}</ThemedText>
            <ThemedIconButton
              accessibilityLabel={t('sleep.next')}
              variant="ghost"
              disabled={selected >= today}
              onPress={() => setSelected(addDays(selected, 1))}
              icon={(c) => <Chevron dir="right" color={c} />}
            />
          </View>
        </Animated.View>

        {night ? (
          <NightDetail night={night} />
        ) : (
          <ThemedSurface variant="outlined" padded="lg" radius="xl" style={{ gap: Spacing.md }}>
            <ThemedText variant="titleLarge">{t('sleep.emptyTitle')}</ThemedText>
            <ThemedText variant="bodyMedium" tone="secondary">
              {t('sleep.emptyBody')}
            </ThemedText>
            <ThemedButton
              variant="secondary"
              label={syncing ? t('common.syncing') : t('sleep.sync')}
              loading={syncing}
              onPress={onSync}
            />
          </ThemedSurface>
        )}

        <SettingsSectionCard title={t('sleep.trend')} icon="calendar">
          <View style={{ paddingHorizontal: Spacing.sm, paddingBottom: Spacing.sm }}>
            <SleepTrend dates={dates} nights={nights} selected={selected} onSelect={setSelected} />
          </View>
        </SettingsSectionCard>

        <SettingsSectionCard title={t('sleep.schedule')} icon="alarm">
          <SettingsItem
            icon="moon"
            title={t('sleep.schedule')}
            subtitle={t('sleep.scheduleBody')}
            trailing={{ kind: 'chevron' }}
            onPress={() => router.push('/(settings)/sleep-schedule')}
          />
        </SettingsSectionCard>
      </ScrollView>
    </SafeAreaView>
  );
}
