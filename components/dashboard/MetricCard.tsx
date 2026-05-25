/*
 * mi-band-9-active — generic dashboard metric card.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Real states only: ready / loading / unsynced. No fake placeholder numbers.
 * See CLAUDE.md rule 8.
 */

import { View } from 'react-native';
import Animated, { FadeInUp } from 'react-native-reanimated';

import { Radius, Spacing } from '@/constants/DesignSystem';
import { ThemedSurface, ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';

type MetricState = 'ready' | 'loading' | 'unsynced';

export interface MetricCardProps {
  readonly label: string;
  readonly value: string | null;
  readonly unit?: string;
  readonly hint?: string;
  readonly tone?: 'accent' | 'success' | 'warning' | 'danger' | 'neutral';
  readonly state: MetricState;
  readonly delay?: number;
}

export function MetricCard({ label, value, unit, hint, tone = 'neutral', state, delay = 0 }: MetricCardProps) {
  const { theme } = useTheme();

  const accentBar =
    tone === 'success' ? theme.success
      : tone === 'warning' ? theme.warning
        : tone === 'danger' ? theme.danger
          : tone === 'accent' ? theme.accent
            : theme.glassBorder;

  return (
    <Animated.View entering={FadeInUp.delay(delay).duration(360).springify().damping(18)}>
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
        <ThemedText variant="caption" tone="tertiary">
          {label.toUpperCase()}
        </ThemedText>

        <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: Spacing.xs, marginTop: Spacing.xs }}>
          {state === 'ready' ? (
            <>
              <ThemedText variant="displayMedium">{value ?? '—'}</ThemedText>
              {unit ? (
                <ThemedText variant="titleMedium" tone="secondary">
                  {unit}
                </ThemedText>
              ) : null}
            </>
          ) : state === 'loading' ? (
            <ThemedText variant="displayMedium" tone="tertiary">
              …
            </ThemedText>
          ) : (
            <ThemedText variant="displayMedium" tone="tertiary">
              —
            </ThemedText>
          )}
        </View>

        {hint ? (
          <ThemedText variant="caption" tone="secondary" style={{ marginTop: Spacing.xs }}>
            {hint}
          </ThemedText>
        ) : null}
      </ThemedSurface>
    </Animated.View>
  );
}
