/*
 * mi-band-9-active — notifications forwarding settings.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Visual layout:
 *   1. Title + status line
 *   2. Band preview card (live, theme-tinted "what the band shows")
 *   3. Mute-when-DnD large pressable surface
 *   4. App chip grid (monogram circles, gradient = enabled, ghost = disabled)
 */

import { ScrollView, Switch, View } from 'react-native';
import { useEffect, useMemo, useState } from 'react';
import Animated, { FadeInUp } from 'react-native-reanimated';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Spacing } from '@/constants/DesignSystem';
import { ThemedButton, ThemedSurface, ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';
import { notificationBridge, useNotificationAccess } from '@/libs/services/notificationBridge';
import { t } from '@/libs/services/i18n';
import type { NotificationFilter } from '@/libs/services/notificationBridge';
import { AppChipGrid } from '@/components/notifications/AppChipGrid';
import { BandPreviewCard } from '@/components/notifications/BandPreviewCard';

export default function NotificationsTab() {
  const { theme } = useTheme();
  const granted = useNotificationAccess();
  const [filters, setFilters] = useState<readonly NotificationFilter[]>(() => notificationBridge.getFilters());
  const [mute, setMute] = useState<boolean>(() => notificationBridge.getMuteWhenDndSync());

  useEffect(() => {
    setFilters(notificationBridge.getFilters());
  }, [granted]);

  const toggleApp = (sourceId: string, next: boolean) => {
    const existing = filters.find((f) => f.sourceId === sourceId);
    if (!existing) return;
    notificationBridge.setFilter({ ...existing, enabled: next });
    setFilters(notificationBridge.getFilters());
  };

  const toggleMute = (value: boolean) => {
    notificationBridge.setMuteWhenDnd(value);
    setMute(value);
  };

  const previewApp = useMemo(() => filters.find((f) => f.enabled) ?? filters[0], [filters]);

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: theme.background.primary }} edges={['top']}>
      <ScrollView
        contentContainerStyle={{ padding: Spacing.xl, gap: Spacing.xl, paddingBottom: Spacing.xxxl }}
        contentInsetAdjustmentBehavior="automatic"
      >
        <Animated.View entering={FadeInUp.duration(360)}>
          <ThemedText variant="caption" tone="accent" style={{ letterSpacing: 1.4 }}>
            NOTIFICATIONS · 通知
          </ThemedText>
          <ThemedText variant="headlineLarge" style={{ marginTop: Spacing.xs }}>
            {t('notifications.title')}
          </ThemedText>
          <ThemedText variant="bodyMedium" tone="secondary" style={{ marginTop: Spacing.xs }}>
            {granted ? '所有開啟的 App 都會即時轉到手環' : t('notifications.permissionBody')}
          </ThemedText>
        </Animated.View>

        {!granted ? (
          <Animated.View entering={FadeInUp.duration(420).delay(100)}>
            <ThemedSurface variant="elevated" padded="lg" style={{ gap: Spacing.md }}>
              <ThemedText variant="titleMedium" tone="warning">
                {t('notifications.permission')}
              </ThemedText>
              <ThemedButton
                label={t('onboarding.notifications.cta')}
                size="md"
                fullWidth
                onPress={() => notificationBridge.requestAccess()}
              />
            </ThemedSurface>
          </Animated.View>
        ) : null}

        <BandPreviewCard
          appName={previewApp?.appName ?? 'Messages'}
          title={previewApp ? `${previewApp.appName} · 樣本通知` : 'Sample notification'}
          body="這是預覽 — 手環上每則訊息會像這樣呈現。"
          when="now"
        />

        <Animated.View entering={FadeInUp.duration(440).delay(160)}>
          <ThemedSurface variant="card" padded="lg">
            <View
              style={{
                flexDirection: 'row',
                justifyContent: 'space-between',
                alignItems: 'center',
              }}
            >
              <View style={{ flex: 1, paddingRight: Spacing.md, gap: Spacing.xs }}>
                <ThemedText variant="titleMedium">{t('notifications.muteDnd')}</ThemedText>
                <ThemedText variant="bodyMedium" tone="secondary">
                  手機勿擾時手環也安靜
                </ThemedText>
              </View>
              <Switch
                value={mute}
                onValueChange={toggleMute}
                trackColor={{ true: theme.accent, false: theme.border }}
                thumbColor={theme.background.secondary}
              />
            </View>
          </ThemedSurface>
        </Animated.View>

        <View style={{ gap: Spacing.md }}>
          <ThemedText variant="caption" tone="tertiary" style={{ letterSpacing: 1.2 }}>
            ALLOW-LIST · 允許推送的 App
          </ThemedText>
          {filters.length === 0 ? (
            <ThemedSurface variant="outlined" padded="lg">
              <ThemedText variant="bodyMedium" tone="secondary">
                {granted
                  ? '尚未有 App 在允許名單中。允許通知存取後即可選擇。'
                  : t('common.unsynced')}
              </ThemedText>
            </ThemedSurface>
          ) : (
            <AppChipGrid chips={filters} onToggle={toggleApp} />
          )}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}
