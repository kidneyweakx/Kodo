/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Foreground service of type `connectedDevice|location` for workout GPS.
 *  Only runs while a workout is active; stopping the workout immediately
 *  unbinds the location listener (see docs/POWER.md).
 */
package com.kidneyweakx.miband9active.gps

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper

class MiBand9GpsService : Service() {

    private var locationManager: LocationManager? = null
    private val listener = LocationListener { loc -> latestLocation = loc; emitters.forEach { it(loc) } }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,         // 1s minimum interval
                2.0f,           // 2m minimum displacement
                listener,
                Looper.getMainLooper(),
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { locationManager?.removeUpdates(listener) } catch (_: Throwable) {}
        emitters.clear()
        latestLocation = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Workout GPS", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Workout active")
            .setContentText("Recording GPS for your Mi Band 9 Active workout")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "miband9active.workout"
        const val NOTIF_ID = 9001

        @Volatile var latestLocation: Location? = null
            private set

        private val emitters = mutableListOf<(Location) -> Unit>()
        fun onLocation(listener: (Location) -> Unit): () -> Unit {
            emitters += listener
            return { emitters -= listener }
        }

        fun start(context: Context) {
            val intent = Intent(context, MiBand9GpsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MiBand9GpsService::class.java))
        }
    }
}
