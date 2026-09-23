/*  Copyright (C) 2016-2026 Andreas Shimokawa, Arjan Schrijver, beardhatcode,
 *                          Carsten Pfeiffer, Daniele Gobbetti, Enrico Brambilla, José Rebelo,
 *                          Taavi Eomäe, Avery Sterk, Thomas Kuehne  (Gadgetbridge WeatherSpec)
 *  Copyright (C) 2022-2026 Daniele Gobbetti, Enrico Brambilla, José Rebelo,
 *                          TylerWilliamson, Thomas Kuehne           (Gadgetbridge GenericWeatherReceiver)
 *  Copyright (C) 2026 kidneyweakx                                   (Kotlin port, slimmed)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  The subset of Gadgetbridge's WeatherSpec the Xiaomi weather service
 *  consumes, with the SAME units: temperatures in Kelvin (Int), wind in km/h,
 *  timestamps in unix seconds, condition codes in OpenWeatherMap numbering.
 *
 *  JSON (de)serialisation uses WeatherSpec's GenericWeatherReceiver keys, so
 *  one parser handles the ACTION_GENERIC_WEATHER broadcast AND our own
 *  persisted copy.
 */
package com.kidneyweakx.miband9active.xiaomi.services

import org.json.JSONArray
import org.json.JSONObject

