/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.margelo.nitro.miband9active

import com.margelo.nitro.core.Promise

class HybridCameraRemote : HybridHybridCameraRemoteSpec() {
    private var _armed = false
    override val armed: Boolean get() = _armed
    override fun arm(): Promise<Unit> = Promise.async { _armed = true }
    override fun disarm(): Promise<Unit> = Promise.async { _armed = false }
    override fun onShutter(listener: () -> Unit): () -> Unit = noopUnsubscribe()
}
