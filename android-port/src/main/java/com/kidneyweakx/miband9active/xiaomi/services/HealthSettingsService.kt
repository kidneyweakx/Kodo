/*  Copyright (C) 2023-2024 José Rebelo, Yoran Vulker  (Gadgetbridge XiaomiHealthService)
 *  Copyright (C) 2026 kidneyweakx                     (Kotlin port — settings half only)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Command type 8, configuration commands only (activity sync lives in
 *  the activity package and HybridBandLink; workout GPS in gps/WorkoutGpsController):
 *
 *    0  SET_USER_INFO                    8/9   SpO2 GET/SET
 *    10/11 heart-rate GET/SET            12/13 standing (inactivity) reminder GET/SET
 *    14/15 stress GET/SET                21/22 goal notification GET/SET
 *    35/36 vitality score GET/SET        42/43 goals (secondary goal) GET/SET
 *
 *  Values the band reports on connect are persisted (FEAT_* flags + values)
 *  unless the user has a pending local change, which is pushed instead.
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.util.Log
import java.time.LocalDate
import java.time.Period
import java.util.Locale
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.json.JSONArray
import org.json.JSONObject

object HealthSettingsService {
    private const val TAG = "MB9A_HealthCfg"

    private const val KEY_MONITORING = "health_monitoring"
    private const val KEY_STANDING = "health_standing_reminder"
    private const val KEY_PROFILE = "user_profile"
    private const val KEY_CURRENT_GOALS = "goals_current"
    private const val KEY_SUPPORTED_GOALS = "goals_supported"

    // dirty groups
    private const val G_HR = "hr"
    private const val G_SPO2 = "spo2"
    private const val G_STRESS = "stress"
    private const val G_GOAL_NOTIF = "goal_notification"
    private const val G_GOALS = "goals"
    private const val G_VITALITY = "vitality"
    private const val G_STANDING = "standing"
    private const val G_PROFILE = "user_profile"
    private val MONITORING_GROUPS = listOf(G_HR, G_SPO2, G_STRESS, G_GOAL_NOTIF, G_GOALS, G_VITALITY)

    private const val GENDER_MALE = 1
    private const val GENDER_FEMALE = 2

    private const val GOAL_MOVING_TIME = 3
    private const val GOAL_STANDING_TIME = 4

    /** hrIntervalSec follows the upstream pref: 0 = off, -1 = smart, else seconds (60/600/1800). */
    data class Monitoring(
        val hrIntervalSec: Int,
        val hrSleepDetection: Boolean,
        val sleepBreathingQuality: Boolean,
        val hrHighAlert: Int,
        val hrLowAlert: Int,
        val spo2AllDay: Boolean,
        val spo2LowAlert: Int,
        val stressAllDay: Boolean,
        val stressRelaxReminder: Boolean,
        val goalNotification: Boolean,
        /** "standing_time" | "active_time" */
        val secondaryGoal: String,
        val vitalitySevenDay: Boolean,
        val vitalityDaily: Boolean,
    )

    data class Standing(
        val enabled: Boolean,
        val start: HourMin,
        val end: HourMin,
        val dnd: Boolean,
        val dndStart: HourMin,
        val dndEnd: HourMin,
    )

    data class Profile(
        val heightCm: Int,
        val weightKg: Float,
        val birthYear: Int,
        val birthMonth: Int,
        val birthDay: Int,
        /** "male" | "female" | "other" */
        val gender: String,
        val stepGoal: Int,
        val calorieGoal: Int,
        val standingHoursGoal: Int,
        val activeMinutesGoal: Int,
    )

    // ================================================================ monitoring

    /** Only non-null once the band's heart-rate config arrived or the user set values. */
    fun getMonitoring(): Monitoring? {
        val o = FeatureStore.getJson(KEY_MONITORING) ?: return null
        if (!o.optBoolean("known", false)) return null
        return Monitoring(
            hrIntervalSec = o.optInt("hrIntervalSec", 0),
            hrSleepDetection = o.optBoolean("hrSleepDetection"),
            sleepBreathingQuality = o.optBoolean("sleepBreathingQuality"),
            hrHighAlert = o.optInt("hrHighAlert"),
            hrLowAlert = o.optInt("hrLowAlert"),
            spo2AllDay = o.optBoolean("spo2AllDay"),
            spo2LowAlert = o.optInt("spo2LowAlert"),
            stressAllDay = o.optBoolean("stressAllDay"),
            stressRelaxReminder = o.optBoolean("stressRelaxReminder"),
            goalNotification = o.optBoolean("goalNotification"),
            secondaryGoal = o.optString("secondaryGoal", "standing_time"),
            vitalitySevenDay = o.optBoolean("vitalitySevenDay"),
            vitalityDaily = o.optBoolean("vitalityDaily"),
        )
    }

    val monitoringPendingPush: Boolean
        get() = MONITORING_GROUPS.any { FeatureStore.isDirty(it) }

    private fun Monitoring.toJson(): JSONObject = JSONObject()
        .put("known", true)
        .put("hrIntervalSec", hrIntervalSec)
        .put("hrSleepDetection", hrSleepDetection)
        .put("sleepBreathingQuality", sleepBreathingQuality)
        .put("hrHighAlert", hrHighAlert)
        .put("hrLowAlert", hrLowAlert)
        .put("spo2AllDay", spo2AllDay)
        .put("spo2LowAlert", spo2LowAlert)
        .put("stressAllDay", stressAllDay)
        .put("stressRelaxReminder", stressRelaxReminder)
        .put("goalNotification", goalNotification)
        .put("secondaryGoal", secondaryGoal)
        .put("vitalitySevenDay", vitalitySevenDay)
        .put("vitalityDaily", vitalityDaily)

    /** Merge band-reported fields of one group, unless that group has a pending local change. */
    private fun mergeReported(group: String, markKnown: Boolean, apply: (JSONObject) -> Unit) {
        if (FeatureStore.isDirty(group)) return
        val o = FeatureStore.getJson(KEY_MONITORING) ?: JSONObject()
        apply(o)
        if (markKnown) o.put("known", true)
        FeatureStore.putJson(KEY_MONITORING, o)
    }

    suspend fun setMonitoring(new: Monitoring): Monitoring {
        val old = getMonitoring()
        FeatureStore.putJson(KEY_MONITORING, new.toJson())
        val changed = mutableListOf<String>()
        if (old == null || old.hrIntervalSec != new.hrIntervalSec || old.hrSleepDetection != new.hrSleepDetection ||
            old.sleepBreathingQuality != new.sleepBreathingQuality || old.hrHighAlert != new.hrHighAlert ||
            old.hrLowAlert != new.hrLowAlert
        ) changed += G_HR
        if (old == null || old.spo2AllDay != new.spo2AllDay || old.spo2LowAlert != new.spo2LowAlert) changed += G_SPO2
        if (old == null || old.stressAllDay != new.stressAllDay || old.stressRelaxReminder != new.stressRelaxReminder) changed += G_STRESS
        if (old == null || old.goalNotification != new.goalNotification) changed += G_GOAL_NOTIF
        if (old == null || old.secondaryGoal != new.secondaryGoal) changed += G_GOALS
        if (old == null || old.vitalitySevenDay != new.vitalitySevenDay || old.vitalityDaily != new.vitalityDaily) changed += G_VITALITY
        changed.forEach { FeatureStore.setDirty(it, true) }
        changed.forEach { group -> if (pushGroup(group, new)) FeatureStore.setDirty(group, false) }
        return getMonitoring() ?: new
    }

    private suspend fun pushGroup(group: String, m: Monitoring): Boolean = when (group) {
        G_HR -> sendHeartRate(m)
        G_SPO2 -> sendSpo2(m)
        G_STRESS -> sendStress(m)
        G_GOAL_NOTIF -> sendGoalNotification(m)
        G_GOALS -> sendGoals(m)
        G_VITALITY -> sendVitality(m)
        else -> false
    }

    /** GET every config from the band and wait for the answers (settings screen pull). */
    suspend fun refreshMonitoring(): Monitoring? {
        if (!BandChannel.isConnected) return getMonitoring()
        val t = HealthCommands.COMMAND_TYPE
        for (sub in listOf(
            HealthCommands.CMD_CONFIG_HEART_RATE_GET,
            HealthCommands.CMD_CONFIG_SPO2_GET,
            HealthCommands.CMD_CONFIG_STRESS_GET,
            HealthCommands.CMD_CONFIG_GOAL_NOTIFICATION_GET,
            HealthCommands.CMD_CONFIG_GOALS_GET,
            HealthCommands.CMD_CONFIG_VITALITY_SCORE_GET,
        )) {
            // replies are processed by handleCommand() via the dispatcher; we only wait for them
            BandChannel.request(t, sub, timeoutMs = 3_000)
        }
        return getMonitoring()
    }

    // --- heart rate (10/11)

    private fun onHeartRate(hr: XiaomiProto.HeartRate) {
        FeatureStore.setFeature(FeatureStore.FEAT_HEART_RATE, true)
        mergeReported(G_HR, markKnown = true) { o ->
            val sec = when {
                hr.disabled -> 0
                hr.interval == 0 -> -1 // smart
                else -> hr.interval * 60 // 33493b9214: the band reports minutes
            }
            o.put("hrIntervalSec", sec)
            o.put("hrSleepDetection", hr.advancedMonitoring.enabled)
            o.put("sleepBreathingQuality", hr.breathingScore == 1)
            o.put("hrHighAlert", if (hr.alarmHighEnabled) hr.alarmHighThreshold else 0)
            o.put(
                "hrLowAlert",
                if (hr.heartRateAlarmLow.alarmLowEnabled) hr.heartRateAlarmLow.alarmLowThreshold else 0,
            )
        }
    }

    /** XiaomiHealthService.setHeartRateConfig() */
    private suspend fun sendHeartRate(m: Monitoring): Boolean {
        val intervalMin = if (m.hrIntervalSec == -1) 0 else m.hrIntervalSec / 60
        val heartRate = XiaomiProto.HeartRate.newBuilder()
            .setDisabled(m.hrIntervalSec == 0)
            .setInterval(intervalMin)
            .setAdvancedMonitoring(XiaomiProto.AdvancedMonitoring.newBuilder().setEnabled(m.hrSleepDetection))
            .setBreathingScore(if (m.sleepBreathingQuality) 1 else 2)
            .setAlarmHighEnabled(m.hrHighAlert > 0)
            .setAlarmHighThreshold(m.hrHighAlert)
            .setHeartRateAlarmLow(
                XiaomiProto.HeartRateAlarmLow.newBuilder()
                    .setAlarmLowEnabled(m.hrLowAlert > 0)
                    .setAlarmLowThreshold(m.hrLowAlert),
            )
            .setUnknown7(1)
        return BandChannel.send(HealthCommands.COMMAND_TYPE, HealthCommands.CMD_CONFIG_HEART_RATE_SET) {
            setHealth(XiaomiProto.Health.newBuilder().setHeartRate(heartRate))
        }
    }

    // --- SpO2 (8/9) — 31a0e1f802: numeric mode, all-day = 2

    private fun onSpo2(spo2: XiaomiProto.SpO2) {
        FeatureStore.setFeature(FeatureStore.FEAT_SPO2, true)
        mergeReported(G_SPO2, markKnown = false) { o ->
            o.put("spo2AllDay", spo2.mode == XiaomiProto.Spo2Mode.SPO2_MODE_ALL_DAY)
            o.put("spo2LowAlert", if (spo2.alarmLow.alarmLowEnabled) spo2.alarmLow.alarmLowThreshold else 0)
        }
    }

    private suspend fun sendSpo2(m: Monitoring): Boolean {
        val alarmLow = XiaomiProto.Spo2AlarmLow.newBuilder().setAlarmLowEnabled(m.spo2LowAlert != 0)
        if (m.spo2LowAlert != 0) alarmLow.setAlarmLowThreshold(m.spo2LowAlert)
        val spo2 = XiaomiProto.SpO2.newBuilder()
            .setUnknown1(1)
            .setMode(if (m.spo2AllDay) XiaomiProto.Spo2Mode.SPO2_MODE_ALL_DAY else XiaomiProto.Spo2Mode.SPO2_MODE_OFF)
            .setAlarmLow(alarmLow)
        return BandChannel.send(HealthCommands.COMMAND_TYPE, HealthCommands.CMD_CONFIG_SPO2_SET) {
            setHealth(XiaomiProto.Health.newBuilder().setSpo2(spo2))
        }
    }

    // --- stress (14/15)

    private fun onStress(stress: XiaomiProto.Stress) {
        FeatureStore.setFeature(FeatureStore.FEAT_STRESS, true)
        mergeReported(G_STRESS, markKnown = false) { o ->
            o.put("stressAllDay", stress.allDayTracking)
            o.put("stressRelaxReminder", stress.relaxReminder.enabled)
        }
    }

    private suspend fun sendStress(m: Monitoring): Boolean {
        val stress = XiaomiProto.Stress.newBuilder()
            .setAllDayTracking(m.stressAllDay)
            .setRelaxReminder(XiaomiProto.RelaxReminder.newBuilder().setEnabled(m.stressRelaxReminder).setUnknown2(0))
        return BandChannel.send(HealthCommands.COMMAND_TYPE, HealthCommands.CMD_CONFIG_STRESS_SET) {
            setHealth(XiaomiProto.Health.newBuilder().setStress(stress))
        }
    }

    // --- goal notification (21/22)

    private fun onGoalNotification(g: XiaomiProto.GoalNotification) {
        FeatureStore.setFeature(FeatureStore.FEAT_GOAL_NOTIFICATION, true)
        mergeReported(G_GOAL_NOTIF, markKnown = false) { o -> o.put("goalNotification", g.enabled) }
    }

    private suspend fun sendGoalNotification(m: Monitoring): Boolean {
        val g = XiaomiProto.GoalNotification.newBuilder().setEnabled(m.goalNotification).setUnknown2(1)
        return BandChannel.send(HealthCommands.COMMAND_TYPE, HealthCommands.CMD_CONFIG_GOAL_NOTIFICATION_SET) {
            setHealth(XiaomiProto.Health.newBuilder().setGoalNotification(g))
        }
    }

    // --- goals (42/43)

    private fun onGoals(goals: XiaomiProto.GoalsConfig) {
        val current = goals.currentGoalsList.map { it.id }
        val supported = goals.supportedGoalsList.map { it.id }
        FeatureStore.putJsonArray(KEY_CURRENT_GOALS, JSONArray(current))
        FeatureStore.putJsonArray(KEY_SUPPORTED_GOALS, JSONArray(supported))
        FeatureStore.setFeature(
            FeatureStore.FEAT_GOAL_SECONDARY,
            GOAL_STANDING_TIME in supported || GOAL_MOVING_TIME in supported,
        )
        mergeReported(G_GOALS, markKnown = false) { o ->
            o.put("secondaryGoal", if (GOAL_MOVING_TIME in current) "active_time" else "standing_time")
        }
    }

    /** XiaomiHealthService.sendGoalsConfig() */
    private suspend fun sendGoals(m: Monitoring): Boolean {
        val current = FeatureStore.getJsonArray(KEY_CURRENT_GOALS).toIntList()
        val supported = FeatureStore.getJsonArray(KEY_SUPPORTED_GOALS).toIntList()
        val cfg = XiaomiProto.GoalsConfig.newBuilder()
        current.filter { it != GOAL_STANDING_TIME && it != GOAL_MOVING_TIME }
            .forEach { cfg.addCurrentGoals(XiaomiProto.Goal.newBuilder().setId(it)) }
        cfg.addCurrentGoals(
            XiaomiProto.Goal.newBuilder().setId(if (m.secondaryGoal == "active_time") GOAL_MOVING_TIME else GOAL_STANDING_TIME),
        )
        supported.forEach { cfg.addSupportedGoals(XiaomiProto.Goal.newBuilder().setId(it)) }
        return BandChannel.send(HealthCommands.COMMAND_TYPE, HealthCommands.CMD_CONFIG_GOALS_SET) {
            setHealth(XiaomiProto.Health.newBuilder().setGoalsConfig(cfg))
        }
    }

    // --- vitality score (35/36)

    private fun onVitality(v: XiaomiProto.VitalityScore) {
        FeatureStore.setFeature(FeatureStore.FEAT_VITALITY_SCORE, true)
        mergeReported(G_VITALITY, markKnown = false) { o ->
            o.put("vitalitySevenDay", v.sevenDay)
            o.put("vitalityDaily", v.dailyProgress)
        }
    }

    private suspend fun sendVitality(m: Monitoring): Boolean {
        val v = XiaomiProto.VitalityScore.newBuilder().setSevenDay(m.vitalitySevenDay).setDailyProgress(m.vitalityDaily)
        return BandChannel.send(HealthCommands.COMMAND_TYPE, HealthCommands.CMD_CONFIG_VITALITY_SCORE_SET) {
            setHealth(XiaomiProto.Health.newBuilder().setVitalityScore(v))
        }
    }

    // ================================================================ standing reminder (12/13)

    fun getStanding(): Standing? {
        val o = FeatureStore.getJson(KEY_STANDING) ?: return null
        return Standing(
            enabled = o.optBoolean("enabled"),
            // upstream defaults: 06:00-22:00, DND 12:00-14:00
            start = HourMin.fromJson(o.optJSONObject("start"), 6, 0),
            end = HourMin.fromJson(o.optJSONObject("end"), 22, 0),
            dnd = o.optBoolean("dnd"),
            dndStart = HourMin.fromJson(o.optJSONObject("dndStart"), 12, 0),
            dndEnd = HourMin.fromJson(o.optJSONObject("dndEnd"), 14, 0),
        )
    }

    val standingPendingPush: Boolean get() = FeatureStore.isDirty(G_STANDING)

    private fun Standing.toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("start", start.toJson())
        .put("end", end.toJson())
        .put("dnd", dnd)
        .put("dndStart", dndStart.toJson())
        .put("dndEnd", dndEnd.toJson())

    suspend fun setStanding(s: Standing): Standing {
        FeatureStore.putJson(KEY_STANDING, s.toJson())
        FeatureStore.setDirty(G_STANDING, true)
        if (sendStanding(s)) FeatureStore.setDirty(G_STANDING, false)
        return s
    }

    suspend fun refreshStanding(): Standing? {
        BandChannel.request(HealthCommands.COMMAND_TYPE, HealthCommands.CMD_CONFIG_STANDING_REMINDER_GET)
        return getStanding()
    }

    private fun onStanding(r: XiaomiProto.StandingReminder) {
        FeatureStore.setFeature(FeatureStore.FEAT_INACTIVITY, true)
        if (FeatureStore.isDirty(G_STANDING)) return
        val s = Standing(
            enabled = r.enabled,
            start = HourMin(r.start.hour, r.start.minute),
            end = HourMin(r.end.hour, r.end.minute),
            dnd = r.dnd,
            dndStart = HourMin(r.dndStart.hour, r.dndStart.minute),
            dndEnd = HourMin(r.dndEnd.hour, r.dndEnd.minute),
        )
        FeatureStore.putJson(KEY_STANDING, s.toJson())
    }

    /** XiaomiHealthService.setStandingReminderConfig() */
    private suspend fun sendStanding(s: Standing): Boolean {
        val r = XiaomiProto.StandingReminder.newBuilder()
            .setEnabled(s.enabled)
            .setStart(s.start.toProto())
            .setEnd(s.end.toProto())
            .setDnd(s.dnd)
            .setDndStart(s.dndStart.toProto())
            .setDndEnd(s.dndEnd.toProto())
            .build()
        return BandChannel.send(HealthCommands.COMMAND_TYPE, HealthCommands.CMD_CONFIG_STANDING_REMINDER_SET) {
            setHealth(XiaomiProto.Health.newBuilder().setStandingReminder(r))
        }
    }

    // ================================================================ user info (0)

    fun getProfile(): Profile? {
        val o = FeatureStore.getJson(KEY_PROFILE) ?: return null
        return Profile(
            heightCm = o.optInt("heightCm"),
            weightKg = o.optDouble("weightKg").toFloat(),
            birthYear = o.optInt("birthYear"),
            birthMonth = o.optInt("birthMonth"),
            birthDay = o.optInt("birthDay"),
            gender = o.optString("gender", "other"),
            stepGoal = o.optInt("stepGoal"),
            calorieGoal = o.optInt("calorieGoal"),
            standingHoursGoal = o.optInt("standingHoursGoal"),
            activeMinutesGoal = o.optInt("activeMinutesGoal"),
        )
    }

    suspend fun setProfile(p: Profile): Profile {
        require(p.heightCm in 50..260) { "heightCm out of range" }
        require(p.weightKg in 10f..400f) { "weightKg out of range" }
        require(runCatching { LocalDate.of(p.birthYear, p.birthMonth, p.birthDay) }.isSuccess) { "invalid birthday" }
        FeatureStore.putJson(
            KEY_PROFILE,
            JSONObject()
                .put("heightCm", p.heightCm)
                .put("weightKg", p.weightKg.toDouble())
                .put("birthYear", p.birthYear)
                .put("birthMonth", p.birthMonth)
                .put("birthDay", p.birthDay)
                .put("gender", p.gender)
                .put("stepGoal", p.stepGoal)
                .put("calorieGoal", p.calorieGoal)
                .put("standingHoursGoal", p.standingHoursGoal)
                .put("activeMinutesGoal", p.activeMinutesGoal),
        )
        FeatureStore.setDirty(G_PROFILE, true)
        if (sendUserInfo(p)) FeatureStore.setDirty(G_PROFILE, false)
        return p
    }

    /** XiaomiHealthService.setUserInfo() */
    private suspend fun sendUserInfo(p: Profile): Boolean {
        val birth = LocalDate.of(p.birthYear, p.birthMonth, p.birthDay)
        val age = Period.between(birth, LocalDate.now()).years
        // "Compute the approximate max heart rate from the user age"
        var maxHeartRate = Math.round(if (age <= 40) 220.0 - age else 207 - 0.7 * age).toInt()
        if (maxHeartRate < 100 || maxHeartRate > 220) maxHeartRate = 175
        val userInfo = XiaomiProto.UserInfo.newBuilder()
            .setHeight(p.heightCm)
            .setWeight(p.weightKg)
            .setBirthday(String.format(Locale.ROOT, "%04d%02d%02d", p.birthYear, p.birthMonth, p.birthDay).toInt())
            .setGender(if (p.gender != "female") GENDER_MALE else GENDER_FEMALE) // upstream: TODO other gender
            .setMaxHeartRate(maxHeartRate)
            .setGoalCalories(p.calorieGoal)
            .setGoalSteps(p.stepGoal)
            .setGoalStanding(p.standingHoursGoal)
            .setGoalMoving(p.activeMinutesGoal)
            .build()
        return BandChannel.send(HealthCommands.COMMAND_TYPE, HealthCommands.CMD_SET_USER_INFO) {
            setHealth(XiaomiProto.Health.newBuilder().setUserInfo(userInfo))
        }
    }

    // ================================================================ lifecycle

    /** XiaomiHealthService.initialize() — settings part. */
    suspend fun onConnected() {
        // setUserInfo() — only with a profile the user actually entered (no fake defaults).
        getProfile()?.let { if (sendUserInfo(it)) FeatureStore.setDirty(G_PROFILE, false) }

        val t = HealthCommands.COMMAND_TYPE
        val m = getMonitoring()
        for ((group, get) in listOf(
            G_SPO2 to HealthCommands.CMD_CONFIG_SPO2_GET,
            G_HR to HealthCommands.CMD_CONFIG_HEART_RATE_GET,
            G_STRESS to HealthCommands.CMD_CONFIG_STRESS_GET,
            G_GOAL_NOTIF to HealthCommands.CMD_CONFIG_GOAL_NOTIFICATION_GET,
            G_GOALS to HealthCommands.CMD_CONFIG_GOALS_GET,
            G_VITALITY to HealthCommands.CMD_CONFIG_VITALITY_SCORE_GET,
        )) {
            if (m != null && FeatureStore.isDirty(group)) {
                if (pushGroup(group, m)) FeatureStore.setDirty(group, false)
            } else {
                BandChannel.send(t, get)
            }
        }
        val s = getStanding()
        if (s != null && FeatureStore.isDirty(G_STANDING)) {
            if (sendStanding(s)) FeatureStore.setDirty(G_STANDING, false)
        } else {
            BandChannel.send(t, HealthCommands.CMD_CONFIG_STANDING_REMINDER_GET)
        }
    }

    fun handleCommand(cmd: XiaomiProto.Command) {
        val h = if (cmd.hasHealth()) cmd.health else null
        when (cmd.subtype) {
            HealthCommands.CMD_CONFIG_SPO2_GET -> if (h != null && h.hasSpo2()) onSpo2(h.spo2)
            HealthCommands.CMD_CONFIG_HEART_RATE_GET -> if (h != null && h.hasHeartRate()) onHeartRate(h.heartRate)
            HealthCommands.CMD_CONFIG_STANDING_REMINDER_GET -> if (h != null && h.hasStandingReminder()) onStanding(h.standingReminder)
            HealthCommands.CMD_CONFIG_STRESS_GET -> if (h != null && h.hasStress()) onStress(h.stress)
            HealthCommands.CMD_CONFIG_GOAL_NOTIFICATION_GET -> if (h != null && h.hasGoalNotification()) onGoalNotification(h.goalNotification)
            HealthCommands.CMD_CONFIG_GOALS_GET -> if (h != null && h.hasGoalsConfig()) onGoals(h.goalsConfig)
            HealthCommands.CMD_CONFIG_VITALITY_SCORE_GET -> if (h != null && h.hasVitalityScore()) onVitality(h.vitalityScore)
            HealthCommands.CMD_SET_USER_INFO,
            HealthCommands.CMD_CONFIG_SPO2_SET,
            HealthCommands.CMD_CONFIG_HEART_RATE_SET,
            HealthCommands.CMD_CONFIG_STANDING_REMINDER_SET,
            HealthCommands.CMD_CONFIG_STRESS_SET,
            HealthCommands.CMD_CONFIG_GOAL_NOTIFICATION_SET,
            HealthCommands.CMD_CONFIG_GOALS_SET,
            HealthCommands.CMD_CONFIG_VITALITY_SCORE_SET -> Log.d(TAG, "health set ack ${cmd.subtype}, status=${cmd.status}")
            else -> Unit // activity / realtime / workout handled elsewhere
        }
    }

    private fun HourMin.toProto(): XiaomiProto.HourMinute =
        XiaomiProto.HourMinute.newBuilder().setHour(hour).setMinute(minute).build()

    private fun JSONArray?.toIntList(): List<Int> =
        if (this == null) emptyList() else (0 until length()).map { optInt(it) }
}