data class WeatherData(
    /** "broadcast" | "owm" | "app" (ours; not part of WeatherSpec). */
    val source: String,
    val timestamp: Int,
    val location: String,
    /** 0 false, 1 true, -1 unknown */
    val isCurrentLocation: Int,
    val latitude: Float?,
    val longitude: Float?,
    val currentTempK: Int,
    val todayMinTempK: Int,
    val todayMaxTempK: Int,
    val conditionCode: Int,
    val conditionText: String,
    val humidity: Int,
    val windSpeedKmh: Float,
    val windDirection: Int,
    /** null when the provider did not include it (we never invent a 0). */
    val uvIndex: Float?,
    /** mbar, 0 when unknown (WeatherSpec default). */
    val pressureMb: Float,
    /** -1 when unknown (WeatherSpec.AirQuality default). */
    val aqi: Int,
    val sunRise: Int,
    val sunSet: Int,
    val forecasts: List<Daily>,
    val hourly: List<Hourly>,
) {
    data class Daily(
        val minTempK: Int,
        val maxTempK: Int,
        val conditionCode: Int,
        val aqi: Int,
        val sunRise: Int,
        val sunSet: Int,
    )

    data class Hourly(
        val timestamp: Int,
        val tempK: Int,
        val conditionCode: Int,
        val windSpeedKmh: Float,
        val windDirection: Int,
    )

    /** WeatherSpec.windSpeedAsBeaufort() */
    fun windSpeedAsBeaufort(): Int = toBeaufort(windSpeedKmh)

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("source", source)
            .put("timestamp", timestamp)
            .put("location", location)
            .put("isCurrentLocation", isCurrentLocation)
            .put("currentTemp", currentTempK)
            .put("todayMinTemp", todayMinTempK)
            .put("todayMaxTemp", todayMaxTempK)
            .put("currentConditionCode", conditionCode)
            .put("currentCondition", conditionText)
            .put("currentHumidity", humidity)
            .put("windSpeed", windSpeedKmh.toDouble())
            .put("windDirection", windDirection)
            .put("pressure", pressureMb.toDouble())
            .put("sunRise", sunRise)
            .put("sunSet", sunSet)
        latitude?.let { o.put("latitude", it.toDouble()) }
        longitude?.let { o.put("longitude", it.toDouble()) }
        uvIndex?.let { o.put("uvIndex", it.toDouble()) }
        if (aqi >= 0) o.put("airQuality", JSONObject().put("aqi", aqi))
        val f = JSONArray()
        forecasts.forEach { d ->
            val e = JSONObject()
                .put("minTemp", d.minTempK)
                .put("maxTemp", d.maxTempK)
                .put("conditionCode", d.conditionCode)
                .put("sunRise", d.sunRise)
                .put("sunSet", d.sunSet)
            if (d.aqi >= 0) e.put("airQuality", JSONObject().put("aqi", d.aqi))
            f.put(e)
        }
        o.put("forecasts", f)
        val h = JSONArray()
        hourly.forEach { x ->
            h.put(
                JSONObject()
                    .put("timestamp", x.timestamp)
                    .put("temp", x.tempK)
                    .put("conditionCode", x.conditionCode)
                    .put("windSpeed", x.windSpeedKmh.toDouble())
                    .put("windDirection", x.windDirection),
            )
        }
        o.put("hourly", h)
        return o
    }

    companion object {
        // Lower bounds of beaufort regions 1 to 12 (WeatherSpec)
        private val BEAUFORT = floatArrayOf(2f, 6f, 12f, 20f, 29f, 39f, 50f, 62f, 75f, 89f, 103f, 118f)

        fun toBeaufort(speedKmh: Float): Int {
            var level = 0
            while (level < BEAUFORT.size && BEAUFORT[level] < speedKmh) level++
            return level
        }

        /** GenericWeatherReceiver.weatherFromJson() */
        fun fromGbJson(o: JSONObject, source: String): WeatherData {
            val forecasts = mutableListOf<Daily>()
            o.optJSONArray("forecasts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    forecasts += Daily(
                        minTempK = f.int("minTemp", 0),
                        maxTempK = f.int("maxTemp", 0),
                        conditionCode = f.int("conditionCode", 0),
                        aqi = f.optJSONObject("airQuality")?.int("aqi", -1) ?: -1,
                        sunRise = f.int("sunRise", 0),
                        sunSet = f.int("sunSet", 0),
                    )
                }
            }
            val hourly = mutableListOf<Hourly>()
            o.optJSONArray("hourly")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val h = arr.optJSONObject(i) ?: continue
                    hourly += Hourly(
                        timestamp = h.int("timestamp", 0),
                        tempK = h.int("temp", 0),
                        conditionCode = h.int("conditionCode", 0),
                        windSpeedKmh = h.float("windSpeed", 0f),
                        windDirection = h.int("windDirection", 0),
                    )
                }
            }
            return WeatherData(
                source = (o.opt("source") as? String) ?: source,
                timestamp = o.int("timestamp", (System.currentTimeMillis() / 1000).toInt()),
                location = (o.opt("location") as? String) ?: "",
                isCurrentLocation = o.int("isCurrentLocation", -1),
                latitude = if (o.has("latitude")) o.float("latitude", 0f) else null,
                longitude = if (o.has("longitude")) o.float("longitude", 0f) else null,
                currentTempK = o.int("currentTemp", 0),
                todayMinTempK = o.int("todayMinTemp", 0),
                todayMaxTempK = o.int("todayMaxTemp", 0),
                conditionCode = o.int("currentConditionCode", 0),
                conditionText = (o.opt("currentCondition") as? String) ?: "",
                humidity = o.int("currentHumidity", 0),
                windSpeedKmh = o.float("windSpeed", 0f),
                windDirection = o.int("windDirection", 0),
                uvIndex = if (o.opt("uvIndex") is Number) o.float("uvIndex", 0f) else null,
                pressureMb = o.float("pressure", 0f),
                aqi = o.optJSONObject("airQuality")?.int("aqi", -1) ?: -1,
                sunRise = o.int("sunRise", 0),
                sunSet = o.int("sunSet", 0),
                forecasts = forecasts,
                hourly = hourly,
            )
        }

        /** GenericWeatherReceiver.getInt(): any JSON number, else default. */
        private fun JSONObject.int(name: String, default: Int): Int = (opt(name) as? Number)?.toInt() ?: default
        private fun JSONObject.float(name: String, default: Float): Float = (opt(name) as? Number)?.toFloat() ?: default
    }
}
