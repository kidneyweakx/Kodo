/*
 * mi-band-9-active — Nitro HybridObject spec for system-level band actions.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported semantically from Gadgetbridge (AGPL-3.0):
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiSystemService
 *     (clock, language, device info, battery, find phone, vibration patterns)
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiHealthService
 *     (settings only: user info, heart-rate / SpO2 / stress config, goal
 *      notification, goals, vitality score)
 *   - nodomain.freeyourgadget.gadgetbridge.devices.xiaomi.XiaomiCoordinator (feature gating)
 *
 * Also ported (XiaomiSystemService / XiaomiNotificationService): live device
 * state (worn / asleep / charging, 2/78 + 2/79), band lock password (2/9 +
 * 2/21), display items (2/29 + 2/30) and "screen on for notifications"
 * (7/6 + 7/7). Each is gated by the band's own answer (BandFeatures).
 *
 * Mi Band 9 Active does NOT support `findDevice` (phone -> band ring) nor
 * manual heart-rate measurement (MiBand9ActiveCoordinator). Wrist-raise and
 * band DND are not implemented upstream for Xiaomi protobuf devices, so they
 * are intentionally not exposed.
 *
 * Every getter returns a persisted value that came from the band (after the
 * last connect) or from the user (pending push). `undefined` means "we do not
 * know yet" — render "—", never a default.
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type { BatteryInfo } from '../types';

export interface BandDeviceInfo {
  readonly serialNumber: string;
  readonly firmware: string;
  readonly model: string;
}

export interface BandSystemSettings {
  readonly use24HourClock: boolean;
  /** 'auto' (follow phone locale) or one of getSupportedLanguages(), e.g. 'zh_TW'. */
  readonly language: string;
}

export type HeartRateInterval = 'off' | 'smart' | '1m' | '10m' | '30m';
export type SecondaryGoal = 'standing_time' | 'active_time';

export interface HealthMonitoringSettings {
  readonly heartRateInterval: HeartRateInterval;
  /** "Use heart rate for sleep detection" (advanced monitoring). */
  readonly heartRateSleepDetection: boolean;
  readonly sleepBreathingQuality: boolean;
  /** bpm, 0 = alert off. Band accepts 100..150 in steps of 10. */
  readonly heartRateHighAlertBpm: number;
  /** bpm, 0 = alert off. Band accepts 40, 45, 50. */
  readonly heartRateLowAlertBpm: number;
  readonly spo2AllDay: boolean;
  /** %, 0 = alert off. Band accepts 80, 85, 90. */
  readonly spo2LowAlertPct: number;
  readonly stressAllDay: boolean;
  readonly stressRelaxReminder: boolean;
  readonly goalNotification: boolean;
  readonly secondaryGoal: SecondaryGoal;
  readonly vitalitySevenDay: boolean;
  readonly vitalityDaily: boolean;
}

export type UserGender = 'male' | 'female' | 'other';

export interface UserProfile {
  readonly heightCm: number;
  readonly weightKg: number;
  readonly birthYear: number;
  readonly birthMonth: number;
  readonly birthDay: number;
  readonly gender: UserGender;
  readonly stepGoal: number;
  readonly calorieGoal: number;
  readonly standingHoursGoal: number;
  readonly activeMinutesGoal: number;
}

/** What the band itself reported it supports (answers to the GET commands). */
export interface BandFeatures {
  readonly heartRateConfig: boolean;
  readonly spo2: boolean;
  readonly stress: boolean;
  readonly inactivity: boolean;
  readonly goalNotification: boolean;
  readonly secondaryGoal: boolean;
  readonly vitalityScore: boolean;
  readonly sleepModeSchedule: boolean;
  readonly cameraRemote: boolean;
  readonly multipleWeatherLocations: boolean;
  readonly alarmSlots: number;
  readonly reminderSlots: number;
  /** Always false on Mi Band 9 Active (MiBand9ActiveCoordinator). */
  readonly findBand: boolean;
  /** Always false on Mi Band 9 Active (MiBand9ActiveCoordinator). */
  readonly manualHeartRate: boolean;
  /** XiaomiCoordinator.supportsRealtimeData() — always true; see HybridBandLink.startRealtimeHeartRate. */
  readonly realtimeHeartRate: boolean;
  /** Band answered the device-state GET (FEAT_DEVICE_ACTIONS): worn / asleep / charging available. */
  readonly deviceState: boolean;
  /** Band answered the password GET (FEAT_PASSWORD). */
  readonly password: boolean;
  /** Band reported a non-empty display-item list (FEAT_DISPLAY_ITEMS). */
  readonly displayItems: boolean;
  /** Band answered the screen-on-for-notifications GET (FEAT_SCREEN_ON_ON_NOTIFICATIONS). */
  readonly screenOnOnNotifications: boolean;
}

/**
 * Live band state (XiaomiSystemService.handleBasicDeviceState / handleDeviceState).
 * Not persisted: cleared on disconnect, so `undefined` = not connected / not reported.
 */
export interface BandDeviceState {
  readonly charging?: boolean;
  readonly worn?: boolean;
  readonly asleep?: boolean;
  /** Epoch ms of the last band report. */
  readonly updatedAt: number;
}

/** Band lock (PasswordCapabilityImpl.Mode.NUMBERS_6). The digits never leave native. */
export interface BandPasswordState {
  readonly enabled: boolean;
  /** A 6-digit password is known (from the band or set here). */
  readonly hasPassword: boolean;
}

