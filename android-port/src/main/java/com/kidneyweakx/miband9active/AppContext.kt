/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Holds an application Context that the Nitro Hybrid implementations can
 *  use without going through React/Expo native module lifecycle. Populated
 *  by [InitializerProvider] which Android instantiates automatically before
 *  Application.onCreate via the `<provider>` declared in the manifest.
 */
package com.kidneyweakx.miband9active

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper

object AppContext {
    @Volatile private var app: Context? = null
    val context: Context
        get() = app ?: throw IllegalStateException("AppContext not initialized; declare <provider android:name=\"com.kidneyweakx.miband9active.InitializerProvider\" /> in the manifest.")

    internal fun install(context: Context) {
        app = context.applicationContext
    }
}

class InitializerProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { AppContext.install(it) }
        // Band-initiated features (find phone, GPS workout, weather request,
        // camera) must be handled even before JS creates any HybridObject.
        // Cheap: two Flow collectors on the (lazily created) driver singleton.
        com.kidneyweakx.miband9active.xiaomi.services.DeviceFeatures.ensureStarted()
        // Keep-alive (docs/POWER.md rule 1). Posted to the main looper so it runs
        // after every ContentProvider (incl. WorkManager's androidx.startup
        // initializer) and Application.onCreate; the disk reads then move to IO.
        Handler(Looper.getMainLooper()).post { onProcessStarted() }
        return true
    }

    /**
     * Every cold start — reboot (the system re-binds the NotificationListener
     * or WorkManager's boot receiver reschedules jobs), OEM kill + rebind, a
     * WorkManager wake, or the user opening the app — re-arms the passive
     * reconnect and re-applies the periodic-sync setting. No BOOT_COMPLETED
     * receiver of our own is needed: both paths above already start the process.
     */
    private fun onProcessStarted() {
        val ctx = context?.applicationContext ?: return
        com.kidneyweakx.miband9active.xiaomi.services.DeviceFeatures.launch {
            com.kidneyweakx.miband9active.xiaomi.protocol.PowerLog.event(
                com.kidneyweakx.miband9active.xiaomi.protocol.PowerLog.PROCESS_START,
                "pid ${android.os.Process.myPid()}",
                wakeup = true,
            )
            try {
                DriverHolder.armReconnect("process start")
            } catch (t: Throwable) {
                android.util.Log.w("MB9A_AppContext", "arming reconnect at start failed", t)
            }
            try {
                com.kidneyweakx.miband9active.sync.MiBand9PeriodicSyncWorker.reconcile(ctx)
            } catch (t: Throwable) {
                android.util.Log.w("MB9A_AppContext", "periodic sync reconcile failed", t)
            }
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
