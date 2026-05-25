/*
 * mi-band-9-active — onboarding step 7/8: paste auth key + pair.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { TextInput, View } from 'react-native';

import { Radius, Spacing } from '@/constants/DesignSystem';
import { ThemedButton, ThemedSurface, ThemedText } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { useTheme } from '@/context/ThemeContext';
import { bandLink } from '@/libs/services/bandLink';
import { cache, cacheKeys } from '@/libs/services/cache';
import { t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';

export default function AuthScreen() {
  const { theme } = useTheme();
  const params = useLocalSearchParams<{ deviceId: string; name: string }>();
  const [authKey, setAuthKey] = useState('');
  const [pairing, setPairing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const deviceId = String(params.deviceId ?? '');
  const name = String(params.name ?? '');

  const onPair = async () => {
    setPairing(true);
    setError(null);
    try {
      await bandLink.pair(deviceId, authKey.trim());
      cache.set(cacheKeys.onboardingDone, true);
      void hapticsBridge.fire('success');
      router.replace('/(onboarding)/done');
    } catch {
      void hapticsBridge.fire('error');
      setError(t('errors.authFailed'));
      setPairing(false);
    }
  };

  return (
    <OnboardingScaffold
      stepIndex={6}
      totalSteps={7}
      eyebrow="STEP 7"
      title={t('onboarding.auth.title')}
      body={t('onboarding.auth.body')}
      footer={
        <ThemedButton
          label={t('onboarding.auth.cta')}
          size="lg"
          fullWidth
          loading={pairing}
          disabled={authKey.trim().length < 8}
          onPress={onPair}
        />
      }
    >
      <View style={{ gap: Spacing.lg }}>
        <ThemedSurface variant="elevated" padded="lg">
          <ThemedText variant="caption" tone="tertiary" style={{ marginBottom: Spacing.xs }}>
            {name || 'Mi Band 9 Active'}
          </ThemedText>
          <ThemedText variant="bodyMedium" tone="secondary">
            {deviceId}
          </ThemedText>
        </ThemedSurface>

        <View
          style={{
            borderRadius: Radius.lg,
            borderWidth: 1.5,
            borderColor: error ? theme.danger : theme.glassBorder,
            paddingHorizontal: Spacing.lg,
            paddingVertical: Spacing.md,
            backgroundColor: theme.background.secondary,
          }}
        >
          <ThemedText variant="caption" tone="tertiary">
            {t('onboarding.auth.placeholder')}
          </ThemedText>
          <TextInput
            value={authKey}
            onChangeText={setAuthKey}
            autoCapitalize="none"
            autoCorrect={false}
            placeholder="0x…"
            placeholderTextColor={theme.text.tertiary}
            style={{
              color: theme.text.primary,
              fontFamily: 'Menlo',
              fontSize: 16,
              paddingVertical: Spacing.sm,
            }}
          />
        </View>

        {error ? (
          <ThemedText variant="bodyMedium" tone="error">
            {error}
          </ThemedText>
        ) : null}
      </View>
    </OnboardingScaffold>
  );
}