export interface BandDisplayItem {
  /** Band screen code, e.g. 'heart_rate'. */
  readonly code: string;
  /** Band-provided label (band language). */
  readonly name: string;
  readonly enabled: boolean;
  /** Enabled and shown in the band's "More" section. */
  readonly inMoreSection: boolean;
  /** The band's settings screen: always kept enabled. */
  readonly isSettings: boolean;
}

export interface BandDisplayItems {
  /** Enabled items in band order (main, then More), then disabled ones. */
  readonly items: readonly BandDisplayItem[];
  /** Epoch ms when stored (band answer or local change). */
  readonly fetchedAt: number;
}

export type VibrationCategory =
  | 'none'
  | 'call'
  | 'task'
  | 'alarm'
  | 'notification'
  | 'standing'
  | 'sms'
  | 'goal'
  | 'event'
  | 'unknown';

export interface VibrationAssignment {
  readonly category: VibrationCategory;
  /** Band vibrator id assigned to this category (0 = default). */
  readonly presetId: number;
}

export interface VibrationCustomPattern {
  readonly id: number;
  readonly name: string;
  readonly category: VibrationCategory;
}

/** Read-only: editing custom patterns is not supported on this band upstream. */
export interface VibrationPatternsInfo {
  readonly assignments: readonly VibrationAssignment[];
  readonly customPatterns: readonly VibrationCustomPattern[];
  readonly fetchedAt: number;
}

export interface HybridSystemControl extends HybridObject<{ android: 'kotlin' }> {
  // ---- find phone (band -> phone) ------------------------------------------
  /** Start ringing locally (e.g. for a test button). */
  ringPhone(): void;
  /** Stop ringing and tell the band the phone was found. */
  silencePhone(): void;
  readonly isRinging: boolean;
  /** Fires true when the band starts a find-phone, false when ringing stops. */
  onFindPhone(listener: (ringing: boolean) => void): () => void;

  // ---- clock / language ----------------------------------------------------
  /** Push phone time, timezone, DST and 12/24h to the band. Resolves false if not connected. */
  syncClock(): Promise<boolean>;
  getSupportedLanguages(): string[];
  getSystemSettings(): BandSystemSettings;
  /** Persists; pushes clock format + language now if connected, else on next connect. */
  setSystemSettings(settings: BandSystemSettings): Promise<BandSystemSettings>;

  // ---- device info / battery ----------------------------------------------
  /** Last device info the band reported (persisted), undefined if never received. */
  getDeviceInfo(): BandDeviceInfo | undefined;
  requestDeviceInfo(): Promise<BandDeviceInfo | undefined>;
  /** Asks the band for its battery; undefined if not connected / no reply in 5 s. */
  requestBattery(): Promise<BatteryInfo | undefined>;

  // ---- feature flags ------------------------------------------------------
  getFeatures(): BandFeatures | undefined;

  // ---- health monitoring settings ------------------------------------------
  getHealthMonitoring(): HealthMonitoringSettings | undefined;
  readonly healthMonitoringPendingPush: boolean;
  refreshHealthMonitoring(): Promise<HealthMonitoringSettings | undefined>;
  setHealthMonitoring(settings: HealthMonitoringSettings): Promise<HealthMonitoringSettings>;

  // ---- user profile (drives band kcal / goals) -----------------------------
  /** undefined until the user entered a profile — we never push a made-up one. */
  getUserProfile(): UserProfile | undefined;
  setUserProfile(profile: UserProfile): Promise<UserProfile>;

  // ---- vibration patterns (read-only) --------------------------------------
  getVibrationPatterns(): VibrationPatternsInfo | undefined;
  refreshVibrationPatterns(): Promise<VibrationPatternsInfo | undefined>;

  // ---- live device state ---------------------------------------------------
  getDeviceState(): BandDeviceState | undefined;
  /** Fires on every change of worn / asleep / charging while connected. */
  onDeviceStateChange(listener: (state: BandDeviceState) => void): () => void;

  // ---- band lock password (FEAT_PASSWORD) ----------------------------------
  /** Persisted; undefined until the band answered or the user set one. */
  getPassword(): BandPasswordState | undefined;
  refreshPassword(): Promise<BandPasswordState | undefined>;
  /**
   * Persists + pushes now, or on the next connect. `password` must be exactly
   * 6 digits; omit it to keep the stored one (e.g. to disable the lock).
   * Rejects when no valid password is known.
   */
  setPassword(enabled: boolean, password?: string): Promise<BandPasswordState>;

  // ---- display items (FEAT_DISPLAY_ITEMS) ----------------------------------
  getDisplayItems(): BandDisplayItems | undefined;
  refreshDisplayItems(): Promise<BandDisplayItems | undefined>;
  /**
   * Enabled item codes in display order; codes after the marker 'more' go to
   * the "More" section; unknown codes are ignored; the settings item is always
   * kept. Pushes now or on the next connect. Rejects if the list was never
   * received from the band.
   */
  setDisplayItems(enabledCodes: readonly string[]): Promise<BandDisplayItems>;

  // ---- screen on for notifications (FEAT_SCREEN_ON_ON_NOTIFICATIONS) -------
  getScreenOnOnNotifications(): boolean | undefined;
  refreshScreenOnOnNotifications(): Promise<boolean | undefined>;
  setScreenOnOnNotifications(enabled: boolean): Promise<boolean>;

  // ---- phone status (onboarding / settings) --------------------------------
  /** PowerManager.isIgnoringBatteryOptimizations(packageName). */
  isIgnoringBatteryOptimizations(): boolean;
  /** BluetoothAdapter.isEnabled; false when no adapter or on SecurityException. */
  isBluetoothEnabled(): boolean;
}
