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
 * After a restore rewrote SharedPreferences files underneath the stores,
 * bring their in-memory state and scheduled work back in line — only through
 * each store's public API:
 *
 *   NotificationPrefs  keeps an in-memory allow-list (hot path); replay the
 *                      restored allow-list / toggles through its setters.
 *   OwmWeather         re-(un)schedule its periodic worker from the restored config.
 *   CalendarService    same for the calendar push worker (setSettings also
 *                      syncs when connected; harmless when not).
 *   BandStore          re-arm the connection when a band + key was restored
 *                      (DriverHolder.ensureConnected, best effort, background).
 */
package com.kidneyweakx.miband9active.dataport

import android.util.Log
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.notifications.NotificationPrefs
import com.kidneyweakx.miband9active.xiaomi.services.CalendarService
import com.kidneyweakx.miband9active.xiaomi.services.OwmWeather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

internal object NativeStateHooks {
    private const val TAG = "MB9A_Backup"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val NOTIFICATION_PREFS = NotificationPrefs.PREFS
    private const val WEATHER_PREFS = "miband9active_weather"
    private const val FEATURE_PREFS = "miband9active_band_features"

    fun afterPrefsRestored(name: String, json: JSONObject) {
        runCatching {
            when (name) {
                NOTIFICATION_PREFS -> syncNotificationPrefs(json)
                WEATHER_PREFS -> OwmWeather.setConfig(OwmWeather.getConfig())
                FEATURE_PREFS -> scope.launch {
                    runCatching { CalendarService.setSettings(CalendarService.getSettings()) }
                        .onFailure { Log.w(TAG, "calendar reschedule after restore failed", it) }
                }
            }
        }.onFailure { Log.w(TAG, "post-restore hook for $name failed", it) }
    }

    fun afterRestore(restoredBand: Boolean) {
        if (!restoredBand) return
        scope.launch {
            runCatching { DriverHolder.ensureConnected() }
                .onFailure { Log.i(TAG, "reconnect after restore did not complete: ${it.message}") }
        }
    }

    /**
     * The file is already rewritten; the setters make the in-memory copy match
     * (and write the same values back). `seen` is only a UI hint and is merged.
     */
    private fun syncNotificationPrefs(json: JSONObject) {
        val allowed = PrefsDump.stringSet(json, NotificationPrefs.KEY_ALLOWED).orEmpty()
        for (pkg in NotificationPrefs.allowed - allowed) NotificationPrefs.setAllowed(pkg, false)
        for (pkg in allowed) NotificationPrefs.setAllowed(pkg, true)
        PrefsDump.stringSet(json, NotificationPrefs.KEY_SEEN)?.forEach { NotificationPrefs.recordSeen(it) }
        PrefsDump.boolean(json, NotificationPrefs.KEY_MUTE_DND)?.let { NotificationPrefs.setMuteWhenDnd(it) }
        PrefsDump.boolean(json, NotificationPrefs.KEY_CALL_ALERTS)?.let { NotificationPrefs.setCallAlertsEnabled(it) }
    }
}
