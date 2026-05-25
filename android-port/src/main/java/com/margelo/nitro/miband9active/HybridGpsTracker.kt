/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.gps.MiBand9GpsService
import com.margelo.nitro.core.Promise

class HybridGpsTracker : HybridHybridGpsTrackerSpec() {
    private var _active = false

    override val isWorkoutActive: Boolean get() = _active
    override val lastSample: Variant_NullType_GpsSample
        get() {
            val loc = MiBand9GpsService.latestLocation ?: return Defaults.GPS
            return Variant_NullType_GpsSample.create(loc.toSample())
        }

    override fun startWorkout(type: WorkoutType): Promise<Unit> = Promise.async {
        MiBand9GpsService.start(AppContext.context)
        _active = true
    }

    override fun stopWorkout(): Promise<Unit> = Promise.async {
        MiBand9GpsService.stop(AppContext.context)
        _active = false
    }

    override fun onSample(listener: (sample: GpsSample) -> Unit): () -> Unit =
        MiBand9GpsService.onLocation { loc -> listener(loc.toSample()) }

    private fun android.location.Location.toSample(): GpsSample = GpsSample(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = if (hasAltitude()) Variant_NullType_Double.create(altitude) else null,
        accuracyMeters = if (hasAccuracy()) Variant_NullType_Double.create(accuracy.toDouble()) else null,
        speedMps = if (hasSpeed()) Variant_NullType_Double.create(speed.toDouble()) else null,
        bearingDegrees = if (hasBearing()) Variant_NullType_Double.create(bearing.toDouble()) else null,
        takenAt = time.toDouble(),
    )
}
