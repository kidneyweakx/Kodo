/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.gps.MiBand9GpsService
import com.kidneyweakx.miband9active.xiaomi.services.HealthCommands
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

class HybridGpsTracker : HybridHybridGpsTrackerSpec() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var _active = false
    private var pushUnsub: (() -> Unit)? = null

    override val isWorkoutActive: Boolean get() = _active
    override val lastSample: Variant_NullType_GpsSample
        get() {
            val loc = MiBand9GpsService.latestLocation ?: return Defaults.GPS
            return Variant_NullType_GpsSample.create(loc.toSample())
        }

    override fun startWorkout(type: WorkoutType): Promise<Unit> = Promise.async {
        MiBand9GpsService.start(AppContext.context)
        _active = true
        // Push every fix to the band on the Health channel.
        pushUnsub?.invoke()
        pushUnsub = MiBand9GpsService.onLocation { loc ->
            val drv = DriverHolder.current ?: return@onLocation
            scope.launch {
                val wl = XiaomiProto.WorkoutLocation.newBuilder()
                    .setUnknown1(10)
                    .setTimestamp((loc.time / 1000L).toInt())
                    .setLatitude(loc.latitude)
                    .setLongitude(loc.longitude)
                    .setAltitude(if (loc.hasAltitude()) loc.altitude else 0.0)
                    .setSpeed(if (loc.hasSpeed()) loc.speed else 0f)
                    .setBearing(if (loc.hasBearing()) loc.bearing else 0f)
                    .setHorizontalAccuracy(if (loc.hasAccuracy()) loc.accuracy else 0f)
                    .build()
                drv.sendCommand(
                    XiaomiProto.Command.newBuilder()
                        .setType(HealthCommands.COMMAND_TYPE)
                        .setSubtype(48)
                        .setHealth(
                            XiaomiProto.Health.newBuilder().setWorkoutLocation(wl),
                        )
                        .build(),
                )
            }
        }
    }

    override fun stopWorkout(): Promise<Unit> = Promise.async {
        pushUnsub?.invoke(); pushUnsub = null
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
