/*
 * mi-band-9-active — settings tab: band status + display + band prefs + sync +
 * Health Connect + system access + about + danger zone.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */

import Constants from 'expo-constants';
import { router } from 'expo-router';
import { useMemo, useState } from 'react';
import { Alert, Pressable, ScrollView, View } from 'react-native';
import Animated, { FadeInDown } from 'react-native-reanimated';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Radius, Spacing, tabularNums } from '@/constants/DesignSystem';
import { ThemedSurface, ThemedText, readableTextOn } from '@/components/themed';
import { useTheme } from '@/context/ThemeContext';
import {
  SettingsDivider,
  SettingsRow,
  SettingsSection,
} from '@/components/settings/SettingsRow';
import {
  bandLink,
  useBatteryInfo,
  useConnectionState,
  usePairedBand,
} from '@/libs/services/bandLink';
import { cache, cacheKeys } from '@/libs/services/cache';
import { notificationBridge, useNotificationAccess } from '@/libs/services/notificationBridge';
import { permissions } from '@/libs/services/permissions';
import { getLocale, setLocale } from '@/libs/services/i18n';
import type { SupportedLocale } from '@/libs/services/i18n';
import type { ThemeId, ThemeMode, TintIntensity } from '@/context/ThemeContext';

const THEME_IDS: readonly { id: ThemeId; label: string }[] = [
  { id: 'aurora', label: 'Aurora' },
  { id: 'graphite', label: 'Graphite' },
  { id: 'ember', label: 'Ember' },
  { id: 'lagoon', label: 'Lagoon' },
];
const MODE_OPTS: readonly { v: ThemeMode; label: string }[] = [
  { v: 'auto', label: 'Auto' },
  { v: 'light', label: 'Light' },
  { v: 'dark', label: 'Dark' },
];
const TINT_OPTS: readonly { v: TintIntensity; label: string }[] = [
  { v: 'subtle', label: 'Subtle' },
  { v: 'balanced', label: 'Balanced' },
  { v: 'vivid', label: 'Vivid' },
];
const LANGS: readonly { code: SupportedLocale; label: string }[] = [
  { code: 'zh-Hant', label: '繁中' },
  { code: 'en', label: 'EN' },
];
const STEP_GOALS = [6_000, 8_000, 10_000, 12_000] as const;
const HR_INTERVALS = [
  { v: 'off' as const, label: 'Off' },
  { v: '1m' as const, label: '1m' },
  { v: '10m' as const, label: '10m' },
  { v: '30m' as const, label: '30m' },
];
const SYNC_INTERVALS = [
  { v: 0, label: 'Off' },
  { v: 30, label: '30m' },
  { v: 60, label: '60m' },
];

type SegmentedOption<T extends string | number> = { v: T; label: string };

function Segmented<T extends string | number>({
  options,
  value,
  onChange,
}: {
  readonly options: readonly SegmentedOption<T>[];
  readonly value: T;
  readonly onChange: (v: T) => void;
}) {
  const { theme } = useTheme();
  return (
    <View
      style={{
        flexDirection: 'row',
        backgroundColor: theme.background.tertiary,
        borderRadius: Radius.md,
        padding: 3,
        gap: 2,
      }}
    >
      {options.map((opt) => {
        const selected = opt.v === value;
        return (
          <Pressable
            key={String(opt.v)}
            onPress={() => onChange(opt.v)}
            accessibilityRole="radio"
            accessibilityState={{ selected }}
            style={{
              flex: 1,
              paddingVertical: 6,
              alignItems: 'center',
              borderRadius: Radius.sm,
              backgroundColor: selected ? theme.accent : 'transparent',
            }}
          >
            <ThemedText
              variant="caption"
              style={{
                color: selected ? readableTextOn(theme.accent) : theme.text.secondary,
                letterSpacing: 0.4,
                fontWeight: '600',
              }}
            >
              {opt.label}
            </ThemedText>
          </Pressable>
        );
      })}
    </View>
  );
}

function ThemeSwatchChips({
  value,
  onChange,
}: {
  readonly value: ThemeId;
  readonly onChange: (id: ThemeId) => void;
}) {
  const { theme } = useTheme();
  return (
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: Spacing.sm }}>
      {THEME_IDS.map((opt) => {
        const selected = opt.id === value;
        return (
          <Pressable
            key={opt.id}
            onPress={() => onChange(opt.id)}
            accessibilityRole="radio"
            accessibilityState={{ selected }}
            style={{
              paddingVertical: Spacing.sm,
              paddingHorizontal: Spacing.md,
              borderRadius: Radius.pill,
              borderWidth: 1.5,
              borderColor: selected ? theme.accent : theme.glassBorder,
              backgroundColor: selected ? `${theme.accent}22` : 'transparent',
              flexDirection: 'row',
              alignItems: 'center',
              gap: 6,
            }}
          >
            <View
              style={{
                width: 12,
                height: 12,
                borderRadius: 6,
                backgroundColor: opt.id === value ? theme.accent : theme.text.tertiary,
              }}
            />
            <ThemedText
              variant="caption"
              style={{
                color: selected ? theme.accent : theme.text.primary,
                fontWeight: '600',
                letterSpacing: 0.4,
              }}
            >
              {opt.label}
            </ThemedText>
          </Pressable>
        );
      })}
    </View>
  );
}

