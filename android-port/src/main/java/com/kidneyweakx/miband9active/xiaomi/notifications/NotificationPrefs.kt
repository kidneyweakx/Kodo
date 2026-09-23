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

import android.content.Context
import android.content.SharedPreferences
import com.kidneyweakx.miband9active.AppContext

/**
 * Process-wide notification/call settings. Values are mirrored in memory so the
 * NotificationListenerService hot path never touches disk; writes go through
 * `apply()`.
 *
 * Allow-list semantics are strict (docs/POWER.md): a package that is not in
 * [allowed] is dropped before the BLE stack is touched. Packages that posted a
 * notification but aren't allowed are remembered in [seen] so the settings UI
 * can offer them.
 */
object NotificationPrefs {
    const val PREFS = "miband9active_notifications"
    const val KEY_ALLOWED = "allowed_packages"
    const val KEY_SEEN = "seen_packages"
    const val KEY_MUTE_DND = "mute_when_dnd"
    const val KEY_CALL_ALERTS = "call_alerts_enabled"

    private const val MAX_SEEN = 96

    @Volatile private var appContext: Context? = null
    @Volatile private var loaded = false

    @Volatile private var allowedMem: Set<String> = emptySet()
    @Volatile private var seenMem: Set<String> = emptySet()
    @Volatile private var muteDndMem = false
    @Volatile private var callAlertsMem = true

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        ensureLoaded()
    }

    private fun ctx(): Context = appContext ?: AppContext.context.also { appContext = it }

    private fun prefs(): SharedPreferences = ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        val p = prefs()
        allowedMem = p.getStringSet(KEY_ALLOWED, emptySet())?.toSet() ?: emptySet()
        seenMem = p.getStringSet(KEY_SEEN, emptySet())?.toSet() ?: emptySet()
        muteDndMem = p.getBoolean(KEY_MUTE_DND, false)
        callAlertsMem = p.getBoolean(KEY_CALL_ALERTS, true)
        loaded = true
    }

    val allowed: Set<String> get() { ensureLoaded(); return allowedMem }
    val seen: Set<String> get() { ensureLoaded(); return seenMem }
    val muteWhenDnd: Boolean get() { ensureLoaded(); return muteDndMem }
    val callAlertsEnabled: Boolean get() { ensureLoaded(); return callAlertsMem }

    fun isAllowed(pkg: String): Boolean = pkg in allowed

    @Synchronized
    fun setAllowed(pkg: String, enabled: Boolean) {
        ensureLoaded()
        val next = if (enabled) allowedMem + pkg else allowedMem - pkg
        if (next == allowedMem) return
        allowedMem = next
        // Always hand SharedPreferences a fresh set instance (the returned one must not be mutated).
        prefs().edit().putStringSet(KEY_ALLOWED, HashSet(next)).apply()
        if (enabled) recordSeen(pkg)
    }

    @Synchronized
    fun forget(pkg: String) {
        ensureLoaded()
        allowedMem = allowedMem - pkg
        seenMem = seenMem - pkg
        prefs().edit()
            .putStringSet(KEY_ALLOWED, HashSet(allowedMem))
            .putStringSet(KEY_SEEN, HashSet(seenMem))
            .apply()
    }

    /** Cheap in-memory check; only writes when a new package shows up. */
    fun recordSeen(pkg: String) {
        if (pkg in seen) return
        synchronized(this) {
            if (pkg in seenMem || seenMem.size >= MAX_SEEN) return
            seenMem = seenMem + pkg
            prefs().edit().putStringSet(KEY_SEEN, HashSet(seenMem)).apply()
        }
    }

    @Synchronized
    fun setMuteWhenDnd(mute: Boolean) {
        ensureLoaded()
        muteDndMem = mute
        prefs().edit().putBoolean(KEY_MUTE_DND, mute).apply()
    }

    @Synchronized
    fun setCallAlertsEnabled(enabled: Boolean) {
        ensureLoaded()
        callAlertsMem = enabled
        prefs().edit().putBoolean(KEY_CALL_ALERTS, enabled).apply()
    }
}
