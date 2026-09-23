/*
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * JS facade over HybridSystemControl, HybridSedentary, HybridCameraRemote and
 * HybridGpsTracker settings. Sync getters read native persisted state and are
 * safe on the render path; `undefined` means "not known yet" (render "—").
 * Mutations reject when the native module is missing so the UI can show it.
 */

import { NativeCameraRemote, NativeGpsTracker, NativeSedentary, NativeSystemControl } from '@/modules/native';
import { safeAsync, safeCall, safeUnsubscribe } from '@/modules/native/safe';
import type {
  BandDeviceInfo,
  BandFeatures,
  BandSystemSettings,
  BatteryInfo,
  HealthMonitoringSettings,
  SedentaryConfig,
  UserProfile,
  VibrationPatternsInfo,
} from '@/modules/native';

export const system = {
  // ---- phone status
  isIgnoringBatteryOptimizations(): boolean {
    return safeCall(() => NativeSystemControl().isIgnoringBatteryOptimizations(), false);
  },
  isBluetoothEnabled(): boolean {
    return safeCall(() => NativeSystemControl().isBluetoothEnabled(), false);
  },

  // ---- find phone
  isRinging(): boolean {
    return safeCall(() => NativeSystemControl().isRinging, false);
  },
  silencePhone(): void {
    safeCall(() => NativeSystemControl().silencePhone(), undefined);
  },
  onFindPhone(listener: (ringing: boolean) => void): () => void {
    return safeUnsubscribe(() => NativeSystemControl().onFindPhone(listener));
  },

  // ---- clock / language
  syncClock(): Promise<boolean> {
    return safeAsync(() => NativeSystemControl().syncClock(), false);
  },
  supportedLanguages(): string[] {
    return safeCall(() => NativeSystemControl().getSupportedLanguages(), ['auto']);
  },
  getSystemSettings(): BandSystemSettings | undefined {
    return safeCall<BandSystemSettings | undefined>(() => NativeSystemControl().getSystemSettings(), undefined);
  },
  setSystemSettings(settings: BandSystemSettings): Promise<BandSystemSettings> {
    return NativeSystemControl().setSystemSettings(settings);
  },

  // ---- device info / battery / features
  getDeviceInfo(): BandDeviceInfo | undefined {
    return safeCall(() => NativeSystemControl().getDeviceInfo(), undefined);
  },
  requestDeviceInfo(): Promise<BandDeviceInfo | undefined> {
    return safeAsync(() => NativeSystemControl().requestDeviceInfo(), undefined);
  },
  requestBattery(): Promise<BatteryInfo | undefined> {
    return safeAsync(() => NativeSystemControl().requestBattery(), undefined);
  },
  getFeatures(): BandFeatures | undefined {
    return safeCall(() => NativeSystemControl().getFeatures(), undefined);
  },

  // ---- health monitoring
  getHealthMonitoring(): HealthMonitoringSettings | undefined {
    return safeCall(() => NativeSystemControl().getHealthMonitoring(), undefined);
  },
  healthMonitoringPendingPush(): boolean {
    return safeCall(() => NativeSystemControl().healthMonitoringPendingPush, false);
  },
  refreshHealthMonitoring(): Promise<HealthMonitoringSettings | undefined> {
    return safeAsync(() => NativeSystemControl().refreshHealthMonitoring(), undefined);
  },
  setHealthMonitoring(settings: HealthMonitoringSettings): Promise<HealthMonitoringSettings> {
    return NativeSystemControl().setHealthMonitoring(settings);
  },

  // ---- user profile
  getUserProfile(): UserProfile | undefined {
    return safeCall(() => NativeSystemControl().getUserProfile(), undefined);
  },
  setUserProfile(profile: UserProfile): Promise<UserProfile> {
    return NativeSystemControl().setUserProfile(profile);
  },

  // ---- vibration (read-only)
  getVibrationPatterns(): VibrationPatternsInfo | undefined {
    return safeCall(() => NativeSystemControl().getVibrationPatterns(), undefined);
  },
  refreshVibrationPatterns(): Promise<VibrationPatternsInfo | undefined> {
    return safeAsync(() => NativeSystemControl().refreshVibrationPatterns(), undefined);
  },

  // ---- sedentary / inactivity
  getSedentary(): SedentaryConfig | undefined {
    return safeCall(() => NativeSedentary().get(), undefined);
  },
  sedentaryPendingPush(): boolean {
    return safeCall(() => NativeSedentary().pendingPush, false);
  },
  refreshSedentary(): Promise<SedentaryConfig | undefined> {
    return safeAsync(() => NativeSedentary().refresh(), undefined);
  },
  setSedentary(config: SedentaryConfig): Promise<SedentaryConfig> {
    return NativeSedentary().set(config);
  },

  // ---- camera remote
  getCameraRemoteEnabled(): boolean | undefined {
    return safeCall(() => NativeCameraRemote().getEnabled(), undefined);
  },
  refreshCameraRemote(): Promise<boolean | undefined> {
    return safeAsync(() => NativeCameraRemote().refresh(), undefined);
  },
  setCameraRemoteEnabled(enabled: boolean): Promise<boolean> {
    return NativeCameraRemote().setEnabled(enabled);
  },

  // ---- workout GPS
  getSendGpsToBand(): boolean {
    return safeCall(() => NativeGpsTracker().getSendGpsToBand(), false);
  },
  setSendGpsToBand(enabled: boolean): void {
    NativeGpsTracker().setSendGpsToBand(enabled);
  },
};
