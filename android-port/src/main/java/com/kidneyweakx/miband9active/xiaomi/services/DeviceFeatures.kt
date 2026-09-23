/*  Copyright (C) 2023-2025 Andreas Shimokawa, José Rebelo, Yoran Vulker  (Gadgetbridge XiaomiSupport)
 *  Copyright (C) 2026 kidneyweakx                                       (Kotlin port)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Process-wide dispatcher for the device-feature services — the slim
 *  equivalent of XiaomiSupport's `mServiceMap` + `onAuthSuccess()`:
 *
 *   - subscribes ONCE to BandChannel.state / BandChannel.incoming (the driver
 *     singleton's flows survive reconnects, so no stale driver capture);
 *   - on every transition to Connected runs each service's `initialize()`
 *     equivalent (GET configs, re-push pending/persisted state);
 *   - routes unsolicited band messages by command type.
 *
 *  Started lazily by every device-feature Hybrid and by GenericWeatherReceiver.
 *  The lead should also call DeviceFeatures.ensureStarted() at app start
 *  (e.g. from HybridBandLink init) so the band's find-phone / GPS / weather
 *  requests are handled before any feature screen was opened.
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.util.Log
import com.kidneyweakx.miband9active.gps.WorkoutGpsController
import com.kidneyweakx.miband9active.xiaomi.protocol.MiBand9BleDriver
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object DeviceFeatures {
    private const val TAG = "MB9A_Features"

    private val errorHandler = CoroutineExceptionHandler { _, t -> Log.w(TAG, "uncaught in device-feature scope", t) }
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)

    private val started = AtomicBoolean(false)
    private val initMutex = Mutex()
    @Volatile private var initJob: Job? = null

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                BandChannel.state.collect { st -> onState(st) }
            } catch (t: Throwable) {
                Log.w(TAG, "state collector died", t)
                started.set(false)
            }
        }
        scope.launch {
            try {
                BandChannel.incoming.collect { msg ->
                    try {
                        dispatch(msg)
                    } catch (t: Throwable) {
                        Log.w(TAG, "handler failed for ${msg.type}/${msg.subtype}", t)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "incoming collector died", t)
                started.set(false)
            }
        }
    }

    private fun onState(st: MiBand9BleDriver.State) {
        when (st) {
            is MiBand9BleDriver.State.Connected -> {
                initJob?.cancel()
                initJob = scope.launch { initialize() }
            }
            is MiBand9BleDriver.State.Disconnected, is MiBand9BleDriver.State.Error -> {
                initJob?.cancel()
                initJob = null
                WorkoutGpsController.onBandDisconnected()
            }
            else -> Unit
        }
    }

    /** XiaomiSupport.onAuthSuccess(): every service's initialize(), in upstream order. */
    private suspend fun initialize() = initMutex.withLock {
        Log.i("MB9A_POWER", "band connected -> device-feature init")
        // onAuthSuccess(): systemService.setCurrentTime() first (with our 12/24h pref).
        step("clock") { SystemService.syncClock() }
        step("health") { HealthSettingsService.onConnected() }
        step("schedule") { ScheduleService.onConnected() }
        step("weather") { WeatherService.onConnected() }
        step("system") { SystemService.onConnected() }
        step("calendar") { CalendarService.onConnected() }
        step("gps") { WorkoutGpsController.onBandConnected() }
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "init step '$name' failed", t)
        }
    }

    private fun dispatch(msg: MiBand9BleDriver.IncomingCommand) {
        val cmd = msg.command
        when (msg.type) {
            SystemCommands.COMMAND_TYPE -> SystemService.handleCommand(cmd)
            HealthCommands.COMMAND_TYPE -> {
                HealthSettingsService.handleCommand(cmd)
                WorkoutGpsController.handleCommand(cmd)
            }
            ScheduleCommands.COMMAND_TYPE -> ScheduleService.handleCommand(cmd)
            WeatherCommands.COMMAND_TYPE -> WeatherService.handleCommand(cmd)
            WatchfaceCommands.COMMAND_TYPE -> WatchfaceService.handleCommand(cmd)
            else -> Unit
        }
    }
}
