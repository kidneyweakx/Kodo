/*
 * mi-band-9-active — generic dashboard metric card with hero numeral + sparkline.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Honest states only: ready / loading / unsynced. No faked numbers or curves.
 * CLAUDE.md rule 8.
 */

import { View } from 'react-native';
import Animated, { FadeInUp } from 'react-native-reanimated';

import { Radius, Spacing, Typography, tabularNums } from '@/constants/DesignSystem';
import { ThemedSurface, ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';
import { Sparkline } from '@/components/dashboard/Sparkline';

type MetricState = 'ready' | 'loading' | 'unsynced';
type MetricTone = 'accent' | 'success' | 'warning' | 'danger' | 'neutral';

export interface MetricCardProps {
  readonly label: string;
  readonly value: string | null;
  readonly unit?: string;
  readonly hint?: string;
  readonly tone?: MetricTone;
  readonly state: MetricState;
  readonly samples?: readonly number[];
  readonly delay?: number;
  readonly compact?: boolean;
}

export function MetricCard({
  label,
  value,
  unit,
  hint,
  tone = 'neutral',
  state,
  samples = [],
  delay = 0,
  compact = false,
}: MetricCardProps) {
  const { theme } = useTheme();

  const accentBar =
    tone === 'success' ? theme.success
      : tone === 'warning' ? theme.warning
        : tone === 'danger' ? theme.danger
          : tone === 'accent' ? theme.accent
            : theme.glassBorder;

  return (
    <Animated.View entering={FadeInUp.delay(delay).duration(360).springify().damping(20)}>
      <ThemedSurface variant="card" padded="lg" style={{ overflow: 'hidden' }}>
        <View
          style={{
            position: 'absolute',
            left: 0,
            top: 0,
            bottom: 0,
            width: 3,
            backgroundColor: accentBar,
            borderTopLeftRadius: Radius.lg,
            borderBottomLeftRadius: Radius.lg,
          }}
        />

        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start' }}>
          <View style={{ flex: 1 }}>
            <ThemedText variant="caption" tone="tertiary">
              {label.toUpperCase()}
            </ThemedText>

            <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: Spacing.xs, marginTop: Spacing.xs }}>
              {state === 'ready' ? (
                <>
                  <ThemedText
                    style={{
                      ...(compact ? Typography.headlineLarge : Typography.displayLarge),
                      color: theme.text.primary,
                      ...tabularNums,
                    }}
                  >
                    {value ?? '—'}
                  </ThemedText>
                  {unit ? (
                    <ThemedText variant="titleMedium" tone="secondary">
                      {unit}
                    </ThemedText>
                  ) : null}
                </>
              ) : state === 'loading' ? (
                <ThemedText
                  style={{ ...(compact ? Typography.headlineLarge : Typography.displayLarge), color: theme.text.tertiary }}
                >
                  …
                </ThemedText>
              ) : (
                <ThemedText
                  style={{ ...(compact ? Typography.headlineLarge : Typography.displayLarge), color: theme.text.tertiary }}
                >
                  —
                </ThemedText>
              )}
            </View>

            {hint ? (
              <ThemedText variant="caption" tone="secondary" style={{ marginTop: Spacing.xs }}>
                {hint}
              </ThemedText>
            ) : null}

            {state !== 'ready' ? (
              <ThemedText variant="caption" tone="tertiary" style={{ marginTop: Spacing.xs }}>
                尚未同步 / Not synced yet
              </ThemedText>
            ) : null}
          </View>

          {!compact ? (
            <View style={{ marginLeft: Spacing.lg, marginTop: Spacing.sm }}>
              <Sparkline samples={state === 'ready' ? samples : []} width={120} height={42} />
            </View>
          ) : null}
        </View>
      </ThemedSurface>
    </Animated.View>
  );
}
