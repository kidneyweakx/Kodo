/*  Copyright (C) 2023-2025 Andreas Shimokawa, José Rebelo, LuK1337, Yoran Vulker  (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                                                (Kotlin port, slimmed)
 *
 *  Wire ids (command type / subtype) of the Xiaomi protobuf services, copied
 *  from the `COMMAND_TYPE` / `CMD_*` constants of:
 *    XiaomiSystemService, XiaomiVibrationManager, XiaomiHealthService,
 *    XiaomiNotificationService, XiaomiCalendarService, XiaomiWeatherService,
 *    XiaomiScheduleService, XiaomiWatchfaceService, XiaomiDataUploadService,
 *    XiaomiMusicService.
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
    const val CMD_MISC_SETTING_SET_FROM_BAND = 42
    const val CMD_SILENT_MODE_GET = 43
    const val CMD_SILENT_MODE_SET_FROM_PHONE = 44
    const val CMD_SILENT_MODE_SET_FROM_WATCH = 45
    /** XiaomiVibrationManager.CMD_GET */
    const val CMD_VIBRATION_PATTERNS_GET = 46
    const val CMD_WIDGET_SCREENS_GET = 51
    const val CMD_WIDGET_SCREENS_SET = 52
    const val CMD_WIDGET_PARTS_GET = 53
    /** XiaomiVibrationManager.CMD_ADD */
    const val CMD_VIBRATION_PATTERN_ADD = 58
    /** XiaomiVibrationManager.CMD_REMOVE */
    const val CMD_VIBRATION_PATTERN_REMOVE = 61
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

/** XiaomiHealthService (type 8). */
object HealthCommands {
    const val COMMAND_TYPE = 8

    const val CMD_SET_USER_INFO = 0
    const val CMD_ACTIVITY_FETCH_TODAY = 1
    const val CMD_ACTIVITY_FETCH_PAST = 2
    const val CMD_ACTIVITY_FETCH_REQUEST = 3
    const val CMD_ACTIVITY_FETCH_ACK = 5
    const val CMD_CONFIG_SPO2_GET = 8
    const val CMD_CONFIG_SPO2_SET = 9
    const val CMD_CONFIG_HEART_RATE_GET = 10
    const val CMD_CONFIG_HEART_RATE_SET = 11
    const val CMD_CONFIG_STANDING_REMINDER_GET = 12
    const val CMD_CONFIG_STANDING_REMINDER_SET = 13
    const val CMD_CONFIG_STRESS_GET = 14
    const val CMD_CONFIG_STRESS_SET = 15
    const val CMD_CONFIG_GOAL_NOTIFICATION_GET = 21
    const val CMD_CONFIG_GOAL_NOTIFICATION_SET = 22
    const val CMD_WORKOUT_WATCH_STATUS = 26
    const val CMD_WORKOUT_WATCH_OPEN = 30
    const val CMD_CONFIG_VITALITY_SCORE_GET = 35
    const val CMD_CONFIG_VITALITY_SCORE_SET = 36
    const val CMD_CONFIG_GOALS_GET = 42
    const val CMD_CONFIG_GOALS_SET = 43
    const val CMD_REALTIME_STATS_START = 45
    const val CMD_REALTIME_STATS_STOP = 46
    const val CMD_REALTIME_STATS_EVENT = 47
    const val CMD_WORKOUT_LOCATION = 48

