/*
 * mi-band-9-active — Settings › Bedtime mode (XiaomiScheduleService sleep mode).
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import DateTimePicker from '@react-native-community/datetimepicker';
import type { DateTimePickerEvent } from '@react-native-community/datetimepicker';
import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';

import { SettingsPage } from '@/components/settings/SettingsPage';
import { SettingsDivider, SettingsItem, SettingsSectionCard } from '@/components/settings/SettingsKit';
import { useConnectionState } from '@/libs/services/bandLink';
import { schedule } from '@/libs/services/schedule';
import { t } from '@/libs/services/i18n';
import type { SleepModeConfig, TimeOfDay } from '@/modules/native';

const fmt = (x: TimeOfDay) => `${String(x.hour).padStart(2, '0')}:${String(x.minute).padStart(2, '0')}`;

export default function SleepScheduleSettings() {
  const connected = useConnectionState() === 'connected';
  const [cfg, setCfg] = useState<SleepModeConfig | undefined>(() => schedule.getSleepMode());
  const [picking, setPicking] = useState<'start' | 'end' | null>(null);

  useFocusEffect(
    useCallback(() => {
      if (!connected) return;
      void schedule.refreshSleepMode().then((v) => v && setCfg(v));
    }, [connected]),
  );

  const apply = (next: SleepModeConfig) => {
    setCfg(next);
    void schedule.setSleepMode(next).then(setCfg).catch(() => undefined);
  };

  const onPicked = (e: DateTimePickerEvent, date?: Date) => {
    const which = picking;
    setPicking(null);
    if (!cfg || !which || e.type !== 'set' || !date) return;
    apply({ ...cfg, [which]: { hour: date.getHours(), minute: date.getMinutes() } });
  };

  const pickerValue = (() => {
    const d = new Date();
    const time = picking && cfg ? cfg[picking] : { hour: 23, minute: 0 };
    d.setHours(time.hour, time.minute, 0, 0);
    return d;
  })();

  return (
    <SettingsPage title={t('settings.nav.sleepSchedule.title')} subtitle={cfg ? undefined : t('settings.common.unknown')}>
      <SettingsSectionCard title={t('settings.sleepSchedule.enabled')} icon="moon">
        <SettingsItem
          icon="moon"
          title={t('settings.sleepSchedule.enabled')}
          subtitle={t('settings.sleepSchedule.enabledBody')}
          disabled={!cfg}
          trailing={{ kind: 'switch', value: cfg?.enabled ?? false, onChange: (v) => cfg && apply({ ...cfg, enabled: v }) }}
        />
        <SettingsDivider />
        <SettingsItem
          icon="moon"
          title={t('settings.sleepSchedule.bedtime')}
          disabled={!cfg}
          trailing={{ kind: 'chevron', value: cfg ? fmt(cfg.start) : '—' }}
          onPress={() => setPicking('start')}
        />
        <SettingsDivider />
        <SettingsItem
          icon="alarm"
          title={t('settings.sleepSchedule.wake')}
          disabled={!cfg}
          trailing={{ kind: 'chevron', value: cfg ? fmt(cfg.end) : '—' }}
          onPress={() => setPicking('end')}
        />
      </SettingsSectionCard>
      {picking ? <DateTimePicker mode="time" value={pickerValue} is24Hour onChange={onPicked} /> : null}
    </SettingsPage>
  );
}
