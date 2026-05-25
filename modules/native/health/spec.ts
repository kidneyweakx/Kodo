/*
 * mi-band-9-active — Nitro HybridObject spec for health sample storage.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - XiaomiSampleProvider, XiaomiSleepStageSampleProvider, XiaomiStressSampleProvider
 *   - XiaomiSpo2SampleProvider, XiaomiHeartRateRestingSampleProvider, XiaomiPaiSampleProvider
 *   - XiaomiDailySummarySampleProvider, XiaomiTemperatureSampleProvider
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type {
  HealthDailySummary,
  HeartRateSample,
  SleepSegment,
  StressSample,
} from '@/modules/native/types';

export interface HybridHealthStore
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  getDailySummary(dateIso: string): HealthDailySummary | null;
  getDailySummariesRange(fromIso: string, toIso: string): readonly HealthDailySummary[];

  getHeartRateSeries(dateIso: string): readonly HeartRateSample[];
  getStressSeries(dateIso: string): readonly StressSample[];
  getSleepSegments(dateIso: string): readonly SleepSegment[];

  /** Drops all cached samples. Used by 'forget device' flow. */
  clearAll(): void;
}
