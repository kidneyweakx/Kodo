/*
 * mi-band-9-active — Nitro HybridObject spec for weather push.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiWeatherService
 *   - nodomain.freeyourgadget.gadgetbridge.devices.xiaomi.XiaomiWeatherConditions
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type { WeatherPushRequest } from '@/modules/native/types';

export interface HybridWeatherBridge
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  push(request: WeatherPushRequest): Promise<void>;
}
