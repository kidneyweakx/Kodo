/*
 * mi-band-9-active — Nitro HybridObject spec for receiving weather from external apps.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - externalevents.GenericWeatherReceiver (ACTION_GENERIC_WEATHER, extras
 *     WeatherJson / WeatherSecondaryJson / WeatherGz, WeatherSpec JSON keys)
 *
 * The Kotlin receiver accepts both our own action
 * "com.kidneyweakx.miband9active.ACTION_GENERIC_WEATHER" and Gadgetbridge's
 * "nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER", so Breezy
 * Weather & co. can target this app unchanged. Received weather is persisted
 * and pushed to the band natively (no JS round-trip needed).
 *
 * Built-in OpenWeatherMap poller: only runs when the user enabled it AND
 * supplied an API key. WorkManager periodic, >= 6 h, network + battery-not-low
 * (docs/POWER.md "WeatherPushWorker").
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type { WeatherPushRequest } from '../types';
import type { WeatherSnapshot } from '../weather/weather.nitro';

export interface OwmConfig {
  readonly enabled: boolean;
  readonly apiKey: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly locationName: string;
  /** Minimum 360 (6 h), enforced natively. */
  readonly pollMinutes: number;
}

export interface HybridWeatherProvider extends HybridObject<{ android: 'kotlin' }> {
  /**
   * Compact view of the latest stored weather for dashboard cards
   * (°C, Xiaomi condition codes 0..33). null when nothing was ever received.
   */
  getLast(): WeatherPushRequest | null;
  /** Full latest snapshot (OWM condition codes), undefined when none. */
  getLastSnapshot(): WeatherSnapshot | undefined;

  setOwmConfig(config: OwmConfig): void;
  getOwmConfig(): OwmConfig;
  /** One-shot OWM fetch + push. false if disabled / no key / network failure. */
  refreshNow(): Promise<boolean>;

  /** Fires whenever a new snapshot is stored (broadcast, OWM or app push). */
  onWeather(listener: (snapshot: WeatherSnapshot) => void): () => void;
}
