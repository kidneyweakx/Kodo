/*
 * mi-band-9-active — Settings › Reminders (XiaomiScheduleService reminders).
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Same contract as alarms: the band owns the list; every edit returns the
 * band's new list, which replaces ours.
 */

import DateTimePicker from '@react-native-community/datetimepicker';
import type { DateTimePickerEvent } from '@react-native-community/datetimepicker';
import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { Alert, TextInput, View } from 'react-native';

import { Radius, Spacing } from '@/constants/DesignSystem';
import { ThemedButton, ThemedText } from '@/components/themed';
import { SettingsPage } from '@/components/settings/SettingsPage';
import { SettingsChoice, SettingsDivider, SettingsItem, SettingsSectionCard } from '@/components/settings/SettingsKit';
import { useTheme } from '@/context/ThemeContext';
import { useConnectionState } from '@/libs/services/bandLink';
import { schedule } from '@/libs/services/schedule';
import { getLocale, t } from '@/libs/services/i18n';
import { hapticsBridge } from '@/modules/haptics/hapticsBridge';
import type { BandReminder, ReminderDraft, ReminderList, ReminderRepeat } from '@/modules/native';

const REPEATS: readonly ReminderRepeat[] = ['once', 'daily', 'weekly', 'monthly', 'yearly'];
const MAX_TITLE = 20;

const whenLabel = (at: number) =>
  new Date(at).toLocaleString(getLocale() === 'zh-Hant' ? 'zh-TW' : 'en-US', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });

function ReminderEditor({
  initial,
  onSave,
  onDelete,
  onCancel,
}: {
  readonly initial: ReminderDraft;
  readonly onSave: (d: ReminderDraft) => void;
  readonly onDelete?: () => void;
  readonly onCancel: () => void;
}) {
  const { theme } = useTheme();
  const [draft, setDraft] = useState(initial);
  const [picking, setPicking] = useState<'date' | 'time' | null>(null);

  const onPicked = (e: DateTimePickerEvent, date?: Date) => {
    const mode = picking;
    setPicking(null);
    if (e.type !== 'set' || !date) return;
    const cur = new Date(draft.at);
    if (mode === 'date') cur.setFullYear(date.getFullYear(), date.getMonth(), date.getDate());
    else cur.setHours(date.getHours(), date.getMinutes(), 0, 0);
    setDraft((d) => ({ ...d, at: cur.getTime() }));
  };

  const valid = draft.title.trim().length > 0;

  return (
    <View style={{ gap: Spacing.md, padding: Spacing.sm }}>
      <View style={{ gap: Spacing.xs }}>
        <ThemedText variant="caption" tone="secondary">
          {t('settings.reminders.titleLabel')}
        </ThemedText>
        <TextInput
          value={draft.title}
          maxLength={MAX_TITLE}
          onChangeText={(v) => setDraft((d) => ({ ...d, title: v }))}
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
      <View style={{ flexDirection: 'row', gap: Spacing.sm }}>
        <View style={{ flex: 1 }}>
          <ThemedButton
            variant="secondary"
            size="sm"
            fullWidth
            label={new Date(draft.at).toLocaleDateString()}
            onPress={() => setPicking('date')}
          />
        </View>
        <View style={{ flex: 1 }}>
          <ThemedButton
            variant="secondary"
            size="sm"
            fullWidth
            label={new Date(draft.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            onPress={() => setPicking('time')}
          />
        </View>
      </View>
      {picking ? <DateTimePicker mode={picking} value={new Date(draft.at)} is24Hour onChange={onPicked} /> : null}
      <SettingsChoice
        options={REPEATS.map((r) => ({ value: r, label: t(`settings.reminders.repeats.${r}`) }))}
        value={draft.repeat}
        onChange={(r) => setDraft((d) => ({ ...d, repeat: r }))}
      />
      <View style={{ flexDirection: 'row', gap: Spacing.sm }}>
        <View style={{ flex: 1 }}>
          <ThemedButton variant="secondary" label={t('settings.danger.cancel')} fullWidth onPress={onCancel} />
        </View>
        <View style={{ flex: 1 }}>
          <ThemedButton label={t('settings.common.save')} fullWidth disabled={!valid} onPress={() => onSave({ ...draft, title: draft.title.trim() })} />
        </View>
      </View>
      {onDelete ? <ThemedButton variant="destructive" label={t('settings.reminders.delete')} fullWidth onPress={onDelete} /> : null}
    </View>
  );
}

export default function RemindersSettings() {
  const connected = useConnectionState() === 'connected';
  const [list, setList] = useState<ReminderList | undefined>(() => schedule.getCachedReminders());
  const [editing, setEditing] = useState<BandReminder | 'new' | null>(null);

  useFocusEffect(
    useCallback(() => {
      if (!connected) return;
      void schedule.fetchReminders().then(setList).catch(() => undefined);
    }, [connected]),
  );

  const run = (op: Promise<ReminderList>) => {
    setEditing(null);
    op.then((l) => {
      setList(l);
      void hapticsBridge.fire('success');
    }).catch((e: unknown) => Alert.alert(t('settings.common.failed'), e instanceof Error ? e.message : String(e)));
  };

  const reminders = list?.reminders ?? [];
  const full = list != null && reminders.length >= list.maxReminders;
  const nextHour = () => {
    const d = new Date();
    d.setHours(d.getHours() + 1, 0, 0, 0);
    return d.getTime();
  };

  return (
    <SettingsPage
      title={t('settings.nav.reminders.title')}
      subtitle={list ? t('settings.reminders.slots', { used: reminders.length, max: list.maxReminders }) : t('settings.common.unknown')}
    >
      <SettingsSectionCard title={t('settings.nav.reminders.title')} icon="bell">
        {reminders.length === 0 && editing !== 'new' ? (
          <SettingsItem icon="bell" title={list ? t('settings.reminders.empty') : t('settings.common.unknown')} />
        ) : null}
        {reminders.map((r, i) => (
          <View key={r.id}>
            {i > 0 ? <SettingsDivider /> : null}
            {editing !== 'new' && editing?.id === r.id ? (
              <ReminderEditor
                initial={r}
                onCancel={() => setEditing(null)}
                onSave={(d) => run(schedule.updateReminder(r.id, d))}
                onDelete={() => run(schedule.deleteReminders([r.id]))}
              />
            ) : (
              <SettingsItem
                icon="bell"
                title={r.title}
                subtitle={`${whenLabel(r.at)} · ${t(`settings.reminders.repeats.${r.repeat}`)}`}
                disabled={!connected}
                trailing={{ kind: 'chevron' }}
                onPress={() => setEditing(r)}
              />
            )}
          </View>
        ))}
        {editing === 'new' ? (
          <ReminderEditor
            initial={{ title: '', at: nextHour(), repeat: 'once' }}
            onCancel={() => setEditing(null)}
            onSave={(d) => run(schedule.createReminder(d))}
          />
        ) : null}
      </SettingsSectionCard>
      {editing === null ? (
        <ThemedButton
          label={full ? t('settings.reminders.full') : t('settings.reminders.add')}
          size="lg"
          fullWidth
          disabled={!connected || !list || full}
          onPress={() => setEditing('new')}
        />
      ) : null}
    </SettingsPage>
  );
}
