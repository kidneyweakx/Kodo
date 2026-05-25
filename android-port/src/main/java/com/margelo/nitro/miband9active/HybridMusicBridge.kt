/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.services.MusicCommands
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

class HybridMusicBridge : HybridHybridMusicBridgeSpec() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val listeners = CopyOnWriteArrayList<(MusicCommand) -> Unit>()
    private var subJob: Job? = null

    override fun pushNowPlaying(snapshot: MusicNowPlaying) {
        val drv = DriverHolder.current ?: return
        val info = XiaomiProto.MusicInfo.newBuilder()
            .setState(if (snapshot.playing) 1 else 2)
            .setVolume(50)
            .setTrack(snapshot.title)
            .setArtist(snapshot.artist)
            .setPosition((snapshot.positionMs / 1000.0).toInt())
            .setDuration((snapshot.durationMs / 1000.0).toInt())
            .build()
        scope.launch {
            drv.sendCommand(
                XiaomiProto.Command.newBuilder()
                    .setType(MusicCommands.COMMAND_TYPE)
                    .setSubtype(MusicCommands.CMD_INFO_SET)
                    .setMusic(XiaomiProto.Music.newBuilder().setMusicInfo(info))
                    .build(),
            )
        }
    }

    override fun onCommand(listener: (command: MusicCommand) -> Unit): () -> Unit {
        listeners += listener
        ensureSubscribed()
        return { listeners -= listener }
    }

    private fun ensureSubscribed() {
        if (subJob != null) return
        val drv = DriverHolder.current ?: return
        subJob = scope.launch {
            drv.incoming.collect { msg ->
                if (msg.type != MusicCommands.COMMAND_TYPE) return@collect
                if (msg.subtype != MusicCommands.CMD_BUTTON_PRESSED) return@collect
                val key = msg.command.music.mediaKey.key
                val cmd = when (key) {
                    0 -> MusicCommand.PLAY
                    1 -> MusicCommand.PAUSE
                    3 -> MusicCommand.PREVIOUS
                    4 -> MusicCommand.NEXT
                    5 -> if (msg.command.music.mediaKey.volume >= 50) MusicCommand.VOLUMEUP else MusicCommand.VOLUMEDOWN
                    else -> null
                }
                if (cmd != null) listeners.forEach { it(cmd) }
            }
        }
    }
}
