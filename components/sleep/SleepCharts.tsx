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

// Stage breakdown rows and the 7-night stacked trend. Values come straight
// from SleepNight; a night without stage data renders as one neutral bar
// (total only) instead of a guessed split.

import { useState } from 'react';
import { Pressable, View } from 'react-native';
import type { LayoutChangeEvent } from 'react-native';
import Svg, { Rect } from 'react-native-svg';

import { Radius, Spacing, tabularNums } from '@/constants/DesignSystem';
import { ThemedText } from '@/components/themed';
import { formatMinutes } from '@/components/sleep/Hypnogram';
import { sleepStageColors, useTheme } from '@/context/ThemeContext';
import type { SleepStageKey } from '@/context/ThemeContext';
import { getLocale, t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';
import type { SleepNight } from '@/modules/native/health/health.nitro';

const STAGE_ORDER: readonly SleepStageKey[] = ['deep', 'light', 'rem', 'awake'];

const stageMinutes = (n: SleepNight): Record<SleepStageKey, number | null> => ({
  deep: n.deepMinutes,
  light: n.lightMinutes,
  rem: n.remMinutes,
  awake: n.awakeMinutes,
});

/** Swatch · label · minutes · share bar. Hidden rows for stages the band didn't report. */
export function StageBreakdown({ night }: { readonly night: SleepNight }) {
  const { theme, resolvedMode } = useTheme();
  const colors = sleepStageColors(resolvedMode);
  const mins = stageMinutes(night);
  const total = STAGE_ORDER.reduce((s, k) => s + (mins[k] ?? 0), 0);
  if (total <= 0) return null;
  return (
    <View style={{ gap: Spacing.md }}>
      {STAGE_ORDER.map((k) => {
        const v = mins[k];
        if (v == null) return null;
        const pct = Math.round((v / total) * 100);
        return (
          <View key={k} style={{ gap: Spacing.xs }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: Spacing.sm }}>
              <View style={{ width: 10, height: 10, borderRadius: 3, backgroundColor: colors[k] }} />
              <ThemedText variant="bodyMedium" style={{ flex: 1 }}>
                {t(`sleep.stages.${k}`)}
              </ThemedText>
              <ThemedText variant="bodyMedium" tone="secondary" style={tabularNums}>
                {formatMinutes(v)} · {pct}%
              </ThemedText>
            </View>
            <View style={{ height: 6, borderRadius: 3, backgroundColor: theme.background.tertiary, overflow: 'hidden' }}>
              <View style={{ width: `${pct}%`, height: 6, borderRadius: 3, backgroundColor: colors[k] }} />
            </View>
          </View>
        );
      })}
    </View>
  );
}

const weekday = (dateIso: string) => {
  const [y, m, d] = dateIso.split('-').map(Number);
  const locale = getLocale() === 'zh-Hant' ? 'zh-TW' : 'en-US';
  return new Date(y ?? 1970, (m ?? 1) - 1, d ?? 1).toLocaleDateString(locale, { weekday: 'narrow' });
};

/**
 * Stacked bars for `dates` (oldest → newest). Tap selects a night. The total
 * of the selected night is direct-labelled; the header carries the average.
 */
export function SleepTrend({
  dates,
  nights,
  selected,
  onSelect,
}: {
  readonly dates: readonly string[];
  readonly nights: readonly SleepNight[];
  readonly selected: string;
  readonly onSelect: (dateIso: string) => void;
}) {
  const { theme, resolvedMode } = useTheme();
  const colors = sleepStageColors(resolvedMode);
  const [width, setWidth] = useState(0);
  const byDate = new Map(nights.map((n) => [n.date, n]));
  const H = 120;
  const maxMin = Math.max(8 * 60, ...nights.map((n) => n.totalMinutes + (n.awakeMinutes ?? 0)));
  const slot = dates.length > 0 ? width / dates.length : 0;
  const barW = Math.min(28, slot * 0.56);
  const yScale = (min: number) => (min / maxMin) * H;

  const withData = nights.filter((n) => dates.includes(n.date));
  const avg = withData.length ? withData.reduce((s, n) => s + n.totalMinutes, 0) / withData.length : null;

  return (
    <View style={{ gap: Spacing.sm }}>
      {avg != null ? (
        <ThemedText variant="caption" tone="secondary" style={tabularNums}>
          {t('sleep.avg', { value: formatMinutes(avg) })} {t('sleep.perNight')}
        </ThemedText>
      ) : null}
      <View onLayout={(e: LayoutChangeEvent) => setWidth(e.nativeEvent.layout.width)} style={{ height: H + 18 }}>
        {width > 0 ? (
          <Svg width={width} height={H}>
            {dates.map((date, i) => {
              const n = byDate.get(date);
              const cx = slot * i + slot / 2;
              if (!n) {
                return <Rect key={date} x={cx - barW / 2} y={H - 2} width={barW} height={2} rx={1} fill={theme.border} />;
              }
              const mins = stageMinutes(n);
              const hasStages = STAGE_ORDER.some((k) => mins[k] != null);
              const dim = date !== selected ? 0.55 : 1;
              if (!hasStages) {
                const h = yScale(n.totalMinutes);
                return (
                  <Rect key={date} x={cx - barW / 2} y={H - h} width={barW} height={h} rx={4} fill={theme.text.tertiary} opacity={dim} />
                );
              }
              let y = H;
              return STAGE_ORDER.map((k) => {
                const v = mins[k] ?? 0;
                if (v <= 0) return null;
                const h = yScale(v);
                y -= h;
                // 2px surface gap between stacked segments.
                return (
                  <Rect
                    key={`${date}-${k}`}
                    x={cx - barW / 2}
                    y={y + 1}
                    width={barW}
                    height={Math.max(1, h - 2)}
                    rx={Math.min(4, (h - 2) / 2)}
                    fill={colors[k]}
                    opacity={dim}
                  />
                );
              });
            })}
          </Svg>
        ) : null}
        <View style={{ flexDirection: 'row', position: 'absolute', left: 0, right: 0, top: 0, bottom: 0 }}>
          {dates.map((date) => {
            const n = byDate.get(date);
            const isSel = date === selected;
            return (
              <Pressable
                key={date}
                style={{ flex: 1, alignItems: 'center', justifyContent: 'flex-end' }}
                onPress={() => {
                  void hapticsBridge.fire('selection');
                  onSelect(date);
                }}
                accessibilityRole="button"
                accessibilityState={{ selected: isSel }}
                accessibilityLabel={`${date} ${n ? formatMinutes(n.totalMinutes) : '—'}`}
              >
                {isSel && n ? (
                  <ThemedText
                    variant="micro"
                    tone="primary"
                    style={[tabularNums, { position: 'absolute', top: Math.max(0, H - yScale(n.totalMinutes + (n.awakeMinutes ?? 0)) - 16) }]}
                  >
                    {(n.totalMinutes / 60).toFixed(1)}h
                  </ThemedText>
                ) : null}
                <View
                  style={{
                    paddingHorizontal: 6,
                    borderRadius: Radius.sm,
                    backgroundColor: isSel ? theme.accentSoft : 'transparent',
                  }}
                >
                  <ThemedText variant="micro" tone={isSel ? 'accent' : 'tertiary'}>
                    {weekday(date)}
                  </ThemedText>
                </View>
              </Pressable>
            );
          })}
        </View>
      </View>
    </View>
  );
}
