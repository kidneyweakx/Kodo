/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  JS surface over MediaSessionTracker. Band buttons are dispatched to the
 *  active Android media session natively; onCommand only reports them.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.media.MediaSessionTracker
import com.kidneyweakx.miband9active.xiaomi.notifications.BandEventRouter

class HybridMusicBridge : HybridHybridMusicBridgeSpec() {

    init {
        BandEventRouter.ensureStarted()
    }

    override fun pushNowPlaying(snapshot: MusicNowPlaying) {
        MediaSessionTracker.pushManual(
            MediaSessionTracker.NowPlaying(
                track = snapshot.title,
                artist = snapshot.artist,
                album = snapshot.album,
                packageName = snapshot.app,
                positionMs = snapshot.positionMs.toLong(),
                durationMs = snapshot.durationMs.toLong(),
                playing = snapshot.playing,
            ),
        )
    }

    override fun onCommand(listener: (command: MusicCommand) -> Unit): () -> Unit =
        MediaSessionTracker.addListener { cmd ->
            listener(
                when (cmd) {
                    MediaSessionTracker.BandMediaCommand.PLAY -> MusicCommand.PLAY
                    MediaSessionTracker.BandMediaCommand.PAUSE -> MusicCommand.PAUSE
                    MediaSessionTracker.BandMediaCommand.NEXT -> MusicCommand.NEXT
                    MediaSessionTracker.BandMediaCommand.PREVIOUS -> MusicCommand.PREVIOUS
                    MediaSessionTracker.BandMediaCommand.VOLUME_UP -> MusicCommand.VOLUMEUP
                    MediaSessionTracker.BandMediaCommand.VOLUME_DOWN -> MusicCommand.VOLUMEDOWN
                },
            )
        }

    override fun getNowPlaying(): MusicNowPlaying? {
        val np = MediaSessionTracker.nowPlaying() ?: return null
        return MusicNowPlaying(
            title = np.track,
            artist = np.artist,
            album = np.album,
            app = np.packageName,
            positionMs = np.positionMs.toDouble(),
            durationMs = np.durationMs.toDouble(),
            playing = np.playing,
        )
    }

    override fun refresh() {
        MediaSessionTracker.refresh()
    }
}
