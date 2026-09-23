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
 *
 * The ONLY place the device-feature services touch the BLE transport.
 * Everything goes through `DriverHolder`:
 *   - `DriverHolder.driver`  process-wide singleton; `incoming` / `state`
 *                            flows persist across reconnects (subscribe once).
 *   - `DriverHolder.current` the driver only while Connected, else null.
 * If the transport contract changes, only this file needs updating.
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.util.Log
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.protocol.MiBand9BleDriver
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

object BandChannel {
    private const val TAG = "MB9A_BandChannel"
    const val DEFAULT_TIMEOUT_MS = 5_000L

    val incoming: SharedFlow<MiBand9BleDriver.IncomingCommand>
        get() = DriverHolder.driver.incoming

    val state: StateFlow<MiBand9BleDriver.State>
        get() = DriverHolder.driver.state

    val isConnected: Boolean
        get() = DriverHolder.current != null

    /** Fire-and-forget. false when not connected or the write failed. */
    suspend fun send(command: XiaomiProto.Command): Boolean {
        val drv = DriverHolder.current ?: return false
        return try {
            drv.sendCommand(command)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "send ${command.type}/${command.subtype} failed", t)
            false
        }
    }

    suspend fun send(type: Int, subtype: Int, body: XiaomiProto.Command.Builder.() -> Unit = {}): Boolean {
        val builder = XiaomiProto.Command.newBuilder().setType(type).setSubtype(subtype)
        builder.body()
        return send(builder.build())
    }

    /** Plaintext DATA-channel chunk (file uploads). */
    suspend fun sendData(bytes: ByteArray): Boolean {
        val drv = DriverHolder.current ?: return false
        return try {
            drv.sendData(bytes)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendData ${bytes.size}B failed", t)
            false
        }
    }

    /**
     * Sends [command] and waits for the first incoming message matching
     * [match]. The subscription is registered BEFORE the write so a fast reply
     * can't slip past (the incoming SharedFlow has no replay).
     */
    suspend fun request(
        command: XiaomiProto.Command,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        match: (MiBand9BleDriver.IncomingCommand) -> Boolean,
    ): XiaomiProto.Command? {
        val drv = DriverHolder.current ?: return null
        return coroutineScope {
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(timeoutMs) { drv.incoming.first { match(it) } }
            }
            val sent = try {
                drv.sendCommand(command)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "request ${command.type}/${command.subtype} send failed", t)
                false
            }
            if (!sent) {
                waiter.cancel()
                null
            } else {
                val reply = waiter.await()
                if (reply == null) Log.w(TAG, "request ${command.type}/${command.subtype}: no reply in ${timeoutMs}ms")
                reply?.command
            }
        }
    }

    /** Request whose reply has the same type/subtype (the usual GET pattern). */
    suspend fun request(
        type: Int,
        subtype: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        body: XiaomiProto.Command.Builder.() -> Unit = {},
    ): XiaomiProto.Command? {
        val builder = XiaomiProto.Command.newBuilder().setType(type).setSubtype(subtype)
        builder.body()
        return request(builder.build(), timeoutMs) { it.type == type && it.subtype == subtype }
    }
}
