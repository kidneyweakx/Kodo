/*
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
 * Owns the process-wide [MiBand9BleDriver] singleton. Its flows (state,
 * incoming, activityChunks, battery) persist across reconnects, so Hybrids
 * should subscribe to `DriverHolder.driver.*` once and never go stale.
 */
package com.kidneyweakx.miband9active

import android.bluetooth.BluetoothManager
import com.kidneyweakx.miband9active.xiaomi.auth.XiaomiCrypto
import com.kidneyweakx.miband9active.xiaomi.protocol.BandLinkException
import com.kidneyweakx.miband9active.xiaomi.protocol.MiBand9BleDriver

object DriverHolder {

    /** Process-wide driver. Created lazily with the application context. */
    val driver: MiBand9BleDriver by lazy { MiBand9BleDriver(AppContext.context) }

    /**
     * Compat for existing Hybrids: the driver when authenticated, else null.
     * Prefer `driver.incoming` for subscriptions (never stale) and
     * [ensureConnected] for work that needs the band.
     */
    val current: MiBand9BleDriver?
        get() = driver.takeIf { it.isConnected() }

    /**
     * Connect to the band stored in [BandStore] (if not already connected) and
     * wait for the encrypted session. Throws [IllegalStateException] (a
     * [BandLinkException] whose message starts with NOT_PAIRED / BT_OFF /
     * PERMISSION / TIMEOUT / GATT / AUTH_REJECTED / AUTH_KEY_INVALID).
     * Safe to call from WorkManager with no UI running.
     */
    suspend fun ensureConnected(timeoutMs: Long = 30_000): MiBand9BleDriver {
        val d = driver
        if (d.isConnected()) return d
        val band = BandStore.load()
            ?: throw BandLinkException(BandLinkException.NOT_PAIRED, "no paired band stored — pair first")
        val key = XiaomiCrypto.parseAuthKey(band.authKeyHex)
            ?: throw BandLinkException(BandLinkException.AUTH_KEY_INVALID, "stored auth key is corrupt — pair again")
        val adapter = AppContext.context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw BandLinkException(BandLinkException.BT_OFF, "no Bluetooth adapter on this device")
        val device = try {
            adapter.getRemoteDevice(band.id)
        } catch (e: IllegalArgumentException) {
            throw BandLinkException(BandLinkException.GATT, "stored band address '${band.id}' is invalid", e)
        }
        try {
            d.connectAndAwait(device, key, timeoutMs, keepReconnecting = true)
        } catch (e: BandLinkException) {
            throw e
        } catch (e: SecurityException) {
            throw BandLinkException.from(e)
        }
        return d
    }
}
