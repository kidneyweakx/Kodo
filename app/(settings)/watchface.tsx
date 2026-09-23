/*
 * mi-band-9-active — Settings › Watch faces (XiaomiWatchfaceService).
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import * as DocumentPicker from 'expo-document-picker';
import { useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { Alert, View } from 'react-native';

import { Spacing } from '@/constants/DesignSystem';
import { ThemedButton } from '@/components/themed';
import { SettingsPage } from '@/components/settings/SettingsPage';
import { SettingsDivider, SettingsItem, SettingsSectionCard } from '@/components/settings/SettingsKit';
import { useConnectionState } from '@/libs/services/bandLink';
import { watchface } from '@/libs/services/watchface';
import { t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';
import type { WatchfaceInfo } from '@/modules/native';

export default function WatchfaceSettings() {
  const connected = useConnectionState() === 'connected';
  const [faces, setFaces] = useState<readonly WatchfaceInfo[]>(() => watchface.getCachedList());
  const [progress, setProgress] = useState<number | null>(null);

  const reload = useCallback(() => {
    if (!connected) return;
    void watchface.list().then(setFaces).catch(() => undefined);
  }, [connected]);

  useFocusEffect(reload);
  useEffect(() => watchface.onInstallProgress(setProgress), []);

  const fail = (e: unknown) => Alert.alert(t('settings.common.failed'), e instanceof Error ? e.message : String(e));

  const onInstall = async () => {
    const pick = await DocumentPicker.getDocumentAsync({ copyToCacheDirectory: true, multiple: false });
    const asset = pick.canceled ? null : pick.assets[0];
    if (!asset) return;
    setProgress(0);
    try {
      const info = await watchface.install(asset.uri);
      void hapticsBridge.fire('success');
      Alert.alert(t('settings.watchface.installed_ok', { name: info.name || asset.name }));
      reload();
    } catch (e) {
      fail(e);
    } finally {
      setProgress(null);
    }
  };

  const onRemove = (face: WatchfaceInfo) =>
    Alert.alert(t('settings.watchface.remove'), face.name, [
      { text: t('settings.danger.cancel'), style: 'cancel' },
      {
        text: t('settings.watchface.remove'),
        style: 'destructive',
        onPress: () => void watchface.remove(face.id).then(reload).catch(fail),
      },
    ]);

  return (
    <SettingsPage title={t('settings.nav.watchface.title')}>
      <SettingsSectionCard title={t('settings.watchface.installed')} icon="watchface">
        {faces.length === 0 ? <SettingsItem icon="watchface" title={t('settings.watchface.empty')} /> : null}
        {faces.map((f, i) => (
          <View key={f.id}>
            {i > 0 ? <SettingsDivider /> : null}
            <SettingsItem
              icon="watchface"
              title={f.name || f.id}
              subtitle={f.active ? t('settings.watchface.active') : undefined}
              disabled={!connected}
              trailing={f.active ? { kind: 'value', value: '✓' } : { kind: 'chevron', value: t('settings.watchface.setActive') }}
              onPress={f.active ? undefined : () => void watchface.setActive(f.id).then(reload).catch(fail)}
            />
            {f.canDelete && !f.active ? (
              <View style={{ paddingHorizontal: Spacing.sm, paddingBottom: Spacing.sm, alignItems: 'flex-end' }}>
                <ThemedButton size="sm" variant="ghost" label={t('settings.watchface.remove')} disabled={!connected} onPress={() => onRemove(f)} />
              </View>
            ) : null}
          </View>
        ))}
      </SettingsSectionCard>

      <ThemedButton
        label={progress != null ? t('settings.watchface.installing', { pct: Math.round(progress) }) : t('settings.watchface.install')}
        size="lg"
        fullWidth
        loading={progress != null}
        disabled={!connected || progress != null}
        onPress={() => void onInstall()}
      />
    </SettingsPage>
  );
}
