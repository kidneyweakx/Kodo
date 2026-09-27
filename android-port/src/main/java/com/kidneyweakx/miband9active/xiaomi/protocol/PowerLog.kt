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
 * The single sink for `MB9A_POWER` (docs/POWER.md "Auditing"): every
 * wake-up and link transition is logged here AND recorded in a small
 * persisted ring so the "Connection & power" screen can show it after a
 * process death (HybridBandLink.getDiagnostics).
 *
 * Cost: one in-memory append + one async SharedPreferences.apply() per
 * event. Events only happen on real transitions (connect, drop, worker run,
 * process start, BT toggle, realtime HR start/stop), never on a timer.
 */
package com.kidneyweakx.miband9active.xiaomi.protocol

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kidneyweakx.miband9active.AppContext
import org.json.JSONArray
import org.json.JSONObject

object PowerLog {
    const val TAG = "MB9A_POWER"

    // Event kinds (stable strings — the UI may switch on them).
    const val PROCESS_START = "process_start"
    const val WORKER = "worker"
    const val CONNECTED = "connected"
    const val DISCONNECTED = "disconnected"
    const val CONNECT_FAILED = "connect_failed"
    const val RECONNECT_ARMED = "reconnect_armed"
    const val RECONNECT_STOPPED = "reconnect_stopped"
    const val BLUETOOTH = "bluetooth"
    const val SYNC = "sync"
    const val SCAN = "scan"
    const val REALTIME_HR = "realtime_hr"
    const val GPS = "gps"

    data class Event(val at: Long, val kind: String, val detail: String)

    private const val PREFS = "mb9a_power_log"
    private const val K_EVENTS = "events"
    private const val K_WAKEUPS = "wakeups"
    private const val K_LAST_CONNECTED = "lastConnectedAt"
    private const val K_LAST_DISCONNECTED = "lastDisconnectedAt"
    private const val K_LAST_DISCONNECT_REASON = "lastDisconnectReason"
    private const val K_LAST_SYNC = "lastSyncAt"
    private const val K_LAST_SYNC_ERROR = "lastSyncError"
    private const val K_ATTEMPTS = "reconnectAttempts"

    private const val MAX_EVENTS = 50
    private const val MAX_WAKEUPS = 1_000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val lock = Any()
    private var loaded = false
    private val events = ArrayDeque<Event>()
    private val wakeups = ArrayDeque<Long>()

    @Volatile var reconnectAttempts: Int = 0
        private set

    private fun prefs(): SharedPreferences? = try {
        AppContext.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    } catch (_: IllegalStateException) {
        null // AppContext not installed (unit tests) — log only.
    }

    private fun ensureLoaded(p: SharedPreferences) {
        if (loaded) return
        loaded = true
        runCatching {
            val arr = JSONArray(p.getString(K_EVENTS, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                events.addLast(Event(o.optLong("t"), o.optString("k"), o.optString("d")))
            }
        }
        runCatching {
            val arr = JSONArray(p.getString(K_WAKEUPS, "[]"))
            for (i in 0 until arr.length()) wakeups.addLast(arr.getLong(i))
        }
        reconnectAttempts = p.getInt(K_ATTEMPTS, 0)
    }

    /**
     * Log + record one event. [wakeup] = this event means the app did work
     * without the user asking (counted in wakeupsLast24h).
     */
    fun event(kind: String, detail: String, wakeup: Boolean = false) {
        Log.i(TAG, if (detail.isEmpty()) kind else "$kind: $detail")
        val p = prefs() ?: return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            ensureLoaded(p)
            events.addLast(Event(now, kind, detail.take(200)))
            while (events.size > MAX_EVENTS) events.removeFirst()
            if (wakeup) {
                wakeups.addLast(now)
                pruneWakeups(now)
            }
            persistLocked(p)
        }
    }

    private fun pruneWakeups(now: Long) {
        while (wakeups.isNotEmpty() && (wakeups.first() < now - DAY_MS || wakeups.size > MAX_WAKEUPS)) {
            wakeups.removeFirst()
        }
    }

    private fun persistLocked(p: SharedPreferences) {
        val ev = JSONArray()
        events.forEach { ev.put(JSONObject().put("t", it.at).put("k", it.kind).put("d", it.detail)) }
        val wk = JSONArray()
        wakeups.forEach { wk.put(it) }
        p.edit().putString(K_EVENTS, ev.toString()).putString(K_WAKEUPS, wk.toString()).apply()
    }

    // ------------------------------------------------------------ link bookkeeping

    fun onConnected(detail: String) {
        reconnectAttempts = 0
        prefs()?.edit()?.putLong(K_LAST_CONNECTED, System.currentTimeMillis())?.putInt(K_ATTEMPTS, 0)?.apply()
        event(CONNECTED, detail, wakeup = true)
    }

    fun onDisconnected(reason: String, wakeup: Boolean) {
        prefs()?.edit()
            ?.putLong(K_LAST_DISCONNECTED, System.currentTimeMillis())
            ?.putString(K_LAST_DISCONNECT_REASON, reason.take(200))
            ?.apply()
        event(DISCONNECTED, reason, wakeup)
    }

    /** One more connectGatt (active or passive) since the last successful session. */
    fun onConnectAttempt() {
        val p = prefs()
        if (p != null) synchronized(lock) { ensureLoaded(p) }
        reconnectAttempts += 1
        p?.edit()?.putInt(K_ATTEMPTS, reconnectAttempts)?.apply()
    }

    fun onSyncFinished(error: String?) {
        val p = prefs() ?: return
        val e = p.edit()
        if (error == null) {
            e.putLong(K_LAST_SYNC, System.currentTimeMillis()).remove(K_LAST_SYNC_ERROR)
        } else {
            e.putString(K_LAST_SYNC_ERROR, error.take(200))
        }
        e.apply()
    }

    // ------------------------------------------------------------ reads (cheap, synchronous)

    data class Snapshot(
        val lastConnectedAt: Long?,
        val lastDisconnectedAt: Long?,
        val lastDisconnectReason: String?,
        val lastSyncAt: Long?,
        val lastSyncError: String?,
        val reconnectAttempts: Int,
        val wakeupsLast24h: Int,
        /** Newest first. */
        val events: List<Event>,
    )

    fun snapshot(): Snapshot {
        val p = prefs() ?: return Snapshot(null, null, null, null, null, 0, 0, emptyList())
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            ensureLoaded(p)
            pruneWakeups(now)
            Snapshot(
                lastConnectedAt = p.getLong(K_LAST_CONNECTED, 0L).takeIf { it > 0 },
                lastDisconnectedAt = p.getLong(K_LAST_DISCONNECTED, 0L).takeIf { it > 0 },
                lastDisconnectReason = p.getString(K_LAST_DISCONNECT_REASON, null),
                lastSyncAt = p.getLong(K_LAST_SYNC, 0L).takeIf { it > 0 },
                lastSyncError = p.getString(K_LAST_SYNC_ERROR, null),
                reconnectAttempts = reconnectAttempts,
                wakeupsLast24h = wakeups.count { it >= now - DAY_MS },
                events = events.reversed(),
            )
        }
    }

    /** Clears the event ring and wake-up counter; keeps last-sync / last-connect facts. */
    fun clear() {
        val p = prefs() ?: return
        synchronized(lock) {
            ensureLoaded(p)
            events.clear()
            wakeups.clear()
            persistLocked(p)
        }
        Log.i(TAG, "diagnostics cleared")
    }
}
