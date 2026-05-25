/*  Copyright (C) 2022-2025 Daniele Gobbetti, Enrico Brambilla, José Rebelo,
 *                          TylerWilliamson, Thomas Kuehne                (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                                        (Kotlin port, slimmed)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  External weather apps (Breezy, GBWeather, OWMW, Tasker-bridged Samsung
 *  Weather, …) broadcast `ACTION_GENERIC_WEATHER` with a JSON payload that
 *  describes one or more locations. We parse it and hand it to whoever
 *  registers as the [forwarder].
 *
 *  Manifest entry:
 *    <receiver android:name="com.kidneyweakx.miband9active.xiaomi.services.GenericWeatherReceiver"
 *              android:exported="true">
 *        <intent-filter>
 *            <action android:name="com.kidneyweakx.miband9active.ACTION_GENERIC_WEATHER"/>
 *        </intent-filter>
 *    </receiver>
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

data class WeatherSnapshot(
    val timestampSec: Long,
    val location: String,
    val currentTempC: Int,
    val todayMinTempC: Int,
    val todayMaxTempC: Int,
    val currentCondition: String,
    val currentConditionCode: Int,
    val humidityPct: Int,
    val windSpeedKmh: Float,
    val windDirectionDeg: Int,
    val uvIndex: Float,
    val precipProbabilityPct: Int,
)

class GenericWeatherReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_GENERIC_WEATHER) return
        val bundle = intent.extras ?: return
        val jsonStr = bundle.getString(EXTRA_WEATHER_JSON) ?: return
        val parsed = runCatching {
            JSONObject(jsonStr).toSnapshot()
        }.getOrNull() ?: return

        val secondary = bundle.getString(EXTRA_WEATHER_SECONDARY_JSON)
        val secondarySnapshots = secondary?.let {
            runCatching {
                JSONArray(it).let { arr ->
                    (0 until arr.length()).map { i -> arr.getJSONObject(i).toSnapshot() }
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()

        forwarder?.invoke(buildList { add(parsed); addAll(secondarySnapshots) })
    }

    companion object {
        const val ACTION_GENERIC_WEATHER = "com.kidneyweakx.miband9active.ACTION_GENERIC_WEATHER"
        const val EXTRA_WEATHER_JSON = "WeatherJson"
        const val EXTRA_WEATHER_SECONDARY_JSON = "WeatherSecondaryJson"

        @Volatile var forwarder: ((List<WeatherSnapshot>) -> Unit)? = null

        private fun JSONObject.toSnapshot(): WeatherSnapshot = WeatherSnapshot(
            timestampSec = optLong("timestamp", System.currentTimeMillis() / 1000),
            location = optString("location", ""),
            currentTempC = optInt("currentTemp", 0),
            todayMinTempC = optInt("todayMinTemp", 0),
            todayMaxTempC = optInt("todayMaxTemp", 0),
            currentCondition = optString("currentCondition", ""),
            currentConditionCode = optInt("currentConditionCode", 0),
            humidityPct = optInt("currentHumidity", 0),
            windSpeedKmh = optDouble("windSpeed", 0.0).toFloat(),
            windDirectionDeg = optInt("windDirection", 0),
            uvIndex = optDouble("uvIndex", 0.0).toFloat(),
            precipProbabilityPct = optInt("precipProbability", 0),
        )
    }
}
