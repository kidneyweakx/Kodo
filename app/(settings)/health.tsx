/*
 * mi-band-9-active — Settings › Health monitoring.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Mirrors XiaomiHealthService's settings screen. Values are the band's own
 * (fetched on connect) or the user's pending edit. When neither exists yet
 * the section is disabled with a "connect to read" hint instead of showing
 * made-up defaults. The profile is only sent once every field is filled.
 */

import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
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
import { useConnectionState } from '@/libs/services/bandLink';
import { system } from '@/libs/services/system';
import { t } from '@/libs/services/i18n';
import type {
  HealthMonitoringSettings,
  HeartRateInterval,
  SedentaryConfig,
  UserGender,
  UserProfile,
} from '@/modules/native';

const HR_INTERVALS: readonly HeartRateInterval[] = ['off', 'smart', '1m', '10m', '30m'];
const HR_HIGH = [0, 100, 110, 120, 130, 140, 150] as const;
const HR_LOW = [0, 40, 45, 50] as const;
const SPO2_LOW = [0, 80, 85, 90] as const;
const STEP_GOALS = [4000, 6000, 8000, 10000, 12000, 15000, 20000] as const;

const offOr = (v: number, unit: string) => (v === 0 ? t('settings.common.off') : `${v}${unit}`);

function NumberField({
  label,
  value,
  onChange,
  keyboard = 'number-pad',
}: {
  readonly label: string;
  readonly value: string;
  readonly onChange: (v: string) => void;
  readonly keyboard?: 'number-pad' | 'numbers-and-punctuation';
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
        keyboardType={keyboard}
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

const parseProfile = (
  f: { height: string; weight: string; birthday: string; gender: UserGender | null },
  goals: { stepGoal: number; base?: UserProfile },
): UserProfile | null => {
  const height = Number(f.height);
  const weight = Number(f.weight);
  const m = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(f.birthday.trim());
  if (!m || !f.gender || !(height >= 80 && height <= 250) || !(weight >= 20 && weight <= 300)) return null;
  const [year, month, day] = [Number(m[1]), Number(m[2]), Number(m[3])];
  if (month < 1 || month > 12 || day < 1 || day > 31 || year < 1900) return null;
  return {
    heightCm: Math.round(height),
    weightKg: Math.round(weight),
    birthYear: year,
    birthMonth: month,
    birthDay: day,
    gender: f.gender,
    stepGoal: goals.stepGoal,
    // Goals the UI doesn't edit keep the band's values; if the band never
    // reported any we send its documented defaults (upstream XiaomiPreferences).
    calorieGoal: goals.base?.calorieGoal ?? 350,
    standingHoursGoal: goals.base?.standingHoursGoal ?? 12,
    activeMinutesGoal: goals.base?.activeMinutesGoal ?? 30,
  };
};

export default function HealthSettings() {
  const { theme } = useTheme();
  const connected = useConnectionState() === 'connected';
  const [hm, setHm] = useState<HealthMonitoringSettings | undefined>(() => system.getHealthMonitoring());
  const [sed, setSed] = useState<SedentaryConfig | undefined>(() => system.getSedentary());
  const [profile, setProfile] = useState<UserProfile | undefined>(() => system.getUserProfile());
  const [form, setForm] = useState(() => ({
    height: profile ? String(profile.heightCm) : '',
    weight: profile ? String(profile.weightKg) : '',
    birthday: profile
      ? `${profile.birthYear}-${String(profile.birthMonth).padStart(2, '0')}-${String(profile.birthDay).padStart(2, '0')}`
      : '',
    gender: (profile?.gender ?? null) as UserGender | null,
  }));
  const [stepGoal, setStepGoal] = useState<number>(profile?.stepGoal ?? 8000);
  const [saving, setSaving] = useState(false);

  useFocusEffect(
    useCallback(() => {
      if (!connected) return;
      void system.refreshHealthMonitoring().then((v) => v && setHm(v));
      void system.refreshSedentary().then((v) => v && setSed(v));
    }, [connected]),
  );

  const patchHm = (patch: Partial<HealthMonitoringSettings>) => {
    if (!hm) return;
    const next = { ...hm, ...patch };
    setHm(next);
    void system.setHealthMonitoring(next).then(setHm).catch(() => undefined);
  };
  const patchSed = (patch: Partial<SedentaryConfig>) => {
    if (!sed) return;
    const next = { ...sed, ...patch };
    setSed(next);
    void system.setSedentary(next).then(setSed).catch(() => undefined);
  };

  const draft = parseProfile(form, { stepGoal, base: profile });
  const onSaveProfile = async () => {
    if (!draft) return;
    setSaving(true);
    try {
      setProfile(await system.setUserProfile(draft));
    } finally {
      setSaving(false);
    }
  };

  const unknown = t('settings.common.unknown');
  const pendingNote = (pending: boolean) => (pending ? t('settings.common.pending') : undefined);

  return (
    <SettingsPage title={t('settings.nav.health.title')}>
      <SettingsSectionCard
        title={t('settings.health.hr')}
        icon="heart"
        description={hm ? pendingNote(system.healthMonitoringPendingPush()) : unknown}
      >
        <SettingsItem
          icon="heart"
          title={t('settings.health.hrInterval')}
          disabled={!hm}
          below={
            <SettingsChoice
              options={HR_INTERVALS.map((v) => ({ value: v, label: t(`settings.health.intervals.${v}`) }))}
              value={hm?.heartRateInterval ?? 'off'}
              onChange={(v) => patchHm({ heartRateInterval: v })}
              disabled={!hm}
            />
          }
        />
        <SettingsDivider />
        <SettingsItem
          icon="moon"
          title={t('settings.health.hrSleep')}
          subtitle={t('settings.health.hrSleepBody')}
          disabled={!hm}
          trailing={{ kind: 'switch', value: hm?.heartRateSleepDetection ?? false, onChange: (v) => patchHm({ heartRateSleepDetection: v }) }}
        />
        <SettingsDivider />
        <SettingsItem
          icon="moon"
          title={t('settings.health.breathing')}
          disabled={!hm}
          trailing={{ kind: 'switch', value: hm?.sleepBreathingQuality ?? false, onChange: (v) => patchHm({ sleepBreathingQuality: v }) }}
        />
        <SettingsDivider />
        <SettingsItem
          icon="heart"
          title={t('settings.health.hrHigh')}
          disabled={!hm}
          below={
            <SettingsSlider
              stops={HR_HIGH}
              value={hm?.heartRateHighAlertBpm ?? 0}
              onChange={(v) => patchHm({ heartRateHighAlertBpm: v })}
              format={(v) => offOr(v, '')}
              disabled={!hm}
            />
          }
        />
        <SettingsDivider />
        <SettingsItem
          icon="heart"
          title={t('settings.health.hrLow')}
          disabled={!hm}
          below={
            <SettingsChoice
              options={HR_LOW.map((v) => ({ value: v, label: offOr(v, '') }))}
              value={hm?.heartRateLowAlertBpm ?? 0}
              onChange={(v) => patchHm({ heartRateLowAlertBpm: v })}
              disabled={!hm}
            />
          }
        />
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.health.spo2')} icon="shield">
        <SettingsItem
          icon="shield"
          title={t('settings.health.spo2AllDay')}
          subtitle={hm ? undefined : unknown}
          disabled={!hm}
          trailing={{ kind: 'switch', value: hm?.spo2AllDay ?? false, onChange: (v) => patchHm({ spo2AllDay: v }) }}
        />
        <SettingsDivider />
        <SettingsItem
          icon="shield"
          title={t('settings.health.spo2Low')}
          disabled={!hm}
          below={
            <SettingsChoice
              options={SPO2_LOW.map((v) => ({ value: v, label: offOr(v, '%') }))}
              value={hm?.spo2LowAlertPct ?? 0}
              onChange={(v) => patchHm({ spo2LowAlertPct: v })}
              disabled={!hm}
            />
          }
        />
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.health.stress')} icon="wrist">
        <SettingsItem
          icon="wrist"
          title={t('settings.health.stressAllDay')}
          subtitle={hm ? undefined : unknown}
          disabled={!hm}
          trailing={{ kind: 'switch', value: hm?.stressAllDay ?? false, onChange: (v) => patchHm({ stressAllDay: v }) }}
        />
        <SettingsDivider />
        <SettingsItem
          icon="moon"
          title={t('settings.health.relax')}
          disabled={!hm}
          trailing={{ kind: 'switch', value: hm?.stressRelaxReminder ?? false, onChange: (v) => patchHm({ stressRelaxReminder: v }) }}
        />
      </SettingsSectionCard>

      <SettingsSectionCard
        title={t('settings.health.sedentary')}
        icon="walk"
        description={sed ? pendingNote(system.sedentaryPendingPush()) : unknown}
      >
        <SettingsItem
          icon="walk"
          title={t('settings.health.sedentary')}
          subtitle={t('settings.health.sedentaryBody')}
          disabled={!sed}
          trailing={{ kind: 'switch', value: sed?.enabled ?? false, onChange: (v) => patchSed({ enabled: v }) }}
        />
        {sed?.enabled ? (
          <>
            <SettingsDivider />
            <SettingsItem
              icon="clock"
              title={t('settings.health.from')}
              below={
                <SettingsSlider
                  stops={[6, 7, 8, 9, 10, 11, 12]}
                  value={sed.start.hour}
                  onChange={(h) => patchSed({ start: { hour: h, minute: 0 } })}
                  format={(h) => `${h}:00`}
                />
              }
            />
            <SettingsDivider />
            <SettingsItem
              icon="clock"
              title={t('settings.health.to')}
              below={
                <SettingsSlider
                  stops={[17, 18, 19, 20, 21, 22, 23]}
                  value={sed.end.hour}
                  onChange={(h) => patchSed({ end: { hour: h, minute: 0 } })}
                  format={(h) => `${h}:00`}
                />
              }
            />
            <SettingsDivider />
            <SettingsItem
              icon="moon"
              title={t('settings.health.lunchBreak')}
              trailing={{
                kind: 'switch',
                value: sed.dndEnabled,
                onChange: (v) =>
                  patchSed({ dndEnabled: v, dndStart: { hour: 12, minute: 0 }, dndEnd: { hour: 14, minute: 0 } }),
              }}
            />
          </>
        ) : null}
      </SettingsSectionCard>

      <SettingsSectionCard title={t('settings.health.goals')} icon="person" description={t('settings.health.goalsBody')}>
        <SettingsItem
          icon="walk"
          title={t('settings.health.stepGoal')}
          below={
            <SettingsSlider
              stops={STEP_GOALS}
              value={stepGoal}
              onChange={setStepGoal}
              format={(v) => `${v / 1000}k`}
            />
          }
        />
        <SettingsDivider />
        <SettingsItem
          icon="bell"
          title={t('settings.health.goalNotify')}
          disabled={!hm}
          trailing={{ kind: 'switch', value: hm?.goalNotification ?? false, onChange: (v) => patchHm({ goalNotification: v }) }}
        />
        <SettingsDivider />
        <View style={{ padding: Spacing.sm, gap: Spacing.md }}>
          <View style={{ flexDirection: 'row', gap: Spacing.md }}>
            <NumberField label={t('settings.health.height')} value={form.height} onChange={(v) => setForm((f) => ({ ...f, height: v }))} />
            <NumberField label={t('settings.health.weight')} value={form.weight} onChange={(v) => setForm((f) => ({ ...f, weight: v }))} />
          </View>
          <NumberField
            label={t('settings.health.birthday')}
            value={form.birthday}
            keyboard="numbers-and-punctuation"
            onChange={(v) => setForm((f) => ({ ...f, birthday: v }))}
          />
          <View style={{ gap: Spacing.xs }}>
            <ThemedText variant="caption" tone="secondary">
              {t('settings.health.gender')}
            </ThemedText>
            <SettingsChoice
              options={(['male', 'female', 'other'] as const).map((g) => ({ value: g, label: t(`settings.health.genders.${g}`) }))}
              value={form.gender ?? ('' as UserGender)}
              onChange={(g) => setForm((f) => ({ ...f, gender: g }))}
            />
          </View>
          {!draft ? (
            <ThemedText variant="caption" style={{ color: theme.text.tertiary }}>
              {t('settings.health.profileMissing')}
            </ThemedText>
          ) : null}
          <ThemedButton
            label={saving ? t('settings.common.saving') : t('settings.common.save')}
            loading={saving}
            disabled={!draft}
            fullWidth
            onPress={onSaveProfile}
          />
        </View>
      </SettingsSectionCard>
    </SettingsPage>
  );
}
