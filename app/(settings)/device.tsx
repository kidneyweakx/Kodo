/*
 * mi-band-9-active — Settings › Device & display.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Every value is what the band reported (or the user's pending change);
 * `undefined` renders as "connect to read", never as a default.
 */

import { router, useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { TextInput, View } from 'react-native';

import { Radius, Spacing } from '@/constants/DesignSystem';
import { ThemedButton } from '@/components/themed';
import { SettingsPage } from '@/components/settings/SettingsPage';
import {
  SettingsChoice,
  SettingsDivider,
  SettingsItem,
  SettingsSectionCard,
} from '@/components/settings/SettingsKit';
import { useTheme } from '@/context/ThemeContext';
import { useConnectionState } from '@/libs/services/bandLink';
import { system } from '@/libs/services/system';
import { t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';
import type { BandDeviceInfo, BandSystemSettings } from '@/modules/native';
import type { BandDeviceState, BandPasswordState } from '@/libs/services/system';

// Upstream language codes are lower-case (XiaomiSystemService.setLanguage).
const LANGUAGE_CHOICES = [
  { value: 'auto', key: 'settings.device.languageAuto' },
  { value: 'zh_tw', label: '繁中' },
  { value: 'en_us', label: 'English' },
] as const;

/** Live worn / charging / asleep flags pushed by the band (cleared on disconnect). */
function LiveState() {
  const [state, setState] = useState<BandDeviceState | undefined>(() => system.getDeviceState());
  useEffect(() => system.onDeviceStateChange(setState), []);
  if (!state) return null;
  const rows = [
    state.worn != null ? { key: 'worn', icon: 'wrist' as const, value: state.worn } : null,
    state.charging != null ? { key: 'charging', icon: 'battery' as const, value: state.charging } : null,
    state.asleep != null ? { key: 'asleep', icon: 'moon' as const, value: state.asleep } : null,
  ].filter((r): r is NonNullable<typeof r> => r != null);
  if (rows.length === 0) return null;
  return (
    <SettingsSectionCard title={t('settings.device.live')} icon="band">
      {rows.map((r, i) => (
        <View key={r.key}>
          {i > 0 ? <SettingsDivider /> : null}
          <SettingsItem
            icon={r.icon}
            title={t(`settings.device.${r.key}`)}
            trailing={{ kind: 'value', value: r.value ? t('settings.device.yes') : t('settings.device.no') }}
          />
        </View>
      ))}
    </SettingsSectionCard>
  );
}

/** Band lock: the digits go straight to native and never come back to JS. */
function BandLock({ connected }: { readonly connected: boolean }) {
  const { theme } = useTheme();
  const [lock, setLock] = useState<BandPasswordState | undefined>(() => system.getPassword());
  const [entering, setEntering] = useState(false);
  const [code, setCode] = useState('');

  useFocusEffect(
    useCallback(() => {
      if (!connected) return;
      void system.refreshPassword().then((v) => v && setLock(v));
    }, [connected]),
  );

  const apply = (enabled: boolean, password?: string) => {
    void system
      .setPassword(enabled, password)
      .then((v) => {
        setLock(v);
        void hapticsBridge.fire('success');
      })
      .catch(() => undefined);
    setEntering(false);
    setCode('');
  };

  return (
    <>
      <SettingsItem
        icon="shield"
        title={t('settings.device.lock')}
        subtitle={lock ? t('settings.device.lockBody') : t('settings.common.unknown')}
        disabled={!lock}
        trailing={{
          kind: 'switch',
          value: lock?.enabled ?? false,
          onChange: (v) => {
            if (v && !lock?.hasPassword) setEntering(true);
            else apply(v);
          },
        }}
      />
      {entering ? (
        <View style={{ flexDirection: 'row', gap: Spacing.sm, padding: Spacing.sm }}>
          <TextInput
            value={code}
            onChangeText={(v) => setCode(v.replace(/[^0-9]/g, '').slice(0, 6))}
            keyboardType="number-pad"
            secureTextEntry
            maxLength={6}
            placeholder={t('settings.device.lockPlaceholder')}
            placeholderTextColor={theme.text.tertiary}
            style={{
              flex: 1,
              color: theme.text.primary,
              backgroundColor: theme.background.tertiary,
              borderRadius: Radius.md,
              paddingHorizontal: Spacing.md,
              fontSize: 17,
              letterSpacing: 6,
            }}
          />
          <ThemedButton label={t('settings.device.lockSet')} disabled={code.length !== 6} onPress={() => apply(true, code)} />
        </View>
      ) : null}
      {lock?.enabled && !entering ? (
        <View style={{ paddingHorizontal: Spacing.sm, paddingBottom: Spacing.sm, alignItems: 'flex-end' }}>
          <ThemedButton size="sm" variant="ghost" label={t('settings.device.lockSet')} onPress={() => setEntering(true)} />
        </View>
      ) : null}
    </>
  );
}

export default function DeviceSettings() {
  const connected = useConnectionState() === 'connected';
  const [info, setInfo] = useState<BandDeviceInfo | undefined>(() => system.getDeviceInfo());
  const [settings, setSettings] = useState<BandSystemSettings | undefined>(() => system.getSystemSettings());
  const [camera, setCamera] = useState<boolean | undefined>(() => system.getCameraRemoteEnabled());
  const [gps, setGps] = useState(() => system.getSendGpsToBand());
  const [clockSynced, setClockSynced] = useState(false);
  const [screenOn, setScreenOn] = useState<boolean | undefined>(() => system.getScreenOnOnNotifications());

  // Silent revalidation on focus — cached values already painted frame 1.
  useFocusEffect(
    useCallback(() => {
      if (!connected) return;
      void system.requestDeviceInfo().then((v) => v && setInfo(v));
      void system.refreshCameraRemote().then((v) => v !== undefined && setCamera(v));
      void system.refreshScreenOnOnNotifications().then((v) => v !== undefined && setScreenOn(v));
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

      <LiveState />

      <SettingsSectionCard title={t('settings.device.displayItems')} icon="watchface">
        <SettingsItem
          icon="watchface"
          title={t('settings.device.displayItems')}
          subtitle={t('settings.device.displayItemsBody')}
          trailing={{ kind: 'chevron' }}
          onPress={() => router.push('/(settings)/display-items')}
        />
        <SettingsDivider />
        <SettingsItem
          icon="bell"
          title={t('settings.device.screenOn')}
          subtitle={screenOn === undefined ? t('settings.common.unknown') : t('settings.device.screenOnBody')}
          disabled={screenOn === undefined}
          trailing={{
            kind: 'switch',
            value: screenOn ?? false,
            onChange: (v) => {
              setScreenOn(v);
              void system.setScreenOnOnNotifications(v).then(setScreenOn).catch(() => undefined);
            },
          }}
        />
        <SettingsDivider />
        <BandLock connected={connected} />
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
