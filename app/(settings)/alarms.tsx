/*
 * mi-band-9-active — Settings › Alarms (XiaomiScheduleService alarms).
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * The band owns the list: we paint the last fetched copy immediately and
 * refetch on focus. Every edit returns the band's new list, which replaces
 * ours — there is no optimistic local alarm the band doesn't know about.
 */

import DateTimePicker from '@react-native-community/datetimepicker';
import type { DateTimePickerEvent } from '@react-native-community/datetimepicker';
import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { Alert, Pressable, View } from 'react-native';

import { Radius, Spacing, tabularNums } from '@/constants/DesignSystem';
import { ThemedButton, ThemedText, readableTextOn } from '@/components/themed';
import { SettingsPage } from '@/components/settings/SettingsPage';
import { SettingsDivider, SettingsItem, SettingsSectionCard } from '@/components/settings/SettingsKit';
import { useTheme } from '@/context/ThemeContext';
import { useConnectionState } from '@/libs/services/bandLink';
import { schedule } from '@/libs/services/schedule';
import { t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';
import type { AlarmDraft, AlarmList, BandAlarm, TimeOfDay } from '@/modules/native';

const EVERY_DAY = 127;
const WEEKDAYS = 31;
const WEEKEND = 96;

const hhmm = (t0: TimeOfDay) => `${String(t0.hour).padStart(2, '0')}:${String(t0.minute).padStart(2, '0')}`;

const repeatLabel = (mask: number): string => {
  if (mask === 0) return t('settings.alarms.once');
  if (mask === EVERY_DAY) return t('settings.alarms.daily');
  if (mask === WEEKDAYS) return t('settings.alarms.weekdays');
  if (mask === WEEKEND) return t('settings.alarms.weekend');
  const days = t('settings.alarms.days').split(',');
  return days.filter((_, i) => mask & (1 << i)).join(' ');
};

function DayChips({ mask, onChange }: { readonly mask: number; readonly onChange: (m: number) => void }) {
  const { theme } = useTheme();
  const days = t('settings.alarms.days').split(',');
  return (
    <View style={{ flexDirection: 'row', gap: Spacing.xs }}>
      {days.map((label, i) => {
        const on = (mask & (1 << i)) !== 0;
        return (
          <Pressable
            key={label}
            onPress={() => {
              void hapticsBridge.fire('selection');
              onChange(mask ^ (1 << i));
            }}
            accessibilityRole="checkbox"
            accessibilityState={{ checked: on }}
            style={{
              flex: 1,
              minHeight: 36,
              borderRadius: Radius.sm,
              alignItems: 'center',
              justifyContent: 'center',
              backgroundColor: on ? theme.accent : theme.background.tertiary,
            }}
          >
            <ThemedText variant="caption" style={{ color: on ? readableTextOn(theme.accent) : theme.text.secondary, fontWeight: '700' }}>
              {label}
            </ThemedText>
          </Pressable>
        );
      })}
    </View>
  );
}

function AlarmEditor({
  initial,
  onSave,
  onDelete,
  onCancel,
}: {
  readonly initial: AlarmDraft;
  readonly onSave: (d: AlarmDraft) => void;
  readonly onDelete?: () => void;
  readonly onCancel: () => void;
}) {
  const [draft, setDraft] = useState(initial);
  const [picking, setPicking] = useState(false);

  const onTime = (e: DateTimePickerEvent, date?: Date) => {
    setPicking(false);
    if (e.type === 'set' && date) setDraft((d) => ({ ...d, time: { hour: date.getHours(), minute: date.getMinutes() } }));
  };

  const pickerValue = new Date();
  pickerValue.setHours(draft.time.hour, draft.time.minute, 0, 0);

  return (
    <View style={{ gap: Spacing.md, padding: Spacing.sm }}>
      <Pressable onPress={() => setPicking(true)} accessibilityRole="button" style={{ alignItems: 'center' }}>
        <ThemedText variant="displayLarge" style={tabularNums}>
          {hhmm(draft.time)}
        </ThemedText>
      </Pressable>
      {picking ? <DateTimePicker mode="time" value={pickerValue} is24Hour onChange={onTime} /> : null}
      <DayChips mask={draft.repeatDays} onChange={(m) => setDraft((d) => ({ ...d, repeatDays: m }))} />
      <SettingsItem
        icon="moon"
        title={t('settings.alarms.smart')}
        subtitle={t('settings.alarms.smartBody')}
        trailing={{ kind: 'switch', value: draft.smartWakeup, onChange: (v) => setDraft((d) => ({ ...d, smartWakeup: v })) }}
      />
      <View style={{ flexDirection: 'row', gap: Spacing.sm }}>
        <View style={{ flex: 1 }}>
          <ThemedButton variant="secondary" label={t('settings.danger.cancel')} fullWidth onPress={onCancel} />
        </View>
        <View style={{ flex: 1 }}>
          <ThemedButton label={t('settings.common.save')} fullWidth onPress={() => onSave({ ...draft, enabled: true })} />
        </View>
      </View>
      {onDelete ? <ThemedButton variant="destructive" label={t('settings.alarms.delete')} fullWidth onPress={onDelete} /> : null}
    </View>
  );
}

export default function AlarmsSettings() {
  const connected = useConnectionState() === 'connected';
  const [list, setList] = useState<AlarmList | undefined>(() => schedule.getCachedAlarms());
  const [editing, setEditing] = useState<BandAlarm | 'new' | null>(null);

  useFocusEffect(
    useCallback(() => {
      if (!connected) return;
      void schedule.fetchAlarms().then(setList).catch(() => undefined);
    }, [connected]),
  );

  const run = (op: Promise<AlarmList>) => {
    setEditing(null);
    op.then((l) => {
      setList(l);
      void hapticsBridge.fire('success');
    }).catch((e: unknown) => Alert.alert(t('settings.common.failed'), e instanceof Error ? e.message : String(e)));
  };

  const alarms = list?.alarms ?? [];
  const full = list != null && alarms.length >= list.maxAlarms;

  return (
    <SettingsPage
      title={t('settings.nav.alarms.title')}
      subtitle={list ? t('settings.alarms.slots', { used: alarms.length, max: list.maxAlarms }) : t('settings.common.unknown')}
    >
      <SettingsSectionCard title={t('settings.nav.alarms.title')} icon="alarm">
        {alarms.length === 0 && editing !== 'new' ? (
          <SettingsItem icon="alarm" title={list ? t('settings.alarms.empty') : t('settings.common.unknown')} />
        ) : null}
        {alarms.map((a, i) => (
          <View key={a.id}>
            {i > 0 ? <SettingsDivider /> : null}
            {editing !== 'new' && editing?.id === a.id ? (
              <AlarmEditor
                initial={a}
                onCancel={() => setEditing(null)}
                onSave={(d) => run(schedule.updateAlarm(a.id, d))}
                onDelete={() => run(schedule.deleteAlarms([a.id]))}
              />
            ) : (
              <SettingsItem
                icon="alarm"
                title={hhmm(a.time)}
                subtitle={[repeatLabel(a.repeatDays), a.smartWakeup ? t('settings.alarms.smart') : null].filter(Boolean).join(' · ')}
                disabled={!connected}
                onPress={() => setEditing(a)}
                trailing={{
                  kind: 'switch',
                  value: a.enabled,
                  onChange: (v) => run(schedule.updateAlarm(a.id, { ...a, enabled: v })),
                }}
              />
            )}
          </View>
        ))}
        {editing === 'new' ? (
          <AlarmEditor
            initial={{ time: { hour: 7, minute: 0 }, enabled: true, smartWakeup: false, repeatDays: WEEKDAYS }}
            onCancel={() => setEditing(null)}
            onSave={(d) => run(schedule.createAlarm(d))}
          />
        ) : null}
      </SettingsSectionCard>

      {editing === null ? (
        <ThemedButton
          label={full ? t('settings.alarms.full') : t('settings.alarms.add')}
          size="lg"
          fullWidth
          disabled={!connected || !list || full}
          onPress={() => setEditing('new')}
        />
      ) : null}
    </SettingsPage>
  );
}
