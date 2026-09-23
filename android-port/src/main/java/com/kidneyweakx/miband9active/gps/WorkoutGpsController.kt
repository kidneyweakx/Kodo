/*  Copyright (C) 2023-2024 José Rebelo, Yoran Vulker  (Gadgetbridge XiaomiHealthService)
 *  Copyright (C) 2026 kidneyweakx                     (Kotlin port)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Phone GPS for band workouts — XiaomiHealthService.handleWorkoutOpen /
 *  handleWorkoutStatus / onSetGpsLocation:
 *
 *   band  WORKOUT_WATCH_OPEN (8/30)   -> if "send GPS to band" is off or GPS is
 *                                        unavailable: reply WorkoutOpenReply(3,2,10);
 *                                        else start GPS + 5 s "wait for start" timeout
 *   phone first fix                   -> reply WorkoutOpenReply(0,2,2)
 *   band  WORKOUT_WATCH_STATUS (8/26) -> 0 started / 1 resumed / 2 paused / 3 finished
 *   phone every fix while started     -> WORKOUT_LOCATION (8/48), unknown1 = 2
 *
 *  The SaA synthetic workout (sport 810) is ignored, as upstream.
 *  The GPS foreground service is refcounted between the band-driven flow and
 *  a phone-started recording; it stops when neither needs it (docs/POWER.md).
 */
package com.kidneyweakx.miband9active.gps

import android.location.Location
import android.util.Log
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.xiaomi.services.BandChannel
import com.kidneyweakx.miband9active.xiaomi.services.DeviceFeatures
import com.kidneyweakx.miband9active.xiaomi.services.FeatureStore
import com.kidneyweakx.miband9active.xiaomi.services.HealthCommands
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

object WorkoutGpsController {
    private const val TAG = "MB9A_WorkoutGps"

    private const val WORKOUT_STARTED = 0
    private const val WORKOUT_RESUMED = 1
    private const val WORKOUT_PAUSED = 2
    private const val WORKOUT_FINISHED = 3
    private const val SAA_SYNTHETIC_SPORT = 810

    /** PREF_WORKOUT_SEND_GPS_TO_BAND_TIMEOUT default. */
    private const val START_TIMEOUT_MS = 5_000L
    /** Our addition: stop band-driven GPS if the band stays disconnected this long. */
    private const val DISCONNECT_GRACE_MS = 120_000L

    private const val KEY_SEND_GPS = "workout_send_gps_to_band"

    /** "none" | "gps_requested" | "started" | "paused" */
    @Volatile var bandState: String = "none"
        private set

    @Volatile var manualActive: Boolean = false
        private set

    @Volatile private var bandGpsStarted = false
    @Volatile private var gpsFixAcquired = false
    @Volatile private var workoutStarted = false
    @Volatile private var timeoutJob: Job? = null
    @Volatile private var disconnectJob: Job? = null

    private val stateListeners = CopyOnWriteArrayList<(String) -> Unit>()

    init {
        MiBand9GpsService.onLocation { onLocation(it) }
    }

    var sendGpsToBand: Boolean
        get() = FeatureStore.prefs.getBoolean(KEY_SEND_GPS, false)
        set(value) {
            FeatureStore.prefs.edit().putBoolean(KEY_SEND_GPS, value).apply()
        }

    fun addStateListener(listener: (String) -> Unit): () -> Unit {
        stateListeners += listener
        return { stateListeners -= listener }
    }

    private fun setBandState(s: String) {
        if (bandState == s) return
        bandState = s
        stateListeners.forEach { runCatching { it(s) } }
    }

    // ------------------------------------------------------------ phone-started recording

    fun startManual(): Boolean {
        manualActive = true
        val ok = MiBand9GpsService.running || MiBand9GpsService.start(AppContext.context)
        if (!ok) manualActive = false
        return ok
    }

    fun stopManual() {
        manualActive = false
        releaseGpsIfUnused()
    }

