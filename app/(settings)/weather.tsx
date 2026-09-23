/*
 * mi-band-9-active — Settings › Weather & calendar.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Weather comes from an external provider (Gadgetbridge-style broadcast from
 * Breezy Weather etc.) or the optional OpenWeatherMap poller — we never
 * synthesise it. Calendar events are read from CalendarContract natively.
 */

import { useFocusEffect } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { TextInput, View } from 'react-native';

import { Radius, Spacing } from '@/constants/DesignSystem';
import { ThemedButton, ThemedText } from '@/components/themed';
import { SettingsPage } from '@/components/settings/SettingsPage';
import {
  SettingsChoice,
  SettingsDivider,
  SettingsItem,
  SettingsSectionCard,
  SettingsSlider,
} from '@/components/settings/SettingsKit';
import { useTheme } from '@/context/ThemeContext';
import { calendar } from '@/libs/services/calendar';
import { permissions } from '@/libs/services/permissions';
import { weather } from '@/libs/services/weather';
import { t } from '@/libs/services/i18n';
import type { CalendarSyncSettings, OwmConfig, TemperatureUnit, WeatherSnapshot } from '@/modules/native';

const ago = (sec: number) => {
  const m = Math.round((Date.now() / 1000 - sec) / 60);
  if (m < 60) return `${Math.max(1, m)}m`;
  if (m < 1440) return `${Math.round(m / 60)}h`;
  return new Date(sec * 1000).toLocaleDateString();
};

function Field({
  label,
  value,
  onChange,
  secure,
  numeric,
}: {
  readonly label: string;
  readonly value: string;
  readonly onChange: (v: string) => void;
  readonly secure?: boolean;
  readonly numeric?: boolean;
}) {
  const { theme } = useTheme();
  return (
    <View style={{ flex: 1, gap: Spacing.xs }}>
      <ThemedText variant="caption" tone="secondary">
        {label}
      </ThemedText>
      <TextInput
        value={value}
        onChangeText={onChange}
        secureTextEntry={secure}
        autoCapitalize="none"
        autoCorrect={false}
        keyboardType={numeric ? 'numbers-and-punctuation' : 'default'}
        style={{
          color: theme.text.primary,
          backgroundColor: theme.background.tertiary,
          borderRadius: Radius.md,
          paddingHorizontal: Spacing.md,
          paddingVertical: Spacing.sm,
          fontSize: 15,
        }}
      />
    </View>
  );
}

