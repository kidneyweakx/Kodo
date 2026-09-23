/*
 * mi-band-9-active — onboarding 4/4: optional permissions checklist.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Replaces three separate permission screens with one checklist. Every row
 * shows the real OS state (re-read whenever the app returns to foreground,
 * since each action bounces out to a system settings page) and nothing here
 * blocks finishing — the band is already paired by the time we get here.
 */

import { router } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { AppState, View } from 'react-native';

import { ThemedButton } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { SettingsDivider, SettingsItem, SettingsSectionCard } from '@/components/settings/SettingsKit';
import { Spacing } from '@/constants/DesignSystem';
import { bandLink } from '@/libs/services/bandLink';
import { cache, cacheKeys } from '@/libs/services/cache';
import { notificationBridge } from '@/libs/services/notificationBridge';
import { permissions } from '@/libs/services/permissions';
import { t } from '@/libs/services/i18n';
import { NativeSystemControl } from '@/modules/native';
import { safeCall } from '@/modules/native/safe';

interface Status {
  readonly listener: boolean;
  readonly post: boolean;
  readonly battery: boolean;
}

const readStatus = async (): Promise<Status> => ({
  listener: notificationBridge.isAccessGrantedSync(),
  post: await permissions.hasPostNotifications(),
  battery: safeCall(() => NativeSystemControl().isIgnoringBatteryOptimizations(), false),
});

export default function ExtrasScreen() {
  const [status, setStatus] = useState<Status | null>(null);

  const refresh = useCallback(() => {
    void readStatus().then(setStatus);
  }, []);

  useEffect(() => {
    refresh();
    const sub = AppState.addEventListener('change', (s) => {
      if (s === 'active') refresh();
    });
    return () => sub.remove();
  }, [refresh]);

  const onFinish = () => {
    cache.set(cacheKeys.onboardingDone, true);
    bandLink.setPeriodicSync(true, 30);
    // First sync runs in the background; the dashboard shows its progress.
    void bandLink.syncSince(new Date(Date.now() - 7 * 86_400_000).toISOString()).catch(() => undefined);
    router.replace('/(tabs)');
  };

  const trailing = (on: boolean | undefined) =>
    on
      ? ({ kind: 'value', value: `✓ ${t('onboarding.extras.on')}` } as const)
      : ({ kind: 'chevron', value: t('onboarding.extras.enable') } as const);

  return (
    <OnboardingScaffold
      stepIndex={3}
      totalSteps={4}
      title={t('onboarding.extras.title')}
      body={t('onboarding.extras.body')}
      footer={<ThemedButton label={t('onboarding.extras.cta')} size="lg" fullWidth haptic="success" onPress={onFinish} />}
    >
      <View style={{ gap: Spacing.md }}>
        <SettingsSectionCard title={t('onboarding.extras.title')} icon="shield">
          <SettingsItem
            icon="bell"
            title={t('onboarding.extras.notif.title')}
            subtitle={t('onboarding.extras.notif.body')}
            trailing={trailing(status?.listener)}
            onPress={status?.listener ? undefined : () => void permissions.openNotificationListenerSettings()}
          />
          <SettingsDivider />
          <SettingsItem
            icon="phone"
            title={t('onboarding.extras.post.title')}
            subtitle={t('onboarding.extras.post.body')}
            trailing={trailing(status?.post)}
            onPress={
              status?.post ? undefined : () => void permissions.requestPostNotifications().then(refresh)
            }
          />
          <SettingsDivider />
          <SettingsItem
            icon="battery"
            title={t('onboarding.extras.battery.title')}
            subtitle={t('onboarding.extras.battery.body')}
            trailing={trailing(status?.battery)}
            onPress={status?.battery ? undefined : () => void permissions.openBatteryOptimizationSettings()}
          />
        </SettingsSectionCard>
      </View>
    </OnboardingScaffold>
  );
}
