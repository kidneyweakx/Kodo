/*  Copyright (C) 2023-2024 José Rebelo, Yoran Vulker                       (Gadgetbridge)
 *
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 */
package com.kidneyweakx.miband9active.xiaomi.activity

/**
 * Wire ids for the activity-file sync handshake. Mirrors the private
 * constants at the top of `XiaomiHealthService.java` (Gadgetbridge master
 * 75f923904f). Kept separate from `services/SystemCommands.kt#HealthCommands`
 * so this module owns the numbers it depends on.
 *
 *   phone → band  (8, 1)  health.activitySyncRequestToday{unknown1=0}
 *   band  → phone (8, 1)  health.activityRequestFileIds = N × 7-byte file ids
 *   phone → band  (8, 2)  (no body)
 *   band  → phone (8, 2)  health.activityRequestFileIds = N × 7-byte file ids
 *   phone → band  (8, 3)  health.activityRequestFileIds = one 7-byte file id
 *   band  → phone         ACTIVITY channel chunks [total u16][num u16][payload]
 *   phone → band  (8, 5)  health.activitySyncAckFileIds = one 7-byte file id
 */
object ActivityCommands {
    const val COMMAND_TYPE = 8

    const val CMD_ACTIVITY_FETCH_TODAY = 1
    const val CMD_ACTIVITY_FETCH_PAST = 2
    const val CMD_ACTIVITY_FETCH_REQUEST = 3
    const val CMD_ACTIVITY_FETCH_ACK = 5
}
