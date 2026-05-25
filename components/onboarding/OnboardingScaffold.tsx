/*
 * mi-band-9-active — onboarding shell: gradient header, progress dots, content slot, CTA row.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Sized so a single hero button never grows in height (CLAUDE.md rule 1).
 */

import type { ReactNode } from 'react';
import { View } from 'react-native';
import Animated, { FadeInDown, FadeInUp } from 'react-native-reanimated';
import { SafeAreaView } from 'react-native-safe-area-context';
import { LinearGradient } from 'expo-linear-gradient';

import { Radius, Spacing } from '@/constants/DesignSystem';
import { useTheme } from '@/context/ThemeContext';
import { ThemedText } from '@/components/themed';

interface OnboardingScaffoldProps {
  readonly stepIndex: number;
  readonly totalSteps: number;
  readonly eyebrow?: string;
  readonly title: string;
  readonly body?: string;
  readonly children?: ReactNode;
  readonly footer: ReactNode;
}

export function OnboardingScaffold({
  stepIndex,
  totalSteps,
  eyebrow,
  title,
  body,
  children,
  footer,
}: OnboardingScaffoldProps) {
  const { theme } = useTheme();

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: theme.background.primary }} edges={['top', 'bottom']}>
      <LinearGradient
        colors={[theme.background.primary, theme.background.secondary]}
        style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 280, opacity: 0.6 }}
      />
      <View style={{ flex: 1, paddingHorizontal: Spacing.xl, paddingTop: Spacing.lg }}>
        <View style={{ flexDirection: 'row', gap: Spacing.xs, marginBottom: Spacing.xl }}>
          {Array.from({ length: totalSteps }, (_, i) => i).map((i) => (
            <View
              key={i}
              style={{
                flex: 1,
                height: 3,
                borderRadius: Radius.pill,
                backgroundColor: i <= stepIndex ? theme.accent : theme.border,
                opacity: i <= stepIndex ? 1 : 0.6,
              }}
            />
          ))}
        </View>

        <Animated.View entering={FadeInUp.duration(360).springify().damping(18)} style={{ marginBottom: Spacing.lg }}>
          {eyebrow ? (
            <ThemedText variant="caption" tone="accent" style={{ marginBottom: Spacing.sm }}>
              {eyebrow.toUpperCase()}
            </ThemedText>
          ) : null}
          <ThemedText variant="headlineLarge" style={{ marginBottom: body ? Spacing.md : 0 }}>
            {title}
          </ThemedText>
          {body ? (
            <ThemedText variant="bodyLarge" tone="secondary">
              {body}
            </ThemedText>
          ) : null}
        </Animated.View>

        <Animated.View entering={FadeInDown.duration(420).delay(120).springify().damping(18)} style={{ flex: 1 }}>
          {children}
        </Animated.View>

        <View style={{ paddingTop: Spacing.lg, paddingBottom: Spacing.md, gap: Spacing.md }}>{footer}</View>
      </View>
    </SafeAreaView>
  );
}
