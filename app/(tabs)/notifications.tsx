/*
 * mi-band-9-active — notifications forwarding settings (allow-list + DnD mirror).
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { ScrollView, Switch, View } from 'react-native';
import { useEffect, useState } from 'react';
import Animated, { FadeInUp } from 'react-native-reanimated';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Spacing } from '@/constants/DesignSystem';
import { ThemedButton, ThemedSurface, ThemedText } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';
import { notificationBridge, useNotificationAccess } from '@/libs/services/notificationBridge';
import { t } from '@/libs/services/i18n';
import type { NotificationFilter } from '@/libs/services/notificationBridge';

export default function NotificationsTab() {
  const { theme } = useTheme();
  const granted = useNotificationAccess();
  const [filters, setFilters] = useState<readonly NotificationFilter[]>(() => notificationBridge.getFilters());
  const [mute, setMute] = useState<boolean>(() => notificationBridge.getMuteWhenDndSync());

  useEffect(() => {
    setFilters(notificationBridge.getFilters());
  }, [granted]);

  const toggleApp = (filter: NotificationFilter) => {
    const next = { ...filter, enabled: !filter.enabled };
    notificationBridge.setFilter(next);
    setFilters(notificationBridge.getFilters());
  };

  const toggleMute = (value: boolean) => {
    notificationBridge.setMuteWhenDnd(value);
    setMute(value);
  };

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: theme.background.primary }} edges={['top']}>
      <ScrollView
        contentContainerStyle={{ padding: Spacing.xl, gap: Spacing.lg, paddingBottom: Spacing.xxxl }}
        contentInsetAdjustmentBehavior="automatic"
      >
        <Animated.View entering={FadeInUp.duration(360)}>
          <ThemedText variant="caption" tone="accent">
            NOTIFICATIONS
          </ThemedText>
          <ThemedText variant="headlineLarge" style={{ marginTop: Spacing.xs }}>
            {t('notifications.title')}
          </ThemedText>
        </Animated.View>

        {!granted ? (
          <ThemedSurface variant="elevated" padded="lg" style={{ gap: Spacing.md }}>
            <ThemedText variant="titleMedium" tone="warning">
              {t('notifications.permission')}
            </ThemedText>
            <ThemedText variant="bodyMedium" tone="secondary">
              {t('notifications.permissionBody')}
            </ThemedText>
            <ThemedButton
              label={t('onboarding.notifications.cta')}
              size="md"
              fullWidth
              onPress={() => notificationBridge.requestAccess()}
            />
          </ThemedSurface>
        ) : null}

        <ThemedSurface variant="card" padded="lg">
          <View
            style={{
              flexDirection: 'row',
              justifyContent: 'space-between',
              alignItems: 'center',
            }}
          >
            <View style={{ flex: 1, paddingRight: Spacing.md }}>
              <ThemedText variant="titleMedium">{t('notifications.muteDnd')}</ThemedText>
              <ThemedText variant="bodyMedium" tone="secondary">
                Sync the phone's Do Not Disturb to the band.
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

        <ThemedText variant="titleMedium">{t('notifications.apps')}</ThemedText>
        {filters.length === 0 ? (
          <ThemedSurface variant="outlined" padded="lg">
            <ThemedText variant="bodyMedium" tone="secondary">
              {t('common.unsynced')}
            </ThemedText>
          </ThemedSurface>
        ) : (
          filters.map((filter) => (
            <ThemedSurface key={filter.sourceId} variant="card" padded="md">
              <View
                style={{
                  flexDirection: 'row',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                }}
              >
                <View style={{ flex: 1 }}>
                  <ThemedText variant="titleMedium">{filter.appName}</ThemedText>
                  <ThemedText variant="caption" tone="tertiary">
                    {filter.sourceId}
                  </ThemedText>
                </View>
                <Switch
                  value={filter.enabled}
                  onValueChange={() => toggleApp(filter)}
                  trackColor={{ true: theme.accent, false: theme.border }}
                  thumbColor={theme.background.secondary}
                />
              </View>
            </ThemedSurface>
          ))
        )}
      </ScrollView>
    </SafeAreaView>
  );
}
