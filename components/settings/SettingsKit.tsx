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

// Settings building blocks. Visual language borrowed from N-Zik's settings
// (SettingsSectionCard / ModernSettingsEntry / OtherSwitchSettingEntry):
// rounded section cards with an accent-tinted icon tile + small accent title,
// rows of [icon tile · title/subtitle · trailing control], press-scale
// feedback, and a stepped slider with labelled ticks.

import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import { Pressable, View } from 'react-native';
import type { LayoutChangeEvent } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import Animated, {
  interpolateColor,
  useAnimatedStyle,
  useSharedValue,
  withSpring,
  withTiming,
} from 'react-native-reanimated';
import { scheduleOnRN } from 'react-native-worklets';

import { Motion, Radius, Spacing, tabularNums } from '@/constants/DesignSystem';
import { ThemedText, readableTextOn } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';
import { SettingsGlyph } from '@/components/settings/SettingsGlyph';
import type { SettingsGlyphName } from '@/components/settings/SettingsGlyph';

const TILE = 32;

export function IconTile({
  name,
  tone = 'accent',
}: {
  readonly name: SettingsGlyphName;
  readonly tone?: 'accent' | 'danger' | 'muted';
}) {
  const { theme } = useTheme();
  const fg = tone === 'danger' ? theme.danger : tone === 'muted' ? theme.text.secondary : theme.accent;
  const bg = tone === 'accent' ? theme.accentSoft : theme.background.tertiary;
  return (
    <View
      style={{
        width: TILE,
        height: TILE,
        borderRadius: Radius.sm + 2,
        backgroundColor: bg,
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <SettingsGlyph name={name} color={fg} />
    </View>
  );
}

/** Rounded card with an icon-tile header. Rows go inside as children. */
export function SettingsSectionCard({
  title,
  icon,
  description,
  tone = 'accent',
  children,
}: {
  readonly title: string;
  readonly icon: SettingsGlyphName;
  readonly description?: string;
  readonly tone?: 'accent' | 'danger';
  readonly children: ReactNode;
}) {
  const { theme } = useTheme();
  return (
    <View
      style={{
        borderRadius: Radius.xl,
        backgroundColor: theme.background.secondary,
        borderWidth: 1,
        borderColor: theme.border,
        paddingHorizontal: Spacing.sm,
        paddingTop: Spacing.lg,
        paddingBottom: Spacing.sm,
      }}
    >
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          gap: Spacing.md,
          paddingHorizontal: Spacing.sm,
          marginBottom: description ? Spacing.xs : Spacing.sm,
        }}
      >
        <IconTile name={icon} tone={tone} />
        <ThemedText
          variant="caption"
          style={{ color: tone === 'danger' ? theme.danger : theme.accent, fontWeight: '700' }}
          numberOfLines={1}
        >
          {title}
        </ThemedText>
      </View>
      {description ? (
        <ThemedText
          variant="caption"
          tone="tertiary"
          style={{ paddingHorizontal: Spacing.sm, marginBottom: Spacing.sm, letterSpacing: 0.2 }}
        >
          {description}
        </ThemedText>
      ) : null}
      {children}
    </View>
  );
}

/** Pill toggle with a springy thumb; colors derive from the theme. */
export function SettingsSwitch({
  value,
  onValueChange,
  disabled,
  accessibilityLabel,
}: {
  readonly value: boolean;
  readonly onValueChange: (v: boolean) => void;
  readonly disabled?: boolean;
  readonly accessibilityLabel?: string;
}) {
  const { theme } = useTheme();
  const progress = useSharedValue(value ? 1 : 0);
  const onAccent = readableTextOn(theme.accent);

  useEffect(() => {
    progress.set(withSpring(value ? 1 : 0, Motion.spring.press));
  }, [value, progress]);

  const trackStyle = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(progress.value, [0, 1], [theme.background.tertiary, theme.accent]),
    borderColor: interpolateColor(progress.value, [0, 1], [theme.glassBorder, theme.accent]),
  }));
  const thumbStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: progress.value * 20 }, { scale: 0.85 + progress.value * 0.15 }],
    backgroundColor: interpolateColor(
      progress.value,
      [0, 1],
      [theme.text.tertiary, onAccent],
    ),
  }));

  return (
    <Pressable
      onPress={() => {
        if (disabled) return;
        void hapticsBridge.fire('selection');
        onValueChange(!value);
      }}
      hitSlop={10}
      accessibilityRole="switch"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{ checked: value, disabled: !!disabled }}
      style={{ opacity: disabled ? 0.45 : 1 }}
    >
      <Animated.View
        style={[
          { width: 48, height: 28, borderRadius: 14, borderWidth: 1, padding: 3, justifyContent: 'center' },
          trackStyle,
        ]}
      >
        <Animated.View style={[{ width: 20, height: 20, borderRadius: 10 }, thumbStyle]} />
      </Animated.View>
    </Pressable>
  );
}

