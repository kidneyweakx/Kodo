/*
 * mi-band-9-active — onboarding step 4/8: notification access.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Behaviour:
 *   - If NotificationListener access is already granted → auto-advance.
 *   - Otherwise CTA opens the OS settings (POST_NOTIFICATIONS dialog +
 *     Notification Listener picker). When the user returns with access
 *     granted, the `useNotificationAccess` polling effect detects it and
 *     advances automatically.
 *   - Skip is always available.
 */

import { router } from 'expo-router';
import { useEffect, useRef } from 'react';
import { View } from 'react-native';

import { Spacing } from '@/constants/DesignSystem';
import { ThemedButton } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { PermissionHero } from '@/components/onboarding/PermissionHero';
import { useNotificationAccess } from '@/libs/services/notificationBridge';
import { permissions } from '@/libs/services/permissions';
import { t } from '@/libs/services/i18n';

const NEXT = '/(onboarding)/battery' as const;

export default function NotificationsScreen() {
  const granted = useNotificationAccess();
  const advanced = useRef(false);

  useEffect(() => {
    if (granted && !advanced.current) {
      advanced.current = true;
      router.replace(NEXT);
    }
  }, [granted]);

  const onContinue = () => {
    if (advanced.current) return;
    advanced.current = true;
    router.replace(NEXT);
  };

  const onRequest = async () => {
    await permissions.requestPostNotifications();
    await permissions.openNotificationListenerSettings();
  };

  return (
    <OnboardingScaffold
      stepIndex={3}
      totalSteps={7}
      eyebrow="STEP 4"
      title={t('onboarding.notifications.title')}
      body={t('onboarding.notifications.body')}
      footer={
        <View style={{ flexDirection: 'row', gap: Spacing.md }}>
          <View style={{ flex: 1 }}>
            <ThemedButton variant="ghost" label={t('common.skip')} size="lg" fullWidth onPress={onContinue} />
          </View>
          <View style={{ flex: 1 }}>
            <ThemedButton
              label={granted ? t('common.continue') : t('onboarding.notifications.cta')}
              size="lg"
              fullWidth
              onPress={granted ? onContinue : onRequest}
            />
          </View>
        </View>
      }
    >
      <PermissionHero icon="notifications" />
    </OnboardingScaffold>
  );
}
