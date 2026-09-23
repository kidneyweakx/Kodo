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
 * Native persistence for band settings / feature flags (the equivalent of
 * Gadgetbridge's device-specific SharedPreferences + XiaomiPreferences.FEAT_*).
 *
 * "dirty" keys mark a user change that has not reached the band yet; the
 * connect handler pushes those instead of overwriting them with the band's
 * reported value.
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.content.Context
import android.content.SharedPreferences
import com.kidneyweakx.miband9active.AppContext
import org.json.JSONArray
import org.json.JSONObject

object FeatureStore {
    private const val PREFS = "miband9active_band_features"

    // XiaomiPreferences.FEAT_* equivalents (set when the band answers the GET).
    const val FEAT_HEART_RATE = "feat_heart_rate"
    const val FEAT_SPO2 = "feat_spo2"
    const val FEAT_STRESS = "feat_stress"
    const val FEAT_INACTIVITY = "feat_inactivity"
    const val FEAT_GOAL_NOTIFICATION = "feat_goal_notification"
    const val FEAT_GOAL_SECONDARY = "feat_goal_secondary"
    const val FEAT_VITALITY_SCORE = "feat_vitality_score"
    const val FEAT_SLEEP_MODE_SCHEDULE = "feat_sleep_mode_schedule"
    const val FEAT_CAMERA_REMOTE = "feat_camera_remote"
    const val FEAT_MULTIPLE_WEATHER_LOCATIONS = "feat_multiple_weather_locations"
    const val PREF_ALARM_SLOTS = "alarm_slots"
    const val PREF_REMINDER_SLOTS = "reminder_slots"
    /** Set once any GET answer arrived — distinguishes "unknown" from "unsupported". */
    const val PREF_FEATURES_KNOWN = "features_known"

    val prefs: SharedPreferences by lazy {
        AppContext.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun getJson(key: String): JSONObject? =
        prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun getJsonArray(key: String): JSONArray? =
        prefs.getString(key, null)?.let { runCatching { JSONArray(it) }.getOrNull() }

    fun putJson(key: String, value: JSONObject?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value.toString())
        editor.apply()
    }

    fun putJsonArray(key: String, value: JSONArray?) {
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value.toString())
        editor.apply()
    }

    fun isDirty(key: String): Boolean = prefs.getBoolean("dirty_$key", false)

    fun setDirty(key: String, dirty: Boolean) {
        prefs.edit().putBoolean("dirty_$key", dirty).apply()
    }

    fun setFeature(name: String, supported: Boolean) {
        prefs.edit().putBoolean(name, supported).putBoolean(PREF_FEATURES_KNOWN, true).apply()
    }

    fun feature(name: String): Boolean = prefs.getBoolean(name, false)

    fun setInt(name: String, value: Int) {
        prefs.edit().putInt(name, value).putBoolean(PREF_FEATURES_KNOWN, true).apply()
    }

    fun int(name: String): Int = prefs.getInt(name, 0)

    val featuresKnown: Boolean get() = prefs.getBoolean(PREF_FEATURES_KNOWN, false)
}

/** Minimal HourMinute helpers shared by the settings services. */
data class HourMin(val hour: Int, val minute: Int) {
    fun toJson(): JSONObject = JSONObject().put("h", hour).put("m", minute)

    companion object {
        fun fromJson(o: JSONObject?, fallbackHour: Int, fallbackMinute: Int): HourMin =
            if (o == null) HourMin(fallbackHour, fallbackMinute)
            else HourMin(o.optInt("h", fallbackHour), o.optInt("m", fallbackMinute))
    }
}
