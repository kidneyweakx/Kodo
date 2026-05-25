/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.margelo.nitro.core.Promise

class HybridWeatherBridge : HybridHybridWeatherBridgeSpec() {
    override fun push(request: WeatherPushRequest): Promise<Unit> = Promise.async { Unit }
}
