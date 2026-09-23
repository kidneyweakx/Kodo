/*
 * Copyright (C) 2023-2024 José Rebelo, Yoran Vulker and other Gadgetbridge contributors
 *                         (XiaomiMusicService, MediaManager, GBMusicControlReceiver)
 *
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 */
package com.kidneyweakx.miband9active.media

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.notifications.MiBand9NotificationListener
import com.kidneyweakx.miband9active.xiaomi.notifications.MusicCmd
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

/**
 * Native now-playing tracking + band music remote (command type 18).
 *
 *  - Follows the active MediaSession via MediaSessionManager, which is allowed
 *    because our NotificationListenerService is enabled (no extra permission).
 *  - Pushes MusicInfo to the band on metadata / play-state changes, only while
 *    connected, debounced 150 ms and de-duplicated (position-only drift is not
 *    re-sent; seeks are).
 *  - Band MediaKey buttons are dispatched to the active MediaController here
 *    (GBMusicControlReceiver), then reported to JS listeners.
 *  - Band "music screen opened" (18/0) refreshes and answers immediately
 *    (XiaomiMusicService CMD_MUSIC_GET).
 *
 * Mutable state is main-thread only.
 */
object MediaSessionTracker {
    private const val TAG = "MB9A_Media"
    private const val PUSH_DEBOUNCE_MS = 150L
    private const val SEEK_TOLERANCE_MS = 3_000L

    enum class BandMediaCommand { PLAY, PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN }

    /** A real snapshot of what the phone is playing. Never synthesized. */
    data class NowPlaying(
        val track: String,
        val artist: String,
        val album: String,
        val packageName: String,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
    )

    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArrayList<(BandMediaCommand) -> Unit>()

    private var appContext: Context? = null
    private var sessionManager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var controller: MediaController? = null
    private var manual: NowPlaying? = null

