/*
 * mi-band-9-active — onboarding step 4/8: notification access.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { router } from 'expo-router';
import { View } from 'react-native';

import { Spacing } from '@/constants/DesignSystem';
import { ThemedButton } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { PermissionHero } from '@/components/onboarding/PermissionHero';
import { notificationBridge, useNotificationAccess } from '@/libs/services/notificationBridge';
import { t } from '@/libs/services/i18n';

export default function NotificationsScreen() {
  const granted = useNotificationAccess();

  const onRequest = () => {
    try {
      notificationBridge.requestAccess();
    } catch {
      // Pre-prebuild: Nitro module not loaded yet. Allow the flow to advance.
    }
  };

  const onContinue = () => router.push('/(onboarding)/battery');

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
