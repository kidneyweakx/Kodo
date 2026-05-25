/*
 * mi-band-9-active — onboarding step 6/8: scan + select band.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Scan duration is capped (12s default) — long scans are a battery killer.
 */

import { router } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Pressable, View } from 'react-native';
import Animated, { FadeInUp } from 'react-native-reanimated';

import { Radius, Spacing } from '@/constants/DesignSystem';
import { ThemedButton, ThemedSurface, ThemedText } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { useTheme } from '@/context/ThemeContext';
import { bandLink } from '@/libs/services/bandLink';
import { t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';
import type { DiscoveredBand } from '@/modules/native';

type ScanPhase = 'idle' | 'scanning' | 'results' | 'error';

export default function ScanScreen() {
  const { theme } = useTheme();
  const [phase, setPhase] = useState<ScanPhase>('idle');
  const [found, setFound] = useState<readonly DiscoveredBand[]>([]);
  const [picked, setPicked] = useState<DiscoveredBand | null>(null);

  const startScan = useCallback(async () => {
    setPhase('scanning');
    setFound([]);
    setPicked(null);
    try {
      const results = await bandLink.scan(12_000);
      setFound(results);
      setPhase('results');
    } catch {
      setPhase('error');
    }
  }, []);

  useEffect(
    () => () => {
      try {
        bandLink.stopScan();
      } catch {
        // Native side may not be loaded in dev; ignore.
      }
    },
    [],
  );

  const onSelect = (band: DiscoveredBand) => {
    void hapticsBridge.fire('selection');
    setPicked(band);
  };

  const onContinue = () => {
    if (!picked) return;
    router.push({ pathname: '/(onboarding)/auth', params: { deviceId: picked.id, name: picked.name } });
  };

  const body =
    phase === 'idle' ? t('onboarding.scan.body')
      : phase === 'scanning' ? t('onboarding.scan.scanning')
        : phase === 'error' ? t('errors.notFound')
          : found.length === 0 ? t('onboarding.scan.empty')
            : t('onboarding.scan.body');

  return (
    <OnboardingScaffold
      stepIndex={5}
      totalSteps={7}
      eyebrow="STEP 6"
      title={t('onboarding.scan.title')}
      body={body}
      footer={
        <View style={{ flexDirection: 'row', gap: Spacing.md }}>
          <View style={{ flex: 1 }}>
            <ThemedButton
              variant="secondary"
              label={phase === 'scanning' ? t('onboarding.scan.scanning') : t('onboarding.scan.cta')}
              size="lg"
              fullWidth
              loading={phase === 'scanning'}
              onPress={startScan}
            />
          </View>
          <View style={{ flex: 1 }}>
            <ThemedButton
              label={t('onboarding.scan.select')}
              size="lg"
              fullWidth
              disabled={!picked}
              onPress={onContinue}
            />
          </View>
        </View>
      }
    >
      <View style={{ flex: 1, gap: Spacing.sm }}>
        {found.map((band, idx) => {
          const selected = picked?.id === band.id;
          return (
            <Animated.View key={band.id} entering={FadeInUp.delay(idx * 60).duration(280)}>
              <Pressable
                onPress={() => onSelect(band)}
                accessibilityRole="radio"
                accessibilityState={{ selected }}
                style={{
                  borderRadius: Radius.lg,
                  paddingVertical: Spacing.lg,
                  paddingHorizontal: Spacing.lg,
                  borderWidth: 1.5,
                  borderColor: selected ? theme.accent : theme.glassBorder,
                  backgroundColor: theme.background.secondary,
                  flexDirection: 'row',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                }}
              >
                <View style={{ flex: 1 }}>
                  <ThemedText variant="titleMedium">{band.name}</ThemedText>
                  <ThemedText variant="caption" tone="tertiary">
                    {band.id}
                  </ThemedText>
                </View>
                <ThemedText variant="caption" tone="secondary">
                  {band.rssi} dBm
                </ThemedText>
              </Pressable>
            </Animated.View>
          );
        })}
        {phase === 'results' && found.length === 0 ? (
          <ThemedSurface variant="outlined" padded="lg">
            <ThemedText variant="bodyMedium" tone="secondary">
              {t('onboarding.scan.empty')}
            </ThemedText>
          </ThemedSurface>
        ) : null}
      </View>
    </OnboardingScaffold>
  );
}
