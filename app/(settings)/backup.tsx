/*
 * mi-band-9-active — Settings › Backup & import.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Three jobs, all through HybridDataPort:
 *   1. Back up — native zip (samples + native prefs) + the MMKV snapshot,
 *      handed to the share sheet so the user picks Drive / Files / etc.
 *   2. Restore a Kodō backup — inspect first, confirm, then restore.
 *   3. Import Gadgetbridge — list Xiaomi devices in a GB export, import one
 *      device's history, and (if the export carried its auth key) offer to
 *      pair with it so the user never has to dig the key out again.
 * Reachable before pairing (from onboarding) as well as from Settings.
 */

import * as DocumentPicker from 'expo-document-picker';
import { router } from 'expo-router';
import * as Sharing from 'expo-sharing';
import { useEffect, useState } from 'react';
import { Alert, View } from 'react-native';

import { Radius, Spacing, tabularNums } from '@/constants/DesignSystem';
import { ThemedButton, ThemedText } from '@/components/themed';
import { SettingsPage } from '@/components/settings/SettingsPage';
import { SettingsDivider, SettingsItem, SettingsSectionCard } from '@/components/settings/SettingsKit';
import { useTheme } from '@/context/ThemeContext';
import { bandLink } from '@/libs/services/bandLink';
import { cache, cacheKeys } from '@/libs/services/cache';
import { dataPort } from '@/libs/services/dataPort';
import type { GadgetbridgeDevice, ImportSummary } from '@/libs/services/dataPort';
import { t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';

const fmtSize = (bytes: number) =>
  bytes > 1_048_576 ? `${(bytes / 1_048_576).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
const fmtDay = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString() : '—');

async function pickFile(): Promise<string | null> {
  // No copy: GB exports can be hundreds of MB; native reads content:// directly.
  const res = await DocumentPicker.getDocumentAsync({ copyToCacheDirectory: false, multiple: false, type: '*/*' });
  return res.canceled ? null : (res.assets[0]?.uri ?? null);
}

function ProgressBar({ value }: { readonly value: number }) {
  const { theme } = useTheme();
  return (
    <View style={{ height: 6, borderRadius: 3, backgroundColor: theme.background.tertiary, overflow: 'hidden' }}>
      <View style={{ width: `${Math.round(value * 100)}%`, height: 6, borderRadius: 3, backgroundColor: theme.accent }} />
    </View>
  );
}

export default function BackupSettings() {
  const [includeKey, setIncludeKey] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [lastBackup, setLastBackup] = useState<string | null>(null);
  const [gbUri, setGbUri] = useState<string | null>(null);
  const [gbDevices, setGbDevices] = useState<readonly GadgetbridgeDevice[] | null>(null);
  const [gbResult, setGbResult] = useState<{ device: GadgetbridgeDevice; summary: ImportSummary } | null>(null);
  const paired = bandLink.getPairedSync();

  useEffect(
    () =>
      // Progress only: a late event after completion must not resurrect the busy state.
      dataPort.onProgress((_phase, p) => setProgress(p)),
    [],
  );

  const run = async <T,>(label: string, fn: () => Promise<T>): Promise<T | null> => {
    setBusy(label);
    setProgress(0);
    try {
      return await fn();
    } catch (e) {
      void hapticsBridge.fire('error');
      Alert.alert(t('settings.backup.failed'), e instanceof Error ? e.message : String(e));
      return null;
    } finally {
      setBusy(null);
    }
  };

  const onBackup = async () => {
    const summary = await run(t('settings.backup.sharing'), () => dataPort.exportBackup(includeKey, cache.exportSnapshot()));
    if (!summary) return;
    setLastBackup(t('settings.backup.done', { size: fmtSize(summary.sizeBytes) }));
    void hapticsBridge.fire('success');
    if (await Sharing.isAvailableAsync()) {
      await Sharing.shareAsync(summary.uri, { mimeType: 'application/zip', dialogTitle: summary.fileName });
    }
  };

  const onRestore = async () => {
    const uri = await pickFile();
    if (!uri) return;
    const info = await run(t('settings.backup.restore'), () => dataPort.inspect(uri));
    if (!info) return;
    if (info.format !== 'kodo') {
      Alert.alert(t('settings.backup.notKodo'));
      return;
    }
    Alert.alert(
      t('settings.backup.confirmRestore'),
      t('settings.backup.confirmBody', {
        rows: info.rowCount,
        date: fmtDay(info.createdAt),
        band: info.bandName ? ` · ${info.bandName}` : '',
      }),
      [
        { text: t('settings.danger.cancel'), style: 'cancel' },
        {
          text: t('settings.backup.restore'),
          onPress: async () => {
            const res = await run(t('settings.backup.restore'), () => dataPort.restoreBackup(uri));
            if (!res) return;
            if (res.appPrefsJson) cache.importSnapshot(res.appPrefsJson);
            void hapticsBridge.fire('success');
            const rows =
              res.imported.activitySamples + res.imported.sleepSessions + res.imported.dailySummaries + res.imported.workouts;
            Alert.alert(t('settings.backup.restored', { rows }));
            if (res.restoredBand) {
              cache.set(cacheKeys.onboardingDone, true);
              router.replace('/(tabs)');
            }
          },
        },
      ],
    );
  };

  const onPickGb = async () => {
    const uri = await pickFile();
    if (!uri) return;
    setGbResult(null);
    const devices = await run(t('settings.backup.gb'), () => dataPort.inspectGadgetbridge(uri));
    if (!devices) return;
    setGbUri(uri);
    setGbDevices(devices);
  };

  const onImportGb = async (device: GadgetbridgeDevice) => {
    if (!gbUri) return;
    const summary = await run(t('settings.backup.gbImport'), () => dataPort.importGadgetbridge(gbUri, device.address));
    if (!summary) return;
    void hapticsBridge.fire('success');
    setGbResult({ device, summary });
  };

  const onPairWithKey = (device: GadgetbridgeDevice) => {
    if (!device.authKey) return;
    router.push({
      pathname: '/(onboarding)/key',
      params: { deviceId: device.address, name: device.name, authKey: device.authKey },
    });
  };

  const days = (s: ImportSummary) => {
    if (!s.firstDay || !s.lastDay) return 0;
    return Math.round((new Date(s.lastDay).getTime() - new Date(s.firstDay).getTime()) / 86_400_000) + 1;
  };

  return (
    <SettingsPage title={t('settings.nav.backup.title')}>
      {busy ? (
        <View style={{ gap: Spacing.xs }}>
          <ThemedText variant="caption" tone="secondary" style={tabularNums}>
            {busy} · {t('settings.backup.working', { pct: Math.round(progress * 100) })}
          </ThemedText>
          <ProgressBar value={progress} />
        </View>
      ) : null}

      <SettingsSectionCard title={t('settings.backup.create')} icon="database" description={t('settings.backup.createBody')}>
        <SettingsItem
          icon="shield"
          title={t('settings.backup.includeKey')}
          subtitle={includeKey ? t('settings.backup.includeKeyOn') : t('settings.backup.includeKeyOff')}
          trailing={{ kind: 'switch', value: includeKey, onChange: setIncludeKey }}
        />
        <View style={{ padding: Spacing.sm, gap: Spacing.sm }}>
          {lastBackup ? (
            <ThemedText variant="caption" tone="success">
              ✓ {lastBackup}
            </ThemedText>
          ) : null}
          <ThemedButton label={t('settings.backup.create')} fullWidth disabled={!!busy} onPress={() => void onBackup()} />
        </View>
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.backup.restore')} icon="sync" description={t('settings.backup.restoreBody')}>
        <View style={{ padding: Spacing.sm }}>
          <ThemedButton variant="secondary" label={t('settings.backup.restore')} fullWidth disabled={!!busy} onPress={() => void onRestore()} />
        </View>
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.backup.gb')} icon="band" description={t('settings.backup.gbBody')}>
        <View style={{ padding: Spacing.sm }}>
          <ThemedButton variant="secondary" label={t('settings.backup.gbPick')} fullWidth disabled={!!busy} onPress={() => void onPickGb()} />
        </View>
        {gbDevices != null && gbDevices.length === 0 ? (
          <SettingsItem icon="info" title={t('settings.backup.gbNone')} />
        ) : null}
        {(gbDevices ?? []).map((d, i) => {
          const done = gbResult?.device.address === d.address ? gbResult.summary : null;
          return (
            <View key={d.address}>
              {i > 0 ? <SettingsDivider /> : <SettingsDivider inset={false} />}
              <SettingsItem
                icon="band"
                title={d.name || d.address}
                subtitle={
                  done
                    ? t('settings.backup.gbImported', {
                        days: days(done),
                        samples: done.activitySamples,
                        nights: done.sleepSessions,
                        workouts: done.workouts,
                      })
                    : t('settings.backup.gbRange', {
                        from: fmtDay(d.firstSampleAt),
                        to: fmtDay(d.lastSampleAt),
                        count: d.sampleCount,
                      })
                }
                trailing={d.authKey ? { kind: 'value', value: `🔑 ${t('settings.backup.gbKeyFound')}` } : { kind: 'none' }}
              />
              <View style={{ flexDirection: 'row', gap: Spacing.sm, paddingHorizontal: Spacing.sm, paddingBottom: Spacing.sm }}>
                <View style={{ flex: 1 }}>
                  <ThemedButton size="sm" label={t('settings.backup.gbImport')} fullWidth disabled={!!busy} onPress={() => void onImportGb(d)} />
                </View>
                {d.authKey && !paired ? (
                  <View style={{ flex: 1 }}>
                    <ThemedButton size="sm" variant="secondary" label={t('settings.backup.gbPair')} fullWidth onPress={() => onPairWithKey(d)} />
                  </View>
                ) : null}
              </View>
            </View>
          );
        })}
      </SettingsSectionCard>
      <View style={{ height: Radius.sm }} />
    </SettingsPage>
  );
}