type Trailing =
  | { readonly kind: 'chevron'; readonly value?: string }
  | { readonly kind: 'switch'; readonly value: boolean; readonly onChange: (v: boolean) => void }
  | { readonly kind: 'value'; readonly value: string }
  | { readonly kind: 'custom'; readonly node: ReactNode }
  | { readonly kind: 'none' };

/**
 * One settings row. The whole row is the touch target: switches toggle on a
 * row tap, chevrons navigate. Min height 56 so two-line rows never cramp.
 */
export function SettingsItem({
  icon,
  title,
  subtitle,
  trailing = { kind: 'none' },
  onPress,
  disabled,
  tone = 'default',
  below,
}: {
  readonly icon?: SettingsGlyphName;
  readonly title: string;
  readonly subtitle?: string;
  readonly trailing?: Trailing;
  readonly onPress?: () => void;
  readonly disabled?: boolean;
  readonly tone?: 'default' | 'danger';
  /** Full-width content under the title row (sliders, choices). */
  readonly below?: ReactNode;
}) {
  const { theme } = useTheme();
  const scale = useSharedValue(1);
  const pressStyle = useAnimatedStyle(() => ({ transform: [{ scale: scale.value }] }));

  // An explicit onPress wins (e.g. open an editor); otherwise a switch row
  // toggles on a tap anywhere in the row.
  const handlePress =
    onPress ??
    (trailing.kind === 'switch'
      ? () => {
          void hapticsBridge.fire('selection');
          trailing.onChange(!trailing.value);
        }
      : undefined);
  const interactive = !!handlePress && !disabled;

  return (
    <Pressable
      disabled={!interactive}
      onPress={handlePress}
      onPressIn={() => {
        scale.set(withTiming(0.97, { duration: Motion.duration.micro }));
      }}
      onPressOut={() => {
        scale.set(withSpring(1, Motion.spring.press));
      }}
      android_ripple={interactive ? { color: theme.glassBorder, borderless: false } : undefined}
      accessibilityRole={trailing.kind === 'switch' && !onPress ? 'switch' : interactive ? 'button' : undefined}
      accessibilityState={
        trailing.kind === 'switch' ? { checked: trailing.value, disabled: !!disabled } : { disabled: !!disabled }
      }
      style={{ borderRadius: Radius.lg, overflow: 'hidden' }}
    >
      <Animated.View
        style={[
          {
            minHeight: 56,
            paddingHorizontal: Spacing.sm,
            paddingVertical: Spacing.md,
            opacity: disabled ? 0.45 : 1,
          },
          pressStyle,
        ]}
      >
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: Spacing.md }}>
          {icon ? <IconTile name={icon} tone={tone === 'danger' ? 'danger' : 'accent'} /> : null}
          <View style={{ flex: 1, gap: 2 }}>
            <ThemedText
              variant="titleMedium"
              style={{ fontSize: 15, color: tone === 'danger' ? theme.danger : theme.text.primary }}
            >
              {title}
            </ThemedText>
            {subtitle ? (
              <ThemedText variant="caption" tone="secondary" style={{ letterSpacing: 0.1, fontWeight: '400' }}>
                {subtitle}
              </ThemedText>
            ) : null}
          </View>
          {trailing.kind === 'switch' ? (
            <SettingsSwitch
              value={trailing.value}
              onValueChange={trailing.onChange}
              disabled={disabled}
              accessibilityLabel={title}
            />
          ) : trailing.kind === 'chevron' ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: Spacing.xs }}>
              {trailing.value ? (
                <ThemedText variant="bodyMedium" tone="tertiary" style={tabularNums}>
                  {trailing.value}
                </ThemedText>
              ) : null}
              <SettingsGlyph name="chevron" size={16} color={theme.text.tertiary} />
            </View>
          ) : trailing.kind === 'value' ? (
            <ThemedText variant="bodyMedium" tone="tertiary" style={tabularNums}>
              {trailing.value}
            </ThemedText>
          ) : trailing.kind === 'custom' ? (
            trailing.node
          ) : null}
        </View>
        {below ? <View style={{ marginTop: Spacing.md, marginLeft: icon ? TILE + Spacing.md : 0 }}>{below}</View> : null}
      </Animated.View>
    </Pressable>
  );
}

