/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.xiaomi.services.GenericWeatherReceiver
import com.kidneyweakx.miband9active.xiaomi.services.WeatherSnapshot
import com.kidneyweakx.miband9active.xiaomi.XiaomiWeatherConditions
import android.content.SharedPreferences

class HybridWeatherProvider : HybridHybridWeatherProviderSpec() {

    override fun getLast(): Variant_NullType_WeatherPushRequest {
        val last = lastSnapshot ?: return Defaults.WEATHER
        return Variant_NullType_WeatherPushRequest.create(last)
    }

    override fun setOwmConfig(config: OwmConfig) {
        val prefs = AppContext.context.getSharedPreferences(OWM_PREFS, android.content.Context.MODE_PRIVATE)
        val effective = config.copy(pollMinutes = config.pollMinutes.coerceAtLeast(30.0))
        prefs.edit()
            .putBoolean("enabled", effective.enabled)
            .putString("apiKey", effective.apiKey)
            .putFloat("lat", effective.latitude.toFloat())
            .putFloat("lon", effective.longitude.toFloat())
            .putString("name", effective.locationName)
            .putFloat("pollMinutes", effective.pollMinutes.toFloat())
            .apply()
    }

    override fun getOwmConfig(): OwmConfig {
        val prefs = AppContext.context.getSharedPreferences(OWM_PREFS, android.content.Context.MODE_PRIVATE)
        return OwmConfig(
            enabled = prefs.getBoolean("enabled", false),
            apiKey = prefs.getString("apiKey", "") ?: "",
            latitude = prefs.getFloat("lat", 0f).toDouble(),
            longitude = prefs.getFloat("lon", 0f).toDouble(),
            locationName = prefs.getString("name", "") ?: "",
            pollMinutes = prefs.getFloat("pollMinutes", 30f).toDouble().coerceAtLeast(30.0),
        )
    }

    override fun onExternalWeather(listener: (snapshot: WeatherPushRequest) -> Unit): () -> Unit {
        val forwarder: (List<WeatherSnapshot>) -> Unit = { list ->
            list.firstOrNull()?.let {
                lastSnapshot = it.toRequest()
                listener(it.toRequest())
            }
        }
        GenericWeatherReceiver.forwarder = forwarder
        return { GenericWeatherReceiver.forwarder = null }
    }

    private fun WeatherSnapshot.toRequest(): WeatherPushRequest = WeatherPushRequest(
        locationName = location,
        latitude = 0.0,
        longitude = 0.0,
        current = WeatherCurrent(
            tempC = currentTempC.toDouble(),
            conditionCode = XiaomiWeatherConditions.convertOwmConditionToXiaomi(currentConditionCode).toDouble(),
            humidity = humidityPct.toDouble(),
            aqi = 0.0,
        ),
        daily = emptyArray(),
    )

    companion object {
        private const val OWM_PREFS = "miband9active_weather"
        @Volatile private var lastSnapshot: WeatherPushRequest? = null
    }
}
