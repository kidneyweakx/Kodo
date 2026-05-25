/*
 * mi-band-9-active — Today hero: battery + steps + last-sync chip.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Loads from sync cache so frame 1 is never blank (CLAUDE.md rule 10).
 * Falls back to '—' + "尚未同步" when there's no real data (rule 8).
 */

import { View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import Animated, { FadeInUp } from 'react-native-reanimated';

import { Radius, Spacing, Typography, tabularNums } from '@/constants/DesignSystem';
import { ThemedSurface, ThemedText, readableTextOn } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';

export interface HeroSummaryProps {
  readonly steps: number | null;
  readonly stepGoal: number;
  readonly batteryPercent: number | null;
  readonly charging: boolean;
  readonly lastSyncedLabel: string;
  readonly connectionLabel: string;
}

export function HeroSummary({
  steps,
  stepGoal,
  batteryPercent,
  charging,
  lastSyncedLabel,
  connectionLabel,
}: HeroSummaryProps) {
  const { theme } = useTheme();

  const progress = steps != null && stepGoal > 0 ? Math.min(1, steps / stepGoal) : 0;
  const onAccent = readableTextOn(theme.accent);

  return (
    <Animated.View entering={FadeInUp.duration(420).springify().damping(20)}>
      <ThemedSurface variant="elevated" padded={false} style={{ overflow: 'hidden' }}>
        <LinearGradient
          colors={[theme.accent, theme.accentStrong]}
          start={{ x: 0, y: 0 }}
          end={{ x: 1, y: 1 }}
          style={{ paddingHorizontal: Spacing.xl, paddingVertical: Spacing.xl, gap: Spacing.lg }}
        >
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
            <View>
              <ThemedText
                variant="caption"
                style={{ color: onAccent, opacity: 0.72, letterSpacing: 1 }}
              >
                STEPS · 步數
              </ThemedText>
            </View>
            <View
              style={{
                paddingVertical: 4,
                paddingHorizontal: Spacing.md,
                borderRadius: Radius.pill,
                backgroundColor: 'rgba(0,0,0,0.18)',
              }}
            >
              <ThemedText variant="caption" style={{ color: onAccent }}>
                {connectionLabel}
              </ThemedText>
            </View>
          </View>

          <View style={{ flexDirection: 'row', alignItems: 'flex-end', gap: Spacing.md }}>
            <ThemedText
              style={{ ...Typography.hero, color: onAccent, ...tabularNums }}
            >
              {steps != null ? steps.toLocaleString() : '—'}
            </ThemedText>
            <ThemedText
              variant="titleMedium"
              style={{ color: onAccent, opacity: 0.7, marginBottom: 10 }}
            >
              / {stepGoal.toLocaleString()}
            </ThemedText>
          </View>

          <View style={{ height: 6, borderRadius: 999, backgroundColor: 'rgba(0,0,0,0.18)', overflow: 'hidden' }}>
            <View
              style={{
                height: '100%',
                width: `${progress * 100}%`,
                backgroundColor: onAccent,
                borderRadius: 999,
              }}
            />
          </View>

          <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
            <ThemedText variant="caption" style={{ color: onAccent, opacity: 0.7 }}>
              {lastSyncedLabel}
            </ThemedText>
            <ThemedText
              variant="caption"
              style={{ color: onAccent, opacity: 0.85, ...tabularNums }}
            >
              {batteryPercent != null
                ? `${batteryPercent}%${charging ? ' ⏚' : ''} · 手環電量 / Band`
                : '— · 尚未同步 / Not synced'}
            </ThemedText>
          </View>
        </LinearGradient>
      </ThemedSurface>
    </Animated.View>
  );
}
