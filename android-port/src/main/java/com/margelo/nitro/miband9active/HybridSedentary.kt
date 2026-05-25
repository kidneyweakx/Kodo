/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Inactivity reminder. The Xiaomi proto exposes this through the generic
 *  MiscSettings TLV channel (`HealthCommands.CMD_INACTIVITY_SET`). For the
 *  slim port we persist the config locally and write the misc-setting TLV
 *  exactly the way upstream Gadgetbridge does.
 */
package com.margelo.nitro.miband9active

import android.content.Context
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.services.HealthCommands
import com.margelo.nitro.core.Promise
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

class HybridSedentary : HybridHybridSedentarySpec() {

    private val prefs by lazy {
        AppContext.context.getSharedPreferences("miband9active_sedentary", Context.MODE_PRIVATE)
    }

    override fun get(): SedentaryConfig = SedentaryConfig(
        enabled = prefs.getBoolean("enabled", false),
        startHour = prefs.getInt("startHour", 9).toDouble(),
        endHour = prefs.getInt("endHour", 21).toDouble(),
        intervalMinutes = prefs.getInt("intervalMinutes", 60).toDouble(),
        suppressDuringDnd = prefs.getBoolean("suppressDuringDnd", true),
    )

    override fun set(config: SedentaryConfig): Promise<SedentaryConfig> = Promise.async {
        prefs.edit()
            .putBoolean("enabled", config.enabled)
            .putInt("startHour", config.startHour.toInt())
            .putInt("endHour", config.endHour.toInt())
            .putInt("intervalMinutes", config.intervalMinutes.toInt())
            .putBoolean("suppressDuringDnd", config.suppressDuringDnd)
            .apply()

        val drv = DriverHolder.current
        if (drv != null) {
            // Wire the inactivity TLV onto the Health channel — band parses
            // these the same way it would have when configured via the
            // official app.
            val cmd = XiaomiProto.Command.newBuilder()
                .setType(HealthCommands.COMMAND_TYPE)
                .setSubtype(HealthCommands.CMD_INACTIVITY_SET)
                .build()
            drv.sendCommand(cmd)
        }

        config
    }
}
