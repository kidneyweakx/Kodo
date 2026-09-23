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
 * Optional built-in OpenWeatherMap source for users without a weather app.
 * docs/POWER.md "WeatherPushWorker": periodic >= 6 h, network connected +
 * battery not low, and ONLY when the user enabled it with their own API key.
 * Uses the free 2.5 endpoints (/weather + /forecast); results are converted
 * to WeatherSpec units (Kelvin, km/h, OWM codes). Daily/hourly entries are
 * real aggregates of the 3-hourly forecast; UV / AQI are not available from
 * these endpoints and are left unset rather than faked.
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kidneyweakx.miband9active.AppContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object OwmWeather {
    private const val TAG = "MB9A_OWM"
    private const val PREFS = "miband9active_weather"
    private const val WORK_NAME = "miband9active.owm_weather"
    const val MIN_POLL_MINUTES = 360

    data class Config(
        val enabled: Boolean,
        val apiKey: String,
        val latitude: Double,
        val longitude: Double,
        val locationName: String,
        val pollMinutes: Int,
    ) {
        val usable: Boolean get() = enabled && apiKey.isNotBlank()
    }

    private val prefs get() = AppContext.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getConfig(): Config {
        val p = prefs
        return Config(
            enabled = p.getBoolean("enabled", false),
            apiKey = p.getString("apiKey", "") ?: "",
            latitude = p.getFloat("lat", 0f).toDouble(),
            longitude = p.getFloat("lon", 0f).toDouble(),
            locationName = p.getString("name", "") ?: "",
            pollMinutes = maxOf(MIN_POLL_MINUTES, p.getInt("pollMinutesInt", MIN_POLL_MINUTES)),
        )
    }

    /** Persists and (re)schedules or cancels the periodic worker. */
    fun setConfig(config: Config) {
        val effective = config.copy(pollMinutes = maxOf(MIN_POLL_MINUTES, config.pollMinutes))
        prefs.edit()
            .putBoolean("enabled", effective.enabled)
            .putString("apiKey", effective.apiKey.trim())
            .putFloat("lat", effective.latitude.toFloat())
            .putFloat("lon", effective.longitude.toFloat())
            .putString("name", effective.locationName)
            .putInt("pollMinutesInt", effective.pollMinutes)
            .apply()
        val wm = WorkManager.getInstance(AppContext.context)
        if (effective.usable) {
            val request = PeriodicWorkRequestBuilder<OwmWeatherWorker>(effective.pollMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            wm.cancelUniqueWork(WORK_NAME)
        }
    }

    /** One fetch + store (+ push if connected). false when disabled or on failure. */
    suspend fun refresh(): Boolean {
        val cfg = getConfig()
        if (!cfg.usable) return false
        val data = withContext(Dispatchers.IO) { runCatching { fetch(cfg) }.onFailure { Log.w(TAG, "fetch failed", it) }.getOrNull() }
            ?: return false
        WeatherService.store(listOf(data))
        return true
    }

    private fun fetch(cfg: Config): WeatherData {
        val q = "lat=${cfg.latitude.fmt()}&lon=${cfg.longitude.fmt()}&appid=${URLEncoder.encode(cfg.apiKey, "UTF-8")}"
        val current = JSONObject(httpGet("https://api.openweathermap.org/data/2.5/weather?$q"))
        val forecast = JSONObject(httpGet("https://api.openweathermap.org/data/2.5/forecast?$q"))

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val nowSec = (System.currentTimeMillis() / 1000).toInt()
        val currentTempK = current.getJSONObject("main").getDouble("temp")
        val currentCode = current.getJSONArray("weather").getJSONObject(0).getInt("id")

        data class Slot(val dt: Int, val date: LocalDate, val hour: Int, val tempK: Double, val minK: Double, val maxK: Double, val code: Int, val windMs: Double, val windDeg: Int)
        val list = forecast.getJSONArray("list")
        val slots = (0 until list.length()).map { i ->
            val e = list.getJSONObject(i)
            val main = e.getJSONObject("main")
            val dt = e.getInt("dt")
            val local = Instant.ofEpochSecond(dt.toLong()).atZone(zone)
            val wind = e.optJSONObject("wind")
            Slot(
                dt = dt,
                date = local.toLocalDate(),
                hour = local.hour,
                tempK = main.getDouble("temp"),
                minK = main.optDouble("temp_min", main.getDouble("temp")),
                maxK = main.optDouble("temp_max", main.getDouble("temp")),
                code = e.getJSONArray("weather").getJSONObject(0).getInt("id"),
                windMs = wind?.optDouble("speed", 0.0) ?: 0.0,
                windDeg = wind?.optInt("deg", 0) ?: 0,
            )
        }

        val todaySlots = slots.filter { it.date == today }
        val todayMin = (todaySlots.map { it.minK } + currentTempK).minOrNull() ?: currentTempK
        val todayMax = (todaySlots.map { it.maxK } + currentTempK).maxOrNull() ?: currentTempK

        val daily = slots.filter { it.date.isAfter(today) }
            .groupBy { it.date }
            .toSortedMap()
            .values
            .map { day ->
                val midday = day.minByOrNull { abs(it.hour - 12) } ?: day.first()
                WeatherData.Daily(
                    minTempK = day.minOf { it.minK }.roundToInt(),
                    maxTempK = day.maxOf { it.maxK }.roundToInt(),
                    conditionCode = midday.code,
                    aqi = -1,
                    sunRise = 0,
                    sunSet = 0,
                )
            }

        val hourly = slots.filter { it.dt >= nowSec }.take(8).map {
            WeatherData.Hourly(
                timestamp = it.dt,
                tempK = it.tempK.roundToInt(),
                conditionCode = it.code,
                windSpeedKmh = (it.windMs * 3.6).toFloat(),
                windDirection = it.windDeg,
            )
        }

        val sys = current.optJSONObject("sys")
        val wind = current.optJSONObject("wind")
        val name = cfg.locationName.ifBlank { current.optString("name", "") }
        return WeatherData(
            source = "owm",
            timestamp = current.optInt("dt", nowSec),
            location = name,
            isCurrentLocation = -1,
            latitude = cfg.latitude.toFloat(),
            longitude = cfg.longitude.toFloat(),
            currentTempK = currentTempK.roundToInt(),
            todayMinTempK = todayMin.roundToInt(),
            todayMaxTempK = todayMax.roundToInt(),
            conditionCode = currentCode,
            conditionText = current.getJSONArray("weather").getJSONObject(0).optString("description", ""),
            humidity = current.getJSONObject("main").optInt("humidity", 0),
            windSpeedKmh = ((wind?.optDouble("speed", 0.0) ?: 0.0) * 3.6).toFloat(),
            windDirection = wind?.optInt("deg", 0) ?: 0,
            uvIndex = null,
            pressureMb = current.getJSONObject("main").optDouble("pressure", 0.0).toFloat(),
            aqi = -1,
            sunRise = sys?.optInt("sunrise", 0) ?: 0,
            sunSet = sys?.optInt("sunset", 0) ?: 0,
            forecasts = daily,
            hourly = hourly,
        )
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.requestMethod = "GET"
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("OWM HTTP $code")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun Double.fmt(): String = String.format(Locale.ROOT, "%.4f", this)
}

class OwmWeatherWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        Log.i("MB9A_POWER", "OWM weather worker wake")
        OwmWeather.refresh()
        // Never retry-loop: the next periodic window will try again.
        return Result.success()
    }
}
