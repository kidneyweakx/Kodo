/*
 * mi-band-9-active — Floating dock at the bottom of the dashboard.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { useEffect } from 'react';
import { Pressable, View } from 'react-native';
import Animated, {
  Easing,
  useAnimatedStyle,
  useSharedValue,
  withRepeat,
  withTiming,
} from 'react-native-reanimated';
import { BlurView } from 'expo-blur';

import { Motion, Radius, Shadow, Spacing } from '@/constants/DesignSystem';
import { ThemedText, readableTextOn } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';

export interface StickyActionDockProps {
  readonly syncing: boolean;
  readonly progress: number;
  readonly lastSyncLabel: string;
  readonly onSync: () => void;
  readonly onOpenWorkout: () => void;
}

export function StickyActionDock({
  syncing,
  progress,
  lastSyncLabel,
  onSync,
  onOpenWorkout,
}: StickyActionDockProps) {
  const { theme, resolvedMode } = useTheme();
  const pulse = useSharedValue(0);

  useEffect(() => {
    if (syncing) {
      pulse.value = withRepeat(
        withTiming(1, { duration: 1200, easing: Easing.inOut(Easing.cubic) }),
        -1,
        true,
      );
    } else {
      pulse.value = withTiming(0, { duration: Motion.duration.base });
    }
  }, [pulse, syncing]);

  const haloStyle = useAnimatedStyle(() => ({
    opacity: 0.25 + pulse.value * 0.35,
    transform: [{ scale: 1 + pulse.value * 0.06 }],
  }));

  const onSyncTap = () => {
    void hapticsBridge.fire('tap');
    onSync();
  };
  const onWorkoutTap = () => {
    void hapticsBridge.fire('selection');
    onOpenWorkout();
  };

  const onAccent = readableTextOn(theme.accent);

  return (
    <View
      pointerEvents="box-none"
      style={{
        position: 'absolute',
        left: Spacing.lg,
        right: Spacing.lg,
        bottom: Spacing.xl,
      }}
    >
      <Animated.View
        pointerEvents="none"
        style={[
          {
            position: 'absolute',
            inset: -6,
            borderRadius: Radius.xl,
            backgroundColor: theme.accent,
          },
          haloStyle,
        ]}
      />
      <BlurView
        tint={resolvedMode === 'light' ? 'light' : 'dark'}
        intensity={28}
        style={{
          borderRadius: Radius.xl,
          overflow: 'hidden',
          borderWidth: 1,
          borderColor: theme.glassBorder,
          backgroundColor: theme.background.secondary,
          ...Shadow.heavy,
        }}
      >
        <View style={{ flexDirection: 'row', gap: Spacing.sm, padding: Spacing.sm }}>
          <Pressable
            onPress={onSyncTap}
            accessibilityRole="button"
            accessibilityLabel="Sync now"
            style={{
              flex: 1,
              flexDirection: 'row',
              alignItems: 'center',
              gap: Spacing.sm,
              paddingVertical: Spacing.md,
              paddingHorizontal: Spacing.lg,
              borderRadius: Radius.lg,
              backgroundColor: theme.accent,
            }}
          >
            <View
              style={{
                width: 8,
                height: 8,
                borderRadius: 4,
                backgroundColor: onAccent,
              }}
            />
            <ThemedText
              variant="titleMedium"
              style={{ color: onAccent, flex: 1 }}
            >
              {syncing
                ? `同步中 / SYNCING ${Math.round(progress * 100)}%`
                : '立即同步 / SYNC NOW'}
            </ThemedText>
          </Pressable>
          <Pressable
            onPress={onWorkoutTap}
            accessibilityRole="button"
            accessibilityLabel="Open workout"
            style={{
              paddingVertical: Spacing.md,
              paddingHorizontal: Spacing.lg,
              borderRadius: Radius.lg,
              borderWidth: 1,
              borderColor: theme.glassBorder,
              backgroundColor: 'transparent',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <ThemedText variant="titleMedium" tone="primary">
              GO
            </ThemedText>
          </Pressable>
        </View>
        <View
          style={{
            paddingHorizontal: Spacing.lg,
            paddingBottom: Spacing.sm,
            paddingTop: 0,
          }}
        >
          <ThemedText variant="caption" tone="tertiary" style={{ letterSpacing: 1 }}>
            {lastSyncLabel.toUpperCase()}
          </ThemedText>
        </View>
      </BlurView>
    </View>
  );
}
