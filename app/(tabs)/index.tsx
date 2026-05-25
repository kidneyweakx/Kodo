/*
 * mi-band-9-active — Today dashboard.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Layout (top → bottom):
 *   1. Header eyebrow + band name
 *   2. SyncStatusBar (pulses + scanning beam while syncing)
 *   3. ActivityRings (Apple/Garmin-style triple ring, no value = ghost rings)
 *   4. RingLegend
 *   5. MetricCard list (HR / Sleep / Stress / SpO₂ / PAI)
 *   6. Floating StickyActionDock at the bottom — Sync now / GO
 *
 * Render-path contract: initial state seeds from MMKV via getSync (rule 10).
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
import { ActivityRings, RingLegend } from '@/components/dashboard/ActivityRings';
import { MetricCard } from '@/components/dashboard/MetricCard';
import { StickyActionDock } from '@/components/dashboard/StickyActionDock';
import { SyncStatusBar } from '@/components/dashboard/SyncStatusBar';
import { bandLink, usePairedBand } from '@/libs/services/bandLink';
import { useDashboardSummary } from '@/libs/services/healthStore';
import { useSyncStatus } from '@/libs/services/syncStatus';
import { t } from '@/libs/services/i18n';
import { cache, cacheKeys } from '@/libs/services/cache';

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

const STEP_GOAL = 8_000;
const SLEEP_GOAL_MINUTES = 8 * 60;
const VITALITY_GOAL = 100;

export default function TodayScreen() {
  const { theme } = useTheme();
  const paired = usePairedBand();
  const dashboard = useDashboardSummary();
  const syncStatus = useSyncStatus();
  const lastSync = cache.getSync<string>(cacheKeys.lastSyncAt);

  const isSynced = dashboard.summary != null;

  const ringValues = useMemo(() => {
    const s = dashboard.summary;
    return {
      steps: s?.steps != null
        ? { label: '步數', value: s.steps, goal: STEP_GOAL, unit: 'steps' }
        : null,
      sleep: s?.sleepMinutes != null
        ? { label: '睡眠', value: s.sleepMinutes, goal: SLEEP_GOAL_MINUTES, unit: 'min' }
        : null,
      vitality: s?.paiScore != null
        ? { label: '活力', value: s.paiScore, goal: VITALITY_GOAL, unit: 'PAI' }
        : null,
    };
  }, [dashboard.summary]);

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

  const onSync = async () => {
    try {
      const since = new Date(Date.now() - 86_400_000).toISOString();
      await bandLink.syncSince(since);
    } catch {
      /* native not loaded in dev */
    }
  };
  const onOpenWorkout = () => {
    // Placeholder; a future Workout route can subscribe to HybridGpsTracker.
  };

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: theme.background.primary }} edges={['top']}>
      <AmbientBlobs />
      <ScrollView
        contentContainerStyle={{ padding: Spacing.xl, paddingBottom: 200, gap: Spacing.xl }}
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

        <SyncStatusBar />

        <View style={{ alignItems: 'center' }}>
          <ActivityRings
            steps={ringValues.steps}
            sleep={ringValues.sleep}
            vitality={ringValues.vitality}
            synced={isSynced}
          />
          <RingLegend
            steps={ringValues.steps}
            sleep={ringValues.sleep}
            vitality={ringValues.vitality}
          />
        </View>

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

      <StickyActionDock
        syncing={syncStatus.phase !== 'idle' && syncStatus.phase !== 'error'}
        progress={syncStatus.progress}
        lastSyncLabel={formatRelative(lastSync)}
        onSync={onSync}
        onOpenWorkout={onOpenWorkout}
      />
    </SafeAreaView>
  );
}
