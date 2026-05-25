/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.services.WeatherCommands
import com.margelo.nitro.core.Promise
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

class HybridWeatherBridge : HybridHybridWeatherBridgeSpec() {

    override fun push(request: WeatherPushRequest): Promise<Unit> = Promise.async {
        val drv = DriverHolder.current ?: return@async

        val current = XiaomiProto.WeatherCurrent.newBuilder()
            .setWeatherCondition(request.current.conditionCode.toInt())
            .setTemperature(
                XiaomiProto.WeatherUnitValue.newBuilder()
                    .setUnit("C")
                    .setValue(request.current.tempC.toInt()),
            )
            .setHumidity(
                XiaomiProto.WeatherUnitValue.newBuilder()
                    .setUnit("%")
                    .setValue(request.current.humidity.toInt()),
            )
            .build()

        // The daily-forecast schema in the Xiaomi proto is large and
        // wearable-specific; for the slim port we push current-only and let
        // the band display the next-day icon from its own cache.
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(WeatherCommands.COMMAND_TYPE)
            .setSubtype(WeatherCommands.CMD_WEATHER_SET)
            .setWeather(
                XiaomiProto.Weather.newBuilder().setCurrent(current),
            )
            .build()
        drv.sendCommand(cmd)
    }
}
