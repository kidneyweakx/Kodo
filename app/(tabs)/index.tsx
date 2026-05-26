/*
 * mi-band-9-active — Today dashboard.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Layout (top → bottom):
 *   1. Eyebrow + brand title + connection chip
 *   2. SyncStatusBar (pulses + scanning beam while syncing)
 *   3. ActivityRings (Apple-style triple ring, ghost when no sync) + RingLegend
 *   4. Bento grid 2×2: HR / Sleep / Stress / SpO₂
 *   5. PAI hero card (wide)
 *   6. Floating StickyActionDock — Sync now / GO
 *
 * Render-path contract: initial state seeds from MMKV via getSync (rule 10).
 * Empty/cold = '—' + "尚未同步", never a faked number (rule 8).
 */

import { useMemo } from 'react';
import { ScrollView, View } from 'react-native';
import Animated, { FadeInDown } from 'react-native-reanimated';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Radius, Spacing } from '@/constants/DesignSystem';
import { ThemedSurface, ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';
import { AmbientBlobs } from '@/components/dashboard/AmbientBlobs';
import { ActivityRings, RingLegend } from '@/components/dashboard/ActivityRings';
import { MetricBento } from '@/components/dashboard/MetricBento';
import { MetricCard } from '@/components/dashboard/MetricCard';
import { RecentWorkoutsCard } from '@/components/dashboard/RecentWorkoutsCard';
import { StickyActionDock } from '@/components/dashboard/StickyActionDock';
import { SyncStatusBar } from '@/components/dashboard/SyncStatusBar';
import { bandLink, useBatteryInfo, useConnectionState, usePairedBand } from '@/libs/services/bandLink';
import { useDashboardSummary, useRecentWorkouts } from '@/libs/services/healthStore';
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

const localizedDate = () => {
  const d = new Date();
  const md = d.toLocaleDateString('en-US', { month: 'short', day: '2-digit' });
  const wd = d.toLocaleDateString('en-US', { weekday: 'short' });
  return `${md.toUpperCase()} · ${wd.toUpperCase()}`;
};

export default function TodayScreen() {
  const { theme } = useTheme();
  const paired = usePairedBand();
  const battery = useBatteryInfo();
  const connectionState = useConnectionState();
  const dashboard = useDashboardSummary();
  const workouts = useRecentWorkouts(10);
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

  const bentos = useMemo(() => {
    const s = dashboard.summary;
    const state: 'ready' | 'loading' | 'unsynced' = s
      ? 'ready'
      : dashboard.state === 'cold'
        ? 'unsynced'
        : 'loading';
    type Card = {
      label: string;
      value: string | null;
      unit?: string;
      hint?: string;
      tone: 'accent' | 'success' | 'warning' | 'danger' | 'neutral';
    };
    const cards = [
      {
        label: 'Heart · 心率',
        value: s?.restingHeartRate != null ? String(s.restingHeartRate) : null,
        unit: 'bpm',
        hint: 'Resting · 靜息',
        tone: 'danger',
      },
      {
        label: 'Sleep · 睡眠',
        value:
          s?.sleepMinutes != null
            ? `${Math.floor(s.sleepMinutes / 60)}h${String(s.sleepMinutes % 60).padStart(2, '0')}`
            : null,
        hint: 'Last night · 昨夜',
        tone: 'success',
      },
      {
        label: 'Stress · 壓力',
        value: s?.stressAverage != null ? String(s.stressAverage) : null,
        unit: '/100',
        hint: 'Average · 平均',
        tone: 'warning',
      },
      {
        label: 'SpO₂ · 血氧',
        value: s?.spo2Average != null ? `${s.spo2Average}` : null,
        unit: '%',
        hint: 'Average · 平均',
        tone: 'accent',
      },
    ] as const satisfies readonly Card[];
    const pai: Card = {
      label: 'PAI Vitality · 活力指數',
      value: s?.paiScore != null ? String(s.paiScore) : null,
      unit: '/100',
      hint: '7-day rolling · 七日累計',
      tone: 'success',
    };
    return { state, cards, pai };
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

  const connectionLabel =
    connectionState === 'connected'
      ? 'LINKED · 已連線'
      : connectionState === 'connecting' || connectionState === 'authenticating'
        ? 'CONNECTING · 連線中'
        : connectionState === 'scanning'
          ? 'SCANNING · 掃描中'
          : connectionState === 'error'
            ? 'ERROR · 失敗'
            : 'OFFLINE · 未連線';

  const connectionTone =
    connectionState === 'connected'
      ? theme.success
      : connectionState === 'error'
        ? theme.danger
        : theme.text.tertiary;

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: theme.background.primary }} edges={['top']}>
      <AmbientBlobs />
      <ScrollView
        contentContainerStyle={{
          paddingHorizontal: Spacing.lg,
          paddingTop: Spacing.lg,
          paddingBottom: 220,
          gap: Spacing.xl,
        }}
        showsVerticalScrollIndicator={false}
      >
        {/* Header */}
        <Animated.View entering={FadeInDown.duration(360)}>
          <View
            style={{
              flexDirection: 'row',
              justifyContent: 'space-between',
              alignItems: 'center',
            }}
          >
            <View style={{ flex: 1, gap: 2 }}>
              <ThemedText variant="eyebrow" tone="accent">
                {localizedDate()}
              </ThemedText>
              <ThemedText variant="headlineLarge">
                {paired?.name ?? t('app.name')}
              </ThemedText>
            </View>
            <View
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                gap: 6,
                paddingVertical: 6,
                paddingHorizontal: Spacing.md,
                borderRadius: Radius.pill,
                backgroundColor: `${connectionTone}22`,
              }}
            >
              <View
                style={{
                  width: 6,
                  height: 6,
                  borderRadius: 3,
                  backgroundColor: connectionTone,
                }}
              />
              <ThemedText variant="caption" style={{ color: connectionTone, letterSpacing: 1 }}>
                {connectionLabel}
              </ThemedText>
            </View>
          </View>
        </Animated.View>

        <SyncStatusBar />

        {/* Hero rings */}
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

        {/* Section heading */}
        <View
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            gap: Spacing.sm,
            marginTop: -Spacing.md,
          }}
        >
          <ThemedText variant="eyebrow" tone="tertiary">
            VITALS · 體徵
          </ThemedText>
          <View style={{ flex: 1, height: 1, backgroundColor: theme.glassBorder, opacity: 0.5 }} />
        </View>

        {/* Bento 2×2 */}
        <View style={{ gap: Spacing.md }}>
          <View style={{ flexDirection: 'row', gap: Spacing.md }}>
            <MetricBento {...bentos.cards[0]} state={bentos.state} delay={60} />
            <MetricBento {...bentos.cards[1]} state={bentos.state} delay={120} />
          </View>
          <View style={{ flexDirection: 'row', gap: Spacing.md }}>
            <MetricBento {...bentos.cards[2]} state={bentos.state} delay={180} />
            <MetricBento {...bentos.cards[3]} state={bentos.state} delay={240} />
          </View>
        </View>

        {/* PAI hero (wide card) */}
        <MetricCard
          label={bentos.pai.label}
          value={bentos.pai.value}
          unit={bentos.pai.unit}
          hint={bentos.pai.hint}
          tone="success"
          state={bentos.state}
          samples={[]}
          delay={300}
        />

        {/* Recent workouts */}
        <RecentWorkoutsCard workouts={workouts} delay={360} />

        {/* Band status card — battery + last sync, when known */}
        {(battery || lastSync) && (
          <ThemedSurface variant="elevated" padded="lg" radius="lg">
            <View
              style={{
                flexDirection: 'row',
                alignItems: 'center',
                justifyContent: 'space-between',
              }}
            >
              <View style={{ gap: 4 }}>
                <ThemedText variant="eyebrow" tone="tertiary">
                  BAND · 手環
                </ThemedText>
                <ThemedText variant="titleLarge">
                  {paired?.name ?? 'Mi Band 9 Active'}
                </ThemedText>
                <ThemedText variant="caption" tone="tertiary">
                  {lastSync ? formatRelative(lastSync) : t('common.unsynced')}
                </ThemedText>
              </View>
              {battery && (
                <View style={{ alignItems: 'flex-end', gap: 2 }}>
                  <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: 2 }}>
                    <ThemedText variant="displayMedium" style={{ color: theme.text.primary }}>
                      {Math.round(battery.percent)}
                    </ThemedText>
                    <ThemedText variant="titleMedium" tone="secondary">
                      %
                    </ThemedText>
                  </View>
                  <ThemedText
                    variant="caption"
                    tone={battery.charging ? 'accent' : 'tertiary'}
                    style={{ letterSpacing: 1 }}
                  >
                    {battery.charging ? '⚡ CHARGING · 充電中' : 'BATTERY · 電量'}
                  </ThemedText>
                </View>
              )}
            </View>
          </ThemedSurface>
        )}
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
