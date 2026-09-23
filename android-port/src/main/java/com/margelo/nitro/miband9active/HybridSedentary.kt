/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Inactivity / standing reminder — XiaomiHealthService
 *  CMD_CONFIG_STANDING_REMINDER_GET (8/12) / _SET (8/13), proto
 *  Health.standingReminder { enabled, start, end, dnd, dndStart, dndEnd }.
 *  The previous version sent an EMPTY command on subtype 29 (which is not a
 *  standing-reminder command) and exposed an interval the band doesn't have.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.xiaomi.services.DeviceFeatures
import com.kidneyweakx.miband9active.xiaomi.services.HealthSettingsService
import com.kidneyweakx.miband9active.xiaomi.services.HourMin
import com.margelo.nitro.core.Promise

class HybridSedentary : HybridHybridSedentarySpec() {

    init {
        DeviceFeatures.ensureStarted()
    }

    override val pendingPush: Boolean get() = HealthSettingsService.standingPendingPush

    override fun get(): SedentaryConfig? = HealthSettingsService.getStanding()?.toNitro()

    override fun refresh(): Promise<SedentaryConfig?> = Promise.async {
        HealthSettingsService.refreshStanding()?.toNitro()
    }

    override fun set(config: SedentaryConfig): Promise<SedentaryConfig> = Promise.async {
        HealthSettingsService.setStanding(
            HealthSettingsService.Standing(
                enabled = config.enabled,
                start = config.start.toHourMin(),
                end = config.end.toHourMin(),
                dnd = config.dndEnabled,
                dndStart = config.dndStart.toHourMin(),
                dndEnd = config.dndEnd.toHourMin(),
            ),
        ).toNitro()
    }

    private fun TimeOfDay.toHourMin(): HourMin {
        val h = hour.toInt()
        val m = minute.toInt()
        require(h in 0..23 && m in 0..59) { "invalid time $h:$m" }
        return HourMin(h, m)
    }

    private fun HourMin.toNitro() = TimeOfDay(hour = hour.toDouble(), minute = minute.toDouble())

    private fun HealthSettingsService.Standing.toNitro() = SedentaryConfig(
        enabled = enabled,
        start = start.toNitro(),
        end = end.toNitro(),
        dndEnabled = dnd,
        dndStart = dndStart.toNitro(),
        dndEnd = dndEnd.toNitro(),
    )
}
