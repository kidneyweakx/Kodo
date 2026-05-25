/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.margelo.nitro.core.Promise

class HybridSedentary : HybridHybridSedentarySpec() {
    private var config = SedentaryConfig(
        enabled = false,
        startHour = 9.0,
        endHour = 21.0,
        intervalMinutes = 60.0,
        suppressDuringDnd = true,
    )
    override fun get(): SedentaryConfig = config
    override fun set(config: SedentaryConfig): Promise<SedentaryConfig> = Promise.async {
        this.config = config
        config
    }
}
