/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Phone GPS for workouts. Band pushes follow upstream XiaomiHealthService:
 *  fixes are only sent (8/48, unknown1 = 2) while the BAND reports a started
 *  workout, after answering its WORKOUT_WATCH_OPEN — see WorkoutGpsController.
 *  The old version streamed every fix with unknown1 = 10 regardless of the
 *  band's state and never answered the open request.
 */
package com.margelo.nitro.miband9active

import android.location.Location
import com.kidneyweakx.miband9active.gps.MiBand9GpsService
import com.kidneyweakx.miband9active.gps.WorkoutGpsController
import com.kidneyweakx.miband9active.xiaomi.services.DeviceFeatures
import com.margelo.nitro.core.Promise

class HybridGpsTracker : HybridHybridGpsTrackerSpec() {

    init {
        DeviceFeatures.ensureStarted()
    }

    override val isWorkoutActive: Boolean get() = WorkoutGpsController.manualActive

    override val lastSample: Variant_NullType_GpsSample
        get() {
            val loc = MiBand9GpsService.latestLocation ?: return Defaults.GPS
            return Variant_NullType_GpsSample.create(loc.toSample())
        }

    override val bandWorkoutState: BandWorkoutState get() = WorkoutGpsController.bandState.toNitro()

    override fun startWorkout(type: WorkoutType): Promise<Unit> = Promise.async {
        if (!WorkoutGpsController.startManual()) {
            throw IllegalStateException("Could not start GPS (location permission, GPS off, or app not in foreground)")
        }
    }

    override fun stopWorkout(): Promise<Unit> = Promise.async {
        WorkoutGpsController.stopManual()
    }

    override fun onSample(listener: (sample: GpsSample) -> Unit): () -> Unit =
        MiBand9GpsService.onLocation { loc -> listener(loc.toSample()) }

    override fun getSendGpsToBand(): Boolean = WorkoutGpsController.sendGpsToBand

    override fun setSendGpsToBand(enabled: Boolean) {
        WorkoutGpsController.sendGpsToBand = enabled
    }

    override fun onBandWorkoutState(listener: (state: BandWorkoutState) -> Unit): () -> Unit =
        WorkoutGpsController.addStateListener { listener(it.toNitro()) }

    private fun String.toNitro(): BandWorkoutState = when (this) {
        "gps_requested" -> BandWorkoutState.GPS_REQUESTED
        "started" -> BandWorkoutState.STARTED
        "paused" -> BandWorkoutState.PAUSED
        else -> BandWorkoutState.NONE
    }

    private fun Location.toSample(): GpsSample = GpsSample(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = if (hasAltitude()) Variant_NullType_Double.create(altitude) else Variant_NullType_Double.create(Defaults.NULL),
        accuracyMeters = if (hasAccuracy()) Variant_NullType_Double.create(accuracy.toDouble()) else Variant_NullType_Double.create(Defaults.NULL),
        speedMps = if (hasSpeed()) Variant_NullType_Double.create(speed.toDouble()) else Variant_NullType_Double.create(Defaults.NULL),
        bearingDegrees = if (hasBearing()) Variant_NullType_Double.create(bearing.toDouble()) else Variant_NullType_Double.create(Defaults.NULL),
        takenAt = time.toDouble(),
    )
}