    // What the band last received (dedupe).
    private var lastSentKey: String? = null
    private var lastSentPositionMs = 0L
    private var lastSentAtElapsed = 0L
    private var lastSentPlaying = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = schedulePush()
        override fun onMetadataChanged(metadata: MediaMetadata?) = schedulePush()
        override fun onSessionDestroyed() {
            bindController(null)
            refreshController()
            schedulePush()
        }
    }

    private val pushRunnable = Runnable { pushToBand(force = false) }

    // ------------------------------------------------------------------ lifecycle

    fun start(context: Context) {
        val app = context.applicationContext
        main.post {
            appContext = app
            if (sessionsListener != null) return@post
            val msm = app.getSystemService(MediaSessionManager::class.java) ?: return@post
            sessionManager = msm
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessionsChanged(list) }
            try {
                msm.addOnActiveSessionsChangedListener(listener, MiBand9NotificationListener.componentName(app), main)
                sessionsListener = listener
                onSessionsChanged(msm.getActiveSessions(MiBand9NotificationListener.componentName(app)))
            } catch (e: SecurityException) {
                Log.w(TAG, "no media session access (notification access not granted?)", e)
            }
        }
    }

    fun stop() {
        main.post {
            val msm = sessionManager
            val listener = sessionsListener
            if (msm != null && listener != null) {
                runCatching { msm.removeOnActiveSessionsChangedListener(listener) }
            }
            sessionsListener = null
            bindController(null)
        }
    }

    fun addListener(listener: (BandMediaCommand) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** A media notification was posted/updated; the session callback normally covers it. */
    fun onMediaNotification() {
        main.post {
            if (controller == null) {
                refreshController()
                schedulePush()
            }
        }
    }

    fun onBandConnectionChanged(connected: Boolean) {
        main.post {
            // New link → the band may have lost what we sent; allow the next change through.
            lastSentKey = null
            if (!connected) main.removeCallbacks(pushRunnable)
        }
    }

    // ------------------------------------------------------------------ session tracking

    private fun onSessionsChanged(list: List<MediaController>?) {
        val controllers = list.orEmpty()
        // Prefer whatever is actually playing; upstream simply takes controllers[0].
        val pick = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
        val current = controller
        if (pick != null && current != null && pick.sessionToken == current.sessionToken) return
        bindController(pick)
        schedulePush()
    }

    private fun bindController(next: MediaController?) {
        controller?.let { runCatching { it.unregisterCallback(controllerCallback) } }
        controller = next
        next?.registerCallback(controllerCallback, main)
    }

    /** MediaManager.refresh(): active sessions, else the media-key session (API 33+). */
    private fun refreshController() {
        val ctx = appContext ?: AppContext.context.also { appContext = it }
        val msm = sessionManager ?: ctx.getSystemService(MediaSessionManager::class.java)?.also { sessionManager = it } ?: return
        try {
            val controllers = msm.getActiveSessions(MiBand9NotificationListener.componentName(ctx))
            if (controllers.isNotEmpty()) {
                onSessionsChanged(controllers)
                return
            }
            if (Build.VERSION.SDK_INT >= 33) {
                val token = msm.mediaKeyEventSession
                if (token != null) {
                    if (controller?.sessionToken != token) bindController(MediaController(ctx, token))
                    return
                }
            }
            bindController(null)
        } catch (e: SecurityException) {
            Log.w(TAG, "no media session access", e)
        } catch (t: Throwable) {
            Log.w(TAG, "media refresh failed", t)
        }
    }

    private fun schedulePush() {
        main.removeCallbacks(pushRunnable)
        main.postDelayed(pushRunnable, PUSH_DEBOUNCE_MS)
    }

    // ------------------------------------------------------------------ snapshot

    /** Main-thread. Real session data, else the last JS-provided snapshot, else null. */
    private fun currentSnapshot(): NowPlaying? {
        val c = controller
        val metadata = c?.metadata
        if (c != null && metadata != null) {
            val state = c.playbackState
            return NowPlaying(
                track = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = (metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                packageName = c.packageName.orEmpty(),
                positionMs = extrapolatedPosition(state),
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L),
                playing = state?.state == PlaybackState.STATE_PLAYING,
            )
        }
        return manual
    }

    private fun extrapolatedPosition(state: PlaybackState?): Long {
        if (state == null) return 0L
        val base = state.position.coerceAtLeast(0L)
        if (state.state != PlaybackState.STATE_PLAYING || state.lastPositionUpdateTime <= 0L) return base
        val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return (base + elapsed * state.playbackSpeed).toLong().coerceAtLeast(0L)
    }

    private fun volumePercent(): Int {
        val am = (appContext ?: AppContext.context).getSystemService(AudioManager::class.java) ?: return 0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return (100f * am.getStreamVolume(AudioManager.STREAM_MUSIC) / max).roundToInt()
    }

    // ------------------------------------------------------------------ phone → band

    /** XiaomiMusicService.sendMusicStateToDevice. Main thread. */
    private fun pushToBand(force: Boolean) {
        val drv = DriverHolder.current ?: return
        val snap = currentSnapshot()
        val volume = volumePercent()

        val key = if (snap == null) {
            "none|$volume"
        } else {
            listOf(snap.playing, snap.track, snap.artist, snap.durationMs / 1000, volume).joinToString("|")
        }
        if (!force && key == lastSentKey && snap != null) {
            // Same track/state/volume: only re-send on a seek (position jumped vs. extrapolation).
            val expected = lastSentPositionMs +
                if (lastSentPlaying) SystemClock.elapsedRealtime() - lastSentAtElapsed else 0L
            if (abs(snap.positionMs - expected) < SEEK_TOLERANCE_MS) return
        } else if (!force && key == lastSentKey) {
            return
        }

        val info = XiaomiProto.MusicInfo.newBuilder().setVolume(volume)
        if (snap == null) {
            info.setState(MusicCmd.STATE_NOTHING)
        } else {
            info.setState(if (snap.playing) MusicCmd.STATE_PLAYING else MusicCmd.STATE_PAUSED)
                .setTrack(snap.track)
                .setArtist(snap.artist)
                .setPosition((snap.positionMs / 1000L).toInt().coerceAtLeast(0))
                .setDuration((snap.durationMs / 1000L).toInt().coerceAtLeast(0))
        }
        val command = XiaomiProto.Command.newBuilder()
            .setType(MusicCmd.TYPE)
            .setSubtype(MusicCmd.SEND)
            .setMusic(XiaomiProto.Music.newBuilder().setMusicInfo(info))
            .build()

        lastSentKey = key
        lastSentPositionMs = snap?.positionMs ?: 0L
        lastSentAtElapsed = SystemClock.elapsedRealtime()
        lastSentPlaying = snap?.playing == true

        io.launch {
            try { drv.sendCommand(command) } catch (t: Throwable) { Log.w(TAG, "music send failed", t) }
        }
    }

    // ------------------------------------------------------------------ JS-facing

    /** Snapshot from JS; only used while no Android media session exists. */
    fun pushManual(snapshot: NowPlaying) {
        main.post {
            manual = snapshot
            if (controller?.metadata == null) pushToBand(force = true)
        }
    }

    /** Thread-safe read for JS (hops to main and waits briefly). */
    fun nowPlaying(): NowPlaying? {
        if (Looper.myLooper() == Looper.getMainLooper()) return currentSnapshot()
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: NowPlaying? = null
        main.post {
            try {
                if (controller == null) refreshController()
                result = currentSnapshot()
            } finally {
                latch.countDown()
            }
        }
        latch.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result
    }

    fun refresh() {
        main.post {
            refreshController()
            pushToBand(force = true)
        }
    }

    // ------------------------------------------------------------------ band → phone

    /** Called by BandEventRouter for type-18 commands. Must not block. */
    fun onBandCommand(subtype: Int, cmd: XiaomiProto.Command) {
        when (subtype) {
            MusicCmd.GET -> main.post {
                Log.d(TAG, "band requested music state")
                refreshController()
                pushToBand(force = true)
            }
            MusicCmd.BUTTON -> {
                val key = cmd.music.mediaKey.key
                val requestedVolume = cmd.music.mediaKey.volume
                main.post { onBandButton(key, requestedVolume) }
            }
            else -> Log.w(TAG, "unknown music command $subtype")
        }
    }

    private fun onBandButton(key: Int, requestedVolume: Int) {
        val command = when (key) {
            MusicCmd.BUTTON_PLAY -> BandMediaCommand.PLAY
            MusicCmd.BUTTON_PAUSE -> BandMediaCommand.PAUSE
            MusicCmd.BUTTON_PREVIOUS -> BandMediaCommand.PREVIOUS
            MusicCmd.BUTTON_NEXT -> BandMediaCommand.NEXT
            // upstream: compare the band's requested level with the phone's current one
            MusicCmd.BUTTON_VOLUME ->
                if (requestedVolume > volumePercent()) BandMediaCommand.VOLUME_UP else BandMediaCommand.VOLUME_DOWN
            else -> {
                Log.w(TAG, "unexpected media key $key")
                return
            }
        }
        dispatch(command)
        listeners.forEach { l ->
            try { l(command) } catch (t: Throwable) { Log.w(TAG, "music listener threw", t) }
        }
    }

    /** GBMusicControlReceiver: MediaController transport controls, media-key fallback. */
    private fun dispatch(command: BandMediaCommand) {
        val ctx = appContext ?: AppContext.context
        val am = ctx.getSystemService(AudioManager::class.java)
        when (command) {
            BandMediaCommand.VOLUME_UP, BandMediaCommand.VOLUME_DOWN -> {
                val dir = if (command == BandMediaCommand.VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
                // upstream sendPhoneVolume → onSetPhoneVolume → sendMusicStateToDevice
                pushToBand(force = true)
                return
            }
            else -> Unit
        }
        if (controller == null) refreshController()
        val c = controller
        if (c != null) {
            val tc = c.transportControls
            when (command) {
                BandMediaCommand.PLAY -> tc.play()
                BandMediaCommand.PAUSE -> tc.pause()
                BandMediaCommand.NEXT -> tc.skipToNext()
                BandMediaCommand.PREVIOUS -> tc.skipToPrevious()
                else -> Unit
            }
            return
        }
        // No session: let the system route a media key (resumes the last player).
        val keyCode = when (command) {
            BandMediaCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            BandMediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            BandMediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            BandMediaCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return
        }
        val now = SystemClock.uptimeMillis()
        am?.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am?.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }
}
