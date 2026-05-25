/*
 * mi-band-9-active — "what the band shows" preview card.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * A stylised mock of the band's notification screen so users get an
 * immediate sense of *what* the next push will look like before it lands.
 * Uses the live theme accent for the side stripe so the preview reflects
 * their current palette.
 */

import { View } from 'react-native';
import Animated, { FadeInUp } from 'react-native-reanimated';
import { LinearGradient } from 'expo-linear-gradient';

import { Shadow, Spacing } from '@/constants/DesignSystem';
import { ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';

export interface BandPreviewCardProps {
  readonly appName: string;
  readonly title: string;
  readonly body: string;
  readonly when: string;
}

export function BandPreviewCard({ appName, title, body, when }: BandPreviewCardProps) {
  const { theme } = useTheme();

  return (
    <Animated.View entering={FadeInUp.duration(420).springify().damping(18)}>
      <View
        style={{
          alignSelf: 'center',
          width: 230,
          height: 132,
          borderRadius: 24,
          overflow: 'hidden',
          borderWidth: 1,
          borderColor: theme.glassBorder,
          backgroundColor: theme.background.tertiary,
          ...Shadow.heavy,
        }}
      >
        <LinearGradient
          colors={[theme.accent, theme.accentStrong]}
          start={{ x: 0, y: 0 }}
          end={{ x: 0, y: 1 }}
          style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 4 }}
        />
        <View style={{ padding: Spacing.md, paddingLeft: Spacing.lg, gap: Spacing.xs }}>
          <View
            style={{
              flexDirection: 'row',
              justifyContent: 'space-between',
              alignItems: 'center',
            }}
          >
            <ThemedText variant="caption" tone="accent" style={{ letterSpacing: 1.2 }}>
              {appName.toUpperCase()}
            </ThemedText>
            <ThemedText variant="caption" tone="tertiary">
              {when}
            </ThemedText>
          </View>
          <ThemedText
            variant="titleMedium"
            numberOfLines={1}
            style={{ color: theme.text.primary }}
          >
            {title || ' '}
          </ThemedText>
          <ThemedText
            variant="bodyMedium"
            tone="secondary"
            numberOfLines={2}
            style={{ lineHeight: 18 }}
          >
            {body}
          </ThemedText>
        </View>
        <View
          style={{
            position: 'absolute',
            bottom: Spacing.sm,
            right: Spacing.md,
            width: 28,
            height: 4,
            borderRadius: 2,
            backgroundColor: theme.glassBorder,
          }}
        />
      </View>
      <ThemedText
        variant="caption"
        tone="tertiary"
        style={{ textAlign: 'center', marginTop: Spacing.sm, letterSpacing: 1 }}
      >
        PREVIEW · 預覽手環上的樣子
      </ThemedText>
    </Animated.View>
  );
}
