/*
 * mi-band-9-active — Nitro HybridObject spec for weather push.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiWeatherService
 *     (type 10: current 0, daily 1, hourly 2, band request 3, locations 5/6/7/8, prefs 10)
 *   - nodomain.freeyourgadget.gadgetbridge.devices.xiaomi.XiaomiWeatherConditions
 *   - nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec (field semantics)
 *
 * Temperatures cross the bridge in °C; condition codes are OpenWeatherMap codes
 * (WeatherSpec semantics) — the native side maps them to Xiaomi icons.
 */

import type { HybridObject } from 'react-native-nitro-modules';

export interface WeatherDaily {
  readonly minTempC: number;
  readonly maxTempC: number;
  /** OpenWeatherMap condition code. */
  readonly conditionCode: number;
  readonly aqi?: number;
  /** Unix seconds. */
  readonly sunriseSec?: number;
  readonly sunsetSec?: number;
}

export interface WeatherHourly {
  /** Unix seconds. */
  readonly timestampSec: number;
  readonly tempC: number;
  /** OpenWeatherMap condition code. */
  readonly conditionCode: number;
  readonly windKmh?: number;
  readonly windDirectionDeg?: number;
}

export type WeatherSource = 'broadcast' | 'owm' | 'app';

export interface WeatherSnapshot {
  readonly source: WeatherSource;
  /** Unix seconds when the provider produced the data. */
  readonly timestampSec: number;
  readonly location: string;
  readonly isCurrentLocation?: boolean;
  readonly latitude?: number;
  readonly longitude?: number;
  /** OpenWeatherMap condition code. */
  readonly conditionCode: number;
  readonly conditionText?: string;
  readonly currentTempC: number;
  readonly todayMinTempC: number;
  readonly todayMaxTempC: number;
  readonly humidityPct: number;
  readonly windKmh: number;
  readonly windDirectionDeg: number;
  /** Omitted when the provider has no UV data (never sent as a fake 0). */
  readonly uvIndex?: number;
  readonly pressureMb?: number;
  readonly aqi?: number;
  readonly sunriseSec?: number;
  readonly sunsetSec?: number;
  /** Next days (tomorrow onward), chronological. */
  readonly daily: readonly WeatherDaily[];
  /** Upcoming hours, chronological. */
  readonly hourly: readonly WeatherHourly[];
}

export type TemperatureUnit = 'celsius' | 'fahrenheit';

export interface HybridWeatherBridge extends HybridObject<{ android: 'kotlin' }> {
  /**
   * Stores the snapshot as the latest weather and pushes it (current + daily +
   * hourly) when the band is connected. Resolves true if it reached the band.
   */
  push(snapshot: WeatherSnapshot): Promise<boolean>;
  /** Re-push the stored snapshot. false if none stored or not connected. */
  pushLast(): Promise<boolean>;
  getTemperatureUnit(): TemperatureUnit;
  setTemperatureUnit(unit: TemperatureUnit): Promise<void>;
}
