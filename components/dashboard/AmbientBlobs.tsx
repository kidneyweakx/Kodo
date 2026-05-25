/*
 * mi-band-9-active — ambient gradient blobs behind the dashboard header.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Cinema-mobile cue from ui-ux-pro-max: 2–3 absolute, blurred, slowly
 * oscillating circles at low opacity to avoid a flat tile feel.
 * Reanimated, native driver only — never crosses the JS bridge per frame.
 */

import { useEffect } from 'react';
import { StyleSheet, View } from 'react-native';
import Animated, {
  Easing,
  useAnimatedStyle,
  useSharedValue,
  withRepeat,
  withTiming,
} from 'react-native-reanimated';

import { Motion } from '@/constants/DesignSystem';
import { useTheme } from '@/context/ThemeContext';

const Blob = ({
  color,
  size,
  topPct,
  leftPct,
  delayMs,
}: {
  readonly color: string;
  readonly size: number;
  readonly topPct: number;
  readonly leftPct: number;
  readonly delayMs: number;
}) => {
  const t = useSharedValue(0);

  useEffect(() => {
    const start = setTimeout(() => {
      t.value = withRepeat(
        withTiming(1, { duration: Motion.duration.ambient, easing: Easing.inOut(Easing.cubic) }),
        -1,
        true,
      );
    }, delayMs);
    return () => clearTimeout(start);
  }, [t, delayMs]);

  const style = useAnimatedStyle(() => ({
    transform: [
      { translateX: -size / 2 + (t.value - 0.5) * 32 },
      { translateY: -size / 2 + (t.value - 0.5) * 24 },
      { scale: 0.95 + t.value * 0.1 },
    ],
    opacity: 0.16 + t.value * 0.08,
  }));

  return (
    <Animated.View
      pointerEvents="none"
      style={[
        {
          position: 'absolute',
          top: `${topPct}%`,
          left: `${leftPct}%`,
          width: size,
          height: size,
          borderRadius: size / 2,
          backgroundColor: color,
        },
        style,
      ]}
    />
  );
};

export function AmbientBlobs() {
  const { theme } = useTheme();
  return (
    <View pointerEvents="none" style={StyleSheet.absoluteFill}>
      <Blob color={theme.accent} size={320} topPct={-8} leftPct={-10} delayMs={0} />
      <Blob color={theme.secondary} size={260} topPct={18} leftPct={62} delayMs={1200} />
      <Blob color={theme.accentStrong} size={200} topPct={42} leftPct={8} delayMs={600} />
    </View>
  );
}
