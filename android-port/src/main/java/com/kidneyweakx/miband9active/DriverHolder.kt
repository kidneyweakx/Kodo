/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Process-wide reference to the currently connected MiBand9BleDriver,
 *  shared by every Hybrid implementation. HybridBandLink owns the lifecycle;
 *  others read it.
 */
package com.kidneyweakx.miband9active

import com.kidneyweakx.miband9active.xiaomi.protocol.MiBand9BleDriver

object DriverHolder {
    @Volatile var current: MiBand9BleDriver? = null
}
