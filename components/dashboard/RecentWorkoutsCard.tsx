/*
 * mi-band-9-active — recent workouts list card on the dashboard.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Lists the most recent workouts pulled from the band via HybridHealthStore.
 * Honest empty state — no fake samples (CLAUDE.md rule 8).
 */

import { useMemo } from 'react';
import { View } from 'react-native';
import Animated, { FadeInUp } from 'react-native-reanimated';

import { Radius, Spacing, tabularNums } from '@/constants/DesignSystem';
import { ThemedSurface, ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';
import type { WorkoutKind, WorkoutSummary } from '@/modules/native';

const KIND_LABEL: Record<WorkoutKind, string> = {
  running: 'Run · 跑步',
  walking: 'Walk · 步行',
  treadmill: 'Treadmill · 跑步機',
  outdoor_cycling: 'Cycling · 戶外騎行',
  indoor_cycling: 'Indoor Bike · 室內單車',
  freestyle: 'Freestyle · 自由訓練',
  pool_swimming: 'Pool Swim · 游泳',
  hiit: 'HIIT · 高強度間歇',
  elliptical: 'Elliptical · 橢圓機',
  rowing: 'Rowing · 划船',
  jump_rope: 'Jump Rope · 跳繩',
  other: 'Workout · 運動',
};

const KIND_GLYPH: Record<WorkoutKind, string> = {
  running: '▶',
  walking: '◇',
  treadmill: '▤',
  outdoor_cycling: '◯',
  indoor_cycling: '◉',
  freestyle: '✦',
  pool_swimming: '≈',
  hiit: '✧',
  elliptical: '◐',
  rowing: '═',
  jump_rope: '◌',
  other: '·',
};

function formatDuration(seconds: number): string {
  if (seconds <= 0) return '—';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (h > 0) return `${h}h${String(m).padStart(2, '0')}`;
  return `${m}m`;
}

function formatDistance(meters: number | null): string | null {
  if (meters == null || meters <= 0) return null;
  if (meters < 1000) return `${Math.round(meters)} m`;
  return `${(meters / 1000).toFixed(2)} km`;
}

function formatWhen(iso: string): string {
  const d = new Date(iso);
  const today = new Date();
  const sameDay = d.toDateString() === today.toDateString();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  const isYesterday = d.toDateString() === yesterday.toDateString();
  const time = d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false });
  if (sameDay) return `今日 ${time}`;
  if (isYesterday) return `昨日 ${time}`;
  return d.toLocaleDateString('en-US', { month: 'short', day: '2-digit' }).toUpperCase();
}

export interface RecentWorkoutsCardProps {
  readonly workouts: readonly WorkoutSummary[];
  readonly delay?: number;
}

export function RecentWorkoutsCard({ workouts, delay = 0 }: RecentWorkoutsCardProps) {
  const { theme } = useTheme();

  const items = useMemo(() => workouts.slice(0, 5), [workouts]);

  if (items.length === 0) {
    return (
      <Animated.View entering={FadeInUp.delay(delay).duration(360)}>
        <ThemedSurface variant="outlined" padded="lg" radius="lg">
          <ThemedText variant="eyebrow" tone="tertiary" style={{ marginBottom: Spacing.xs }}>
            WORKOUTS · 運動
          </ThemedText>
          <ThemedText variant="bodyMedium" tone="secondary">
            尚未紀錄任何運動 · No workouts yet
          </ThemedText>
          <ThemedText variant="caption" tone="tertiary" style={{ marginTop: 4 }}>
            手環上開始一次運動,結束後同步即可顯示
          </ThemedText>
        </ThemedSurface>
      </Animated.View>
    );
  }

  return (
    <Animated.View entering={FadeInUp.delay(delay).duration(360)}>
      <ThemedSurface variant="elevated" padded={false} radius="lg">
        <View style={{ paddingHorizontal: Spacing.lg, paddingTop: Spacing.lg, paddingBottom: Spacing.sm }}>
          <ThemedText variant="eyebrow" tone="tertiary">
            WORKOUTS · 運動 · {items.length}
          </ThemedText>
        </View>
        <View style={{ gap: 0 }}>
          {items.map((w, i) => {
            const isLast = i === items.length - 1;
            const distance = formatDistance(w.distanceMeters);
            const kcal = w.kcal != null && w.kcal > 0 ? `${Math.round(w.kcal)} kcal` : null;
            return (
              <View
                key={w.id}
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  paddingHorizontal: Spacing.lg,
                  paddingVertical: Spacing.md,
                  borderBottomWidth: isLast ? 0 : 1,
                  borderBottomColor: theme.glassBorder,
                  gap: Spacing.md,
                }}
              >
                <View
                  style={{
                    width: 36,
                    height: 36,
                    borderRadius: Radius.md,
                    backgroundColor: `${theme.accent}22`,
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  <ThemedText
                    variant="titleLarge"
                    style={{ color: theme.accent }}
                  >
                    {KIND_GLYPH[w.kind]}
                  </ThemedText>
                </View>
                <View style={{ flex: 1, gap: 2 }}>
                  <ThemedText variant="titleMedium">
                    {KIND_LABEL[w.kind]}
                  </ThemedText>
                  <ThemedText variant="caption" tone="tertiary" style={{ letterSpacing: 0.6 }}>
                    {formatWhen(w.startedAt)}
                  </ThemedText>
                </View>
                <View style={{ alignItems: 'flex-end', gap: 2 }}>
                  <ThemedText variant="titleMedium" style={tabularNums}>
                    {formatDuration(w.durationSeconds)}
                  </ThemedText>
                  <ThemedText variant="caption" tone="tertiary" style={tabularNums}>
                    {[distance, kcal].filter(Boolean).join(' · ') || '—'}
                  </ThemedText>
                </View>
              </View>
            );
          })}
        </View>
      </ThemedSurface>
    </Animated.View>
  );
}
