/*  Copyright (C) 2023-2026 José Rebelo and Gadgetbridge contributors  (Gadgetbridge XiaomiHealthService realtime stats)
 *
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * Realtime heart rate, opt-in only (docs/POWER.md rule 6). Port of
 * XiaomiHealthService.enableRealtimeStats / handleRealtimeStats:
 *   (8, 45) CMD_REALTIME_STATS_START  — no body
 *   (8, 46) CMD_REALTIME_STATS_STOP   — no body
 *   (8, 47) CMD_REALTIME_STATS_EVENT  — Health.realTimeStats (steps, calories, heartRate, ...)
 * MiBand9ActiveCoordinator inherits XiaomiCoordinator.supportsRealtimeData() = true.
 *
 * Stops automatically when: the last listener unsubscribes, the link drops,
 * or after a hard cap of 5 minutes. Like upstream, an event that arrives while
 * we did not ask for one is answered with STOP (failsafe for a stream left
 * running across an app restart).
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.util.Log
import com.kidneyweakx.miband9active.xiaomi.protocol.PowerLog
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

object RealtimeStatsService {
    private const val TAG = "MB9A_RealtimeHR"
    const val HARD_CAP_MS = 5 * 60_000L

    private val listeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val lock = Any()

    @Volatile var started = false
        private set
    @Volatile private var capJob: Job? = null
    /** Last time we sent STOP for an unsolicited event, to avoid a STOP per packet. */
    @Volatile private var lastFailsafeStopAt = 0L

    fun addListener(listener: (Int) -> Unit): () -> Unit {
        listeners += listener
        return {
            listeners -= listener
            if (listeners.isEmpty() && started) stop("last listener unsubscribed")
        }
    }

    /** No-op when not connected (we never connect for realtime data). */
    fun start() {
        synchronized(lock) {
            if (started) {
                // Extend: the cap restarts from the latest explicit start.
                armCap()
                return
            }
            if (!BandChannel.isConnected) {
                Log.i(TAG, "start ignored: band not connected")
                return
            }
            started = true
            armCap()
        }
        PowerLog.event(PowerLog.REALTIME_HR, "start (cap ${HARD_CAP_MS / 60_000} min)")
        DeviceFeatures.launch {
            if (!BandChannel.send(HealthCommands.COMMAND_TYPE, HealthCommands.CMD_REALTIME_STATS_START)) {
                synchronized(lock) {
                    started = false
                    capJob?.cancel()
                    capJob = null
                }
                PowerLog.event(PowerLog.REALTIME_HR, "start failed: send refused")
            }
        }
    }

    fun stop(reason: String = "stop requested") {
        val wasStarted = synchronized(lock) {
            val w = started
            started = false
            capJob?.cancel()
            capJob = null
            w
        }
        if (!wasStarted) return
        PowerLog.event(PowerLog.REALTIME_HR, "stop: $reason")
        DeviceFeatures.launch {
            BandChannel.send(HealthCommands.COMMAND_TYPE, HealthCommands.CMD_REALTIME_STATS_STOP)
        }
    }

    /** Link dropped: the band forgets the stream; just reset our side. */
    fun onDisconnected() {
        val wasStarted = synchronized(lock) {
            val w = started
            started = false
            capJob?.cancel()
            capJob = null
            w
        }
        if (wasStarted) PowerLog.event(PowerLog.REALTIME_HR, "stop: band disconnected")
    }

    private fun armCap() {
        capJob?.cancel()
        capJob = DeviceFeatures.launch {
            delay(HARD_CAP_MS)
            stop("hard cap ${HARD_CAP_MS / 60_000} min reached")
        }
    }

    /** Health (type 8) commands. Must not block: runs on the incoming collector. */
    fun handleCommand(cmd: XiaomiProto.Command) {
        if (cmd.subtype != HealthCommands.CMD_REALTIME_STATS_EVENT) return
        if (!started) {
            // XiaomiHealthService.handleRealtimeStats failsafe.
            val now = System.currentTimeMillis()
            if (now - lastFailsafeStopAt > 5_000L) {
                lastFailsafeStopAt = now
                Log.i(TAG, "unsolicited realtime stats — sending STOP")
                DeviceFeatures.launch {
                    BandChannel.send(HealthCommands.COMMAND_TYPE, HealthCommands.CMD_REALTIME_STATS_STOP)
                }
            }
            return
        }
        if (!cmd.hasHealth() || !cmd.health.hasRealTimeStats()) return
        val stats = cmd.health.realTimeStats
        if (!stats.hasHeartRate()) return
        val bpm = stats.heartRate
        // 0 = no reading yet (sensor warming up / band off-wrist): not a measurement.
        if (bpm <= 0 || bpm > 250) return
        listeners.forEach { l ->
            try {
                l(bpm)
            } catch (t: Throwable) {
                Log.w(TAG, "listener threw", t)
            }
        }
    }
}
