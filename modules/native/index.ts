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

import type { HybridBandLink } from '@/modules/native/bandLink/spec';
import type { HybridCalendarBridge } from '@/modules/native/calendar/spec';
import type { HybridHealthStore } from '@/modules/native/health/spec';
import type { HybridMusicBridge } from '@/modules/native/music/spec';
import type { HybridNotificationBridge } from '@/modules/native/notifications/spec';
import type { HybridSystemControl } from '@/modules/native/system/spec';
import type { HybridWeatherBridge } from '@/modules/native/weather/spec';

let _bandLink: HybridBandLink | null = null;
let _health: HybridHealthStore | null = null;
let _notifications: HybridNotificationBridge | null = null;
let _system: HybridSystemControl | null = null;
let _music: HybridMusicBridge | null = null;
let _weather: HybridWeatherBridge | null = null;
let _calendar: HybridCalendarBridge | null = null;

export const NativeBandLink = (): HybridBandLink => {
  if (!_bandLink) _bandLink = NitroModules.createHybridObject<HybridBandLink>('BandLink');
  return _bandLink;
};

export const NativeHealthStore = (): HybridHealthStore => {
  if (!_health) _health = NitroModules.createHybridObject<HybridHealthStore>('HealthStore');
  return _health;
};

export const NativeNotificationBridge = (): HybridNotificationBridge => {
  if (!_notifications)
    _notifications = NitroModules.createHybridObject<HybridNotificationBridge>('NotificationBridge');
  return _notifications;
};

export const NativeSystemControl = (): HybridSystemControl => {
  if (!_system) _system = NitroModules.createHybridObject<HybridSystemControl>('SystemControl');
  return _system;
};

export const NativeMusicBridge = (): HybridMusicBridge => {
  if (!_music) _music = NitroModules.createHybridObject<HybridMusicBridge>('MusicBridge');
  return _music;
};

export const NativeWeatherBridge = (): HybridWeatherBridge => {
  if (!_weather) _weather = NitroModules.createHybridObject<HybridWeatherBridge>('WeatherBridge');
  return _weather;
};

export const NativeCalendarBridge = (): HybridCalendarBridge => {
  if (!_calendar) _calendar = NitroModules.createHybridObject<HybridCalendarBridge>('CalendarBridge');
  return _calendar;
};

export type {
  HybridBandLink,
  HybridCalendarBridge,
  HybridHealthStore,
  HybridMusicBridge,
  HybridNotificationBridge,
  HybridSystemControl,
  HybridWeatherBridge,
};

export * from '@/modules/native/types';
