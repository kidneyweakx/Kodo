/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Weather sources: GenericWeatherReceiver broadcasts (stored + pushed
 *  natively) and the optional OpenWeatherMap poller (OwmWeather, >= 6 h
 *  WorkManager, network + battery-not-low, only with a user API key).
 *  Nothing here invents weather: without a source, getLast() is null.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.xiaomi.XiaomiWeatherConditions
import com.kidneyweakx.miband9active.xiaomi.services.DeviceFeatures
import com.kidneyweakx.miband9active.xiaomi.services.OwmWeather
import com.kidneyweakx.miband9active.xiaomi.services.WeatherService
import com.margelo.nitro.core.Promise
import java.time.Instant
import java.time.ZoneId

class HybridWeatherProvider : HybridHybridWeatherProviderSpec() {

    init {
        DeviceFeatures.ensureStarted()
    }

    override fun getLast(): Variant_NullType_WeatherPushRequest {
        val w = WeatherService.latest() ?: return Defaults.WEATHER
        val zone = ZoneId.systemDefault()
        val request = WeatherPushRequest(
            locationName = w.location,
            latitude = w.latitude?.toDouble() ?: 0.0,
            longitude = w.longitude?.toDouble() ?: 0.0,
            current = WeatherCurrent(
                tempC = (w.currentTempK - 273).toDouble(),
                conditionCode = XiaomiWeatherConditions.convertOwmConditionToXiaomi(w.conditionCode).toDouble(),
                humidity = w.humidity.toDouble(),
                // WeatherCurrent.aqi is non-nullable in types.ts: -1 = unknown (render "—"), never a fake 0.
                aqi = if (w.aqi >= 0) w.aqi.toDouble() else -1.0,
            ),
            daily = w.forecasts.mapIndexed { i, d ->
                WeatherDailyForecast(
                    date = Instant.ofEpochSecond(w.timestamp.toLong()).atZone(zone).toLocalDate().plusDays((i + 1).toLong()).toString(),
                    highC = (d.maxTempK - 273).toDouble(),
                    lowC = (d.minTempK - 273).toDouble(),
                    conditionCode = XiaomiWeatherConditions.convertOwmConditionToXiaomi(d.conditionCode).toDouble(),
                )
            }.toTypedArray(),
        )
        return Variant_NullType_WeatherPushRequest.create(request)
    }

    override fun getLastSnapshot(): WeatherSnapshot? = WeatherService.latest()?.toSnapshot()

    override fun setOwmConfig(config: OwmConfig) {
        OwmWeather.setConfig(
            OwmWeather.Config(
                enabled = config.enabled,
                apiKey = config.apiKey,
                latitude = config.latitude,
                longitude = config.longitude,
                locationName = config.locationName,
                pollMinutes = config.pollMinutes.toInt(),
            ),
        )
    }

    override fun getOwmConfig(): OwmConfig {
        val c = OwmWeather.getConfig()
        return OwmConfig(
            enabled = c.enabled,
            apiKey = c.apiKey,
            latitude = c.latitude,
            longitude = c.longitude,
            locationName = c.locationName,
            pollMinutes = c.pollMinutes.toDouble(),
        )
    }

    override fun refreshNow(): Promise<Boolean> = Promise.async { OwmWeather.refresh() }

    override fun onWeather(listener: (snapshot: WeatherSnapshot) -> Unit): () -> Unit =
        WeatherService.addListener { listener(it.toSnapshot()) }
}
