/*
 * mi-band-9-active — shared native bridge types.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * These types are the JS/native contract. Kotlin and Swift implementations
 * MUST match these shapes. Ported semantically (not byte-for-byte) from
 * Gadgetbridge's `nodomain.freeyourgadget.gadgetbridge.devices.xiaomi` and
 * `…service.devices.xiaomi.*` packages.
 */

export type ConnectionState =
  | 'disconnected'
  | 'scanning'
  | 'connecting'
  | 'authenticating'
  | 'connected'
  | 'error';

export interface DiscoveredBand {
  /** BLE MAC address (Android) or UUID (iOS). */
  readonly id: string;
  /** Advertised name. Will always match /^Xiaomi( Smart)? Band 9 Active [0-9A-F]{4}$/. */
  readonly name: string;
  /** RSSI in dBm, more negative is weaker. */
  readonly rssi: number;
}

export interface PairedBand {
  readonly id: string;
  readonly name: string;
  /** 32-byte hex of the negotiated session key. Never leaves the device. */
  readonly authKey: string;
  /** ISO-8601. */
  readonly pairedAt: string;
}

/** Same bucketing Gadgetbridge uses in `XiaomiStressSampleProvider`. */
export type StressBucket = 'relaxed' | 'mild' | 'moderate' | 'high';

export interface HealthDailySummary {
  readonly date: string;
  readonly steps: number;
  readonly distanceMeters: number;
  readonly activeCalories: number;
  readonly restingHeartRate: number | null;
  readonly averageHeartRate: number | null;
  readonly sleepMinutes: number | null;
  readonly stressAverage: number | null;
  readonly spo2Average: number | null;
  readonly paiScore: number | null;
}

export type SleepStage = 'awake' | 'light' | 'deep' | 'rem';

export interface SleepSegment {
  readonly startedAt: string;
  readonly endedAt: string;
  readonly stage: SleepStage;
}

export interface HeartRateSample {
  readonly takenAt: string;
  readonly bpm: number;
}

export interface StressSample {
  readonly takenAt: string;
  readonly score: number;
  readonly bucket: StressBucket;
}

export interface BatteryInfo {
  readonly percent: number;
  readonly charging: boolean;
  readonly updatedAt: string;
}

export type NotificationCategory =
  | 'message'
  | 'call'
  | 'mail'
  | 'calendar'
  | 'social'
  | 'system'
  | 'other';

export interface NotificationPushRequest {
  /** Package id on Android. App bundle id on iOS. */
  readonly sourceId: string;
  readonly appName: string;
  readonly title: string;
  readonly body: string;
  /** Unix epoch ms. */
  readonly postedAt: number;
  readonly category: NotificationCategory;
  /** Base64 PNG, optional. Implementation downscales to 24x24 before send. */
  readonly iconBase64?: string;
}

export interface MusicNowPlaying {
  readonly title: string;
  readonly artist: string;
  readonly album: string;
  readonly app: string;
  readonly positionMs: number;
  readonly durationMs: number;
  readonly playing: boolean;
}

export interface WeatherDailyForecast {
  readonly date: string;
  readonly highC: number;
  readonly lowC: number;
  /** Gadgetbridge `XiaomiWeatherConditions` numeric code. */
  readonly conditionCode: number;
}

export interface WeatherCurrent {
  readonly tempC: number;
  readonly conditionCode: number;
  readonly humidity: number;
  readonly aqi: number;
}

export interface WeatherPushRequest {
  readonly locationName: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly current: WeatherCurrent;
  readonly daily: readonly WeatherDailyForecast[];
}

export interface CalendarEventPush {
  readonly id: string;
  readonly title: string;
  readonly location: string;
  readonly startsAt: number;
  readonly endsAt: number;
  readonly allDay: boolean;
}
