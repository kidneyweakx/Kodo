/*
 * mi-band-9-active — honest sparkline. When data is empty we render a
 * baseline + "尚未同步 / not synced yet" caption, never a faked curve.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Implements CLAUDE.md rule 8 for chart placeholders.
 */

import { View } from 'react-native';
import Svg, { Defs, LinearGradient, Path, Stop } from 'react-native-svg';

import { Spacing } from '@/constants/DesignSystem';
import { ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';

export interface SparklineProps {
  readonly samples: readonly number[];
  readonly width?: number;
  readonly height?: number;
  readonly emptyHint?: string;
}

const buildPath = (samples: readonly number[], width: number, height: number): string => {
  if (samples.length < 2) return '';
  const min = Math.min(...samples);
  const max = Math.max(...samples);
  const range = max - min || 1;
  const stepX = width / (samples.length - 1);
  return samples
    .map((v, i) => {
      const x = i * stepX;
      const y = height - ((v - min) / range) * height;
      return `${i === 0 ? 'M' : 'L'} ${x.toFixed(2)} ${y.toFixed(2)}`;
    })
    .join(' ');
};

export function Sparkline({
  samples,
  width = 220,
  height = 56,
  emptyHint = '尚未同步 / Not synced yet',
}: SparklineProps) {
  const { theme } = useTheme();

  if (samples.length < 2) {
    return (
      <View style={{ width, height, justifyContent: 'center' }}>
        <View
          style={{
            height: 1,
            backgroundColor: theme.border,
            marginVertical: Spacing.xs,
            opacity: 0.6,
          }}
        />
        <ThemedText variant="caption" tone="tertiary">
          {emptyHint}
        </ThemedText>
      </View>
    );
  }

  const path = buildPath(samples, width, height);

  return (
    <Svg width={width} height={height}>
      <Defs>
        <LinearGradient id="sparkFill" x1="0" y1="0" x2="0" y2="1">
          <Stop offset="0" stopColor={theme.accent} stopOpacity={0.32} />
          <Stop offset="1" stopColor={theme.accent} stopOpacity={0} />
        </LinearGradient>
      </Defs>
      <Path d={`${path} L ${width} ${height} L 0 ${height} Z`} fill="url(#sparkFill)" />
      <Path d={path} stroke={theme.accent} strokeWidth={1.8} fill="none" />
    </Svg>
  );
}
