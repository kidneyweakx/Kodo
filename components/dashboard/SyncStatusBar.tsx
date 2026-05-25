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
import { Pressable, View } from 'react-native';
import Animated, {
  Easing,
  interpolate,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';

import { Motion, Radius, Spacing } from '@/constants/DesignSystem';
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

  const barStyle = useAnimatedStyle(() => ({
    width: `${interpolate(progress.value, [0, 1], [0, 100])}%`,
  }));

  const wrapperStyle = useAnimatedStyle(() => ({
    opacity: visible.value,
  }));

  const onTap = async () => {
    void hapticsBridge.fire('tap');
    try {
      const since = new Date(Date.now() - 86_400_000).toISOString();
      await bandLink.syncSince(since);
    } catch {
      // Native side may not be loaded — ignore in dev.
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
        <View
          style={{
            width: 8,
            height: 8,
            borderRadius: 4,
            backgroundColor: isSyncing ? accent : isError ? theme.danger : theme.success,
          }}
        />
        <View style={{ flex: 1 }}>
          <ThemedText variant="caption" tone="secondary" style={{ letterSpacing: 1 }}>
            {isSyncing
              ? phaseLabel[status.phase] ?? status.label
              : isError
                ? `${phaseLabel.error} · ${status.errorMessage ?? ''}`
                : `LAST SYNC · ${formatRelative(status.lastSyncedAt).toUpperCase()}`}
          </ThemedText>
        </View>
        <ThemedText variant="caption" tone={isError ? 'error' : 'accent'}>
          {isSyncing ? `${Math.round(status.progress * 100)}%` : 'TAP TO SYNC'}
        </ThemedText>
      </Animated.View>
    </Pressable>
  );
}