    // ---- legacy names kept so older call sites still compile. The values
    // used to be wrong (e.g. INACTIVITY_SET = 29); they now alias the upstream ids.
    @Deprecated("Use CMD_REALTIME_STATS_START / _STOP", ReplaceWith("CMD_REALTIME_STATS_START"))
    const val CMD_REALTIME_STARTSTOP = CMD_REALTIME_STATS_START
    @Deprecated("Use CMD_REALTIME_STATS_EVENT", ReplaceWith("CMD_REALTIME_STATS_EVENT"))
    const val CMD_REALTIME_STATS = CMD_REALTIME_STATS_EVENT
    @Deprecated("Use CMD_CONFIG_VITALITY_SCORE_GET", ReplaceWith("CMD_CONFIG_VITALITY_SCORE_GET"))
    const val CMD_VITALITY_SCORE_GET = CMD_CONFIG_VITALITY_SCORE_GET
    @Deprecated("Use CMD_CONFIG_STANDING_REMINDER_GET", ReplaceWith("CMD_CONFIG_STANDING_REMINDER_GET"))
    const val CMD_INACTIVITY_GET = CMD_CONFIG_STANDING_REMINDER_GET
    @Deprecated("Use CMD_CONFIG_STANDING_REMINDER_SET", ReplaceWith("CMD_CONFIG_STANDING_REMINDER_SET"))
    const val CMD_INACTIVITY_SET = CMD_CONFIG_STANDING_REMINDER_SET
    @Deprecated("Use CMD_CONFIG_SPO2_GET", ReplaceWith("CMD_CONFIG_SPO2_GET"))
    const val CMD_SPO2_GET = CMD_CONFIG_SPO2_GET
    @Deprecated("Use CMD_CONFIG_SPO2_SET", ReplaceWith("CMD_CONFIG_SPO2_SET"))
    const val CMD_SPO2_SET = CMD_CONFIG_SPO2_SET
    @Deprecated("Use CMD_CONFIG_STRESS_GET", ReplaceWith("CMD_CONFIG_STRESS_GET"))
    const val CMD_STRESS_GET = CMD_CONFIG_STRESS_GET
    @Deprecated("Use CMD_CONFIG_STRESS_SET", ReplaceWith("CMD_CONFIG_STRESS_SET"))
    const val CMD_STRESS_SET = CMD_CONFIG_STRESS_SET
    @Deprecated("Use CMD_CONFIG_HEART_RATE_GET", ReplaceWith("CMD_CONFIG_HEART_RATE_GET"))
    const val CMD_HEART_RATE_GET = CMD_CONFIG_HEART_RATE_GET
    @Deprecated("Use CMD_CONFIG_HEART_RATE_SET", ReplaceWith("CMD_CONFIG_HEART_RATE_SET"))
    const val CMD_HEART_RATE_SET = CMD_CONFIG_HEART_RATE_SET
    @Deprecated("Use CMD_SET_USER_INFO", ReplaceWith("CMD_SET_USER_INFO"))
    const val CMD_USER_INFO_SET = CMD_SET_USER_INFO
}

/** XiaomiCalendarService (type 12). */
object CalendarCommands {
    const val COMMAND_TYPE = 12
    const val CMD_CALENDAR_SET = 1

    /** Was 0 (wrong). Upstream XiaomiCalendarService.CMD_CALENDAR_SET = 1. */
    @Deprecated("Use CMD_CALENDAR_SET", ReplaceWith("CMD_CALENDAR_SET"))
    const val CMD_EVENTS_SET = CMD_CALENDAR_SET
}

/** XiaomiWeatherService (type 10). */
object WeatherCommands {
    const val COMMAND_TYPE = 10
    const val CMD_SET_CURRENT_WEATHER = 0
    const val CMD_UPDATE_DAILY_FORECAST = 1
    const val CMD_UPDATE_HOURLY_FORECAST = 2
    const val CMD_REQUEST_CONDITIONS_FOR_LOCATION = 3
    const val CMD_GET_LOCATIONS = 5
    const val CMD_SET_LOCATIONS = 6
    const val CMD_ADD_LOCATION = 7
    const val CMD_REMOVE_LOCATIONS = 8
    const val CMD_GET_WEATHER_PREFS = 9
    const val CMD_SET_WEATHER_PREFS = 10

    @Deprecated("Use CMD_SET_CURRENT_WEATHER", ReplaceWith("CMD_SET_CURRENT_WEATHER"))
    const val CMD_WEATHER_SET = CMD_SET_CURRENT_WEATHER
}

/** XiaomiScheduleService (type 17). */
object ScheduleCommands {
    const val COMMAND_TYPE = 17
    const val CMD_ALARMS_GET = 0
    const val CMD_ALARMS_CREATE = 1
    const val CMD_ALARMS_EDIT = 2
    const val CMD_ALARMS_DELETE = 4
    const val CMD_SLEEP_MODE_GET = 8
    const val CMD_SLEEP_MODE_SET = 9
    const val CMD_WORLD_CLOCKS_GET = 10
    const val CMD_WORLD_CLOCKS_SET = 11
    const val CMD_REMINDERS_GET = 14
    const val CMD_REMINDERS_CREATE = 15
    const val CMD_REMINDERS_EDIT = 17
    const val CMD_REMINDERS_DELETE = 18
}

/** XiaomiWatchfaceService (type 4). */
object WatchfaceCommands {
    const val COMMAND_TYPE = 4
    const val CMD_WATCHFACE_LIST = 0
    const val CMD_WATCHFACE_SET = 1
    const val CMD_WATCHFACE_DELETE = 2
    const val CMD_WATCHFACE_INSTALL = 4
}

object MusicCommands {
    const val COMMAND_TYPE = 18
    const val CMD_INFO_SET = 0
    const val CMD_BUTTON_PRESSED = 1
}
