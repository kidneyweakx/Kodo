/*
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 */
package com.kidneyweakx.miband9active.xiaomi.notifications

import android.util.Log
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.calls.CallAlertEngine
import com.kidneyweakx.miband9active.media.MediaSessionTracker
import com.kidneyweakx.miband9active.xiaomi.protocol.MiBand9BleDriver
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The single, process-wide subscriber for band → phone notification (7),
 * music (18) and phonebook (21) commands. Subscribes to the singleton
 * driver's flows (which survive reconnects) exactly once, lazily, instead of
 * capturing whichever driver instance happened to be connected.
 *
 * Handlers must not block: a slow collector on a SharedFlow with a finite
 * buffer makes tryEmit drop packets for every other subscriber.
 */
object BandEventRouter {
    private const val TAG = "MB9A_BandEvents"
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        val driver = DriverHolder.driver
        scope.launch {
            driver.incoming.collect { msg ->
                try {
                    when (msg.type) {
                        NotificationCmd.TYPE -> NotificationForwarder.onBandCommand(msg.subtype, msg.command)
                        MusicCmd.TYPE -> MediaSessionTracker.onBandCommand(msg.subtype, msg.command)
                        PhonebookCmd.TYPE -> CallAlertEngine.onPhonebookCommand(msg.subtype, msg.command)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "handler failed for ${msg.type}/${msg.subtype}", t)
                }
            }
        }
        scope.launch {
            var wasConnected = false
            driver.state.collect { st ->
                val connected = st is MiBand9BleDriver.State.Connected
                if (connected != wasConnected) {
                    wasConnected = connected
                    MediaSessionTracker.onBandConnectionChanged(connected)
                }
            }
        }
    }
}