    // ------------------------------------------------------------ band messages

    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            HealthCommands.CMD_WORKOUT_WATCH_OPEN -> if (cmd.hasHealth()) handleWorkoutOpen(cmd.health.workoutOpenWatch)
            HealthCommands.CMD_WORKOUT_WATCH_STATUS -> if (cmd.hasHealth()) handleWorkoutStatus(cmd.health.workoutStatusWatch)
            else -> Unit
        }
    }

    private fun handleWorkoutOpen(open: XiaomiProto.WorkoutOpenWatch) {
        Log.i(TAG, "workout open on band: sport=${open.sport} started=$workoutStarted gps=$bandGpsStarted fix=$gpsFixAcquired")
        if (open.sport == SAA_SYNTHETIC_SPORT) return

        val ctx = AppContext.context
        if (!sendGpsToBand || !MiBand9GpsService.isGpsEnabled(ctx) || !MiBand9GpsService.hasLocationPermission(ctx)) {
            sendOpenReply(3, 2, 10) // "send location disabled"
            return
        }
        if (!bandGpsStarted) {
            gpsFixAcquired = false
            val ok = MiBand9GpsService.running || MiBand9GpsService.start(ctx)
            if (!ok) {
                // Android refused the location FGS (e.g. background start restriction).
                sendOpenReply(3, 2, 10)
                return
            }
            bandGpsStarted = true
            setBandState("gps_requested")
        }
        if (!workoutStarted) {
            // Re-armed on every OPEN while we wait for WORKOUT_STARTED (upstream behaviour).
            timeoutJob?.cancel()
            timeoutJob = DeviceFeatures.launch {
                delay(START_TIMEOUT_MS)
                Log.i(TAG, "Timed out waiting for workout")
                resetBandFlow()
            }
        }
    }

    private fun handleWorkoutStatus(status: XiaomiProto.WorkoutStatusWatch) {
        Log.i(TAG, "workout status ${status.status} sport=${status.sport}")
        if (status.sport == SAA_SYNTHETIC_SPORT) return
        when (status.status) {
            WORKOUT_STARTED -> {
                workoutStarted = true
                timeoutJob?.cancel()
                timeoutJob = null
                setBandState("started")
            }
            WORKOUT_RESUMED -> if (workoutStarted) setBandState("started")
            WORKOUT_PAUSED -> if (workoutStarted) setBandState("paused")
            WORKOUT_FINISHED -> resetBandFlow()
        }
    }

    /** XiaomiHealthService.onSetGpsLocation() */
    private fun onLocation(location: Location) {
        if (!bandGpsStarted || !BandChannel.isConnected) return
        if (!gpsFixAcquired) {
            gpsFixAcquired = true
            sendOpenReply(0, 2, 2) // "send gps fix"
        }
        if (workoutStarted) {
            val wl = XiaomiProto.WorkoutLocation.newBuilder()
                .setUnknown1(2)
                .setTimestamp((location.time / 1000L).toInt())
                .setLongitude(location.longitude)
                .setLatitude(location.latitude)
                .setAltitude(location.altitude)
                .setSpeed(location.speed)
                .setBearing(location.bearing)
            // upstream leaves horizontal/vertical accuracy unset ("seems to work without them")
            DeviceFeatures.launch {
                BandChannel.send(HealthCommands.COMMAND_TYPE, HealthCommands.CMD_WORKOUT_LOCATION) {
                    setHealth(XiaomiProto.Health.newBuilder().setWorkoutLocation(wl))
                }
            }
        }
    }

    private fun sendOpenReply(u1: Int, u2: Int, u3: Int) {
        DeviceFeatures.launch {
            BandChannel.send(HealthCommands.COMMAND_TYPE, HealthCommands.CMD_WORKOUT_WATCH_OPEN) {
                setHealth(
                    XiaomiProto.Health.newBuilder().setWorkoutOpenReply(
                        XiaomiProto.WorkoutOpenReply.newBuilder().setUnknown1(u1).setUnknown2(u2).setUnknown3(u3),
                    ),
                )
            }
        }
    }

    private fun resetBandFlow() {
        timeoutJob?.cancel()
        timeoutJob = null
        disconnectJob?.cancel()
        disconnectJob = null
        bandGpsStarted = false
        gpsFixAcquired = false
        workoutStarted = false
        setBandState("none")
        releaseGpsIfUnused()
    }

    private fun releaseGpsIfUnused() {
        if (!manualActive && !bandGpsStarted && MiBand9GpsService.running) {
            MiBand9GpsService.stop(AppContext.context)
        }
    }

    // ------------------------------------------------------------ lifecycle hooks

    fun onBandConnected() {
        disconnectJob?.cancel()
        disconnectJob = null
    }

    fun onBandDisconnected() {
        if (!bandGpsStarted) return
        disconnectJob?.cancel()
        disconnectJob = DeviceFeatures.launch {
            delay(DISCONNECT_GRACE_MS)
            Log.i(TAG, "band gone for ${DISCONNECT_GRACE_MS / 1000}s during workout -> stopping GPS")
            resetBandFlow()
        }
    }

    /** Service failed to enter the foreground / GPS unavailable. */
    internal fun onServiceFailed() {
        manualActive = false
        if (bandGpsStarted) sendOpenReply(3, 2, 10)
        timeoutJob?.cancel()
        bandGpsStarted = false
        gpsFixAcquired = false
        workoutStarted = false
        setBandState("none")
    }

    /** "Stop" tapped on the GPS notification. */
    internal fun onServiceStoppedByUser() {
        manualActive = false
        timeoutJob?.cancel()
        bandGpsStarted = false
        gpsFixAcquired = false
        workoutStarted = false
        setBandState("none")
    }
}
