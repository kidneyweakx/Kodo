/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.healthconnect.HealthConnectExporter
import com.kidneyweakx.miband9active.healthconnect.HealthConnectKind as EngineKind
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class HybridHealthConnect : HybridHybridHealthConnectSpec() {

    override fun status(): HealthConnectStatus {
        val kinds = EngineKind.values().toList()
        val engineStatus = runBlocking(Dispatchers.IO) {
            HealthConnectExporter.status(AppContext.context, kinds)
        }
        return HealthConnectStatus(
            installed = engineStatus.installed,
            grantedKinds = engineStatus.granted.map { it.toBridge() }.toTypedArray(),
        )
    }

    override fun requestPermissions(kinds: Array<HealthConnectKind>): Promise<HealthConnectStatus> = Promise.async {
        // Permissions UI must be launched from an Activity; the JS side is
        // expected to call Health Connect's permission contract via Expo,
        // then call status() again. Here we just return current status.
        status()
    }

    override fun exportDay(dateIso: String): Promise<Double> = Promise.async {
        // The actual sample read needs HybridHealthStore + a paired band;
        // when those are wired the export becomes a one-liner. For now,
        // calling with no samples is a no-op.
        0.0
    }

    override fun revokeAndClear(): Promise<Unit> = Promise.async { Unit }

    private fun EngineKind.toBridge(): HealthConnectKind = when (this) {
        EngineKind.STEPS -> HealthConnectKind.STEPS
        EngineKind.HEART_RATE -> HealthConnectKind.HEARTRATE
        EngineKind.SPO2 -> HealthConnectKind.SPO2
        EngineKind.SLEEP -> HealthConnectKind.SLEEP
        EngineKind.ACTIVE_CALORIES -> HealthConnectKind.ACTIVECALORIES
        EngineKind.DISTANCE -> HealthConnectKind.DISTANCE
    }

    @Suppress("unused")
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
}
