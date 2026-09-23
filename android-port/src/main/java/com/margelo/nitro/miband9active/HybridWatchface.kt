/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  JS facade over WatchfaceService (XiaomiWatchfaceService + XiaomiDataUploadService).
 *  `uri` accepts content://, file:// or an absolute path; the watchface id is
 *  read from the file header (XiaomiFWHelper) instead of trusting the caller.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.xiaomi.services.DeviceFeatures
import com.kidneyweakx.miband9active.xiaomi.services.WatchfaceService
import com.margelo.nitro.core.Promise
import java.util.concurrent.CopyOnWriteArrayList

class HybridWatchface : HybridHybridWatchfaceSpec() {

    init {
        DeviceFeatures.ensureStarted()
    }

    private val progressListeners = CopyOnWriteArrayList<(Double) -> Unit>()

    override fun getCachedList(): Array<WatchfaceInfo> = WatchfaceService.cachedList().toNitro()

    override fun list(): Promise<Array<WatchfaceInfo>> = Promise.async { WatchfaceService.list().toNitro() }

    override fun inspect(uri: String): Promise<WatchfaceFileInfo> = Promise.async {
        val info = WatchfaceService.inspect(uri)
        WatchfaceFileInfo(id = info.id, name = info.name, sizeBytes = info.bytes.size.toDouble())
    }

    override fun install(uri: String): Promise<WatchfaceFileInfo> = Promise.async {
        val info = WatchfaceService.install(uri) { p ->
            val pct = p.toDouble()
            progressListeners.forEach { runCatching { it(pct) } }
        }
        WatchfaceFileInfo(id = info.id, name = info.name, sizeBytes = info.bytes.size.toDouble())
    }

    override fun setActive(watchfaceId: String): Promise<Unit> = Promise.async { WatchfaceService.setActive(watchfaceId) }

    override fun remove(watchfaceId: String): Promise<Unit> = Promise.async { WatchfaceService.delete(watchfaceId) }

    override fun onInstallProgress(listener: (percent: Double) -> Unit): () -> Unit {
        progressListeners += listener
        return { progressListeners -= listener }
    }

    private fun List<WatchfaceService.Face>.toNitro(): Array<WatchfaceInfo> =
        map { WatchfaceInfo(id = it.id, name = it.name, canDelete = it.canDelete, active = it.active) }.toTypedArray()
}
