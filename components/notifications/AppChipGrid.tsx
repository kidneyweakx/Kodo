/*
 * mi-band-9-active — circular monogram chip grid for the notifications screen.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Each app becomes a 56px circle with the first character of its label on a
 * deterministic gradient seeded by the package name. Active = full gradient,
 * inactive = ghost. Tap to toggle (haptic = selection).
 */

import { useMemo } from 'react';
import { Pressable, View } from 'react-native';
import Animated, { FadeIn } from 'react-native-reanimated';
import Svg, { Defs, LinearGradient as SvgGradient, Stop, Circle } from 'react-native-svg';

import { Spacing } from '@/constants/DesignSystem';
import { ThemedText, readableTextOn } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';

export interface AppChip {
  readonly sourceId: string;
  readonly appName: string;
  readonly enabled: boolean;
}

export interface AppChipGridProps {
  readonly chips: readonly AppChip[];
  readonly onToggle: (sourceId: string, next: boolean) => void;
}

const SIZE = 64;

const hashHue = (seed: string): number => {
  let h = 0;
  for (let i = 0; i < seed.length; i++) {
    h = (h * 31 + seed.charCodeAt(i)) >>> 0;
  }
  return h % 360;
};

const hslToHex = (h: number, s: number, l: number): string => {
  const k = (n: number) => (n + h / 30) % 12;
  const a = s * Math.min(l, 1 - l);
  const f = (n: number) =>
    l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)));
  const r = Math.round(f(0) * 255);
  const g = Math.round(f(8) * 255);
  const b = Math.round(f(4) * 255);
  return `#${[r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('')}`;
};

export function AppChipGrid({ chips, onToggle }: AppChipGridProps) {
  const { theme } = useTheme();

  return (
    <View
      style={{
        flexDirection: 'row',
        flexWrap: 'wrap',
        gap: Spacing.md,
        rowGap: Spacing.lg,
      }}
    >
      {chips.map((chip, idx) => (
        <Animated.View key={chip.sourceId} entering={FadeIn.delay(idx * 40).duration(280)}>
          <ChipDot chip={chip} onToggle={onToggle} theme={theme} />
        </Animated.View>
      ))}
    </View>
  );
}

function ChipDot({
  chip,
  onToggle,
  theme,
}: {
  readonly chip: AppChip;
  readonly onToggle: (sourceId: string, next: boolean) => void;
  readonly theme: ReturnType<typeof useTheme>['theme'];
}) {
  const initial = (chip.appName?.[0] ?? '?').toUpperCase();
  const { c1, c2 } = useMemo(() => {
    const baseHue = hashHue(chip.sourceId);
    return {
      c1: hslToHex(baseHue, 0.6, 0.55),
      c2: hslToHex((baseHue + 28) % 360, 0.7, 0.45),
    };
  }, [chip.sourceId]);

  const fg = readableTextOn(c1);
  const enabled = chip.enabled;

  const gradientId = `appchip-${chip.sourceId.replace(/[^a-z0-9]/gi, '')}`;

  return (
    <Pressable
      onPress={() => {
        void hapticsBridge.fire('selection');
        onToggle(chip.sourceId, !enabled);
      }}
      accessibilityRole="switch"
      accessibilityState={{ checked: enabled }}
      accessibilityLabel={`${chip.appName} ${enabled ? 'on' : 'off'}`}
      style={{
        width: SIZE + 16,
        alignItems: 'center',
        gap: Spacing.xs,
      }}
    >
      <View
        style={{
          width: SIZE,
          height: SIZE,
          borderRadius: SIZE / 2,
          alignItems: 'center',
          justifyContent: 'center',
          opacity: enabled ? 1 : 0.45,
        }}
      >
        <Svg width={SIZE} height={SIZE} style={{ position: 'absolute' }}>
          <Defs>
            <SvgGradient id={gradientId} x1="0" y1="0" x2="1" y2="1">
              <Stop offset="0" stopColor={c1} />
              <Stop offset="1" stopColor={c2} />
            </SvgGradient>
          </Defs>
          {enabled ? (
            <Circle cx={SIZE / 2} cy={SIZE / 2} r={SIZE / 2 - 1} fill={`url(#${gradientId})`} />
          ) : (
            <Circle
              cx={SIZE / 2}
              cy={SIZE / 2}
              r={SIZE / 2 - 1}
              fill="transparent"
              stroke={theme.glassBorder}
              strokeWidth={1.5}
            />
          )}
        </Svg>
        <ThemedText
          variant="headlineMedium"
          style={{ color: enabled ? fg : theme.text.tertiary }}
        >
          {initial}
        </ThemedText>
      </View>
      <ThemedText
        variant="caption"
        tone={enabled ? 'primary' : 'tertiary'}
        numberOfLines={1}
        style={{ maxWidth: SIZE + 16, textAlign: 'center' }}
      >
        {chip.appName}
      </ThemedText>
    </Pressable>
  );
}