const formatRelative = (iso: string | null): string => {
  if (!iso) return '—';
  const delta = Date.now() - new Date(iso).getTime();
  if (delta < 60_000) return '剛剛 / now';
  if (delta < 3_600_000) return `${Math.floor(delta / 60_000)}m ago`;
  if (delta < 86_400_000) return `${Math.floor(delta / 3_600_000)}h ago`;
  return new Date(iso).toLocaleDateString();
};

const connectionLabel = (state: string): string =>
  state === 'connected' ? '已連線 / Linked'
    : state === 'connecting' || state === 'authenticating' ? '連線中 / Connecting'
      : state === 'scanning' ? '掃描中 / Scanning'
        : state === 'error' ? '錯誤 / Error'
          : '離線 / Offline';

export default function SettingsTab() {
  const { theme, themeId, themeMode, tintIntensity, increaseContrast,
    setTheme, setThemeMode, setTintIntensity, setIncreaseContrast } = useTheme();
  const paired = usePairedBand();
  const battery = useBatteryInfo();
  const connection = useConnectionState();
  const listenerGranted = useNotificationAccess();
  const [lang, setLang] = useState<SupportedLocale>(getLocale());

  // Preferences mirrored locally (band sync is best-effort).
  const [use24h, setUse24h] = useState<boolean>(
    () => cache.getSync<boolean>(cacheKeys.use24HourClock) ?? true,
  );
  const [stepGoal, setStepGoal] = useState<number>(
    () => cache.getSync<number>(cacheKeys.stepGoal) ?? 8_000,
  );
  const [hrInterval, setHrInterval] = useState<'off' | '1m' | '10m' | '30m'>(
    () => cache.getSync<'off' | '1m' | '10m' | '30m'>(cacheKeys.heartRateInterval) ?? '30m',
  );
  const [syncMin, setSyncMin] = useState<number>(
    () => cache.getSync<number>(cacheKeys.autoSyncIntervalMin) ?? 30,
  );
  const [muteDnd, setMuteDnd] = useState<boolean>(
    () => notificationBridge.getMuteWhenDndSync(),
  );

  const lastSync = cache.getSync<string>(cacheKeys.lastSyncAt);
  const version = (Constants.expoConfig as { version?: string } | null)?.version ?? '0.0.1';
  const platformVersion =
    (Constants.expoConfig as { android?: { versionCode?: number } } | null)?.android?.versionCode ?? 1;

  const onLang = (code: SupportedLocale) => {
    setLocale(code);
    cache.set(cacheKeys.language, code);
    setLang(code);
  };

  const onSetUse24h = (v: boolean) => {
    setUse24h(v);
    cache.set(cacheKeys.use24HourClock, v);
  };
  const onSetStepGoal = (v: number) => {
    setStepGoal(v);
    cache.set(cacheKeys.stepGoal, v);
  };
  const onSetHr = (v: 'off' | '1m' | '10m' | '30m') => {
    setHrInterval(v);
    cache.set(cacheKeys.heartRateInterval, v);
  };
  const onSetSync = (v: number) => {
    setSyncMin(v);
    cache.set(cacheKeys.autoSyncIntervalMin, v);
  };
  const onSetMuteDnd = (v: boolean) => {
    setMuteDnd(v);
    notificationBridge.setMuteWhenDnd(v);
  };

  const onSyncNow = async () => {
    try {
      const since = new Date(Date.now() - 86_400_000).toISOString();
      await bandLink.syncSince(since);
    } catch (e) {
      Alert.alert('Sync failed', e instanceof Error ? e.message : String(e));
    }
  };

  const onUnpair = () => {
    Alert.alert(
      '解除配對 / Unpair',
      '會清掉手環的金鑰跟同步紀錄,要重新走 onboarding 配對。',
      [
        { text: '取消 / Cancel', style: 'cancel' },
        {
          text: '解除 / Unpair',
          style: 'destructive',
          onPress: async () => {
            await bandLink.forget();
            cache.remove(cacheKeys.onboardingDone);
            router.replace('/(onboarding)/welcome');
          },
        },
      ],
    );
  };

  const onClearAllData = () => {
    Alert.alert(
      '清除所有資料 / Clear all data',
      '會清掉同步資料、設定、配對 — 不可復原。',
      [
        { text: '取消 / Cancel', style: 'cancel' },
        {
          text: '清除 / Clear',
          style: 'destructive',
          onPress: () => {
            cache.clear();
            router.replace('/(onboarding)/welcome');
          },
        },
      ],
    );
  };

  const connectionTone = useMemo(() => {
    if (connection === 'connected') return theme.success;
    if (connection === 'error') return theme.danger;
    return theme.text.tertiary;
  }, [connection, theme]);

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: theme.background.primary }} edges={['top']}>
      <ScrollView
        contentContainerStyle={{
          paddingHorizontal: Spacing.lg,
          paddingTop: Spacing.lg,
          paddingBottom: Spacing.xxxl + Spacing.xxl,
          gap: Spacing.xl,
        }}
        showsVerticalScrollIndicator={false}
      >
        {/* Header */}
        <Animated.View entering={FadeInDown.duration(320)}>
          <ThemedText variant="eyebrow" tone="accent">
            SETTINGS · 設定
          </ThemedText>
          <ThemedText variant="headlineLarge" style={{ marginTop: 2 }}>
            偏好設定
          </ThemedText>
        </Animated.View>

        {/* Band hero */}
        <ThemedSurface variant="elevated" padded="lg" radius="lg">
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', gap: Spacing.md }}>
            <View style={{ flex: 1, gap: 4 }}>
              <ThemedText variant="eyebrow" tone="tertiary">
                BAND · 手環
              </ThemedText>
              <ThemedText variant="titleLarge">
                {paired?.name ?? 'Not paired'}
              </ThemedText>
              <ThemedText variant="caption" tone="tertiary" style={tabularNums}>
                {paired?.id ?? '—'}
              </ThemedText>
              <View
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: 6,
                  marginTop: 4,
                }}
              >
                <View
                  style={{
                    width: 6,
                    height: 6,
                    borderRadius: 3,
                    backgroundColor: connectionTone,
                  }}
                />
                <ThemedText
                  variant="caption"
                  style={{ color: connectionTone, letterSpacing: 0.6 }}
                >
                  {connectionLabel(connection)} · Last sync {formatRelative(lastSync)}
                </ThemedText>
              </View>
            </View>
            {battery && (
              <View style={{ alignItems: 'flex-end', gap: 0 }}>
                <View style={{ flexDirection: 'row', alignItems: 'baseline', gap: 2 }}>
                  <ThemedText variant="displayMedium">
                    {Math.round(battery.percent)}
                  </ThemedText>
                  <ThemedText variant="titleMedium" tone="secondary">
                    %
                  </ThemedText>
                </View>
                <ThemedText
                  variant="caption"
                  tone={battery.charging ? 'accent' : 'tertiary'}
                  style={{ letterSpacing: 0.6 }}
                >
                  {battery.charging ? '⚡ Charging' : 'BATTERY'}
                </ThemedText>
              </View>
            )}
          </View>
        </ThemedSurface>

        {/* Display */}
        <SettingsSection title="DISPLAY · 顯示" caption="App appearance on this phone">
          <SettingsRow
            kind="custom"
            label="Mode · 模式"
            first
            accessory={<View style={{ width: 220 }}><Segmented options={MODE_OPTS} value={themeMode} onChange={setThemeMode} /></View>}
          />
          <SettingsDivider />
          <SettingsRow
            kind="custom"
            label="Theme · 配色"
            sublabel="Accent + background pair"
            belowAccessory={<ThemeSwatchChips value={themeId} onChange={setTheme} />}
          />
          <SettingsDivider />
          <SettingsRow
            kind="custom"
            label="Tint · 強度"
            accessory={<View style={{ width: 220 }}><Segmented options={TINT_OPTS} value={tintIntensity} onChange={setTintIntensity} /></View>}
          />
          <SettingsDivider />
          <SettingsRow
            kind="switch"
            label="Increase contrast · 高對比"
            sublabel="Stronger text + border contrast"
            switchValue={increaseContrast}
            onSwitchChange={setIncreaseContrast}
            last
          />
        </SettingsSection>

        {/* Language */}
        <SettingsSection title="LANGUAGE · 語言">
          <SettingsRow
            kind="custom"
            label="Display language"
            first
            last
            accessory={<View style={{ width: 160 }}><Segmented options={LANGS.map((l) => ({ v: l.code, label: l.label }))} value={lang} onChange={onLang} /></View>}
          />
        </SettingsSection>

        {/* Band preferences */}
        <SettingsSection title="BAND · 手環偏好" caption="Synced to the band on next handshake">
          <SettingsRow
            kind="switch"
            label="24-hour clock · 24 小時制"
            switchValue={use24h}
            onSwitchChange={onSetUse24h}
            first
          />
          <SettingsDivider />
          <SettingsRow
            kind="custom"
            label="Step goal · 步數目標"
            accessory={
              <View style={{ width: 220 }}>
                <Segmented
                  options={STEP_GOALS.map((g) => ({ v: g, label: `${g / 1000}k` }))}
                  value={stepGoal}
                  onChange={onSetStepGoal}
                />
              </View>
            }
          />
          <SettingsDivider />
          <SettingsRow
            kind="custom"
            label="HR interval · 心率間隔"
            sublabel="Power-aware default is 30m"
            accessory={<View style={{ width: 220 }}><Segmented options={HR_INTERVALS} value={hrInterval} onChange={onSetHr} /></View>}
          />
          <SettingsDivider />
          <SettingsRow
            kind="action"
            label="Sync clock to band · 同步時間"
            value="Sync"
            onPress={() => {
              // Best-effort — relies on native HybridSystemControl.syncClock
              import('@/modules/native').then((m) => m.NativeSystemControl().syncClock().catch(() => undefined));
            }}
            last
          />
        </SettingsSection>

        {/* Sync */}
        <SettingsSection title="SYNC · 同步" caption="Background pull on a power-aware schedule">
          <SettingsRow
            kind="custom"
            label="Auto sync · 自動同步"
            accessory={<View style={{ width: 220 }}><Segmented options={SYNC_INTERVALS} value={syncMin} onChange={onSetSync} /></View>}
            first
          />
          <SettingsDivider />
          <SettingsRow
            kind="display"
            label="Last sync · 上次同步"
            value={formatRelative(lastSync)}
          />
          <SettingsDivider />
          <SettingsRow
            kind="action"
            label="Sync now · 立即同步"
            value="Run"
            tone="accent"
            onPress={onSyncNow}
            last
          />
        </SettingsSection>

        {/* Notifications & Health Connect */}
        <SettingsSection title="DATA · 資料權限">
          <SettingsRow
            kind="action"
            label="Notification listener · 通知存取"
            sublabel={listenerGranted ? '已授權 · Granted' : '未授權 — 系統設定中開啟'}
            value={listenerGranted ? 'Granted' : 'Grant'}
            tone={listenerGranted ? 'accent' : 'default'}
            onPress={() => permissions.openNotificationListenerSettings()}
            first
          />
          <SettingsDivider />
          <SettingsRow
            kind="switch"
            label="Mute when DND · 勿擾時靜音"
            sublabel="Drop notifications while phone is in DND"
            switchValue={muteDnd}
            onSwitchChange={onSetMuteDnd}
          />
          <SettingsDivider />
          <SettingsRow
            kind="action"
            label="Health Connect · 健康資料"
            sublabel="Google Fit / Sleep as Android read from here"
            value="Open"
            onPress={() => {
              // Health Connect Settings deep link via package URI
              import('react-native').then(({ Linking }) =>
                Linking.sendIntent('androidx.health.ACTION_HEALTH_CONNECT_SETTINGS').catch(() =>
                  Linking.openSettings(),
                ),
              );
            }}
          />
          <SettingsDivider />
          <SettingsRow
            kind="action"
            label="Battery optimization · 省電白名單"
            sublabel="Background sync needs this OFF for our package"
            value="Open"
            onPress={() => permissions.openBatteryOptimizationSettings()}
            last
          />
        </SettingsSection>

        {/* About */}
        <SettingsSection title="ABOUT · 關於">
          <SettingsRow
            kind="display"
            label="App version"
            value={`${version} · build ${platformVersion}`}
            first
          />
          <SettingsDivider />
          <SettingsRow
            kind="display"
            label="License"
            value="AGPL-3.0-or-later"
          />
          <SettingsDivider />
          <SettingsRow
            kind="display"
            label="Ported from"
            value="Gadgetbridge"
            last
          />
        </SettingsSection>

        {/* Danger zone */}
        <SettingsSection title="DANGER · 危險區" caption="These are irreversible">
          <SettingsRow
            kind="action"
            label="解除配對 / Unpair band"
            value="Unpair"
            tone="destructive"
            onPress={onUnpair}
            first
          />
          <SettingsDivider />
          <SettingsRow
            kind="action"
            label="清除所有資料 / Clear all data"
            sublabel="Forgets band, settings, cached samples"
            value="Clear"
            tone="destructive"
            onPress={onClearAllData}
            last
          />
        </SettingsSection>
      </ScrollView>
    </SafeAreaView>
  );
}
