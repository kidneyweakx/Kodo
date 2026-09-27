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

// Hypnogram: one lane per stage (awake → REM → light → deep, top to bottom),
// a rounded bar per stage span. Stage is encoded by lane AND colour AND the
// lane label, so it reads without colour vision. Tap anywhere on the plot to
// inspect the span under the finger.

import { useMemo, useState } from 'react';
import { Pressable, View } from 'react-native';
import type { LayoutChangeEvent } from 'react-native';
import Svg, { Line, Rect } from 'react-native-svg';

import { Spacing, tabularNums } from '@/constants/DesignSystem';
import { ThemedText } from '@/components/themed';
import { sleepStageColors, useTheme } from '@/context/ThemeContext';
import { t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';
import type { SleepSegment, SleepStage } from '@/modules/native';

const LANES: readonly SleepStage[] = ['awake', 'rem', 'light', 'deep'];
const LANE_H = 34;
const GUTTER = 64;
const AXIS_H = 20;

export const hhmm = (iso: string | number) => {
  const d = new Date(iso);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};

export const formatMinutes = (min: number) => {
  const h = Math.floor(min / 60);
  const m = Math.round(min % 60);
  return h > 0 ? t('sleep.hm', { h, m }) : t('sleep.m', { m });
};

export function Hypnogram({
  segments,
  bedAt,
  wakeAt,
}: {
  readonly segments: readonly SleepSegment[];
  readonly bedAt: string;
  readonly wakeAt: string;
}) {
  const { theme, resolvedMode } = useTheme();
  const colors = sleepStageColors(resolvedMode);
  const [width, setWidth] = useState(0);
  const [selected, setSelected] = useState<number | null>(null);

  const start = new Date(bedAt).getTime();
  const end = new Date(wakeAt).getTime();
  const span = Math.max(1, end - start);
  const plotW = Math.max(0, width - GUTTER);
  const xOf = (ms: number) => GUTTER + ((ms - start) / span) * plotW;

  const bars = useMemo(
    () =>
      segments.map((s) => ({
        seg: s,
        x0: new Date(s.startedAt).getTime(),
        x1: new Date(s.endedAt).getTime(),
        lane: LANES.indexOf(s.stage),
      })),
    [segments],
  );

  // Whole hours between bed and wake, thinned so labels never collide.
  const ticks = useMemo(() => {
    if (plotW <= 0) return [] as number[];
    const first = new Date(start);
    first.setMinutes(0, 0, 0);
    const out: number[] = [];
    for (let ms = first.getTime() + 3_600_000; ms < end; ms += 3_600_000) out.push(ms);
    const minGap = 44;
    const stride = Math.max(1, Math.ceil(minGap / ((3_600_000 / span) * plotW)));
    return out.filter((_, i) => i % stride === 0);
  }, [start, end, span, plotW]);

  const onPress = (x: number) => {
    const ms = start + ((x - GUTTER) / Math.max(1, plotW)) * span;
    const i = bars.findIndex((b) => ms >= b.x0 && ms < b.x1);
    if (i >= 0) {
      void hapticsBridge.fire('selection');
      setSelected(i === selected ? null : i);
    }
  };

  const picked = selected != null ? bars[selected] : undefined;
  const height = LANES.length * LANE_H + AXIS_H;

  return (
    <View style={{ gap: Spacing.sm }}>
      <View style={{ minHeight: 20 }}>
        {picked ? (
          <ThemedText variant="caption" tone="primary" style={tabularNums}>
            {t(`sleep.stages.${picked.seg.stage}`)} · {hhmm(picked.x0)}–{hhmm(picked.x1)} ·{' '}
            {formatMinutes((picked.x1 - picked.x0) / 60_000)}
          </ThemedText>
        ) : (
          <ThemedText variant="caption" tone="tertiary">
            {t('sleep.tapHint')}
          </ThemedText>
        )}
      </View>
      <Pressable
        onLayout={(e: LayoutChangeEvent) => setWidth(e.nativeEvent.layout.width)}
        onPress={(e) => onPress(e.nativeEvent.locationX)}
        accessibilityRole="image"
        accessibilityLabel={t('sleep.hypnogram')}
        style={{ height }}
      >
        {width > 0 ? (
          <Svg width={width} height={height}>
            {ticks.map((ms) => (
              <Line
                key={ms}
                x1={xOf(ms)}
                x2={xOf(ms)}
                y1={0}
                y2={LANES.length * LANE_H}
                stroke={theme.border}
                strokeWidth={1}
              />
            ))}
            {bars.map((b, i) => {
              if (b.lane < 0) return null;
              const x = xOf(b.x0);
              const w = Math.max(1.5, xOf(b.x1) - x - (xOf(b.x1) - x > 4 ? 2 : 0));
              const dim = selected != null && selected !== i;
              return (
                <Rect
                  key={b.seg.startedAt}
                  x={x}
                  y={b.lane * LANE_H + 5}
                  width={w}
                  height={LANE_H - 10}
                  rx={w > 8 ? 4 : 1}
                  fill={colors[b.seg.stage]}
                  opacity={dim ? 0.35 : 1}
                />
              );
            })}
          </Svg>
        ) : null}
        {/* Lane labels + time axis as native text (crisp, themeable). */}
        {LANES.map((stage, i) => (
          <View
            key={stage}
            pointerEvents="none"
            style={{ position: 'absolute', left: 0, top: i * LANE_H, height: LANE_H, width: GUTTER - 8, justifyContent: 'center' }}
          >
            <ThemedText variant="caption" tone="secondary" numberOfLines={1}>
              {t(`sleep.stages.${stage}`)}
            </ThemedText>
          </View>
        ))}
        <View pointerEvents="none" style={{ position: 'absolute', left: GUTTER, right: 0, top: LANES.length * LANE_H + 4 }}>
          {width > 0 ? (
            <>
              <ThemedText variant="micro" tone="tertiary" style={[tabularNums, { position: 'absolute', left: 0 }]}>
                {hhmm(bedAt)}
              </ThemedText>
              {ticks
                .filter((ms) => xOf(ms) - GUTTER > 40 && GUTTER + plotW - xOf(ms) > 40)
                .map((ms) => (
                  <ThemedText
                    key={ms}
                    variant="micro"
                    tone="tertiary"
                    style={[tabularNums, { position: 'absolute', left: xOf(ms) - GUTTER - 14 }]}
                  >
                    {hhmm(ms)}
                  </ThemedText>
                ))}
              <ThemedText variant="micro" tone="tertiary" style={[tabularNums, { position: 'absolute', right: 0 }]}>
                {hhmm(wakeAt)}
              </ThemedText>
            </>
          ) : null}
        </View>
      </Pressable>
    </View>
  );
}
