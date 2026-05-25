/*  Copyright (C) 2023-2024 José Rebelo                  (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                        (Kotlin port)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  UUIDs we care about for Mi Band 9 Active. Other Xiaomi-family UUIDs are
 *  dropped — see FEATURES.md "Excluded".
 */
package com.kidneyweakx.miband9active.xiaomi.protocol

import java.util.UUID

object XiaomiUuids {

    /** Service ID advertised by Mi Band 9 Active (and by other Xiaomi V2 watches). */
    val SERVICE_V2: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    val V2_CHARACTERISTIC_RX: UUID = UUID.fromString("0000005e-0000-1000-8000-00805f9b34fb")
    val V2_CHARACTERISTIC_TX: UUID = UUID.fromString("0000005f-0000-1000-8000-00805f9b34fb")

    /** Service that V1 also lives behind (Mi Band 8 etc.). */
    val V1_SERVICE_ENCRYPTED: UUID = SERVICE_V2

    /** V1 characteristic set (encrypted, shared by Mi Band 8 / Redmi Watch 3 / S1 Active / Band 2). */
    val V1_CHARACTERISTIC_COMMAND_READ: UUID = UUID.fromString("00000051-0000-1000-8000-00805f9b34fb")
    val V1_CHARACTERISTIC_COMMAND_WRITE: UUID = UUID.fromString("00000052-0000-1000-8000-00805f9b34fb")
    val V1_CHARACTERISTIC_ACTIVITY_DATA: UUID = UUID.fromString("00000053-0000-1000-8000-00805f9b34fb")
    val V1_CHARACTERISTIC_DATA_UPLOAD: UUID = UUID.fromString("00000055-0000-1000-8000-00805f9b34fb")

    /** Standard CCC descriptor UUID. */
    val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
