/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Defaults the Nitro-generated specs return when the engine is not yet
 *  hooked up (no paired band, no Health Connect, …). Centralised so the
 *  Hybrid implementations stay small.
 */
package com.margelo.nitro.miband9active

import com.margelo.nitro.core.NullType

object Defaults {
    val NULL: NullType = NullType.NULL

    val BAND: Variant_NullType_PairedBand get() = Variant_NullType_PairedBand.create(NULL)
    val BATTERY: Variant_NullType_BatteryInfo get() = Variant_NullType_BatteryInfo.create(NULL)
    val SUMMARY: Variant_NullType_HealthDailySummary get() = Variant_NullType_HealthDailySummary.create(NULL)
    val GPS: Variant_NullType_GpsSample get() = Variant_NullType_GpsSample.create(NULL)
    val WEATHER: Variant_NullType_WeatherPushRequest get() = Variant_NullType_WeatherPushRequest.create(NULL)
}

fun noopUnsubscribe(): () -> Unit = { }
