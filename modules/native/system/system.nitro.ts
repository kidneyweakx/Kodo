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

  // ---- phone status (onboarding / settings) --------------------------------
  /** PowerManager.isIgnoringBatteryOptimizations(packageName). */
  isIgnoringBatteryOptimizations(): boolean;
  /** BluetoothAdapter.isEnabled; false when no adapter or on SecurityException. */
  isBluetoothEnabled(): boolean;
}
