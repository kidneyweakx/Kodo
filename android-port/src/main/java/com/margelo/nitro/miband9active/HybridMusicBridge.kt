/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

class HybridMusicBridge : HybridHybridMusicBridgeSpec() {
    override fun pushNowPlaying(snapshot: MusicNowPlaying) {}
    override fun onCommand(listener: (command: MusicCommand) -> Unit): () -> Unit = noopUnsubscribe()
}
