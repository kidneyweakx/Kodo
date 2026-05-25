/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later.
 *
 *  Pure-data row produced by DailyDetailsParser. Shape mirrors Gadgetbridge's
 *  XiaomiActivitySample minus the GreenDAO/Device/User bookkeeping fields —
 *  our persistence layer is MMKV/Room, not GreenDAO.
 */
package com.kidneyweakx.miband9active.xiaomi.activity

const val NOT_MEASURED: Int = Int.MIN_VALUE

data class XiaomiActivitySample(
    val timestampSec: Long,
    val steps: Int = NOT_MEASURED,
    val heartRate: Int = NOT_MEASURED,
    val spo2: Int = NOT_MEASURED,
    val stress: Int = NOT_MEASURED,
    val activeCalories: Int = NOT_MEASURED,
    val distanceCm: Int = NOT_MEASURED,
    /** "Body energy" / "Vitality" reading. */
    val energy: Int = NOT_MEASURED,
)
