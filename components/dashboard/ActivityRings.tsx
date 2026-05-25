/*
 * mi-band-9-active — Apple/Garmin-style activity rings (Move / Sleep / Vitality).
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Three concentric SVG rings driven by Reanimated `useAnimatedProps` on the
 * stroke dash-offset so the value never crosses the JS bridge per frame.
 * Empty state: ghost rings + "—" centre, never a faked percentage.
 */

import { useEffect } from 'react';
import { View } from 'react-native';
import Animated, {
  Easing,
  useAnimatedProps,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import type { SharedValue } from 'react-native-reanimated';
import Svg, { Circle, Defs, LinearGradient as SvgGradient, Stop } from 'react-native-svg';

import { Motion, Spacing, Typography, tabularNums } from '@/constants/DesignSystem';
import { ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';

const AnimatedCircle = Animated.createAnimatedComponent(Circle);

export interface RingValue {
  readonly label: string;
  readonly value: number;
  readonly goal: number;
  readonly unit?: string;
}

export interface ActivityRingsProps {
  readonly size?: number;
  readonly steps: RingValue | null;
  readonly sleep: RingValue | null;
  readonly vitality: RingValue | null;
  readonly synced: boolean;
}

const RING_STROKE = 14;
const RING_GAP = 6;

export function ActivityRings({
  size = 260,
  steps,
  sleep,
  vitality,
  synced,
}: ActivityRingsProps) {
  const { theme } = useTheme();

  const progressA = useSharedValue(0); // outer — steps
  const progressB = useSharedValue(0); // middle — sleep
  const progressC = useSharedValue(0); // inner — vitality

  useEffect(() => {
    const animate = (sv: SharedValue<number>, target: number) => {
      sv.value = withTiming(target, {
        duration: Motion.duration.slow,
        easing: Easing.bezier(...Motion.easing.expoOut),
      });
    };
    animate(progressA, steps ? Math.min(1, steps.value / steps.goal) : 0);
    animate(progressB, sleep ? Math.min(1, sleep.value / sleep.goal) : 0);
    animate(progressC, vitality ? Math.min(1, vitality.value / vitality.goal) : 0);
  }, [progressA, progressB, progressC, steps, sleep, vitality]);

  const ringRadius = (index: number) =>
    size / 2 - RING_STROKE / 2 - index * (RING_STROKE + RING_GAP);

  const rA = ringRadius(0);
  const rB = ringRadius(1);
  const rC = ringRadius(2);
  const cA = 2 * Math.PI * rA;
  const cB = 2 * Math.PI * rB;
  const cC = 2 * Math.PI * rC;

  const propsA = useAnimatedProps(() => ({
    strokeDasharray: `${cA} ${cA}`,
    strokeDashoffset: cA * (1 - progressA.value),
  }));
  const propsB = useAnimatedProps(() => ({
    strokeDasharray: `${cB} ${cB}`,
    strokeDashoffset: cB * (1 - progressB.value),
  }));
  const propsC = useAnimatedProps(() => ({
    strokeDasharray: `${cC} ${cC}`,
    strokeDashoffset: cC * (1 - progressC.value),
  }));

  const center = size / 2;

  return (
    <View style={{ width: size, height: size, alignItems: 'center', justifyContent: 'center' }}>
      <Svg width={size} height={size}>
        <Defs>
          <SvgGradient id="ringA" x1="0" y1="0" x2="1" y2="1">
            <Stop offset="0" stopColor={theme.accent} />
            <Stop offset="1" stopColor={theme.accentStrong} />
          </SvgGradient>
          <SvgGradient id="ringB" x1="0" y1="0" x2="1" y2="1">
            <Stop offset="0" stopColor={theme.secondary} />
            <Stop offset="1" stopColor={theme.accent} />
          </SvgGradient>
          <SvgGradient id="ringC" x1="0" y1="0" x2="1" y2="1">
            <Stop offset="0" stopColor={theme.success} />
            <Stop offset="1" stopColor={theme.secondary} />
          </SvgGradient>
        </Defs>

        {/* Ghost tracks */}
        {[rA, rB, rC].map((r, i) => (
          <Circle
            key={`ghost-${i}`}
            cx={center}
            cy={center}
            r={r}
            stroke={theme.glassBorder}
            strokeWidth={RING_STROKE}
            fill="none"
            opacity={0.35}
          />
        ))}

        {/* Foreground rings (rotated -90° to start at 12 o'clock) */}
        <AnimatedCircle
          cx={center}
          cy={center}
          r={rA}
          stroke="url(#ringA)"
          strokeWidth={RING_STROKE}
          strokeLinecap="round"
          fill="none"
          animatedProps={propsA}
          transform={`rotate(-90 ${center} ${center})`}
        />
        <AnimatedCircle
          cx={center}
          cy={center}
          r={rB}
          stroke="url(#ringB)"
          strokeWidth={RING_STROKE}
          strokeLinecap="round"
          fill="none"
          animatedProps={propsB}
          transform={`rotate(-90 ${center} ${center})`}
        />
        <AnimatedCircle
          cx={center}
          cy={center}
          r={rC}
          stroke="url(#ringC)"
          strokeWidth={RING_STROKE}
          strokeLinecap="round"
          fill="none"
          animatedProps={propsC}
          transform={`rotate(-90 ${center} ${center})`}
        />
      </Svg>

      <View
        pointerEvents="none"
        style={{ position: 'absolute', alignItems: 'center', justifyContent: 'center' }}
      >
        <ThemedText
          variant="caption"
          tone="tertiary"
          style={{ letterSpacing: 1.2 }}
        >
          {synced ? 'TODAY · 今日' : 'NOT SYNCED · 未同步'}
        </ThemedText>
        <ThemedText
          style={{
            ...Typography.hero,
            ...tabularNums,
            color: theme.text.primary,
            marginTop: Spacing.xs,
          }}
        >
          {synced && steps ? steps.value.toLocaleString() : '—'}
        </ThemedText>
        <ThemedText variant="bodyMedium" tone="secondary">
          {synced && steps ? `${steps.unit ?? 'steps'} · 目標 ${steps.goal.toLocaleString()}` : 'steps'}
        </ThemedText>
      </View>
    </View>
  );
}

/** Ring legend chips that live under the rings. */
export function RingLegend({ steps, sleep, vitality }: {
  readonly steps: RingValue | null;
  readonly sleep: RingValue | null;
  readonly vitality: RingValue | null;
}) {
  const { theme } = useTheme();
  const rows = [
    { label: '步數 · STEPS', value: steps, color: theme.accent },
    { label: '睡眠 · SLEEP', value: sleep, color: theme.secondary },
    { label: '活力 · VITALITY', value: vitality, color: theme.success },
  ];
  return (
    <View style={{ width: '100%', gap: Spacing.sm, marginTop: Spacing.lg }}>
      {rows.map((row) => (
        <View
          key={row.label}
          style={{
            flexDirection: 'row',
            alignItems: 'center',
            gap: Spacing.md,
          }}
        >
          <View
            style={{
              width: 10,
              height: 10,
              borderRadius: 5,
              backgroundColor: row.color,
            }}
          />
          <ThemedText variant="caption" tone="tertiary" style={{ flex: 1, letterSpacing: 1 }}>
            {row.label}
          </ThemedText>
          <ThemedText
            variant="titleMedium"
            style={{ ...tabularNums, color: theme.text.primary }}
          >
            {row.value ? `${row.value.value.toLocaleString()} / ${row.value.goal.toLocaleString()}` : '— / —'}
          </ThemedText>
        </View>
      ))}
    </View>
  );
}
