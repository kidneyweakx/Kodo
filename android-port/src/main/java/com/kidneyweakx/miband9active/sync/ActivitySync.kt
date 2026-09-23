/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Seam between HybridBandLink.syncSince / the periodic worker and the
 *  activity-file pipeline. The fetch/parse/ack implementation lands in the
 *  activity-sync commit; until then a sync reports zero files.
 */
package com.kidneyweakx.miband9active.sync

import com.kidneyweakx.miband9active.xiaomi.protocol.MiBand9BleDriver

object ActivitySync {
    @Suppress("UNUSED_PARAMETER")
    suspend fun run(driver: MiBand9BleDriver, onProgress: (phase: String, progress: Double) -> Unit): Int = 0
}
