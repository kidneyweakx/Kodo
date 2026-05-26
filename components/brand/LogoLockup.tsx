/*
 * mi-band-9-active — animated logo + wordmark + tagline for onboarding.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { useEffect } from 'react';
import { View } from 'react-native';
import Animated, {
  Easing,
  FadeInUp,
  useAnimatedStyle,
  useSharedValue,
  withDelay,
  withRepeat,
  withSequence,
  withTiming,
} from 'react-native-reanimated';

import { Motion, Spacing, Typography } from '@/constants/DesignSystem';
import { AppLogo } from '@/components/brand/AppLogo';
import { ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';

export interface LogoLockupProps {
  readonly tagline?: string;
  readonly size?: number;
}

export function LogoLockup({ tagline, size = 132 }: LogoLockupProps) {
  const { theme } = useTheme();
  const dialRotate = useSharedValue(0);
  const shimmer = useSharedValue(0);

  useEffect(() => {
    // Subtle dial sweep — pointer ticks every ~6s, never distracting.
    dialRotate.value = withRepeat(
      withSequence(
        withTiming(360, { duration: 6000, easing: Easing.bezier(...Motion.easing.expoOut) }),
        withTiming(360, { duration: 0 }),
      ),
      -1,
      false,
    );
    shimmer.value = withDelay(
      400,
      withRepeat(
        withTiming(1, { duration: 2200, easing: Easing.inOut(Easing.cubic) }),
        -1,
        true,
      ),
    );
  }, [dialRotate, shimmer]);

  const dialStyle = useAnimatedStyle(() => ({
    transform: [{ rotate: `${dialRotate.value}deg` }],
  }));
  const shimmerStyle = useAnimatedStyle(() => ({
    opacity: 0.35 + shimmer.value * 0.45,
  }));

  return (
    <View style={{ alignItems: 'center', gap: Spacing.lg }}>
      <View
        style={{
          width: size + 32,
          height: size + 32,
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <Animated.View
          pointerEvents="none"
          style={[
            {
              position: 'absolute',
              width: size + 32,
              height: size + 32,
              borderRadius: (size + 32) / 2,
              backgroundColor: theme.accent,
              opacity: 0.16,
            },
            shimmerStyle,
          ]}
        />
        <Animated.View style={dialStyle}>
          <AppLogo size={size} />
        </Animated.View>
      </View>

      <Animated.View entering={FadeInUp.duration(480).delay(150).springify().damping(18)}>
        <ThemedText
          variant="displayMedium"
          style={{
            color: theme.text.primary,
            textAlign: 'center',
            letterSpacing: -0.5,
          }}
        >
          Kodō
        </ThemedText>
      </Animated.View>

      <Animated.View entering={FadeInUp.duration(560).delay(320).springify().damping(20)}>
        <ThemedText
          variant="caption"
          tone="accent"
          style={{
            ...Typography.caption,
            textAlign: 'center',
            letterSpacing: 2.5,
          }}
        >
          SLIM · LOCAL · OPEN
        </ThemedText>
      </Animated.View>

      {tagline ? (
        <Animated.View entering={FadeInUp.duration(640).delay(480).springify().damping(20)}>
          <ThemedText
            variant="bodyLarge"
            tone="secondary"
            style={{ textAlign: 'center', paddingHorizontal: Spacing.xl }}
          >
            {tagline}
          </ThemedText>
        </Animated.View>
      ) : null}
    </View>
  );
}
