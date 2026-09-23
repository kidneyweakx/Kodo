/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  XiaomiSystemService camera-remote config (2/7 get, 2/8 set). The old
 *  version treated EVERY type-2 message carrying `camera` as a shutter press
 *  — including the band's own ack of our SET — and captured a stale driver.
 */
package com.margelo.nitro.miband9active

import com.kidneyweakx.miband9active.xiaomi.services.DeviceFeatures
import com.kidneyweakx.miband9active.xiaomi.services.SystemService
import com.margelo.nitro.core.Promise

class HybridCameraRemote : HybridHybridCameraRemoteSpec() {

    init {
        DeviceFeatures.ensureStarted()
    }

    override fun getEnabled(): Boolean? = SystemService.getCameraEnabled()

    override fun refresh(): Promise<Boolean?> = Promise.async { SystemService.refreshCamera() }

    override fun setEnabled(enabled: Boolean): Promise<Boolean> = Promise.async { SystemService.setCameraEnabled(enabled) }

    override fun onShutter(listener: () -> Unit): () -> Unit = SystemService.addShutterListener(listener)
}
