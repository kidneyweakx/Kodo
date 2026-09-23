/*
 * mi-band-9-active — Settings › About.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import Constants from 'expo-constants';
import { Linking } from 'react-native';

import { SettingsPage } from '@/components/settings/SettingsPage';
import { SettingsDivider, SettingsItem, SettingsSectionCard } from '@/components/settings/SettingsKit';
import { t } from '@/libs/services/i18n';

const SOURCE_URL = 'https://github.com/kidneyweakx/Kodo';
const UPSTREAM_URL = 'https://gadgetbridge.org';

export default function AboutSettings() {
  const config = Constants.expoConfig;
  const version = config?.version ?? '—';
  const build = config?.android?.versionCode;

  return (
    <SettingsPage title={t('settings.nav.about.title')}>
      <SettingsSectionCard title="Kodō" icon="info">
        <SettingsItem icon="info" title={t('settings.about.version')} trailing={{ kind: 'value', value: version }} />
        <SettingsDivider />
        <SettingsItem
          icon="database"
          title={t('settings.about.build')}
          trailing={{ kind: 'value', value: build != null ? String(build) : '—' }}
        />
        <SettingsDivider />
        <SettingsItem
          icon="shield"
          title={t('settings.about.privacy')}
          subtitle={t('settings.about.privacyBody')}
        />
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.about.license')} icon="globe">
        <SettingsItem
          icon="globe"
          title={t('settings.about.source')}
          subtitle="AGPL-3.0-or-later"
          trailing={{ kind: 'chevron' }}
          onPress={() => void Linking.openURL(SOURCE_URL)}
        />
        <SettingsDivider />
        <SettingsItem
          icon="band"
          title={`${t('settings.about.upstream')} Gadgetbridge`}
          subtitle={t('settings.about.upstreamBody')}
          trailing={{ kind: 'chevron' }}
          onPress={() => void Linking.openURL(UPSTREAM_URL)}
        />
      </SettingsSectionCard>
    </SettingsPage>
  );
}
