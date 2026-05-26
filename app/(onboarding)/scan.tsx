/*
 * mi-band-9-active — onboarding step 6/8: scan + select band.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Defensive re-check of the same prerequisites Gadgetbridge's DiscoveryActivityV2
 * verifies before each scan: BT permissions held, adapter on, location services
 * on. The user may have toggled any of these off between steps.
 *
 * Scan duration is capped (12s default) — long scans are a battery killer.
 */

import { router } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Alert, Pressable, View } from 'react-native';
import Animated, { FadeInUp } from 'react-native-reanimated';

import { Radius, Spacing } from '@/constants/DesignSystem';
import { ThemedButton, ThemedSurface, ThemedText } from '@/components/themed';
import { OnboardingScaffold } from '@/components/onboarding/OnboardingScaffold';
import { useTheme } from '@/context/ThemeContext';
import { bandLink } from '@/libs/services/bandLink';
import { permissions } from '@/libs/services/permissions';
import { t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';
import type { DiscoveredBand } from '@/modules/native';

type ScanPhase = 'idle' | 'scanning' | 'results' | 'error';
type ScanError = 'permission' | 'native-missing' | 'unknown';

export default function ScanScreen() {
  const { theme } = useTheme();
  const [phase, setPhase] = useState<ScanPhase>('idle');
  const [reason, setReason] = useState<ScanError | null>(null);
  const [found, setFound] = useState<readonly DiscoveredBand[]>([]);
  const [picked, setPicked] = useState<DiscoveredBand | null>(null);

  const startScan = useCallback(async () => {
    console.log('[scan.tsx] startScan invoked');
    setPhase('scanning');
    setReason(null);
    setFound([]);
    setPicked(null);

    const granted = await permissions.hasBluetoothPermissions();
    console.log('[scan.tsx] hasBluetoothPermissions =', granted);
    if (!granted) {
      const result = await permissions.requestBluetooth();
      console.log('[scan.tsx] requestBluetooth result =', result);
      if (!result.permissionsGranted) {
        setPhase('error');
        setReason('permission');
        Alert.alert(
          'Permission',
          '請允許 Bluetooth + Location 權限後再試一次。\nGrant Bluetooth + Location permissions, then retry.',
        );
        return;
      }
    }
    await permissions.promptEnableBluetooth();

    try {
      const t0 = Date.now();
      console.log('[scan.tsx] calling bandLink.scan(12000)…');
      const results = await bandLink.scan(12_000);
      console.log(
        `[scan.tsx] scan returned in ${Date.now() - t0}ms, found=${results.length}`,
        results,
      );
      setFound(results);
      setPhase('results');
    } catch (e) {
      console.warn('[scan.tsx] scan threw:', e);
      setPhase('error');
      const code = (e as Error & { code?: string }).code;
      if (code === 'NATIVE_NOT_IMPLEMENTED') {
        setReason('native-missing');
      } else {
        setReason('unknown');
      }
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

  const onOpenLocationSettings = async () => {
    await permissions.openLocationServicesSettings();
  };

  const body =
    phase === 'idle' ? t('onboarding.scan.body')
      : phase === 'scanning' ? t('onboarding.scan.scanning')
        : phase === 'error' && reason === 'permission' ? '缺少 Bluetooth/Location 權限 — Missing permissions'
          : phase === 'error' && reason === 'native-missing' ? '尚未實作原生掃描層 (HybridBandLink.kt) — Native scan layer not yet implemented'
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
          <ThemedSurface variant="outlined" padded="lg" style={{ gap: Spacing.sm }}>
            <ThemedText variant="bodyMedium" tone="secondary">
              {t('onboarding.scan.empty')}
            </ThemedText>
            <ThemedText variant="caption" tone="tertiary">
              可能原因 / Possible causes:{'\n'}
              1. 位置服務(GPS) 未在系統層開啟 (Android 6+ BLE 必要){'\n'}
              2. 手環已被 Gadgetbridge / Mi Fitness 配對連線中 — 配對中的 BLE 裝置不會廣播,請先在那邊解除配對{'\n'}
              3. 手環距離過遠或螢幕已休眠
            </ThemedText>
            <Pressable onPress={onOpenLocationSettings}>
              <ThemedText variant="caption" tone="accent">
                開啟位置服務設定 / Open location services →
              </ThemedText>
            </Pressable>
          </ThemedSurface>
        ) : null}
        {phase === 'error' && reason === 'native-missing' ? (
          <ThemedSurface variant="outlined" padded="lg" style={{ gap: Spacing.sm }}>
            <ThemedText variant="caption" tone="error">
              HybridBandLink Kotlin 尚未實作
            </ThemedText>
            <ThemedText variant="bodyMedium" tone="secondary">
              權限/設定都已就緒,但 modules/native/bandLink 的 Kotlin 端尚未生成。
              請執行 `bun nitrogen` 並補上 android/.../HybridBandLink.kt。
            </ThemedText>
          </ThemedSurface>
        ) : null}
      </View>
    </OnboardingScaffold>
  );
}
