/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo, LuK1337, Yoran Vulker  (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                                                (Kotlin port, slimmed)
 *
 *  The constants we actually need from `XiaomiSystemService`. The bulk of
 *  that class is GBApplication / preferences / device-event glue we don't
 *  want — the values below are the wire IDs needed to build outgoing
 *  Command frames against the proto schema.
 *
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.kidneyweakx.miband9active.xiaomi.services

object SystemCommands {
    const val COMMAND_TYPE = 2

    const val CMD_BATTERY = 1
    const val CMD_DEVICE_INFO = 2
    const val CMD_CLOCK = 3
    const val CMD_FIRMWARE_INSTALL = 5
    const val CMD_LANGUAGE = 6
    const val CMD_CAMERA_REMOTE_GET = 7
    const val CMD_CAMERA_REMOTE_SET = 8
    const val CMD_PASSWORD_GET = 9
    const val CMD_MISC_SETTING_GET = 14
    const val CMD_MISC_SETTING_SET = 15
    const val CMD_FIND_PHONE = 17
    const val CMD_FIND_WATCH = 18
    const val CMD_PASSWORD_SET = 21
    const val CMD_DND_MODE_SET = 23
    const val CMD_DISPLAY_ITEMS_GET = 29
    const val CMD_DISPLAY_ITEMS_SET = 30
    const val CMD_WORKOUT_TYPES_GET = 39
    const val CMD_SILENT_MODE_GET = 43
    const val CMD_SILENT_MODE_SET_FROM_PHONE = 44
    const val CMD_WIDGET_SCREENS_GET = 51
    const val CMD_WIDGET_SCREENS_SET = 52
    const val CMD_WIDGET_PARTS_GET = 53
    const val CMD_DEVICE_STATE_GET = 78
    const val CMD_DEVICE_STATE = 79
}

object NotificationCommands {
    const val COMMAND_TYPE = 7

    const val CMD_NOTIFICATION_SEND = 0
    const val CMD_NOTIFICATION_DISMISS = 1
    const val CMD_CALL = 4
    const val CMD_CANNED_MESSAGES_GET = 6
    const val CMD_CANNED_MESSAGES_SET = 7
    const val CMD_PHONEBOOK_SET = 8
    const val CMD_CONTACTS_SYNC_REQUEST = 12
}

object HealthCommands {
    const val COMMAND_TYPE = 8

    const val CMD_REALTIME_STARTSTOP = 0
    const val CMD_REALTIME_STATS = 1

    // Activity sync. Mirrors XiaomiHealthService.java:72-75 in Gadgetbridge.
    const val CMD_ACTIVITY_FETCH_TODAY = 1
    const val CMD_ACTIVITY_FETCH_PAST = 2
    const val CMD_ACTIVITY_FETCH_REQUEST = 3
    const val CMD_ACTIVITY_FETCH_ACK = 5
    const val CMD_FETCH_ACTIVITY_DETAILS = 6
    const val CMD_VITALITY_SCORE_GET = 21
    const val CMD_INACTIVITY_GET = 28
    const val CMD_INACTIVITY_SET = 29
    const val CMD_SPO2_GET = 36
    const val CMD_SPO2_SET = 37
    const val CMD_STRESS_GET = 40
    const val CMD_STRESS_SET = 41
    const val CMD_HEART_RATE_GET = 17
    const val CMD_HEART_RATE_SET = 18
    const val CMD_SLEEP_HISTORY_GET = 8
    const val CMD_USER_INFO_SET = 11
}

object CalendarCommands {
    const val COMMAND_TYPE = 12
    const val CMD_EVENTS_SET = 0
}

object WeatherCommands {
    const val COMMAND_TYPE = 10
    const val CMD_WEATHER_SET = 0
}

object MusicCommands {
    const val COMMAND_TYPE = 18
    const val CMD_INFO_SET = 0
    const val CMD_BUTTON_PRESSED = 1
}
