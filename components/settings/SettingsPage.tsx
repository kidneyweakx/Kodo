/*
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 */

// Chrome for every settings sub-page: back button + large title + scrolling
// stack of SettingsSectionCards. Paints on frame 1 (no data dependencies).

import { router } from 'expo-router';
import type { ReactNode } from 'react';
import { ScrollView, View } from 'react-native';
import Animated, { FadeInDown } from 'react-native-reanimated';
import { SafeAreaView } from 'react-native-safe-area-context';
import Svg, { Path } from 'react-native-svg';

import { Spacing } from '@/constants/DesignSystem';
import { ThemedIconButton, ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';

export function SettingsPage({
  title,
  subtitle,
  children,
}: {
  readonly title: string;
  readonly subtitle?: string;
  readonly children: ReactNode;
}) {
  const { theme } = useTheme();
  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: theme.background.primary }} edges={['top']}>
      <View style={{ paddingHorizontal: Spacing.md, paddingTop: Spacing.sm }}>
        <ThemedIconButton
          accessibilityLabel="Back"
          variant="ghost"
          onPress={() => router.back()}
          icon={(color) => (
            <Svg width={22} height={22} viewBox="0 0 24 24" fill="none">
              <Path d="M14.5 6l-6 6 6 6" stroke={color} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
            </Svg>
          )}
        />
      </View>
      <ScrollView
        contentContainerStyle={{
          paddingHorizontal: Spacing.lg,
          paddingBottom: Spacing.xxxl,
          gap: Spacing.lg,
        }}
        showsVerticalScrollIndicator={false}
      >
        <Animated.View entering={FadeInDown.duration(280)} style={{ gap: Spacing.xs, marginBottom: Spacing.xs }}>
          <ThemedText variant="headlineLarge">{title}</ThemedText>
          {subtitle ? (
            <ThemedText variant="bodyMedium" tone="secondary">
              {subtitle}
            </ThemedText>
          ) : null}
        </Animated.View>
        {children}
      </ScrollView>
    </SafeAreaView>
  );
}
