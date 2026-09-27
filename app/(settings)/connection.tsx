/*
 * mi-band-9-active — Settings › Connection & power.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Makes docs/POWER.md observable: link state + passive reconnect, the
 * WorkManager sync schedule, the keep-alive prerequisites (notification
 * listener, battery optimisation), the wake-up budget and the persisted
 * link/power event log. getDiagnostics() is synchronous and cheap, so we
 * re-read on focus and every few seconds while the page is open.
 */

import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { View } from 'react-native';

import { Spacing, tabularNums } from '@/constants/DesignSystem';
import { ThemedText } from '@/components/themed';
import { LiveHeartRate } from '@/components/dashboard/LiveHeartRate';
import { SettingsPage } from '@/components/settings/SettingsPage';
import { SettingsDivider, SettingsItem, SettingsSectionCard } from '@/components/settings/SettingsKit';
import { useTheme } from '@/context/ThemeContext';
import { bandLink, useConnectionState } from '@/libs/services/bandLink';
import type { LinkDiagnostics } from '@/libs/services/bandLink';
import { notificationBridge } from '@/libs/services/notificationBridge';
import { permissions } from '@/libs/services/permissions';
import { t } from '@/libs/services/i18n';

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : t('settings.connection.never'));

export default function ConnectionSettings() {
  const { theme } = useTheme();
  const connection = useConnectionState();
  const [diag, setDiag] = useState<LinkDiagnostics | null>(() => bandLink.getDiagnostics());

  useFocusEffect(
    useCallback(() => {
      setDiag(bandLink.getDiagnostics());
      const id = setInterval(() => setDiag(bandLink.getDiagnostics()), 3_000);
      return () => clearInterval(id);
    }, []),
  );

  if (!diag) {
    return (
      <SettingsPage title={t('settings.nav.connection.title')} subtitle={t('settings.common.unknown')}>
        <View />
      </SettingsPage>
    );
  }

  const onOff = (v: boolean) => (v ? t('settings.connection.on') : t('settings.connection.off'));
  const overBudget = diag.wakeupsLast24h >= 50;

  return (
    <SettingsPage title={t('settings.nav.connection.title')}>
      <SettingsSectionCard title={t('settings.connection.link')} icon="band">
        <SettingsItem icon="band" title={t('settings.connection.state')} trailing={{ kind: 'value', value: t(`settings.state.${connection === 'authenticating' ? 'connecting' : connection}`) }} />
        <SettingsDivider />
        <SettingsItem icon="sync" title={t('settings.connection.bluetooth')} trailing={{ kind: 'value', value: onOff(diag.bluetoothEnabled) }} />
        <SettingsDivider />
        <SettingsItem icon="shield" title={t('settings.connection.bonded')} trailing={{ kind: 'value', value: onOff(diag.bonded) }} />
        <SettingsDivider />
        <SettingsItem
          icon="sync"
          title={t('settings.connection.reconnect')}
          subtitle={
            diag.reconnectArmed
              ? t('settings.connection.reconnectArmed', { n: diag.reconnectAttempts })
              : t('settings.connection.reconnectIdle')
          }
        />
        <SettingsDivider />
        <SettingsItem icon="clock" title={t('settings.connection.lastConnected')} subtitle={when(diag.lastConnectedAt)} />
        <SettingsDivider />
        <SettingsItem
          icon="unlink"
          title={t('settings.connection.lastDisconnected')}
          subtitle={[when(diag.lastDisconnectedAt), diag.lastDisconnectReason].filter(Boolean).join(' · ')}
        />
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.connection.background')} icon="sync">
        <SettingsItem
          icon="sync"
          title={t('settings.connection.background')}
          subtitle={diag.periodicSyncEnabled ? t('settings.connection.every', { min: diag.periodicSyncIntervalMinutes }) : t('settings.connection.off')}
        />
        <SettingsDivider />
        <SettingsItem icon="clock" title={t('settings.connection.nextRun')} trailing={{ kind: 'value', value: diag.nextPeriodicSyncAt ? new Date(diag.nextPeriodicSyncAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '—' }} />
        <SettingsDivider />
        <SettingsItem
          icon="database"
          title={t('settings.connection.lastSync')}
          subtitle={diag.lastSyncError ? `${when(diag.lastSyncAt)} · ${t('settings.connection.lastError')}: ${diag.lastSyncError}` : when(diag.lastSyncAt)}
        />
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.connection.keepAlive')} icon="shield">
        <SettingsItem
          icon="bell"
          title={t('settings.connection.listener')}
          subtitle={t('settings.connection.listenerBody')}
          trailing={diag.notificationListenerConnected ? { kind: 'value', value: '✓' } : { kind: 'chevron' }}
          onPress={diag.notificationListenerConnected ? undefined : () => notificationBridge.requestAccess()}
        />
        <SettingsDivider />
        <SettingsItem
          icon="battery"
          title={t('settings.connection.battery')}
          subtitle={diag.ignoringBatteryOptimizations ? t('settings.connection.batteryOn') : t('settings.connection.batteryOff')}
          trailing={diag.ignoringBatteryOptimizations ? { kind: 'value', value: '✓' } : { kind: 'chevron' }}
          onPress={diag.ignoringBatteryOptimizations ? undefined : () => void permissions.openBatteryOptimizationSettings()}
        />
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.connection.live')} icon="heart">
        <View style={{ padding: Spacing.sm }}>
          <LiveHeartRate />
        </View>
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.connection.power')} icon="battery">
        <SettingsItem
          icon="battery"
          title={t('settings.connection.wakeups')}
          subtitle={t('settings.connection.wakeupsBody')}
          trailing={{ kind: 'value', value: String(diag.wakeupsLast24h) }}
        />
        <SettingsDivider />
        <View style={{ padding: Spacing.sm, gap: Spacing.sm }}>
          <ThemedText variant="caption" tone="secondary">
            {t('settings.connection.events')}
          </ThemedText>
          {diag.events.length === 0 ? (
            <ThemedText variant="caption" tone="tertiary">
              {t('settings.connection.noEvents')}
            </ThemedText>
          ) : (
            diag.events.slice(0, 30).map((e) => (
              <View key={`${e.at}-${e.kind}`} style={{ flexDirection: 'row', gap: Spacing.sm }}>
                <ThemedText variant="micro" tone="tertiary" style={[tabularNums, { width: 64 }]}>
                  {new Date(e.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                </ThemedText>
                <ThemedText variant="micro" style={{ flex: 1, color: overBudget ? theme.warning : theme.text.secondary }}>
                  {e.kind}
                  {e.detail ? ` · ${e.detail}` : ''}
                </ThemedText>
              </View>
            ))
          )}
        </View>
        <SettingsDivider />
        <SettingsItem
          icon="trash"
          tone="danger"
          title={t('settings.connection.clear')}
          onPress={() => {
            bandLink.clearDiagnostics();
            setDiag(bandLink.getDiagnostics());
          }}
        />
      </SettingsSectionCard>
    </SettingsPage>
  );
}
