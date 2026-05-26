/*
 * mi-band-9-active — compact bento tile for the dashboard 2×2 grid.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Honest states only (ready / loading / unsynced) — CLAUDE.md rule 8.
 * Designed to live in a 2-col grid alongside other tiles; the larger,
 * full-width hero variant is still in MetricCard.tsx.
 */

import { View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import Animated, { FadeInUp } from 'react-native-reanimated';

import { Spacing, Typography, tabularNums } from '@/constants/DesignSystem';
import { ThemedSurface, ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';

type MetricState = 'ready' | 'loading' | 'unsynced';
type MetricTone = 'accent' | 'success' | 'warning' | 'danger' | 'neutral';

export interface MetricBentoProps {
  readonly label: string;
  readonly value: string | null;
  readonly unit?: string;
  readonly hint?: string;
  readonly tone?: MetricTone;
  readonly state: MetricState;
  readonly delay?: number;
}

const toneColor = (theme: ReturnType<typeof useTheme>['theme'], tone: MetricTone) => {
  switch (tone) {
    case 'success': return theme.success;
    case 'warning': return theme.warning;
    case 'danger': return theme.danger;
    case 'accent': return theme.accent;
    default: return theme.text.secondary;
  }
};

export function MetricBento({
  label,
  value,
  unit,
  hint,
  tone = 'neutral',
  state,
  delay = 0,
}: MetricBentoProps) {
  const { theme } = useTheme();
  const accent = toneColor(theme, tone);

  return (
    <Animated.View entering={FadeInUp.delay(delay).duration(360).springify().damping(20)} style={{ flex: 1 }}>
      <ThemedSurface variant="elevated" padded={false} radius="lg" style={{ overflow: 'hidden' }}>
        {/* Soft tone wash — keeps the card alive even when value is empty. */}
        <LinearGradient
          pointerEvents="none"
          colors={[`${accent}22`, 'transparent']}
          start={{ x: 0, y: 0 }}
          end={{ x: 1, y: 1 }}
          style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 }}
        />
        <View style={{ padding: Spacing.lg, gap: Spacing.sm }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
            <View
              style={{
                width: 6,
                height: 6,
                borderRadius: 3,
                backgroundColor: accent,
              }}
            />
            <ThemedText variant="eyebrow" tone="tertiary">
              {label.toUpperCase()}
            </ThemedText>
          </View>

          <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: 4 }}>
            <ThemedText
              style={{
                ...Typography.displayMedium,
                ...tabularNums,
                color: state === 'ready' ? theme.text.primary : theme.text.tertiary,
              }}
            >
              {state === 'ready' ? (value ?? '—') : state === 'loading' ? '…' : '—'}
            </ThemedText>
            {unit && state === 'ready' ? (
              <ThemedText variant="titleMedium" tone="secondary">
                {unit}
              </ThemedText>
            ) : null}
          </View>

          {hint ? (
            <ThemedText variant="caption" tone="secondary">
              {hint}
            </ThemedText>
          ) : state !== 'ready' ? (
            <ThemedText variant="caption" tone="tertiary">
              尚未同步
            </ThemedText>
          ) : (
            <View style={{ height: Spacing.md }} />
          )}
        </View>
      </ThemedSurface>
    </Animated.View>
  );
}
