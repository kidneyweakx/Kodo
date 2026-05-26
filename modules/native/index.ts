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

import type { HybridBandLink } from '@/modules/native/bandLink/bandLink.nitro';
import type { HybridCalendarBridge } from '@/modules/native/calendar/calendar.nitro';
import type { HybridCameraRemote } from '@/modules/native/cameraRemote/cameraRemote.nitro';
import type { HybridGpsTracker } from '@/modules/native/gps/gps.nitro';
import type { HybridHealthConnect } from '@/modules/native/healthConnect/healthConnect.nitro';
import type { HybridHealthStore } from '@/modules/native/health/health.nitro';
import type { HybridMusicBridge } from '@/modules/native/music/music.nitro';
import type { HybridNotificationBridge } from '@/modules/native/notifications/notifications.nitro';
import type { HybridSedentary } from '@/modules/native/sedentary/sedentary.nitro';
import type { HybridSystemControl } from '@/modules/native/system/system.nitro';
import type { HybridWatchface } from '@/modules/native/watchface/watchface.nitro';
import type { HybridWeatherBridge } from '@/modules/native/weather/weather.nitro';
import type { HybridWeatherProvider } from '@/modules/native/weatherProvider/weatherProvider.nitro';

const lazyHybrid = <T extends HybridObject<{ android: 'kotlin' }>>(name: string) => {
  let instance: T | null = null;
  return (): T => {
    if (!instance) instance = NitroModules.createHybridObject<T>(name);
    return instance;
  };
};

// Registration keys must match the names in nitro.json `autolinking` (i.e.
// the spec interface name, including the `Hybrid` prefix), otherwise
// HybridObjectRegistry on the C++ side won't find a constructor.
export const NativeBandLink = lazyHybrid<HybridBandLink>('HybridBandLink');
export const NativeHealthStore = lazyHybrid<HybridHealthStore>('HybridHealthStore');
export const NativeNotificationBridge = lazyHybrid<HybridNotificationBridge>('HybridNotificationBridge');
export const NativeSystemControl = lazyHybrid<HybridSystemControl>('HybridSystemControl');
export const NativeMusicBridge = lazyHybrid<HybridMusicBridge>('HybridMusicBridge');
export const NativeWeatherBridge = lazyHybrid<HybridWeatherBridge>('HybridWeatherBridge');
export const NativeCalendarBridge = lazyHybrid<HybridCalendarBridge>('HybridCalendarBridge');
export const NativeCameraRemote = lazyHybrid<HybridCameraRemote>('HybridCameraRemote');
export const NativeGpsTracker = lazyHybrid<HybridGpsTracker>('HybridGpsTracker');
export const NativeSedentary = lazyHybrid<HybridSedentary>('HybridSedentary');
export const NativeWeatherProvider = lazyHybrid<HybridWeatherProvider>('HybridWeatherProvider');
export const NativeHealthConnect = lazyHybrid<HybridHealthConnect>('HybridHealthConnect');
export const NativeWatchface = lazyHybrid<HybridWatchface>('HybridWatchface');

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
  HybridWatchface,
  HybridWeatherBridge,
  HybridWeatherProvider,
};
export type { WatchfaceInfo } from '@/modules/native/watchface/watchface.nitro';

export * from '@/modules/native/types';
export type { GpsSample, WorkoutType } from '@/modules/native/gps/gps.nitro';
export type { SedentaryConfig } from '@/modules/native/sedentary/sedentary.nitro';
export type { OwmConfig } from '@/modules/native/weatherProvider/weatherProvider.nitro';
export type { HealthConnectKind, HealthConnectStatus } from '@/modules/native/healthConnect/healthConnect.nitro';
