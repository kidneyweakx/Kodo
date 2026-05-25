/*  Copyright (C) 2017-2024 Andreas Shimokawa, Yoran Vulker  (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                            (Kotlin port)
 *
 *  This file is part of mi-band-9-active and ported from Gadgetbridge.
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package gg.solidarity.miband9active.xiaomi

/**
 * Mi Band 9 Active accepts the Xiaomi weather condition codes documented here.
 * The codes above 33 are known to crash some Xiaomi watches when their icon set
 * does not cover them, so we map every OpenWeatherMap condition into the
 * narrower 0..33 range.
 *
 * Translated from `XiaomiWeatherConditions.java` in Gadgetbridge.
 */
object XiaomiWeatherConditions {
    const val CLEAR_SKY: Byte = 0
    const val CLOUDY: Byte = 1
    const val OVERCAST: Byte = 2
    const val SHOWER: Byte = 3
    const val THUNDERSTORM: Byte = 4
    const val HAIL: Byte = 5
    const val SLEET: Byte = 6
    const val LIGHT_RAIN: Byte = 7
    const val MODERATE_RAIN: Byte = 8
    const val HEAVY_RAINFALL: Byte = 9
    const val RAINSTORM: Byte = 10
    const val DOWNPOUR: Byte = 11
    const val HEAVY_RAINSTORM: Byte = 12
    const val SNOW_SHOWERS: Byte = 13
    const val LIGHT_SNOW: Byte = 14
    const val MODERATE_SNOW: Byte = 15
    const val HEAVY_SNOW: Byte = 16
    const val BLIZZARD: Byte = 17
    const val MIST: Byte = 18
    const val FREEZING_RAIN: Byte = 19
    const val SANDSTORM: Byte = 20
    const val LIGHT_TO_MODERATE_RAIN: Byte = 21
    const val MODERATE_TO_HEAVY_RAIN: Byte = 22
    const val HEAVY_RAIN_TO_RAINSTORM: Byte = 23
    const val RAINSTORM_TO_DOWNPOUR: Byte = 24
    const val DOWNPOUR_TO_HEAVY_RAINSTORM: Byte = 25
    const val LIGHT_TO_MODERATE_SNOW: Byte = 26
    const val MODERATE_TO_HEAVY_SNOW: Byte = 27
    const val HEAVY_SNOW_TO_BLIZZARD: Byte = 28
    const val DUST: Byte = 29
    const val WINDY: Byte = 30
    const val STRONG_SANDSTORM: Byte = 31
    const val MODERATE_FOG: Byte = 32
    const val SNOW: Byte = 33

    fun convertOwmConditionToXiaomi(openWeatherMapCondition: Int): Byte = when (openWeatherMapCondition) {
        // 2xx: Thunderstorm
        200, 201, 202, 210, 211, 212, 221, 230, 231, 232 -> THUNDERSTORM
        // 3xx: Drizzle → light rain (no equivalent on band)
        300, 301, 302, 310, 311, 312, 313, 314, 321 -> LIGHT_RAIN
        // 5xx: Rain
        500 -> LIGHT_RAIN
        501 -> MODERATE_RAIN
        502 -> RAINSTORM
        503 -> DOWNPOUR
        504 -> HEAVY_RAINSTORM
        511 -> FREEZING_RAIN
        520, 521 -> SHOWER
        522, 531 -> HEAVY_RAINFALL
        // 6xx: Snow
        600 -> LIGHT_SNOW
        601 -> MODERATE_SNOW
        602 -> HEAVY_SNOW
        611, 612 -> SLEET
        615, 616 -> SNOW_SHOWERS
        620, 621 -> HEAVY_SNOW
        622 -> BLIZZARD
        // 7xx: Atmosphere
        701, 711, 721, 731, 741 -> MIST
        751 -> SANDSTORM
        761, 762, 771 -> DUST
        781, 900 -> WINDY
        // 800
        800 -> CLEAR_SKY
        // 80x
        801, 802, 803, 804 -> OVERCAST
        // 9xx extreme
        901 -> WINDY
        903, 904 -> CLEAR_SKY
        905 -> WINDY
        906 -> HAIL
        951, 952, 953, 954, 955 -> CLEAR_SKY
        956, 957, 958, 959, 960, 961, 902, 962 -> WINDY
        else -> CLEAR_SKY
    }
}
