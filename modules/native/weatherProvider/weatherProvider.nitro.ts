/*
 * mi-band-9-active — Nitro HybridObject spec for receiving weather from external apps.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - GenericWeatherReceiver (ACTION_GENERIC_WEATHER intent format)
 *   - LineageOsWeatherReceiver, CMWeatherReceiver (alternative sources)
 *
 * The Kotlin side registers a BroadcastReceiver listening on
 * "com.kidneyweakx.miband9active.ACTION_GENERIC_WEATHER" and parses the same
 * payload shape Gadgetbridge ships (so existing weather apps can target us).
 *
 * For users without a weather app, we also expose a simple OWM polling
 * configuration that the Kotlin worker honors via WorkManager every 30 min.
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type { WeatherPushRequest } from '../types';

export interface OwmConfig {
  readonly enabled: boolean;
  readonly apiKey: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly locationName: string;
  /** Minimum 30 min, enforced. */
  readonly pollMinutes: number;
}

export interface HybridWeatherProvider
  extends HybridObject<{ android: 'kotlin' }> {
  /** Latest snapshot we hold (cached). */
  getLast(): WeatherPushRequest | null;

  setOwmConfig(config: OwmConfig): void;
  getOwmConfig(): OwmConfig;

  /** Fires when an external broadcast arrives. The JS side then calls
   *  weatherBridge.push() to forward to the band. */
  onExternalWeather(listener: (snapshot: WeatherPushRequest) => void): () => void;
}