export interface ChoiceOption<T extends string | number> {
  readonly value: T;
  readonly label: string;
}

/** Segmented single-choice control with a sliding accent pill. */
export function SettingsChoice<T extends string | number>({
  options,
  value,
  onChange,
  disabled,
}: {
  readonly options: readonly ChoiceOption<T>[];
  readonly value: T;
  readonly onChange: (v: T) => void;
  readonly disabled?: boolean;
}) {
  const { theme } = useTheme();
  const [width, setWidth] = useState(0);
  const index = Math.max(0, options.findIndex((o) => o.value === value));
  const segment = options.length > 0 ? (width - 6) / options.length : 0;
  const x = useSharedValue(index * segment);

  useEffect(() => {
    x.set(withSpring(index * segment, Motion.spring.press));
  }, [index, segment, x]);

  const pillStyle = useAnimatedStyle(() => ({ transform: [{ translateX: x.value }] }));

  return (
    <View
      onLayout={(e: LayoutChangeEvent) => setWidth(e.nativeEvent.layout.width)}
      style={{
        flexDirection: 'row',
        backgroundColor: theme.background.tertiary,
        borderRadius: Radius.md,
        padding: 3,
        opacity: disabled ? 0.45 : 1,
      }}
    >
      {width > 0 ? (
        <Animated.View
          style={[
            {
              position: 'absolute',
              top: 3,
              bottom: 3,
              left: 3,
              width: segment,
              borderRadius: Radius.sm,
              backgroundColor: theme.accent,
            },
            pillStyle,
          ]}
        />
      ) : null}
      {options.map((opt) => {
        const selected = opt.value === value;
        return (
          <Pressable
            key={String(opt.value)}
            disabled={disabled}
            onPress={() => {
              if (selected) return;
              void hapticsBridge.fire('selection');
              onChange(opt.value);
            }}
            accessibilityRole="radio"
            accessibilityState={{ selected }}
            style={{ flex: 1, minHeight: 34, alignItems: 'center', justifyContent: 'center' }}
          >
            <ThemedText
              variant="caption"
              style={{
                color: selected ? readableTextOn(theme.accent) : theme.text.secondary,
                fontWeight: '700',
                letterSpacing: 0.3,
              }}
            >
              {opt.label}
            </ThemedText>
          </Pressable>
        );
      })}
    </View>
  );
}

/**
 * Stepped slider with labelled ticks (N-Zik style). `stops` are the only
 * reachable values; the thumb snaps while dragging and `onChange` fires on
 * release so we don't spam the band with intermediate writes.
 */
