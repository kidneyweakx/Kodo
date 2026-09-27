/*
 * mi-band-9-active — Settings › Band screens (XiaomiSystemService display items).
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Toggle which apps the band shows. Order is the band's own; the settings
 * screen can't be hidden (upstream keeps it enabled). Labels come from the
 * band in its own language.
 */

import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { View } from 'react-native';

import { SettingsPage } from '@/components/settings/SettingsPage';
import { SettingsDivider, SettingsItem, SettingsSectionCard } from '@/components/settings/SettingsKit';
import { useConnectionState } from '@/libs/services/bandLink';
import { system } from '@/libs/services/system';
import type { BandDisplayItems } from '@/libs/services/system';
import { t } from '@/libs/services/i18n';

export default function DisplayItemsSettings() {
  const connected = useConnectionState() === 'connected';
  const [data, setData] = useState<BandDisplayItems | undefined>(() => system.getDisplayItems());

  useFocusEffect(
    useCallback(() => {
      if (!connected) return;
      void system.refreshDisplayItems().then((v) => v && setData(v));
    }, [connected]),
  );

  const toggle = (code: string, enabled: boolean) => {
    if (!data) return;
    const items = data.items.map((i) => (i.code === code ? { ...i, enabled } : i));
    setData({ ...data, items });
    const codes = items.filter((i) => i.enabled || i.isSettings).map((i) => i.code);
    void system.setDisplayItems(codes).then(setData).catch(() => undefined);
  };

  return (
    <SettingsPage
      title={t('settings.device.displayItems')}
      subtitle={data ? t('settings.device.displayItemsBody') : t('settings.common.unknown')}
    >
      {data ? (
        <SettingsSectionCard title={t('settings.device.displayItems')} icon="watchface">
          {data.items.map((item, i) => (
            <View key={item.code}>
              {i > 0 ? <SettingsDivider /> : null}
              <SettingsItem
                title={item.name || item.code}
                subtitle={
                  item.isSettings
                    ? t('settings.device.alwaysOn')
                    : item.inMoreSection
                      ? t('settings.device.more')
                      : undefined
                }
                disabled={item.isSettings}
                trailing={{ kind: 'switch', value: item.enabled || item.isSettings, onChange: (v) => toggle(item.code, v) }}
              />
            </View>
          ))}
        </SettingsSectionCard>
      ) : null}
    </SettingsPage>
  );
}