export default function WeatherSettings() {
  const [snap, setSnap] = useState<WeatherSnapshot | undefined>(() => weather.getLastSnapshot());
  const [unit, setUnit] = useState<TemperatureUnit>(() => weather.getTemperatureUnit());
  const [owm, setOwm] = useState(() => {
    const c = weather.getOwmConfig();
    return {
      enabled: c?.enabled ?? false,
      apiKey: c?.apiKey ?? '',
      lat: c ? String(c.latitude) : '',
      lon: c ? String(c.longitude) : '',
      name: c?.locationName ?? '',
    };
  });
  const [busy, setBusy] = useState(false);
  const [cal, setCal] = useState<CalendarSyncSettings | undefined>(() => calendar.getSyncSettings());
  const [calPerm, setCalPerm] = useState(() => calendar.hasPermission());
  const [calSent, setCalSent] = useState<number | null>(null);

  useEffect(() => weather.onWeather(setSnap), []);
  useFocusEffect(useCallback(() => setCalPerm(calendar.hasPermission()), []));

  const owmConfig = (): OwmConfig | null => {
    const lat = Number(owm.lat);
    const lon = Number(owm.lon);
    if (!owm.apiKey.trim() || !Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
    return { enabled: owm.enabled, apiKey: owm.apiKey.trim(), latitude: lat, longitude: lon, locationName: owm.name.trim(), pollMinutes: 360 };
  };
  const validOwm = owmConfig();

  const saveOwm = async (refresh: boolean) => {
    if (!validOwm) return;
    weather.setOwmConfig(validOwm);
    if (!refresh) return;
    setBusy(true);
    try {
      await weather.refreshNow();
      setSnap(weather.getLastSnapshot());
    } finally {
      setBusy(false);
    }
  };

  const applyCal = async (next: CalendarSyncSettings) => {
    if (next.enabled && !calPerm) {
      const ok = await permissions.requestCalendar();
      setCalPerm(ok);
      if (!ok) return;
    }
    setCal(next);
    setCalSent(await calendar.setSyncSettings(next).catch(() => null));
  };

  return (
    <SettingsPage title={t('settings.nav.weather.title')}>
      <SettingsSectionCard title={t('settings.weather.current')} icon="cloud" description={t('settings.weather.sourceBody')}>
        <SettingsItem
          icon="cloud"
          title={snap ? `${snap.location || '—'} · ${Math.round(snap.currentTempC)}°C` : t('settings.weather.none')}
          subtitle={
            snap
              ? [snap.conditionText, t('settings.weather.updated', { when: ago(snap.timestampSec) }), snap.source].filter(Boolean).join(' · ')
              : undefined
          }
          trailing={snap ? { kind: 'chevron', value: t('settings.weather.pushNow') } : { kind: 'none' }}
          onPress={snap ? () => void weather.pushLast() : undefined}
        />
        <SettingsDivider />
        <SettingsItem
          icon="palette"
          title={t('settings.weather.unit')}
          below={
            <SettingsChoice
              options={[
                { value: 'celsius' as const, label: '°C' },
                { value: 'fahrenheit' as const, label: '°F' },
              ]}
              value={unit}
              onChange={(u) => {
                setUnit(u);
                void weather.setTemperatureUnit(u);
              }}
            />
          }
        />
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.weather.owm')} icon="globe" description={t('settings.weather.owmBody')}>
        <SettingsItem
          icon="sync"
          title={t('settings.weather.owm')}
          disabled={!validOwm}
          trailing={{
            kind: 'switch',
            value: owm.enabled && !!validOwm,
            onChange: (v) => {
              setOwm((o) => ({ ...o, enabled: v }));
              if (validOwm) weather.setOwmConfig({ ...validOwm, enabled: v });
            },
          }}
        />
        <View style={{ padding: Spacing.sm, gap: Spacing.md }}>
          <Field label={t('settings.weather.owmKey')} value={owm.apiKey} secure onChange={(v) => setOwm((o) => ({ ...o, apiKey: v }))} />
          <View style={{ flexDirection: 'row', gap: Spacing.md }}>
            <Field label={t('settings.weather.owmLat')} value={owm.lat} numeric onChange={(v) => setOwm((o) => ({ ...o, lat: v }))} />
            <Field label={t('settings.weather.owmLon')} value={owm.lon} numeric onChange={(v) => setOwm((o) => ({ ...o, lon: v }))} />
          </View>
          <Field label={t('settings.weather.owmName')} value={owm.name} onChange={(v) => setOwm((o) => ({ ...o, name: v }))} />
          <View style={{ flexDirection: 'row', gap: Spacing.sm }}>
            <View style={{ flex: 1 }}>
              <ThemedButton variant="secondary" label={t('settings.common.save')} fullWidth disabled={!validOwm} onPress={() => void saveOwm(false)} />
            </View>
            <View style={{ flex: 1 }}>
              <ThemedButton
                label={t('settings.weather.owmRefresh')}
                fullWidth
                loading={busy}
                disabled={!validOwm || busy}
                onPress={() => void saveOwm(true)}
              />
            </View>
          </View>
        </View>
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.weather.calendar')} icon="calendar">
        <SettingsItem
          icon="calendar"
          title={t('settings.weather.calendarSync')}
          subtitle={
            cal?.enabled && !calPerm
              ? t('settings.weather.calendarPerm')
              : calSent != null
                ? t('settings.weather.calendarSynced', { count: calSent })
                : undefined
          }
          trailing={{
            kind: 'switch',
            value: (cal?.enabled ?? false) && calPerm,
            onChange: (v) => void applyCal({ enabled: v, lookaheadDays: cal?.lookaheadDays ?? 7, includeAllDay: cal?.includeAllDay ?? true }),
          }}
        />
        {cal?.enabled && calPerm ? (
          <>
            <SettingsDivider />
            <SettingsItem
              icon="clock"
              title={t('settings.weather.lookahead')}
              below={
                <SettingsSlider
                  stops={[1, 3, 7, 14, 30]}
                  value={cal.lookaheadDays}
                  onChange={(d) => void applyCal({ ...cal, lookaheadDays: d })}
                  format={(d) => `${d}d`}
                />
              }
            />
            <SettingsDivider />
            <SettingsItem
              icon="calendar"
              title={t('settings.weather.allDay')}
              trailing={{ kind: 'switch', value: cal.includeAllDay, onChange: (v) => void applyCal({ ...cal, includeAllDay: v }) }}
            />
          </>
        ) : null}
      </SettingsSectionCard>
    </SettingsPage>
  );
}