export function SettingsSlider({
  stops,
  value,
  onChange,
  format,
  disabled,
}: {
  readonly stops: readonly number[];
  readonly value: number;
  readonly onChange: (v: number) => void;
  readonly format: (v: number) => string;
  readonly disabled?: boolean;
}) {
  const { theme } = useTheme();
  const [width, setWidth] = useState(0);
  const count = stops.length;
  const nearest = stops.reduce(
    (best, s, i) => (Math.abs(s - value) < Math.abs((stops[best] ?? 0) - value) ? i : best),
    0,
  );
  const stepPx = count > 1 ? width / (count - 1) : 0;
  const x = useSharedValue(nearest * stepPx);
  // Index under the finger while dragging; null = show the committed value.
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const preview = dragIndex ?? nearest;

  useEffect(() => {
    x.set(withSpring(nearest * stepPx, Motion.spring.press));
  }, [nearest, stepPx, x]);

  const commit = (i: number) => {
    setDragIndex(null);
    const next = stops[i];
    if (next === undefined || next === value) return;
    void hapticsBridge.fire('selection');
    onChange(next);
  };

  const snapTo = (px: number) => {
    'worklet';
    if (stepPx <= 0) return 0;
    return Math.min(count - 1, Math.max(0, Math.round(px / stepPx)));
  };

  const pan = Gesture.Pan()
    .enabled(!disabled && width > 0)
    .onUpdate((e) => {
      const px = Math.min(width, Math.max(0, e.x));
      x.set(px);
      scheduleOnRN(setDragIndex, snapTo(px));
    })
    .onEnd(() => {
      const i = snapTo(x.value);
      x.set(withSpring(i * stepPx, Motion.spring.press));
      scheduleOnRN(commit, i);
    });
  const tap = Gesture.Tap()
    .enabled(!disabled && width > 0)
    .onEnd((e) => {
      const i = snapTo(e.x);
      x.set(withSpring(i * stepPx, Motion.spring.press));
      scheduleOnRN(commit, i);
    });

  const fillStyle = useAnimatedStyle(() => ({ width: x.value }));
  const thumbStyle = useAnimatedStyle(() => ({ transform: [{ translateX: x.value - 11 }] }));

  return (
    <View style={{ opacity: disabled ? 0.45 : 1, gap: Spacing.sm }}>
      <View style={{ flexDirection: 'row', justifyContent: 'flex-end' }}>
        <View style={{ backgroundColor: theme.accentSoft, borderRadius: Radius.sm, paddingHorizontal: Spacing.sm, paddingVertical: 2 }}>
          <ThemedText variant="caption" tone="accent" style={[tabularNums, { fontWeight: '700' }]}>
            {format(stops[preview] ?? value)}
          </ThemedText>
        </View>
      </View>
      <GestureDetector gesture={Gesture.Race(pan, tap)}>
        <View
          onLayout={(e) => setWidth(e.nativeEvent.layout.width)}
          style={{ height: 28, justifyContent: 'center' }}
          accessibilityRole="adjustable"
          accessibilityValue={{ text: format(value) }}
        >
          <View style={{ height: 6, borderRadius: 3, backgroundColor: theme.background.tertiary }} />
          <Animated.View
            style={[{ position: 'absolute', left: 0, height: 6, borderRadius: 3, backgroundColor: theme.accent }, fillStyle]}
          />
          {stops.map((s, i) => (
            <View
              key={s}
              style={{
                position: 'absolute',
                left: i * stepPx - 2,
                width: 4,
                height: 4,
                borderRadius: 2,
                backgroundColor: i <= preview ? readableTextOn(theme.accent) : theme.text.tertiary,
                opacity: 0.6,
              }}
            />
          ))}
          <Animated.View
            style={[
              {
                position: 'absolute',
                left: 0,
                width: 22,
                height: 22,
                borderRadius: 11,
                backgroundColor: theme.accent,
                borderWidth: 3,
                borderColor: theme.background.secondary,
              },
              thumbStyle,
            ]}
          />
        </View>
      </GestureDetector>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
        {stops.map((s, i) =>
          i === 0 || i === count - 1 || count <= 5 ? (
            <ThemedText key={s} variant="micro" tone="tertiary" style={tabularNums}>
              {format(s)}
            </ThemedText>
          ) : null,
        )}
      </View>
    </View>
  );
}

/** Hairline between rows inside a section card, inset past the icon tile. */
export function SettingsDivider({ inset = true }: { readonly inset?: boolean }) {
  const { theme } = useTheme();
  return (
    <View
      style={{
        height: 1,
        backgroundColor: theme.border,
        marginLeft: inset ? Spacing.sm + TILE + Spacing.md : Spacing.sm,
        marginRight: Spacing.sm,
      }}
    />
  );
}
