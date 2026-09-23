/*
 * mi-band-9-active — Nitro HybridObject spec for live GPS push during workouts.
 * Copyright (C) 2026 kidneyweakx
 *
 * AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 * Ported from Gadgetbridge (AGPL-3.0):
 *   - XiaomiHealthService.handleWorkoutOpen / handleWorkoutStatus / onSetGpsLocation
 *     (type 8: WORKOUT_WATCH_STATUS 26, WORKOUT_WATCH_OPEN 30, WORKOUT_LOCATION 48)
 *   - externalevents.gps.GBLocationService
 *
 * Flow (band-driven, as upstream): the band opens an outdoor workout and asks
 * the phone for GPS -> if "send GPS to band" is on, the location foreground
 * service starts -> fixes are streamed once the band reports the workout
 * STARTED -> the service stops on FINISHED or after the start timeout.
 * startWorkout()/stopWorkout() additionally let the app record phone GPS in
 * the foreground. The FGS never runs outside an active workout (docs/POWER.md).
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

export type BandWorkoutState = 'none' | 'gps_requested' | 'started' | 'paused';

export interface HybridGpsTracker extends HybridObject<{ android: 'kotlin' }> {
  /** Phone-initiated recording (app in foreground). */
  startWorkout(type: WorkoutType): Promise<void>;
  stopWorkout(): Promise<void>;
  readonly isWorkoutActive: boolean;
  readonly lastSample: GpsSample | null;
  onSample(listener: (sample: GpsSample) => void): () => void;

  /** Upstream PREF_WORKOUT_SEND_GPS_TO_BAND (default off). */
  getSendGpsToBand(): boolean;
  setSendGpsToBand(enabled: boolean): void;
  readonly bandWorkoutState: BandWorkoutState;
  onBandWorkoutState(listener: (state: BandWorkoutState) => void): () => void;
}
