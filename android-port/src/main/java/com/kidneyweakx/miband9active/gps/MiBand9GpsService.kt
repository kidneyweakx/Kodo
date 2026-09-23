/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Foreground service (type `location`) that owns the GPS listener while a
 *  workout needs phone GPS — the slim equivalent of Gadgetbridge's
 *  GBLocationService with the GPS provider at 1 s.
 *
 *  docs/POWER.md: runs ONLY while a workout is active (phone-started or
 *  band-requested, see WorkoutGpsController), START_NOT_STICKY so the OS never
 *  resurrects it without a workout, and stops as soon as the last owner
 *  releases it. Android 14+: `startForeground(..., FOREGROUND_SERVICE_TYPE_LOCATION)`
 *  requires FOREGROUND_SERVICE_LOCATION in the manifest and a granted
 *  ACCESS_FINE_LOCATION at that moment — otherwise we stop immediately.
 */
package com.kidneyweakx.miband9active.gps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class MiBand9GpsService : Service() {

    private var locationManager: LocationManager? = null
    private val listener = LocationListener { loc ->
        latestLocation = loc
        emitters.forEach { runCatching { it(loc) } }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop requested from notification")
            WorkoutGpsController.onServiceStoppedByUser()
            stopSelf()
            return START_NOT_STICKY
        }
        // Must enter the foreground within 5 s of startForegroundService().
        try {
            ensureChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (t: Throwable) {
            // SecurityException (no location permission / FGS type) or
            // ForegroundServiceStartNotAllowedException (background start).
            Log.w(TAG, "startForeground failed", t)
            running = false
            WorkoutGpsController.onServiceFailed()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!listening) {
            val lm = locationManager
            if (lm == null || !hasLocationPermission(this) || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Log.w(TAG, "GPS unavailable (permission or provider)")
                running = false
                WorkoutGpsController.onServiceFailed()
                stopSelf()
                return START_NOT_STICKY
            }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
            listening = true
            Log.i("MB9A_POWER", "workout GPS listener ON")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { locationManager?.removeUpdates(listener) } catch (_: Throwable) {}
        if (listening) Log.i("MB9A_POWER", "workout GPS listener OFF")
        listening = false
        running = false
        latestLocation = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val zh = Locale.getDefault().language == "zh"
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, if (zh) "運動 GPS" else "Workout GPS", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(): Notification {
        val zh = Locale.getDefault().language == "zh"
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MiBand9GpsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (zh) "運動進行中" else "Workout active")
            .setContentText(if (zh) "正在為手環提供 GPS 定位" else "Sharing phone GPS with your band")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    if (zh) "停止" else "Stop",
                    stop,
                ).build(),
            )
            .build()
    }

    companion object {
        private const val TAG = "MiBand9GpsService"
        const val CHANNEL_ID = "miband9active.workout"
        const val NOTIF_ID = 9001
        private const val ACTION_STOP = "com.kidneyweakx.miband9active.gps.STOP"

        @Volatile var latestLocation: Location? = null
            private set
        @Volatile var running: Boolean = false
            private set
        @Volatile private var listening: Boolean = false

        private val emitters = CopyOnWriteArrayList<(Location) -> Unit>()

        fun onLocation(listener: (Location) -> Unit): () -> Unit {
            emitters += listener
            return { emitters -= listener }
        }

        fun hasLocationPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun isGpsEnabled(context: Context): Boolean =
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                ?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

        /** @return false if the OS refused (background-start restriction, missing permission). */
        fun start(context: Context): Boolean {
            if (!hasLocationPermission(context)) return false
            return try {
                context.startForegroundService(Intent(context, MiBand9GpsService::class.java))
                running = true
                true
            } catch (t: Throwable) {
                // IllegalStateException / ForegroundServiceStartNotAllowedException (API 31+), SecurityException
                Log.w(TAG, "startForegroundService refused", t)
                false
            }
        }

        fun stop(context: Context) {
            running = false
            context.stopService(Intent(context, MiBand9GpsService::class.java))
        }
    }
}
