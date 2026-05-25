/*
 * mi-band-9-active — onboarding hero illustration for permission screens.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { View } from 'react-native';
import Animated, {
  Easing,
  useAnimatedStyle,
  useSharedValue,
  withRepeat,
  withTiming,
} from 'react-native-reanimated';
import { useEffect } from 'react';

import { Radius, Spacing } from '@/constants/DesignSystem';
import { useTheme } from '@/context/ThemeContext';
import { ThemedText } from '@/components/themed';

export type PermissionIcon = 'bluetooth' | 'notifications' | 'battery' | 'band';

const GLYPHS: Record<PermissionIcon, string> = {
  bluetooth: 'BT',
  notifications: 'BELL',
  battery: 'BAT',
  band: 'BAND',
};

export function PermissionHero({ icon }: { readonly icon: PermissionIcon }) {
  const { theme } = useTheme();
  const pulse = useSharedValue(0);

  useEffect(() => {
    pulse.value = withRepeat(withTiming(1, { duration: 1800, easing: Easing.inOut(Easing.ease) }), -1, true);
  }, [pulse]);

  const outerStyle = useAnimatedStyle(() => ({
    transform: [{ scale: 1 + pulse.value * 0.08 }],
    opacity: 0.35 + pulse.value * 0.25,
  }));

  const innerStyle = useAnimatedStyle(() => ({
    transform: [{ scale: 1 + pulse.value * 0.04 }],
  }));

  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', gap: Spacing.xl }}>
      <View style={{ width: 220, height: 220, alignItems: 'center', justifyContent: 'center' }}>
        <Animated.View
          style={[
            {
              position: 'absolute',
              width: 220,
              height: 220,
              borderRadius: Radius.pill,
              backgroundColor: theme.accent,
              opacity: 0.18,
            },
            outerStyle,
          ]}
        />
        <Animated.View
          style={[
            {
              width: 132,
              height: 132,
              borderRadius: Radius.pill,
              backgroundColor: theme.background.secondary,
              borderWidth: 1,
              borderColor: theme.glassBorder,
              alignItems: 'center',
              justifyContent: 'center',
            },
            innerStyle,
          ]}
        >
          <ThemedText variant="displayMedium" tone="accent">
            {GLYPHS[icon]}
          </ThemedText>
        </Animated.View>
      </View>
    </View>
  );
}
