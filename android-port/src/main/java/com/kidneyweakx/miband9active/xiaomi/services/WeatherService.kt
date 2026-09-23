/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo, opcode  (Gadgetbridge XiaomiWeatherService)
 *  Copyright (C) 2026 kidneyweakx                                  (Kotlin port)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Command type 10. Ported: sendCurrentConditions / sendDailyForecast /
 *  sendHourlyForecast, single vs. multiple location handling (5/6/7/8,
 *  incl. #6772 duplicate-location guard), band-initiated condition requests
 *  (3) and the temperature-scale prefs (10).
 *
 *  Also the process-wide weather store (Gadgetbridge's `Weather` singleton):
 *  whatever arrives (broadcast / OWM worker / JS) is persisted, so it can be
 *  re-pushed on the next connect, and pushed immediately if connected.
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.util.Log
import com.kidneyweakx.miband9active.xiaomi.XiaomiWeatherConditions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.json.JSONArray

object WeatherService {
    private const val TAG = "MB9A_Weather"

    private const val TEMPERATURE_SCALE_CELSIUS = 1
    private const val TEMPERATURE_SCALE_FAHRENHEIT = 2

    private const val KEY_WEATHER = "weather_specs"
    private const val KEY_UNIT = "weather_temperature_unit"
    private const val KEY_MULTI = "weather_multi_known"

    private val listeners = CopyOnWriteArrayList<(WeatherData) -> Unit>()
    private val pushMutex = Mutex()

    /** Locations the band already has (multi-location devices). */
    private val cachedLocations: MutableSet<XiaomiProto.WeatherLocation> = ConcurrentHashMap.newKeySet()

    /** While onConnected() awaits GET_LOCATIONS it processes the reply itself. */
    @Volatile private var awaitingLocations = false

    // ================================================================ store

    fun all(): List<WeatherData> {
        val arr = FeatureStore.getJsonArray(KEY_WEATHER) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { runCatching { WeatherData.fromGbJson(it, "app") }.getOrNull() }
        }
    }

    fun latest(): WeatherData? = all().firstOrNull()

    fun addListener(listener: (WeatherData) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /**
     * Weather.setWeatherSpec() + onSendWeather(): persist, notify, and push if
     * (and only if) the band is already connected — like upstream #6186 we
     * never wake the BLE link for weather.
     */
    fun store(specs: List<WeatherData>, autoPush: Boolean = true) {
        if (specs.isEmpty()) return
        val arr = JSONArray()
        specs.forEach { arr.put(it.toJson()) }
        FeatureStore.putJsonArray(KEY_WEATHER, arr)
        val primary = specs.first()
        Log.i(TAG, "stored weather for ${specs.size} location(s), primary='${primary.location}' src=${primary.source}")
        listeners.forEach { runCatching { it(primary) } }
        if (autoPush && BandChannel.isConnected) DeviceFeatures.launch { pushAll() }
    }

    // ================================================================ prefs

    /** "celsius" | "fahrenheit" */
    fun getTemperatureUnit(): String = FeatureStore.prefs.getString(KEY_UNIT, "celsius") ?: "celsius"

    suspend fun setTemperatureUnit(unit: String) {
        FeatureStore.prefs.edit().putString(KEY_UNIT, if (unit == "fahrenheit") "fahrenheit" else "celsius").apply()
        setMeasurementSystem()
    }

    /** XiaomiWeatherService.setMeasurementSystem() */
    private suspend fun setMeasurementSystem(): Boolean {
        val scale = if (getTemperatureUnit() == "fahrenheit") TEMPERATURE_SCALE_FAHRENHEIT else TEMPERATURE_SCALE_CELSIUS
        return BandChannel.send(WeatherCommands.COMMAND_TYPE, WeatherCommands.CMD_SET_WEATHER_PREFS) {
            setWeather(XiaomiProto.Weather.newBuilder().setPrefs(XiaomiProto.WeatherPrefs.newBuilder().setTemperatureScale(scale)))
        }
    }

    private val multipleLocationsSupported: Boolean?
        get() = if (FeatureStore.prefs.getBoolean(KEY_MULTI, false)) FeatureStore.feature(FeatureStore.FEAT_MULTIPLE_WEATHER_LOCATIONS) else null

    private fun setMultipleLocationsSupported(supported: Boolean) {
        FeatureStore.setFeature(FeatureStore.FEAT_MULTIPLE_WEATHER_LOCATIONS, supported)
        FeatureStore.prefs.edit().putBoolean(KEY_MULTI, true).apply()
    }

    // ================================================================ push

    /** XiaomiWeatherService.onSendWeather() */
    suspend fun pushAll(): Boolean = pushMutex.withLock {
        val specs = all()
        if (specs.isEmpty() || !BandChannel.isConnected) return@withLock false
        if (multipleLocationsSupported == true) {
            sendWeatherSpecList(specs)
        } else {
            val spec = specs.first()
            addWeatherLocation(locationOf(spec))
            sendWeatherSpec(spec)
        }
    }

    private suspend fun sendWeatherSpecList(weatherSpecs: List<WeatherData>): Boolean {
        // #6772 - avoid duplicates, otherwise we're unable to tell them apart when sending
        val specsToSend = mutableListOf<WeatherData>()
        val seenKeys = mutableSetOf<String>()
        for (spec in weatherSpecs) {
            if (specsToSend.size >= 5) break
            if (seenKeys.add(getLocationKey(spec.location))) specsToSend += spec
        }
        val locations = specsToSend.map { locationOf(it) }
        for (l in locations) {
            if (l !in cachedLocations) {
                addWeatherLocation(l)
                cachedLocations += l // assume adding location goes according to plan
            }
        }
        BandChannel.send(WeatherCommands.COMMAND_TYPE, WeatherCommands.CMD_SET_LOCATIONS) {
            setWeather(XiaomiProto.Weather.newBuilder().setLocations(XiaomiProto.WeatherLocations.newBuilder().addAllLocation(locations)))
        }
        var ok = true
        for (spec in specsToSend) ok = sendWeatherSpec(spec) && ok
        // request current location list from device to remove dangling locations
        BandChannel.send(WeatherCommands.COMMAND_TYPE, WeatherCommands.CMD_GET_LOCATIONS)
        return ok
    }

    private suspend fun sendWeatherSpec(spec: WeatherData): Boolean {
        Log.d(TAG, "send weather for '${spec.location}'")
        val a = sendCurrentConditions(spec)
        val b = sendDailyForecast(spec)
        val c = sendHourlyForecast(spec)
        return a && b && c
    }

    private suspend fun addWeatherLocation(location: XiaomiProto.WeatherLocation): Boolean =
        BandChannel.send(WeatherCommands.COMMAND_TYPE, WeatherCommands.CMD_ADD_LOCATION) {
            setWeather(XiaomiProto.Weather.newBuilder().setLocation(location))
        }

    private suspend fun sendCurrentConditions(spec: WeatherData): Boolean {
        val current = XiaomiProto.WeatherCurrent.newBuilder()
            .setMetadata(metadataOf(spec))
            .setWeatherCondition(XiaomiWeatherConditions.convertOwmConditionToXiaomi(spec.conditionCode).toInt())
            .setTemperature(unitValue(spec.currentTempK - 273, "℃"))
            .setHumidity(unitValue(spec.humidity, "%"))
            .setWind(unitValue(spec.windSpeedAsBeaufort(), spec.windDirection.toString()))
            .setAqi(unitValue(if (spec.aqi >= 0) spec.aqi else 0, "Unknown"))
            .setWarning(XiaomiProto.WeatherWarnings.newBuilder()) // TODO upstream: warnings not in spec
            .setPressure(spec.pressureMb * 100f)
        // Sent as an sint but displayed with a decimal point (upstream). Omitted when unknown.
        spec.uvIndex?.let { current.setUv(unitValue(Math.round(it), "")) }
        return BandChannel.send(WeatherCommands.COMMAND_TYPE, WeatherCommands.CMD_SET_CURRENT_WEATHER) {
            setWeather(XiaomiProto.Weather.newBuilder().setCurrent(current))
        }
    }

    private suspend fun sendDailyForecast(spec: WeatherData): Boolean {
        val entries = XiaomiProto.ForecastEntries.newBuilder()
        // reconstruct first forecast element from current conditions (today)
        entries.addEntry(
            XiaomiProto.ForecastEntry.newBuilder()
                .setAqi(unitValue(if (spec.aqi >= 0) spec.aqi else 0, "Unknown"))
                .setTemperatureRange(rangeOf(spec.todayMaxTempK - 273, spec.todayMinTempK - 273))
                .setConditionRange(
                    rangeOf(
                        XiaomiWeatherConditions.convertOwmConditionToXiaomi(spec.conditionCode).toInt(),
                        XiaomiWeatherConditions.convertOwmConditionToXiaomi(spec.conditionCode).toInt(),
                    ),
                )
                .setTemperatureSymbol("℃")
                .setSunriseSunset(sunriseSunset(spec.sunRise, spec.sunSet)),
        )
        for (d in spec.forecasts.take(6)) {
            val code = XiaomiWeatherConditions.convertOwmConditionToXiaomi(d.conditionCode).toInt()
            entries.addEntry(
                XiaomiProto.ForecastEntry.newBuilder()
                    .setAqi(unitValue(if (d.aqi >= 0) d.aqi else 0, "Unknown"))
                    .setConditionRange(rangeOf(code, code))
                    .setTemperatureRange(rangeOf(d.maxTempK - 273, d.minTempK - 273))
                    .setTemperatureSymbol("℃")
                    .setSunriseSunset(sunriseSunset(d.sunRise, d.sunSet)),
            )
        }
        return BandChannel.send(WeatherCommands.COMMAND_TYPE, WeatherCommands.CMD_UPDATE_DAILY_FORECAST) {
            setWeather(
                XiaomiProto.Weather.newBuilder().setForecast(
                    XiaomiProto.WeatherForecast.newBuilder().setMetadata(metadataOf(spec)).setEntries(entries),
                ),
            )
        }
    }

    private suspend fun sendHourlyForecast(spec: WeatherData): Boolean {
        val entries = XiaomiProto.ForecastEntries.newBuilder()
        for (h in spec.hourly.take(23)) {
            entries.addEntry(
                XiaomiProto.ForecastEntry.newBuilder()
                    .setAqi(unitValue(0, "Unknown")) // FIXME upstream: when available through spec
                    .setTemperatureRange(rangeOf(0, h.tempK - 273)) // from: not set, but required
                    .setConditionRange(rangeOf(0, XiaomiWeatherConditions.convertOwmConditionToXiaomi(h.conditionCode).toInt()))
                    .setTemperatureSymbol("℃")
                    .setWind(unitValue(WeatherData.toBeaufort(h.windSpeedKmh), h.windDirection.toString())),
            )
        }
        return BandChannel.send(WeatherCommands.COMMAND_TYPE, WeatherCommands.CMD_UPDATE_HOURLY_FORECAST) {
            setWeather(
                XiaomiProto.Weather.newBuilder().setForecast(
                    XiaomiProto.WeatherForecast.newBuilder().setMetadata(metadataOf(spec)).setEntries(entries),
                ),
            )
        }
    }

    private fun metadataOf(spec: WeatherData): XiaomiProto.WeatherMetadata =
        XiaomiProto.WeatherMetadata.newBuilder()
            .setPublicationTimestamp(unixTimestampToISOWithColons(spec.timestamp))
            .setCityName("")
            .setLocationName(spec.location)
            .setLocationKey(getLocationKey(spec.location)) // FIXME upstream: placeholder, key not in spec
            .setIsCurrentLocation(spec.isCurrentLocation == 1)
            .build()

    private fun locationOf(spec: WeatherData): XiaomiProto.WeatherLocation =
        XiaomiProto.WeatherLocation.newBuilder()
            .setCode(getLocationKey(spec.location))
            .setName(spec.location)
            .build()

    private fun getLocationKey(locationName: String): String =
        String.format(Locale.ROOT, "accu:%d", Math.abs(locationName.hashCode()) % 1000000)

    private fun unitValue(value: Int, unit: String): XiaomiProto.WeatherUnitValue =
        XiaomiProto.WeatherUnitValue.newBuilder().setUnit(unit).setValue(value).build()

    private fun rangeOf(from: Int, to: Int): XiaomiProto.WeatherRange =
        XiaomiProto.WeatherRange.newBuilder().setFrom(from).setTo(to).build()

    private fun sunriseSunset(rise: Int, set: Int): XiaomiProto.WeatherSunriseSunset =
        XiaomiProto.WeatherSunriseSunset.newBuilder()
            .setSunrise(if (rise != 0) unixTimestampToISOWithColons(rise) else "")
            .setSunset(if (set != 0) unixTimestampToISOWithColons(set) else "")
            .build()

    /** XiaomiWeatherService.unixTimestampToISOWithColons() */
    fun unixTimestampToISOWithColons(timestamp: Int): String =
        StringBuilder(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(timestamp * 1000L)))
            .insert(22, ':')
            .toString()

    // ================================================================ lifecycle

    /** XiaomiWeatherService.initialize() + first onSendWeather(). */
    suspend fun onConnected() {
        cachedLocations.clear()
        setMeasurementSystem()
        // determine whether multiple weather locations are supported (status 1 = no)
        awaitingLocations = true
        val reply = try {
            BandChannel.request(WeatherCommands.COMMAND_TYPE, WeatherCommands.CMD_GET_LOCATIONS)
        } finally {
            awaitingLocations = false
        }
        if (reply == null) {
            Log.w(TAG, "no answer to GET_LOCATIONS; treating as single-location")
        } else {
            onWeatherLocationsReceived(reply)
        }
        // upstream sends cached weather here when single-location; we also refresh multi-location bands
        pushAll()
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        if (cmd.hasStatus() && cmd.status != 0) Log.w(TAG, "weather command ${cmd.subtype} status ${cmd.status}")
        when (cmd.subtype) {
            WeatherCommands.CMD_GET_LOCATIONS -> if (!awaitingLocations) onWeatherLocationsReceived(cmd)
            WeatherCommands.CMD_REQUEST_CONDITIONS_FOR_LOCATION -> DeviceFeatures.launch { onConditionRequest(cmd) }
            else -> Unit // acks for 0/1/2/6/7/8/10 are log-only upstream
        }
    }

    /** XiaomiWeatherService.onConditionRequestReceived() */
    private suspend fun onConditionRequest(cmd: XiaomiProto.Command) {
        if (cmd.hasStatus() && cmd.status != 0) return
        val specs = all()
        if (cmd.hasWeather() && cmd.weather.hasLocation()) {
            val name = cmd.weather.location.name
            if (!cmd.weather.location.code.isNullOrEmpty() && !name.isNullOrEmpty()) {
                specs.firstOrNull { it.location == name }?.let { pushMutex.withLock { sendWeatherSpec(it) }; return }
            }
        }
        val spec = specs.firstOrNull()
        if (spec == null) {
            Log.w(TAG, "band asked for weather but none is stored")
            return
        }
        pushMutex.withLock { sendWeatherSpec(spec) }
    }

    /** XiaomiWeatherService.onWeatherLocationsReceived() — cache bookkeeping only; never pushes. */
    private fun onWeatherLocationsReceived(cmd: XiaomiProto.Command) {
        if (cmd.hasStatus() && cmd.status == 1) {
            setMultipleLocationsSupported(false)
            return
        }
        if (cmd.hasStatus() && cmd.status != 0) return
        setMultipleLocationsSupported(true)
        if (!cmd.hasWeather() || !cmd.weather.hasLocations()) return
        val retrieved = cmd.weather.locations.locationList
        val duplicates = retrieved.filter { l -> retrieved.count { it == l } > 1 }.toSet()
        val specLocations = all().map { locationOf(it) }.toSet()
        val missingSpec = retrieved.filter { it !in specLocations }.toSet()
        DeviceFeatures.launch {
            if (duplicates.isNotEmpty()) removeLocations(duplicates)
            if (missingSpec.isNotEmpty()) removeLocations(missingSpec)
        }
        cachedLocations.clear()
        cachedLocations += retrieved.filter { it in specLocations && retrieved.count { r -> r == it } == 1 }
    }

    private suspend fun removeLocations(locations: Collection<XiaomiProto.WeatherLocation>) {
        BandChannel.send(WeatherCommands.COMMAND_TYPE, WeatherCommands.CMD_REMOVE_LOCATIONS) {
            setWeather(XiaomiProto.Weather.newBuilder().setLocations(XiaomiProto.WeatherLocations.newBuilder().addAllLocation(locations)))
        }
    }
}
