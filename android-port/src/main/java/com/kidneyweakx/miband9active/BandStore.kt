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
 */
package com.kidneyweakx.miband9active

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent record of the one paired band (MAC + auth key). Native-side
 * source of truth so the engine can reconnect after an app/process restart
 * (JS may not be running at all, e.g. inside the periodic WorkManager job).
 *
 * Gadgetbridge keeps the same data in device-specific SharedPreferences
 * ("authkey", see XiaomiAuthService.getSecretKey).
 */
object BandStore {

    data class StoredBand(
        val id: String,
        val name: String,
        /** 32 lowercase hex chars (normalised). */
        val authKeyHex: String,
        val pairedAtIso: String,
    )

    private const val PREFS = "mb9a_band_store"
    private const val K_ID = "id"
    private const val K_NAME = "name"
    private const val K_KEY = "authKeyHex"
    private const val K_PAIRED_AT = "pairedAtIso"

    private fun prefs(): SharedPreferences =
        AppContext.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var cached: StoredBand? = null
    @Volatile private var loaded = false

    @Synchronized
    fun save(b: StoredBand) {
        prefs().edit()
            .putString(K_ID, b.id)
            .putString(K_NAME, b.name)
            .putString(K_KEY, b.authKeyHex)
            .putString(K_PAIRED_AT, b.pairedAtIso)
            // commit(): the caller (pair) resolves right after; make sure a
            // process death immediately afterwards doesn't lose the band.
            .commit()
        cached = b
        loaded = true
    }

    @Synchronized
    fun load(): StoredBand? {
        if (loaded) return cached
        val p = prefs()
        val id = p.getString(K_ID, null)
        val key = p.getString(K_KEY, null)
        cached = if (id.isNullOrBlank() || key.isNullOrBlank()) {
            null
        } else {
            StoredBand(
                id = id,
                name = p.getString(K_NAME, null) ?: "Mi Band 9 Active",
                authKeyHex = key,
                pairedAtIso = p.getString(K_PAIRED_AT, null) ?: "",
            )
        }
        loaded = true
        return cached
    }

    @Synchronized
    fun clear() {
        prefs().edit().clear().commit()
        cached = null
        loaded = true
    }
}
