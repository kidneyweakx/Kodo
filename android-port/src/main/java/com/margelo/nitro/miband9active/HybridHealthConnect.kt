/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.margelo.nitro.core.Promise

class HybridHealthConnect : HybridHybridHealthConnectSpec() {
    override fun status(): HealthConnectStatus =
        HealthConnectStatus(installed = false, grantedKinds = emptyArray())

    override fun requestPermissions(kinds: Array<HealthConnectKind>): Promise<HealthConnectStatus> =
        Promise.async { HealthConnectStatus(installed = false, grantedKinds = emptyArray()) }

    override fun exportDay(dateIso: String): Promise<Double> = Promise.async { 0.0 }
    override fun revokeAndClear(): Promise<Unit> = Promise.async { Unit }
}
