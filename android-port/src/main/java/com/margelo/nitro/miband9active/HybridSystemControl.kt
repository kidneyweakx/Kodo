/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.services.SystemCommands
import com.margelo.nitro.core.Promise
import java.util.Calendar
import java.util.TimeZone
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

class HybridSystemControl : HybridHybridSystemControlSpec() {

    private var prefs = BandPreferenceSnapshot(
        language = DisplayLanguage.ZH_HANT,
        use24HourClock = true,
        heartRateRealtime = false,
        heartRateInterval = HeartRateInterval._30M,
        stepGoal = 8_000.0,
    )

    /** Phone-side ringer when the band presses "Find phone". The actual ring
     *  must be driven by the JS layer (it owns the audio session) — we just
     *  expose a hook so the BLE side can flip a flag. */
    override fun ringPhone() {
        // Triggered by band -> phone via driver.incoming subscription in JS.
        // Phone-side audio is the React layer's responsibility.
    }
    override fun silencePhone() {}

    override fun syncClock(): Promise<Unit> = Promise.async {
        val drv = DriverHolder.current ?: return@async
        val tz = TimeZone.getDefault()
        val now = Calendar.getInstance(tz)
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(SystemCommands.COMMAND_TYPE)
            .setSubtype(SystemCommands.CMD_CLOCK)
            .setSystem(
                XiaomiProto.System.newBuilder().setClock(
                    XiaomiProto.Clock.newBuilder().setTime(
                        XiaomiProto.Time.newBuilder()
                            .setHour(now.get(Calendar.HOUR_OF_DAY))
                            .setMinute(now.get(Calendar.MINUTE))
                            .setSecond(now.get(Calendar.SECOND))
                            .setMillisecond(now.get(Calendar.MILLISECOND))
                            .build(),
                    ).setDate(
                        XiaomiProto.Date.newBuilder()
                            .setYear(now.get(Calendar.YEAR))
                            .setMonth(now.get(Calendar.MONTH) + 1)
                            .setDay(now.get(Calendar.DAY_OF_MONTH))
                            .build(),
                    ).setTimezone(
                        XiaomiProto.TimeZone.newBuilder()
                            .setZoneOffset(tz.rawOffset / (15 * 60 * 1000))
                            .setDstOffset(if (tz.inDaylightTime(now.time)) 4 else 0)
                            .setName(tz.id)
                            .build(),
                    ).build(),
                ),
            )
            .build()
        drv.sendCommand(cmd)
    }

    override fun getPreferences(): BandPreferenceSnapshot = prefs
    override fun setPreferences(prefs: BandPreferenceSnapshot): Promise<BandPreferenceSnapshot> = Promise.async {
        this.prefs = prefs

        // Mirror language to the band.
        val drv = DriverHolder.current
        if (drv != null) {
            val langCode = when (prefs.language) {
                DisplayLanguage.ZH_HANT -> "zh_TW"
                DisplayLanguage.EN -> "en_US"
            }
            val cmd = XiaomiProto.Command.newBuilder()
                .setType(SystemCommands.COMMAND_TYPE)
                .setSubtype(SystemCommands.CMD_LANGUAGE)
                .setSystem(
                    XiaomiProto.System.newBuilder().setLanguage(
                        XiaomiProto.Language.newBuilder().setCode(langCode).build(),
                    ),
                )
                .build()
            drv.sendCommand(cmd)
        }

        prefs
    }
}
