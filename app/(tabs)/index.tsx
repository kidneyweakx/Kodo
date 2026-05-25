/*
 * mi-band-9-active — Today dashboard.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Render-path contract: initial state seeds from MMKV via getSync (CLAUDE.md
 * rule 10). Refresh happens silently in the background. We never await before
 * frame 1. Fake data is forbidden — when there is no sample, render '—' and a
 * "尚未同步" hint, not a fabricated number (CLAUDE.md rule 8).
 */

import { useMemo } from 'react';
import { ScrollView, View } from 'react-native';
import Animated, { FadeInDown } from 'react-native-reanimated';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Spacing } from '@/constants/DesignSystem';
import { ThemedSurface, ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';
import { MetricCard } from '@/components/dashboard/MetricCard';
import { useConnectionState, usePairedBand } from '@/libs/services/bandLink';
import { useDashboardSummary } from '@/libs/services/healthStore';
import { t } from '@/libs/services/i18n';
import { cache, cacheKeys } from '@/libs/services/cache';
import type { ConnectionState } from '@/modules/native';

const formatRelative = (iso: string | null): string => {
  if (!iso) return t('common.unsynced');
  const delta = Date.now() - new Date(iso).getTime();
  if (delta < 60_000) return t('common.lastSynced', { when: '剛剛 / just now' });
  if (delta < 3_600_000) return t('common.lastSynced', { when: `${Math.floor(delta / 60_000)} 分鐘前 / min ago` });
  if (delta < 86_400_000) return t('common.lastSynced', { when: `${Math.floor(delta / 3_600_000)} 小時前 / h ago` });
  return t('common.lastSynced', { when: new Date(iso).toLocaleDateString() });
};

const connectionLabel = (state: ConnectionState): string => {
  switch (state) {
    case 'connected':
      return '已連線 / Connected';
    case 'connecting':
    case 'authenticating':
      return '連線中 / Connecting…';
    case 'scanning':
      return '掃描中 / Scanning…';
    case 'error':
      return '無法連線 / Connection error';
    case 'disconnected':
    default:
      return '已離線 / Offline';
  }
};

export default function TodayScreen() {
  const { theme } = useTheme();
  const paired = usePairedBand();
  const connectionState = useConnectionState();
  const dashboard = useDashboardSummary();
  const lastSync = cache.getSync<string>(cacheKeys.lastSyncAt);

  const cards = useMemo(() => {
    const s = dashboard.summary;
    const state: 'ready' | 'loading' | 'unsynced' = s
      ? 'ready'
      : dashboard.state === 'cold'
        ? 'unsynced'
        : 'loading';
    return [
      {
        label: t('dashboard.steps'),
        value: s?.steps != null ? s.steps.toLocaleString() : null,
        unit: 'steps',
        tone: 'accent' as const,
        state,
      },
      {
        label: t('dashboard.heartRate'),
        value: s?.restingHeartRate != null ? String(s.restingHeartRate) : null,
        unit: 'bpm',
        hint: 'Resting',
        tone: 'danger' as const,
        state,
      },
      {
        label: t('dashboard.sleep'),
        value:
          s?.sleepMinutes != null
            ? `${Math.floor(s.sleepMinutes / 60)}h ${s.sleepMinutes % 60}m`
            : null,
        tone: 'success' as const,
        state,
      },
      {
        label: t('dashboard.stress'),
        value: s?.stressAverage != null ? String(s.stressAverage) : null,
        unit: '/100',
        tone: 'warning' as const,
        state,
      },
      {
        label: t('dashboard.spo2'),
        value: s?.spo2Average != null ? `${s.spo2Average}` : null,
        unit: '%',
        tone: 'accent' as const,
        state,
      },
      {
        label: t('dashboard.pai'),
        value: s?.paiScore != null ? String(s.paiScore) : null,
        tone: 'success' as const,
        state,
      },
    ];
  }, [dashboard]);

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: theme.background.primary }} edges={['top']}>
      <ScrollView contentContainerStyle={{ padding: Spacing.xl, paddingBottom: Spacing.xxxl, gap: Spacing.lg }}>
        <Animated.View entering={FadeInDown.duration(360)}>
          <ThemedText variant="caption" tone="accent">
            {t('dashboard.title').toUpperCase()}
          </ThemedText>
          <ThemedText variant="headlineLarge" style={{ marginTop: Spacing.xs }}>
            {paired?.name ?? t('app.name')}
          </ThemedText>
          <ThemedText variant="bodyMedium" tone="secondary" style={{ marginTop: Spacing.xs }}>
            {connectionLabel(connectionState)} · {formatRelative(lastSync)}
          </ThemedText>
        </Animated.View>

        <ThemedSurface variant="card" padded="lg" style={{ flexDirection: 'row', alignItems: 'center', gap: Spacing.lg }}>
          <View
            style={{
              width: 56,
              height: 56,
              borderRadius: 28,
              backgroundColor: theme.accentSoft,
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <ThemedText variant="titleLarge" tone="accent">
              {/* show the percent if we have it */}
              {/* MetricCard handles ‘ready/unsynced’ separately — here we only show what we know */}
            </ThemedText>
          </View>
          <View style={{ flex: 1 }}>
            <ThemedText variant="titleMedium">{t('dashboard.battery')}</ThemedText>
            <ThemedText variant="bodyMedium" tone="secondary">
              {paired ? '充電到 80% 後拔掉可延長電池壽命' : t('common.unsynced')}
            </ThemedText>
          </View>
        </ThemedSurface>

        <View style={{ gap: Spacing.md }}>
          {cards.map((c, i) => (
            <MetricCard
              key={c.label}
              label={c.label}
              value={c.value}
              unit={c.unit}
              hint={c.hint}
              tone={c.tone}
              state={c.state}
              delay={80 + i * 60}
            />
          ))}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}
