/*
 * mi-band-9-active — Settings › Sync & data.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Background sync cadence (WorkManager, ≥ 30 min per docs/POWER.md), Health
 * Connect sharing, battery-optimisation state and local data. Every status
 * line is read from the OS / native store; nothing is assumed.
 */

import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { ActivityIndicator, Alert, View } from 'react-native';

import { Spacing } from '@/constants/DesignSystem';
import { ThemedButton } from '@/components/themed';
import { SettingsPage } from '@/components/settings/SettingsPage';
import {
  SettingsDivider,
  SettingsItem,
  SettingsSectionCard,
  SettingsSlider,
} from '@/components/settings/SettingsKit';
import { useTheme } from '@/context/ThemeContext';
import { bandLink } from '@/libs/services/bandLink';
import { cache, cacheKeys } from '@/libs/services/cache';
import { healthStore, localDateIso, useLastSampleAt } from '@/libs/services/healthStore';
import { permissions } from '@/libs/services/permissions';
import { t } from '@/libs/services/i18n';
import { NativeHealthConnect, NativeSystemControl } from '@/modules/native';
import type { HealthConnectKind, HealthConnectStatus } from '@/modules/native';
import { safeCall } from '@/modules/native/safe';

const INTERVALS = [30, 60, 120, 240] as const;
const ALL_KINDS: readonly HealthConnectKind[] = [
  'steps',
  'heartRate',
  'restingHeartRate',
  'spo2',
  'sleep',
  'activeCalories',
  'distance',
  'exercise',
];

const fmtInterval = (m: number) => (m < 60 ? `${m}m` : `${m / 60}h`);

export default function SyncSettings() {
  const { theme } = useTheme();
  const [interval, setIntervalMin] = useState<number>(() => cache.getSync<number>(cacheKeys.autoSyncIntervalMin) ?? 30);
  const [hc, setHc] = useState<HealthConnectStatus | null>(null);
  const [hcBusy, setHcBusy] = useState(false);
  const [exported, setExported] = useState<number | null>(null);
  const [unrestricted, setUnrestricted] = useState(() =>
    safeCall(() => NativeSystemControl().isIgnoringBatteryOptimizations(), false),
  );
  const lastSample = useLastSampleAt();

  useFocusEffect(
    useCallback(() => {
      void NativeHealthConnect().status().then(setHc).catch(() => undefined);
      setUnrestricted(safeCall(() => NativeSystemControl().isIgnoringBatteryOptimizations(), false));
    }, []),
  );

  const auto = interval > 0;
  const applyInterval = (min: number) => {
    setIntervalMin(min);
    cache.set(cacheKeys.autoSyncIntervalMin, min);
    bandLink.setPeriodicSync(min > 0, Math.max(30, min));
  };

  const onConnectHc = async () => {
    setHcBusy(true);
    try {
      setHc(await NativeHealthConnect().requestPermissions(ALL_KINDS));
    } catch (e) {
      Alert.alert(t('settings.sync.hc'), e instanceof Error ? e.message : String(e));
    } finally {
      setHcBusy(false);
    }
  };

  const onExport = async () => {
    setHcBusy(true);
    try {
      const to = localDateIso();
      const from = localDateIso(new Date(Date.now() - 6 * 86_400_000));
      setExported(await NativeHealthConnect().exportRange(from, to));
    } catch (e) {
      Alert.alert(t('settings.sync.hc'), e instanceof Error ? e.message : String(e));
    } finally {
      setHcBusy(false);
    }
  };

  const onClear = () =>
    Alert.alert(t('settings.sync.clear'), t('settings.sync.clearBody'), [
      { text: t('settings.danger.cancel'), style: 'cancel' },
      { text: t('settings.sync.clear'), style: 'destructive', onPress: () => healthStore.clearAll() },
    ]);

  const granted = hc?.grantedKinds.length ?? 0;
  const hcLine =
    hc == null
      ? '…'
      : hc.availability === 'updateRequired'
        ? t('settings.sync.hcStatus.updateRequired')
        : hc.availability === 'unavailable'
          ? t('settings.sync.hcStatus.unavailable')
          : granted > 0
            ? t('settings.sync.hcStatus.available', { count: granted })
            : t('settings.sync.hcStatus.none');

  return (
    <SettingsPage title={t('settings.nav.sync.title')}>
      <SettingsSectionCard title={t('settings.sync.background')} icon="sync">
        <SettingsItem
          icon="sync"
          title={t('settings.sync.auto')}
          subtitle={t('settings.sync.autoBody')}
          trailing={{ kind: 'switch', value: auto, onChange: (v) => applyInterval(v ? 30 : 0) }}
        />
        {auto ? (
          <>
            <SettingsDivider />
            <SettingsItem
              icon="clock"
              title={t('settings.sync.interval')}
              below={<SettingsSlider stops={INTERVALS} value={interval} onChange={applyInterval} format={fmtInterval} />}
            />
          </>
        ) : null}
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.sync.hc')} icon="heart" description={t('settings.sync.hcBody')}>
        <SettingsItem
          icon="shield"
          title={t('settings.sync.hc')}
          subtitle={exported != null ? t('settings.sync.hcExported', { count: exported }) : hcLine}
          trailing={
            hcBusy
              ? { kind: 'custom', node: <ActivityIndicator size="small" color={theme.accent} /> }
              : { kind: 'none' }
          }
        />
        {hc && hc.availability !== 'unavailable' ? (
          <View style={{ flexDirection: 'row', gap: Spacing.sm, paddingHorizontal: Spacing.sm, paddingBottom: Spacing.sm }}>
            <View style={{ flex: 1 }}>
              <ThemedButton
                size="sm"
                variant={granted > 0 ? 'secondary' : 'primary'}
                label={granted > 0 ? t('settings.sync.hcManage') : t('settings.sync.hcConnect')}
                fullWidth
                disabled={hcBusy}
                onPress={onConnectHc}
              />
            </View>
            {granted > 0 ? (
              <View style={{ flex: 1 }}>
                <ThemedButton
                  size="sm"
                  variant="secondary"
                  label={t('settings.sync.hcExport')}
                  fullWidth
                  disabled={hcBusy}
                  onPress={onExport}
                />
              </View>
            ) : null}
          </View>
        ) : null}
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.sync.power')} icon="battery">
        <SettingsItem
          icon="battery"
          title={t('settings.sync.power')}
          subtitle={unrestricted ? t('settings.sync.powerOn') : t('settings.sync.powerOff')}
          trailing={unrestricted ? { kind: 'value', value: '✓' } : { kind: 'chevron' }}
          onPress={unrestricted ? undefined : () => void permissions.openBatteryOptimizationSettings()}
        />
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.sync.data')} icon="database">
        <SettingsItem
          icon="clock"
          title={t('settings.sync.lastSample')}
          trailing={{
            kind: 'value',
            value: lastSample ? new Date(lastSample).toLocaleString() : t('settings.sync.noSample'),
          }}
        />
        <SettingsDivider />
        <SettingsItem
          icon="trash"
          tone="danger"
          title={t('settings.sync.clear')}
          subtitle={t('settings.sync.clearBody')}
          onPress={onClear}
        />
      </SettingsSectionCard>
    </SettingsPage>
  );
}
