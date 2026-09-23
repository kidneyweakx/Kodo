/*
 * Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo, Yoran Vulker (Gadgetbridge)
 *
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 */
package com.kidneyweakx.miband9active.xiaomi.notifications

/**
 * Wire command ids for notifications (type 7), music (type 18) and phonebook
 * (type 21), copied verbatim from upstream:
 *   - XiaomiNotificationService.java  (COMMAND_TYPE = 7, CMD_*)
 *   - XiaomiMusicService.java         (COMMAND_TYPE = 18, CMD_MUSIC_*)
 *   - XiaomiPhonebookService.java     (COMMAND_TYPE = 21, CMD_*)
 *
 * NOTE: `NotificationCommands` / `MusicCommands` in services/SystemCommands.kt
 * carry WRONG values (e.g. music send = 0, button = 1; upstream is 1 / 2) and
 * must not be used for these services.
 */
object NotificationCmd {
    const val TYPE = 7

    const val NOTIFICATION_SEND = 0
    const val NOTIFICATION_DISMISS = 1
    const val CALL_REJECT = 2
    const val CALL_IGNORE = 5
    const val SCREEN_ON_ON_NOTIFICATIONS_GET = 6
    const val SCREEN_ON_ON_NOTIFICATIONS_SET = 7
    const val OPEN_ON_PHONE = 8
    const val CANNED_MESSAGES_GET = 9
    const val CANNED_MESSAGES_SET = 12
    const val CALL_REPLY_SEND = 13
    const val CALL_REPLY_ACK = 14
    const val NOTIFICATION_ICON_REQUEST = 15
    const val NOTIFICATION_ICON_QUERY = 16

    /** Package + id the band uses for the synthetic incoming-call notification. */
    const val CALL_PACKAGE = "phone"
    const val CALL_ID = 0
}

object MusicCmd {
    const val TYPE = 18

    /** band → phone: "send me the current state" (music screen opened). */
    const val GET = 0
    /** phone → band: MusicInfo. */
    const val SEND = 1
    /** band → phone: MediaKey. */
    const val BUTTON = 2

    const val BUTTON_PLAY = 0
    const val BUTTON_PAUSE = 1
    const val BUTTON_PREVIOUS = 3
    const val BUTTON_NEXT = 4
    const val BUTTON_VOLUME = 5

    const val STATE_NOTHING = 0
    const val STATE_PLAYING = 1
    const val STATE_PAUSED = 2
}

object PhonebookCmd {
    const val TYPE = 21

    const val GET_CONTACT = 2
    const val GET_CONTACT_RESPONSE = 3
}
