/*  Copyright (C) 2022-2026 Daniele Gobbetti, Enrico Brambilla, José Rebelo,
 *                          TylerWilliamson, Thomas Kuehne                (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                                        (Kotlin port, slimmed)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  External weather apps (Breezy Weather, GBWeather, Tasker, …) broadcast
 *  ACTION_GENERIC_WEATHER with WeatherSpec JSON:
 *    - "WeatherGz":            gzip'd JSON array of weather objects, or
 *    - "WeatherJson":          primary location object, plus optional
 *      "WeatherSecondaryJson": JSON array of further locations.
 *  Temperatures are Kelvin, wind km/h, codes are OpenWeatherMap codes.
 *
 *  We accept Gadgetbridge's own action too, so apps that only know
 *  Gadgetbridge can target this package unchanged. Parsed weather is stored
 *  natively and pushed to the band only if it is already connected
 *  (upstream #6186: weather must not start the device service).
 *
 *  Manifest entry:
 *    <receiver android:name="com.kidneyweakx.miband9active.xiaomi.services.GenericWeatherReceiver"
 *              android:exported="true">
 *        <intent-filter>
 *            <action android:name="com.kidneyweakx.miband9active.ACTION_GENERIC_WEATHER"/>
 *            <action android:name="nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER"/>
 *        </intent-filter>
 *    </receiver>
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

class GenericWeatherReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_GENERIC_WEATHER && action != ACTION_GENERIC_WEATHER_GB) return
        val bundle = intent.extras ?: run {
            Log.w(TAG, "Intent has no extras")
            return
        }
        try {
            val weathers = mutableListOf<WeatherData>()
            if (bundle.containsKey(EXTRA_WEATHER_GZ)) {
                val compressed = bundle.getByteArray(EXTRA_WEATHER_GZ)
                val json = compressed?.let { gunzipUtf8(it) }
                if (json != null && json.length > 1) {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) weathers += WeatherData.fromGbJson(arr.getJSONObject(i), SOURCE)
                }
            } else {
                val primary = bundle.getString(EXTRA_WEATHER_JSON) ?: run {
                    Log.w(TAG, "Bundle key $EXTRA_WEATHER_JSON not found")
                    return
                }
                weathers += WeatherData.fromGbJson(JSONObject(primary), SOURCE)
                bundle.getString(EXTRA_WEATHER_SECONDARY_JSON)?.let { secondary ->
                    val arr = JSONArray(secondary)
                    for (i in 0 until arr.length()) weathers += WeatherData.fromGbJson(arr.getJSONObject(i), SOURCE)
                }
            }
            Log.i(TAG, "Got generic weather for ${weathers.size} locations")
            DeviceFeatures.ensureStarted()
            WeatherService.store(weathers)
        } catch (t: Throwable) {
            Log.w(TAG, "received broken or incompatible weather data", t)
        }
    }

    private fun gunzipUtf8(bytes: ByteArray): String? = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    companion object {
        private const val TAG = "MB9A_WeatherRx"
        private const val SOURCE = "broadcast"

        const val ACTION_GENERIC_WEATHER = "com.kidneyweakx.miband9active.ACTION_GENERIC_WEATHER"
        const val ACTION_GENERIC_WEATHER_GB = "nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER"
        const val EXTRA_WEATHER_GZ = "WeatherGz"
        const val EXTRA_WEATHER_JSON = "WeatherJson"
        const val EXTRA_WEATHER_SECONDARY_JSON = "WeatherSecondaryJson"
    }
}
