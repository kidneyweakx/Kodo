/*
 * mi-band-9-active — Nitro HybridObject spec for live GPS push during workouts.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport
 *     (onSetGpsLocation)
 *   - nodomain.freeyourgadget.gadgetbridge.externalevents.gps.GBLocationService
 *
 * Power profile: location updates are only requested while a workout is
 * ACTIVE (foreground service of type `connectedDevice|location`). Stopping the
 * workout immediately unbinds the location listener.
 */

import type { HybridObject } from 'react-native-nitro-modules';

export interface GpsSample {
  readonly latitude: number;
  readonly longitude: number;
  readonly altitudeMeters: number | null;
  readonly accuracyMeters: number | null;
  readonly speedMps: number | null;
  readonly bearingDegrees: number | null;
  readonly takenAt: number;
}

export type WorkoutType =
  | 'outdoor_walking'
  | 'outdoor_running'
  | 'cycling'
  | 'hiking'
  | 'freestyle';

export interface HybridGpsTracker
  extends HybridObject<{ android: 'kotlin' }> {
  startWorkout(type: WorkoutType): Promise<void>;
  stopWorkout(): Promise<void>;
  readonly isWorkoutActive: boolean;
  readonly lastSample: GpsSample | null;
  onSample(listener: (sample: GpsSample) => void): () => void;
}
