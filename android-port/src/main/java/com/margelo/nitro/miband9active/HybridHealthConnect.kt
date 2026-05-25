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
        val samples = com.kidneyweakx.miband9active.SampleStore.loadActivity(dateIso)
        val (summary, stages) = com.kidneyweakx.miband9active.SampleStore.loadSleep(dateIso)
        val written = HealthConnectExporter.exportDay(
            context = AppContext.context,
            bandName = "Mi Band 9 Active",
            bandSerial = dateIso,
            samples = samples,
            sleepStages = stages,
            sleepSummary = summary,
        )
        written.toDouble()
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
