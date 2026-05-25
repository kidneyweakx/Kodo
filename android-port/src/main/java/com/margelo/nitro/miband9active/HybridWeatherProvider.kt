/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

class HybridWeatherProvider : HybridHybridWeatherProviderSpec() {
    private var owm = OwmConfig(
        enabled = false,
        apiKey = "",
        latitude = 0.0,
        longitude = 0.0,
        locationName = "",
        pollMinutes = 30.0,
    )

    override fun getLast(): Variant_NullType_WeatherPushRequest = Defaults.WEATHER
    override fun setOwmConfig(config: OwmConfig) {
        // Enforce 30-min minimum
        owm = config.copy(pollMinutes = config.pollMinutes.coerceAtLeast(30.0))
    }
    override fun getOwmConfig(): OwmConfig = owm
    override fun onExternalWeather(listener: (snapshot: WeatherPushRequest) -> Unit): () -> Unit = noopUnsubscribe()
}
