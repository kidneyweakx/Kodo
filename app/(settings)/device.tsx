/*
 * mi-band-9-active — Settings › Device & display.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Every value is what the band reported (or the user's pending change);
 * `undefined` renders as "connect to read", never as a default.
 */

import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';

import { SettingsPage } from '@/components/settings/SettingsPage';
import {
  SettingsChoice,
  SettingsDivider,
  SettingsItem,
  SettingsSectionCard,
} from '@/components/settings/SettingsKit';
import { useConnectionState } from '@/libs/services/bandLink';
import { system } from '@/libs/services/system';
import { t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';
import type { BandDeviceInfo, BandSystemSettings } from '@/modules/native';

// Upstream language codes are lower-case (XiaomiSystemService.setLanguage).
const LANGUAGE_CHOICES = [
  { value: 'auto', key: 'settings.device.languageAuto' },
  { value: 'zh_tw', label: '繁中' },
  { value: 'en_us', label: 'English' },
] as const;

export default function DeviceSettings() {
  const connected = useConnectionState() === 'connected';
  const [info, setInfo] = useState<BandDeviceInfo | undefined>(() => system.getDeviceInfo());
  const [settings, setSettings] = useState<BandSystemSettings | undefined>(() => system.getSystemSettings());
  const [camera, setCamera] = useState<boolean | undefined>(() => system.getCameraRemoteEnabled());
  const [gps, setGps] = useState(() => system.getSendGpsToBand());
  const [clockSynced, setClockSynced] = useState(false);

  // Silent revalidation on focus — cached values already painted frame 1.
  useFocusEffect(
    useCallback(() => {
      if (!connected) return;
      void system.requestDeviceInfo().then((v) => v && setInfo(v));
      void system.refreshCameraRemote().then((v) => v !== undefined && setCamera(v));
    }, [connected]),
  );

  const supported = system.supportedLanguages();
  const languages = LANGUAGE_CHOICES.filter(
    (l) => l.value === 'auto' || supported.length === 0 || supported.map((s) => s.toLowerCase()).includes(l.value),
  ).map((l) => ({ value: l.value as string, label: 'key' in l ? t(l.key) : l.label }));

  const updateSettings = (patch: Partial<BandSystemSettings>) => {
    const next: BandSystemSettings = {
      use24HourClock: settings?.use24HourClock ?? true,
      language: settings?.language ?? 'auto',
      ...patch,
    };
    setSettings(next);
    void system.setSystemSettings(next).then(setSettings).catch(() => undefined);
  };

  const unknown = t('settings.common.unknown');

  return (
    <SettingsPage title={t('settings.nav.device.title')}>
      <SettingsSectionCard title={t('settings.device.info')} icon="band">
        <SettingsItem icon="band" title={t('settings.device.model')} trailing={{ kind: 'value', value: info?.model || '—' }} />
        <SettingsDivider />
        <SettingsItem icon="info" title={t('settings.device.firmware')} trailing={{ kind: 'value', value: info?.firmware || '—' }} />
        <SettingsDivider />
        <SettingsItem
          icon="shield"
          title={t('settings.device.serial')}
          subtitle={info ? undefined : unknown}
          trailing={{ kind: 'value', value: info?.serialNumber || '—' }}
        />
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.device.time')} icon="clock">
        <SettingsItem
          icon="clock"
          title={t('settings.device.clock24')}
          trailing={{
            kind: 'switch',
            value: settings?.use24HourClock ?? true,
            onChange: (v) => updateSettings({ use24HourClock: v }),
          }}
        />
        <SettingsDivider />
        <SettingsItem
          icon="globe"
          title={t('settings.device.language')}
          below={
            <SettingsChoice
              options={languages}
              value={settings?.language?.toLowerCase() ?? 'auto'}
              onChange={(v) => updateSettings({ language: v })}
            />
          }
        />
        <SettingsDivider />
        <SettingsItem
          icon="sync"
          title={t('settings.device.syncClock')}
          subtitle={clockSynced ? t('settings.device.synced') : connected ? undefined : unknown}
          disabled={!connected}
          trailing={{ kind: 'chevron' }}
          onPress={() =>
            void system.syncClock().then((ok) => {
              setClockSynced(ok);
              if (ok) void hapticsBridge.fire('success');
            })
          }
        />
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.device.camera')} icon="camera">
        <SettingsItem
          icon="camera"
          title={t('settings.device.camera')}
          subtitle={camera === undefined ? unknown : t('settings.device.cameraBody')}
          disabled={camera === undefined}
          trailing={{
            kind: 'switch',
            value: camera ?? false,
            onChange: (v) => {
              setCamera(v);
              void system.setCameraRemoteEnabled(v).then(setCamera).catch(() => undefined);
            },
          }}
        />
        <SettingsDivider />
        <SettingsItem
          icon="walk"
          title={t('settings.device.gps')}
          subtitle={t('settings.device.gpsBody')}
          trailing={{
            kind: 'switch',
            value: gps,
            onChange: (v) => {
              setGps(v);
              system.setSendGpsToBand(v);
            },
          }}
        />
      </SettingsSectionCard>
    </SettingsPage>
  );
}
