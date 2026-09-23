/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  JS facade over WeatherService (XiaomiWeatherService port). The old version
 *  sent only `current` with no metadata, unit "C" and an unmapped condition
 *  code; the band needs metadata + "℃" + Xiaomi condition codes, plus the
 *  daily (10/1) and hourly (10/2) forecasts.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.xiaomi.services.DeviceFeatures
import com.kidneyweakx.miband9active.xiaomi.services.WeatherData
import com.kidneyweakx.miband9active.xiaomi.services.WeatherService
import com.margelo.nitro.core.Promise
import kotlin.math.roundToInt

class HybridWeatherBridge : HybridHybridWeatherBridgeSpec() {

    init {
        DeviceFeatures.ensureStarted()
    }

    override fun push(snapshot: WeatherSnapshot): Promise<Boolean> = Promise.async {
        WeatherService.store(listOf(snapshot.toWeatherData()), autoPush = false)
        WeatherService.pushAll()
    }

    override fun pushLast(): Promise<Boolean> = Promise.async { WeatherService.pushAll() }

    override fun getTemperatureUnit(): TemperatureUnit =
        if (WeatherService.getTemperatureUnit() == "fahrenheit") TemperatureUnit.FAHRENHEIT else TemperatureUnit.CELSIUS

    override fun setTemperatureUnit(unit: TemperatureUnit): Promise<Unit> = Promise.async {
        WeatherService.setTemperatureUnit(if (unit == TemperatureUnit.FAHRENHEIT) "fahrenheit" else "celsius")
    }
}

// ---- shared snapshot <-> engine mapping (also used by HybridWeatherProvider) ----

private fun cToK(c: Double): Int = c.roundToInt() + 273

internal fun WeatherSnapshot.toWeatherData(): WeatherData = WeatherData(
    source = when (source) {
        WeatherSource.BROADCAST -> "broadcast"
        WeatherSource.OWM -> "owm"
        WeatherSource.APP -> "app"
    },
    timestamp = timestampSec.toInt(),
    location = location,
    isCurrentLocation = when (isCurrentLocation) {
        true -> 1
        false -> 0
        null -> -1
    },
    latitude = latitude?.toFloat(),
    longitude = longitude?.toFloat(),
    currentTempK = cToK(currentTempC),
    todayMinTempK = cToK(todayMinTempC),
    todayMaxTempK = cToK(todayMaxTempC),
    conditionCode = conditionCode.toInt(),
    conditionText = conditionText ?: "",
    humidity = humidityPct.roundToInt(),
    windSpeedKmh = windKmh.toFloat(),
    windDirection = windDirectionDeg.roundToInt(),
    uvIndex = uvIndex?.toFloat(),
    pressureMb = pressureMb?.toFloat() ?: 0f,
    aqi = aqi?.roundToInt() ?: -1,
    sunRise = sunriseSec?.toInt() ?: 0,
    sunSet = sunsetSec?.toInt() ?: 0,
    forecasts = daily.map {
        WeatherData.Daily(
            minTempK = cToK(it.minTempC),
            maxTempK = cToK(it.maxTempC),
            conditionCode = it.conditionCode.toInt(),
            aqi = it.aqi?.roundToInt() ?: -1,
            sunRise = it.sunriseSec?.toInt() ?: 0,
            sunSet = it.sunsetSec?.toInt() ?: 0,
        )
    },
    hourly = hourly.map {
        WeatherData.Hourly(
            timestamp = it.timestampSec.toInt(),
            tempK = cToK(it.tempC),
            conditionCode = it.conditionCode.toInt(),
            windSpeedKmh = it.windKmh?.toFloat() ?: 0f,
            windDirection = it.windDirectionDeg?.roundToInt() ?: 0,
        )
    },
)

internal fun WeatherData.toSnapshot(): WeatherSnapshot = WeatherSnapshot(
    source = when (source) {
        "broadcast" -> WeatherSource.BROADCAST
        "owm" -> WeatherSource.OWM
        else -> WeatherSource.APP
    },
    timestampSec = timestamp.toDouble(),
    location = location,
    isCurrentLocation = when (isCurrentLocation) {
        1 -> true
        0 -> false
        else -> null
    },
    latitude = latitude?.toDouble(),
    longitude = longitude?.toDouble(),
    conditionCode = conditionCode.toDouble(),
    conditionText = conditionText.ifEmpty { null },
    currentTempC = (currentTempK - 273).toDouble(),
    todayMinTempC = (todayMinTempK - 273).toDouble(),
    todayMaxTempC = (todayMaxTempK - 273).toDouble(),
    humidityPct = humidity.toDouble(),
    windKmh = windSpeedKmh.toDouble(),
    windDirectionDeg = windDirection.toDouble(),
    uvIndex = uvIndex?.toDouble(),
    pressureMb = if (pressureMb > 0f) pressureMb.toDouble() else null,
    aqi = if (aqi >= 0) aqi.toDouble() else null,
    sunriseSec = if (sunRise != 0) sunRise.toDouble() else null,
    sunsetSec = if (sunSet != 0) sunSet.toDouble() else null,
    daily = forecasts.map {
        WeatherDaily(
            minTempC = (it.minTempK - 273).toDouble(),
            maxTempC = (it.maxTempK - 273).toDouble(),
            conditionCode = it.conditionCode.toDouble(),
            aqi = if (it.aqi >= 0) it.aqi.toDouble() else null,
            sunriseSec = if (it.sunRise != 0) it.sunRise.toDouble() else null,
            sunsetSec = if (it.sunSet != 0) it.sunSet.toDouble() else null,
        )
    }.toTypedArray(),
    hourly = hourly.map {
        WeatherHourly(
            timestampSec = it.timestamp.toDouble(),
            tempC = (it.tempK - 273).toDouble(),
            conditionCode = it.conditionCode.toDouble(),
            windKmh = it.windSpeedKmh.toDouble(),
            windDirectionDeg = it.windDirection.toDouble(),
        )
    }.toTypedArray(),
)
