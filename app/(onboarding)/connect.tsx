/*
 * mi-band-9-active — onboarding 2/4: Bluetooth permission + live band scan.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * One screen replaces the old bluetooth → scan pair: if permissions are
 * already granted the scan starts on mount, results stream in through
 * `onScanResult`, and the first band is pre-selected so the common case is a
 * single tap on "Use this band".
 */

import { router } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Linking, Pressable, View } from 'react-native';
import Animated, { FadeInUp } from 'react-native-reanimated';

import { Radius, Spacing, tabularNums } from '@/constants/DesignSystem';
import { ThemedButton, ThemedSurface, ThemedText } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { IconTile } from '@/components/settings/SettingsKit';
import { useTheme } from '@/context/ThemeContext';
import { bandLink } from '@/libs/services/bandLink';
import { permissions } from '@/libs/services/permissions';
import { t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';
import { NativeBandLink } from '@/modules/native';
import { safeUnsubscribe } from '@/modules/native/safe';
import type { DiscoveredBand } from '@/modules/native';

type Phase = 'checking' | 'needsPermission' | 'denied' | 'scanning' | 'done';

const SCAN_MS = 12_000;

const signalLabel = (band: DiscoveredBand): string => {
  // rssi 0 = pre-populated from Android's bonded list, not a live advert.
  if (band.rssi === 0) return t('onboarding.connect.bonded');
  if (band.rssi > -65) return t('onboarding.connect.signal.strong');
  if (band.rssi > -80) return t('onboarding.connect.signal.ok');
  return t('onboarding.connect.signal.weak');
};

export default function ConnectScreen() {
  const { theme } = useTheme();
  const [phase, setPhase] = useState<Phase>('checking');
  const [found, setFound] = useState<readonly DiscoveredBand[]>([]);
  const [picked, setPicked] = useState<string | null>(null);
  const scanning = useRef(false);

  const addBand = useCallback((band: DiscoveredBand) => {
    setFound((prev) => (prev.some((b) => b.id === band.id) ? prev : [...prev, band]));
    setPicked((prev) => prev ?? band.id);
  }, []);

  const runScan = useCallback(async () => {
    if (scanning.current) return;
    scanning.current = true;
    setPhase('scanning');
    try {
      const results = await bandLink.scan(SCAN_MS);
      results.forEach(addBand);
    } catch (e) {
      console.warn('[connect] scan failed', e);
    } finally {
      scanning.current = false;
      setPhase('done');
    }
  }, [addBand]);

  const requestAndScan = useCallback(async () => {
    const readiness = await permissions.requestBluetooth();
    if (!readiness.permissionsGranted) {
      setPhase('denied');
      return;
    }
    await permissions.promptEnableBluetooth();
    void runScan();
  }, [runScan]);

  // Stream results as they arrive instead of waiting the full 12s.
  useEffect(() => safeUnsubscribe(() => NativeBandLink().onScanResult(addBand)), [addBand]);

  useEffect(() => {
    let cancelled = false;
    void permissions.hasBluetoothPermissions().then((granted) => {
      if (cancelled) return;
      if (granted) void runScan();
      else setPhase('needsPermission');
    });
    return () => {
      cancelled = true;
      bandLink.stopScan();
    };
  }, [runScan]);

  const onNext = () => {
    const band = found.find((b) => b.id === picked);
    if (!band) return;
    bandLink.stopScan();
    router.push({ pathname: '/(onboarding)/key', params: { deviceId: band.id, name: band.name } });
  };

  const needsAction = phase === 'needsPermission' || phase === 'denied';
  const empty = phase === 'done' && found.length === 0;

  return (
    <OnboardingScaffold
      stepIndex={1}
      totalSteps={4}
      title={t('onboarding.connect.title')}
      body={t('onboarding.connect.body')}
      footer={
        needsAction ? (
          <ThemedButton label={t('onboarding.connect.cta')} size="lg" fullWidth onPress={requestAndScan} />
        ) : (
          <View style={{ flexDirection: 'row', gap: Spacing.md }}>
            <View style={{ flex: 1 }}>
              <ThemedButton
                variant="secondary"
                label={t('onboarding.connect.rescan')}
                size="lg"
                fullWidth
                disabled={phase === 'scanning' || phase === 'checking'}
                onPress={runScan}
              />
            </View>
            <View style={{ flex: 1 }}>
              <ThemedButton
                label={t('onboarding.connect.next')}
                size="lg"
                fullWidth
                disabled={!picked}
                onPress={onNext}
              />
            </View>
          </View>
        )
      }
    >
      <View style={{ flex: 1, gap: Spacing.sm }}>
        {phase === 'scanning' || phase === 'checking' ? (
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: Spacing.sm, marginBottom: Spacing.xs }}>
            <ActivityIndicator size="small" color={theme.accent} />
            <ThemedText variant="caption" tone="secondary">
              {t('onboarding.connect.searching')}
            </ThemedText>
          </View>
        ) : found.length > 0 ? (
          <ThemedText variant="caption" tone="secondary" style={{ marginBottom: Spacing.xs }}>
            {t('onboarding.connect.found', { count: found.length })}
          </ThemedText>
        ) : null}

        {found.map((band, idx) => {
          const selected = picked === band.id;
          return (
            <Animated.View key={band.id} entering={FadeInUp.delay(idx * 60).duration(280)}>
              <Pressable
                onPress={() => {
                  void hapticsBridge.fire('selection');
                  setPicked(band.id);
                }}
                accessibilityRole="radio"
                accessibilityState={{ selected }}
                style={{
                  borderRadius: Radius.lg,
                  padding: Spacing.md,
                  borderWidth: 1.5,
                  borderColor: selected ? theme.accent : theme.border,
                  backgroundColor: theme.background.secondary,
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: Spacing.md,
                }}
              >
                <IconTile name="band" tone={selected ? 'accent' : 'muted'} />
                <View style={{ flex: 1 }}>
                  <ThemedText variant="titleMedium">{band.name}</ThemedText>
                  <ThemedText variant="caption" tone="tertiary" style={tabularNums}>
                    {band.id} · {signalLabel(band)}
                  </ThemedText>
                </View>
              </Pressable>
            </Animated.View>
          );
        })}

        {phase === 'denied' ? (
          <ThemedSurface variant="outlined" padded="lg" style={{ gap: Spacing.sm }}>
            <ThemedText variant="bodyMedium" tone="error">
              {t('onboarding.connect.permissionDenied')}
            </ThemedText>
            <Pressable onPress={() => void Linking.openSettings()} hitSlop={8}>
              <ThemedText variant="caption" tone="accent">
                {t('onboarding.connect.openSettings')} →
              </ThemedText>
            </Pressable>
          </ThemedSurface>
        ) : null}

        {empty ? (
          <ThemedSurface variant="outlined" padded="lg" style={{ gap: Spacing.sm }}>
            <ThemedText variant="titleMedium">{t('onboarding.connect.empty.title')}</ThemedText>
            <ThemedText variant="bodyMedium" tone="secondary">
              {t('onboarding.connect.empty.tips')}
            </ThemedText>
            <Pressable onPress={() => void permissions.openLocationServicesSettings()} hitSlop={8}>
              <ThemedText variant="caption" tone="accent">
                {t('onboarding.connect.openLocation')} →
              </ThemedText>
            </Pressable>
          </ThemedSurface>
        ) : null}
      </View>
    </OnboardingScaffold>
  );
}
