/*
 * mi-band-9-active — Notifications tab: access, forwarding, calls, music, allow-list.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Forwarding itself is fully native (MiBand9NotificationListener); this
 * screen only edits its preferences. Everything shown is real state: the
 * allow-list comes from apps that actually posted, now-playing comes from the
 * active Android media session.
 */

import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { ScrollView, View } from 'react-native';
import Animated, { FadeInUp } from 'react-native-reanimated';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Spacing } from '@/constants/DesignSystem';
import { ThemedButton, ThemedText } from '@/components/themed';
import { AppChipGrid } from '@/components/notifications/AppChipGrid';
import { SettingsDivider, SettingsItem, SettingsSectionCard } from '@/components/settings/SettingsKit';
import { useTheme } from '@/context/ThemeContext';
import { notificationBridge, useNotificationAccess } from '@/libs/services/notificationBridge';
import type { NotificationFilter } from '@/libs/services/notificationBridge';
import { musicBridge } from '@/libs/services/musicBridge';
import { permissions } from '@/libs/services/permissions';
import { t } from '@/libs/services/i18n';
import type { MusicNowPlaying } from '@/modules/native';

export default function NotificationsTab() {
  const { theme } = useTheme();
  const granted = useNotificationAccess();
  const [filters, setFilters] = useState<readonly NotificationFilter[]>(() => notificationBridge.getFilters());
  const [mute, setMute] = useState(() => notificationBridge.getMuteWhenDndSync());
  const [calls, setCalls] = useState(() => notificationBridge.getCallAlertsEnabledSync());
  const [callPerm, setCallPerm] = useState(true);
  const [nowPlaying, setNowPlaying] = useState<MusicNowPlaying | null>(() => musicBridge.getNowPlayingSync());

  // Tabs stay mounted, so refresh the cheap native snapshots on focus
  // (new apps may have posted, music may have changed) without a skeleton.
  useFocusEffect(
    useCallback(() => {
      setFilters(notificationBridge.getFilters());
      setNowPlaying(musicBridge.getNowPlayingSync());
      void permissions.hasCallAlerts().then(setCallPerm);
    }, []),
  );

  const toggleApp = (sourceId: string, next: boolean) => {
    const existing = filters.find((f) => f.sourceId === sourceId);
    if (!existing) return;
    notificationBridge.setFilter({ ...existing, enabled: next });
    setFilters(notificationBridge.getFilters());
  };

  const onMute = (v: boolean) => {
    notificationBridge.setMuteWhenDnd(v);
    setMute(v);
  };

  const onCalls = async (v: boolean) => {
    if (v && !callPerm) {
      const ok = await permissions.requestCallAlerts();
      setCallPerm(ok);
      notificationBridge.onCallPermissionsChanged();
    }
    notificationBridge.setCallAlertsEnabled(v);
    setCalls(v);
  };

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: theme.background.primary }} edges={['top']}>
      <ScrollView
        contentContainerStyle={{
          paddingHorizontal: Spacing.lg,
          paddingTop: Spacing.lg,
          paddingBottom: Spacing.xxxl + Spacing.xxl,
          gap: Spacing.lg,
        }}
        showsVerticalScrollIndicator={false}
      >
        <Animated.View entering={FadeInUp.duration(320)}>
          <ThemedText variant="headlineLarge">{t('notifications.title')}</ThemedText>
        </Animated.View>

        {!granted ? (
          <SettingsSectionCard title={t('notifications.permission')} icon="bell" description={t('notifications.permissionBody')}>
            <View style={{ paddingHorizontal: Spacing.sm, paddingBottom: Spacing.sm }}>
              <ThemedButton label={t('notifications.grant')} fullWidth onPress={() => notificationBridge.requestAccess()} />
            </View>
          </SettingsSectionCard>
        ) : null}

        <SettingsSectionCard title={t('notifications.forwarding')} icon="bell">
          <SettingsItem
            icon="moon"
            title={t('notifications.muteDnd')}
            subtitle={t('notifications.muteDndBody')}
            trailing={{ kind: 'switch', value: mute, onChange: onMute }}
            disabled={!granted}
          />
          <SettingsDivider />
          <SettingsItem
            icon="phone"
            title={t('notifications.calls')}
            subtitle={calls && !callPerm ? t('notifications.callsPermission') : t('notifications.callsBody')}
            trailing={{ kind: 'switch', value: calls, onChange: (v) => void onCalls(v) }}
          />
          <SettingsDivider />
          <SettingsItem
            icon="music"
            title={t('notifications.music')}
            subtitle={
              nowPlaying
                ? [nowPlaying.title, nowPlaying.artist].filter(Boolean).join(' · ') || nowPlaying.app
                : granted
                  ? t('notifications.musicIdle')
                  : t('notifications.musicBody')
            }
            trailing={nowPlaying ? { kind: 'value', value: nowPlaying.playing ? '▶' : '❚❚' } : { kind: 'none' }}
          />
        </SettingsSectionCard>

        <SettingsSectionCard title={t('notifications.apps')} icon="shield" description={t('notifications.appsBody')}>
          <View style={{ paddingHorizontal: Spacing.sm, paddingBottom: Spacing.sm }}>
            {filters.length === 0 ? (
              <ThemedText variant="bodyMedium" tone="secondary">
                {t('notifications.appsEmpty')}
              </ThemedText>
            ) : (
              <AppChipGrid chips={filters} onToggle={toggleApp} />
            )}
          </View>
        </SettingsSectionCard>
      </ScrollView>
    </SafeAreaView>
  );
}
