/*
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * Health Connect bridge. The permission sheet needs an Activity result
 * contract; a Nitro HybridObject has no Activity of its own, so we register
 * `PermissionController.createRequestPermissionResultContract()` on the
 * current React Activity's ActivityResultRegistry (register-at-any-time
 * API, unregistered as soon as the result arrives) — the same contract
 * Gadgetbridge's HealthConnectPreferencesActivity uses.
 */
package com.margelo.nitro.miband9active

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.PermissionController
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.healthconnect.HealthConnectAvailability as EngineAvailability
import com.kidneyweakx.miband9active.healthconnect.HealthConnectExporter
import com.kidneyweakx.miband9active.healthconnect.HealthConnectKind as EngineKind
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class HybridHealthConnect : HybridHybridHealthConnectSpec() {

    override fun status(): Promise<HealthConnectStatus> = Promise.async {
        currentStatus()
    }

    override fun requestPermissions(kinds: Array<HealthConnectKind>): Promise<HealthConnectStatus> = Promise.async {
        val ctx = AppContext.context
        when (HealthConnectExporter.availability(ctx)) {
            EngineAvailability.UPDATE_REQUIRED -> {
                openPlayStore()
                return@async currentStatus()
            }
            EngineAvailability.UNAVAILABLE -> return@async currentStatus()
            EngineAvailability.AVAILABLE -> Unit
        }

        val wanted = kinds.map { it.toEngine() }.ifEmpty { EngineKind.entries.toList() }
        val permissions = HealthConnectExporter.permissionsFor(wanted)

        val activity = NitroModules.applicationContext?.currentActivity as? ComponentActivity
            ?: throw IllegalStateException("Health Connect permissions need the app in the foreground")

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Set<String>> { cont ->
                val key = "mb9a-hc-permissions-${requestCounter.incrementAndGet()}"
                val holder = arrayOfNulls<ActivityResultLauncher<Set<String>>>(1)
                val launcher = activity.activityResultRegistry.register(
                    key,
                    PermissionController.createRequestPermissionResultContract(),
                ) { granted ->
                    holder[0]?.unregister()
                    if (cont.isActive) cont.resume(granted)
                }
                holder[0] = launcher
                cont.invokeOnCancellation { launcher.unregister() }
                try {
                    launcher.launch(permissions)
                } catch (t: Throwable) {
                    launcher.unregister()
                    throw t
                }
            }
        }
        currentStatus()
    }

    override fun exportDay(dateIso: String): Promise<Double> = Promise.async {
        val date = LocalDate.parse(dateIso.take(10))
        HealthConnectExporter.exportDay(AppContext.context, date).toDouble()
    }

    override fun exportRange(fromIso: String, toIso: String): Promise<Double> = Promise.async {
        val from = LocalDate.parse(fromIso.take(10))
        val to = LocalDate.parse(toIso.take(10))
        require(!to.isBefore(from)) { "toIso is before fromIso" }
        require(!from.plusDays(400).isBefore(to)) { "range too large" }
        HealthConnectExporter.exportRange(AppContext.context, from, to).toDouble()
    }

    override fun revokeAndClear(): Promise<Unit> = Promise.async {
        val ctx = AppContext.context
        // Delete first: deleting needs the WRITE permissions we are about to revoke.
        HealthConnectExporter.deleteAllWritten(ctx)
        HealthConnectExporter.revokeAll(ctx)
        Unit
    }

    // ----------------------------------------------------------------- internals

    private suspend fun currentStatus(): HealthConnectStatus {
        val s = try {
            HealthConnectExporter.status(AppContext.context)
        } catch (t: Throwable) {
            Log.w(TAG, "status failed", t)
            return HealthConnectStatus(HealthConnectAvailability.UNAVAILABLE, false, emptyArray())
        }
        return HealthConnectStatus(
            availability = when (s.availability) {
                EngineAvailability.AVAILABLE -> HealthConnectAvailability.AVAILABLE
                EngineAvailability.UPDATE_REQUIRED -> HealthConnectAvailability.UPDATEREQUIRED
                EngineAvailability.UNAVAILABLE -> HealthConnectAvailability.UNAVAILABLE
            },
            installed = s.installed,
            grantedKinds = s.granted.map { it.toBridge() }.toTypedArray(),
        )
    }

    /** Android 13-: install / update the Health Connect app (official onboarding deep link). */
    private fun openPlayStore() {
        val ctx = AppContext.context
        val uri = Uri.parse(
            "market://details?id=${HealthConnectExporter.PROVIDER_PACKAGE}&url=healthconnect%3A%2F%2Fonboarding",
        )
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.android.vending")
            .putExtra("overlay", true)
            .putExtra("callerId", ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Play Store not available", t)
        }
    }

    private fun HealthConnectKind.toEngine(): EngineKind = when (this) {
        HealthConnectKind.STEPS -> EngineKind.STEPS
        HealthConnectKind.HEARTRATE -> EngineKind.HEART_RATE
        HealthConnectKind.SPO2 -> EngineKind.SPO2
        HealthConnectKind.SLEEP -> EngineKind.SLEEP
        HealthConnectKind.ACTIVECALORIES -> EngineKind.ACTIVE_CALORIES
        HealthConnectKind.DISTANCE -> EngineKind.DISTANCE
        HealthConnectKind.RESTINGHEARTRATE -> EngineKind.RESTING_HEART_RATE
        HealthConnectKind.EXERCISE -> EngineKind.EXERCISE
    }

    private fun EngineKind.toBridge(): HealthConnectKind = when (this) {
        EngineKind.STEPS -> HealthConnectKind.STEPS
        EngineKind.HEART_RATE -> HealthConnectKind.HEARTRATE
        EngineKind.SPO2 -> HealthConnectKind.SPO2
        EngineKind.SLEEP -> HealthConnectKind.SLEEP
        EngineKind.ACTIVE_CALORIES -> HealthConnectKind.ACTIVECALORIES
        EngineKind.DISTANCE -> HealthConnectKind.DISTANCE
        EngineKind.RESTING_HEART_RATE -> HealthConnectKind.RESTINGHEARTRATE
        EngineKind.EXERCISE -> HealthConnectKind.EXERCISE
    }

    companion object {
        private const val TAG = "MB9A_HealthConnect"
        private val requestCounter = AtomicInteger(0)
    }
}
