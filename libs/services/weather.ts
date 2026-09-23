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
 * JS facade over HybridWeatherBridge + HybridWeatherProvider. Broadcast and
 * OWM weather are stored and pushed natively; JS only reads / configures.
 */

import { NativeWeatherBridge, NativeWeatherProvider } from '@/modules/native';
import { safeAsync, safeCall, safeUnsubscribe } from '@/modules/native/safe';
import type { OwmConfig, TemperatureUnit, WeatherPushRequest, WeatherSnapshot } from '@/modules/native';

export const weather = {
  /** Compact view for cards (°C, Xiaomi condition codes); null = no weather yet. */
  getLast(): WeatherPushRequest | null {
    return safeCall(() => NativeWeatherProvider().getLast(), null);
  },
  getLastSnapshot(): WeatherSnapshot | undefined {
    return safeCall(() => NativeWeatherProvider().getLastSnapshot(), undefined);
  },
  onWeather(listener: (snapshot: WeatherSnapshot) => void): () => void {
    return safeUnsubscribe(() => NativeWeatherProvider().onWeather(listener));
  },
  push(snapshot: WeatherSnapshot): Promise<boolean> {
    return safeAsync(() => NativeWeatherBridge().push(snapshot), false);
  },
  pushLast(): Promise<boolean> {
    return safeAsync(() => NativeWeatherBridge().pushLast(), false);
  },
  getTemperatureUnit(): TemperatureUnit {
    return safeCall<TemperatureUnit>(() => NativeWeatherBridge().getTemperatureUnit(), 'celsius');
  },
  setTemperatureUnit(unit: TemperatureUnit): Promise<void> {
    return NativeWeatherBridge().setTemperatureUnit(unit);
  },
  getOwmConfig(): OwmConfig | undefined {
    return safeCall(() => NativeWeatherProvider().getOwmConfig(), undefined);
  },
  setOwmConfig(config: OwmConfig): void {
    NativeWeatherProvider().setOwmConfig(config);
  },
  refreshNow(): Promise<boolean> {
    return safeAsync(() => NativeWeatherProvider().refreshNow(), false);
  },
};
