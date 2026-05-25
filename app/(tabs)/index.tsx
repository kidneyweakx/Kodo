/*
 * mi-band-9-active — Today dashboard with ambient blobs, hero summary, sparkline cards.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Render-path contract: initial state seeds from MMKV via getSync (rule 10).
 * Refresh happens silently in the background. No await before frame 1.
 * Empty/cold = '—' + "尚未同步", never a faked number (rule 8).
 */

import { useMemo } from 'react';
import { ScrollView, View } from 'react-native';
import Animated, { FadeInDown } from 'react-native-reanimated';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Spacing } from '@/constants/DesignSystem';
import { ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';
import { AmbientBlobs } from '@/components/dashboard/AmbientBlobs';
import { HeroSummary } from '@/components/dashboard/HeroSummary';
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
  if (delta < 3_600_000)
    return t('common.lastSynced', { when: `${Math.floor(delta / 60_000)}m ago` });
  if (delta < 86_400_000)
    return t('common.lastSynced', { when: `${Math.floor(delta / 3_600_000)}h ago` });
  return t('common.lastSynced', { when: new Date(iso).toLocaleDateString() });
};

const connectionLabel = (state: ConnectionState): string => {
  switch (state) {
    case 'connected':
      return 'CONNECTED · 已連線';
    case 'connecting':
    case 'authenticating':
      return 'CONNECTING · 連線中';
    case 'scanning':
      return 'SCANNING · 掃描中';
    case 'error':
      return 'ERROR · 連線錯誤';
    case 'disconnected':
    default:
      return 'OFFLINE · 離線';
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

  const stepGoal = 8_000;

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: theme.background.primary }} edges={['top']}>
      <AmbientBlobs />
      <ScrollView
        contentContainerStyle={{ padding: Spacing.xl, paddingBottom: 120, gap: Spacing.lg }}
        showsVerticalScrollIndicator={false}
      >
        <Animated.View entering={FadeInDown.duration(360)}>
          <ThemedText variant="caption" tone="accent" style={{ letterSpacing: 1.4 }}>
            {t('dashboard.title').toUpperCase()} · {new Date().toLocaleDateString()}
          </ThemedText>
          <ThemedText variant="headlineLarge" style={{ marginTop: Spacing.xs }}>
            {paired?.name ?? t('app.name')}
          </ThemedText>
        </Animated.View>

        <HeroSummary
          steps={dashboard.summary?.steps ?? null}
          stepGoal={stepGoal}
          batteryPercent={null}
          charging={false}
          lastSyncedLabel={formatRelative(lastSync)}
          connectionLabel={connectionLabel(connectionState)}
        />

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
              samples={[]}
              delay={80 + i * 60}
            />
          ))}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}
