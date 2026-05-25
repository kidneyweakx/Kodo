/*
 * mi-band-9-active — Nitro module barrel + lazy accessors.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Until `bun nitrogen` is run on a real RN host these resolve via
 * `NitroModules.createHybridObject(...)`. JS callers should only use the
 * libs/services/* facades — never import these directly outside that layer.
 */

import { NitroModules } from 'react-native-nitro-modules';
import type { HybridObject } from 'react-native-nitro-modules';

import type { HybridBandLink } from '@/modules/native/bandLink/spec';
import type { HybridCalendarBridge } from '@/modules/native/calendar/spec';
import type { HybridCameraRemote } from '@/modules/native/cameraRemote/spec';
import type { HybridGpsTracker } from '@/modules/native/gps/spec';
import type { HybridHealthConnect } from '@/modules/native/healthConnect/spec';
import type { HybridHealthStore } from '@/modules/native/health/spec';
import type { HybridMusicBridge } from '@/modules/native/music/spec';
import type { HybridNotificationBridge } from '@/modules/native/notifications/spec';
import type { HybridSedentary } from '@/modules/native/sedentary/spec';
import type { HybridSystemControl } from '@/modules/native/system/spec';
import type { HybridWeatherBridge } from '@/modules/native/weather/spec';
import type { HybridWeatherProvider } from '@/modules/native/weatherProvider/spec';

const lazyHybrid = <T extends HybridObject<{ android: 'kotlin' }>>(name: string) => {
  let instance: T | null = null;
  return (): T => {
    if (!instance) instance = NitroModules.createHybridObject<T>(name);
    return instance;
  };
};

export const NativeBandLink = lazyHybrid<HybridBandLink>('BandLink');
export const NativeHealthStore = lazyHybrid<HybridHealthStore>('HealthStore');
export const NativeNotificationBridge = lazyHybrid<HybridNotificationBridge>('NotificationBridge');
export const NativeSystemControl = lazyHybrid<HybridSystemControl>('SystemControl');
export const NativeMusicBridge = lazyHybrid<HybridMusicBridge>('MusicBridge');
export const NativeWeatherBridge = lazyHybrid<HybridWeatherBridge>('WeatherBridge');
export const NativeCalendarBridge = lazyHybrid<HybridCalendarBridge>('CalendarBridge');
export const NativeCameraRemote = lazyHybrid<HybridCameraRemote>('CameraRemote');
export const NativeGpsTracker = lazyHybrid<HybridGpsTracker>('GpsTracker');
export const NativeSedentary = lazyHybrid<HybridSedentary>('Sedentary');
export const NativeWeatherProvider = lazyHybrid<HybridWeatherProvider>('WeatherProvider');
export const NativeHealthConnect = lazyHybrid<HybridHealthConnect>('HealthConnect');

export type {
  HybridBandLink,
  HybridCalendarBridge,
  HybridCameraRemote,
  HybridGpsTracker,
  HybridHealthConnect,
  HybridHealthStore,
  HybridMusicBridge,
  HybridNotificationBridge,
  HybridSedentary,
  HybridSystemControl,
  HybridWeatherBridge,
  HybridWeatherProvider,
};

export * from '@/modules/native/types';
export type { GpsSample, WorkoutType } from '@/modules/native/gps/spec';
export type { SedentaryConfig } from '@/modules/native/sedentary/spec';
export type { OwmConfig } from '@/modules/native/weatherProvider/spec';
export type { HealthConnectKind, HealthConnectStatus } from '@/modules/native/healthConnect/spec';
