/*
 * mi-band-9-active — sticky sync status pill at the top of the dashboard.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Reanimated worklet drives the progress bar — never crosses JS thread per
 * frame. Tap to trigger a foreground re-sync.
 */

import { useEffect } from 'react';
import { Alert, Pressable, View } from 'react-native';
import Animated, {
  Easing,
  interpolate,
  useAnimatedStyle,
  useSharedValue,
  withRepeat,
  withTiming,
} from 'react-native-reanimated';

import { Motion, Radius, Spacing, tabularNums } from '@/constants/DesignSystem';
import { ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';
import { bandLink } from '@/libs/services/bandLink';
import { useSyncStatus } from '@/libs/services/syncStatus';
import { t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';

const phaseLabel: Record<string, string> = {
  idle: '',
  connecting: 'CONNECTING · 連線中',
  health: 'HEALTH · 健康',
  sleep: 'SLEEP · 睡眠',
  workouts: 'WORKOUTS · 運動',
  settings: 'SETTINGS · 設定',
  done: 'DONE · 完成',
  error: 'ERROR · 失敗',
};

const formatRelative = (iso: string | null): string => {
  if (!iso) return t('common.unsynced');
  const delta = Date.now() - new Date(iso).getTime();
  if (delta < 60_000) return '剛剛 / just now';
  if (delta < 3_600_000) return `${Math.floor(delta / 60_000)}m ago`;
  if (delta < 86_400_000) return `${Math.floor(delta / 3_600_000)}h ago`;
  return new Date(iso).toLocaleDateString();
};

export function SyncStatusBar() {
  const { theme } = useTheme();
  const status = useSyncStatus();
  const progress = useSharedValue(0);
  const visible = useSharedValue(0);
  const scan = useSharedValue(0);
  const dotPulse = useSharedValue(0);

  const isSyncing = status.phase !== 'idle' && status.phase !== 'error';
  const isError = status.phase === 'error';

  useEffect(() => {
    progress.value = withTiming(status.progress, {
      duration: Motion.duration.base,
      easing: Easing.bezier(...Motion.easing.expoOut),
    });
    visible.value = withTiming(isSyncing || isError ? 1 : 0.7, {
      duration: Motion.duration.base,
    });
  }, [progress, visible, status.progress, isSyncing, isError]);

  useEffect(() => {
    if (isSyncing) {
      scan.value = withRepeat(
        withTiming(1, { duration: 1600, easing: Easing.inOut(Easing.cubic) }),
        -1,
        false,
      );
      dotPulse.value = withRepeat(
        withTiming(1, { duration: 900, easing: Easing.inOut(Easing.cubic) }),
        -1,
        true,
      );
    } else {
      scan.value = withTiming(0, { duration: Motion.duration.base });
      dotPulse.value = withTiming(0, { duration: Motion.duration.base });
    }
  }, [scan, dotPulse, isSyncing]);

  const barStyle = useAnimatedStyle(() => ({
    width: `${interpolate(progress.value, [0, 1], [0, 100])}%`,
  }));

  const wrapperStyle = useAnimatedStyle(() => ({
    opacity: visible.value,
  }));

  // Scanning beam — sweeps from -20% to 100% of bar width while syncing.
  const scanStyle = useAnimatedStyle(() => ({
    left: `${interpolate(scan.value, [0, 1], [-20, 100])}%`,
    opacity: isSyncing ? 0.55 : 0,
  }));

  // Pulsing dot — radial halo behind the status indicator.
  const dotHaloStyle = useAnimatedStyle(() => ({
    transform: [{ scale: 1 + dotPulse.value * 1.4 }],
    opacity: 0.55 - dotPulse.value * 0.55,
  }));

  const onTap = async () => {
    if (isSyncing) return;
    void hapticsBridge.fire('tap');
    try {
      const since = new Date(Date.now() - 86_400_000).toISOString();
      await bandLink.syncSince(since);
    } catch (e) {
      console.warn('[SyncStatusBar] sync failed:', e);
      const msg = e instanceof Error ? e.message : String(e);
      Alert.alert('Sync failed', msg);
    }
  };

  const accent = isError ? theme.danger : theme.accent;

  return (
    <Pressable onPress={onTap} accessibilityRole="button" accessibilityLabel="Sync now">
      <Animated.View
        style={[
          {
            flexDirection: 'row',
            alignItems: 'center',
            gap: Spacing.md,
            paddingHorizontal: Spacing.lg,
            paddingVertical: Spacing.md,
            borderRadius: Radius.pill,
            backgroundColor: theme.background.secondary,
            borderWidth: 1,
            borderColor: isError ? theme.danger : theme.glassBorder,
            overflow: 'hidden',
          },
          wrapperStyle,
        ]}
      >
        <Animated.View
          pointerEvents="none"
          style={[
            {
              position: 'absolute',
              left: 0,
              top: 0,
              bottom: 0,
              backgroundColor: accent,
              opacity: 0.18,
            },
            barStyle,
          ]}
        />
        {/* Scanning beam while syncing — pure transform animation, native driver. */}
        <Animated.View
          pointerEvents="none"
          style={[
            {
              position: 'absolute',
              top: 0,
              bottom: 0,
              width: 60,
              backgroundColor: accent,
              opacity: 0,
            },
            scanStyle,
          ]}
        />
        <View
          style={{
            width: 8,
            height: 8,
            borderRadius: 4,
            backgroundColor: isSyncing ? accent : isError ? theme.danger : theme.success,
          }}
        >
          <Animated.View
            pointerEvents="none"
            style={[
              {
                position: 'absolute',
                inset: -3,
                borderRadius: 7,
                backgroundColor: accent,
              },
              dotHaloStyle,
            ]}
          />
        </View>
        <View style={{ flex: 1, gap: 2 }}>
          <ThemedText variant="eyebrow" tone="tertiary">
            {isSyncing
              ? (phaseLabel[status.phase] ?? status.label)
              : isError
                ? phaseLabel.error
                : 'LAST SYNC · 上次同步'}
          </ThemedText>
          <ThemedText
            variant="titleMedium"
            tone={isError ? 'error' : 'primary'}
            style={tabularNums}
          >
            {isSyncing
              ? `${Math.round(status.progress * 100)}%`
              : isError
                ? (status.errorMessage ?? 'Failed')
                : formatRelative(status.lastSyncedAt)}
          </ThemedText>
        </View>
        <View
          style={{
            paddingHorizontal: Spacing.md,
            paddingVertical: 6,
            borderRadius: Radius.pill,
            backgroundColor: isError
              ? `${theme.danger}22`
              : isSyncing
                ? `${accent}22`
                : `${accent}1A`,
          }}
        >
          <ThemedText
            variant="caption"
            tone={isError ? 'error' : 'accent'}
            style={{ letterSpacing: 1 }}
          >
            {isSyncing ? 'SYNCING' : 'TAP TO SYNC'}
          </ThemedText>
        </View>
      </Animated.View>
    </Pressable>
  );
}
