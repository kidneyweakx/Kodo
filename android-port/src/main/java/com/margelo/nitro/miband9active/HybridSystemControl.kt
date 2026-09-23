/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  JS facade over SystemService (clock, language, device info, battery, find
 *  phone, vibration patterns) and HealthSettingsService (monitoring settings,
 *  user profile). All getters are synchronous reads of persisted state.
 */
package com.margelo.nitro.miband9active

import android.bluetooth.BluetoothManager
import android.os.PowerManager
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.PhoneRinger
import com.kidneyweakx.miband9active.xiaomi.services.DeviceFeatures
import com.kidneyweakx.miband9active.xiaomi.services.FeatureStore
import com.kidneyweakx.miband9active.xiaomi.services.HealthSettingsService
import com.kidneyweakx.miband9active.xiaomi.services.SystemService
import com.margelo.nitro.core.Promise
import java.time.Instant

class HybridSystemControl : HybridHybridSystemControlSpec() {

    init {
        DeviceFeatures.ensureStarted()
    }

    // ---- find phone -------------------------------------------------------

    override val isRinging: Boolean get() = PhoneRinger.isRinging

    override fun ringPhone() {
        PhoneRinger.start(AppContext.context)
    }

    override fun silencePhone() {
        PhoneRinger.stopFromUser(AppContext.context)
    }

    override fun onFindPhone(listener: (ringing: Boolean) -> Unit): () -> Unit =
        PhoneRinger.addListener(listener)

    // ---- clock / language -------------------------------------------------

    override fun syncClock(): Promise<Boolean> = Promise.async { SystemService.syncClock() }

    override fun getSupportedLanguages(): Array<String> = SystemService.SUPPORTED_LANGUAGES.toTypedArray()

    override fun getSystemSettings(): BandSystemSettings = SystemService.getSettings().toNitro()

    override fun setSystemSettings(settings: BandSystemSettings): Promise<BandSystemSettings> = Promise.async {
        SystemService.setSettings(SystemService.Settings(settings.use24HourClock, settings.language)).toNitro()
    }

    private fun SystemService.Settings.toNitro() = BandSystemSettings(use24HourClock = use24HourClock, language = language)

    // ---- device info / battery -------------------------------------------

    override fun getDeviceInfo(): BandDeviceInfo? = SystemService.getDeviceInfo()?.toNitro()

    override fun requestDeviceInfo(): Promise<BandDeviceInfo?> = Promise.async {
        SystemService.requestDeviceInfo()?.toNitro()
    }

    private fun SystemService.DeviceInfo.toNitro() =
        BandDeviceInfo(serialNumber = serialNumber, firmware = firmware, model = model)

    override fun requestBattery(): Promise<BatteryInfo?> = Promise.async {
        SystemService.requestBattery()?.let {
            BatteryInfo(
                percent = it.level.toDouble(),
                charging = it.charging,
                updatedAt = Instant.ofEpochMilli(it.atMillis).toString(),
            )
        }
    }

    // ---- features -----------------------------------------------------------

    override fun getFeatures(): BandFeatures? {
        if (!FeatureStore.featuresKnown) return null
        return BandFeatures(
            heartRateConfig = FeatureStore.feature(FeatureStore.FEAT_HEART_RATE),
            spo2 = FeatureStore.feature(FeatureStore.FEAT_SPO2),
            stress = FeatureStore.feature(FeatureStore.FEAT_STRESS),
            inactivity = FeatureStore.feature(FeatureStore.FEAT_INACTIVITY),
            goalNotification = FeatureStore.feature(FeatureStore.FEAT_GOAL_NOTIFICATION),
            secondaryGoal = FeatureStore.feature(FeatureStore.FEAT_GOAL_SECONDARY),
            vitalityScore = FeatureStore.feature(FeatureStore.FEAT_VITALITY_SCORE),
            sleepModeSchedule = FeatureStore.feature(FeatureStore.FEAT_SLEEP_MODE_SCHEDULE),
            cameraRemote = FeatureStore.feature(FeatureStore.FEAT_CAMERA_REMOTE),
            multipleWeatherLocations = FeatureStore.feature(FeatureStore.FEAT_MULTIPLE_WEATHER_LOCATIONS),
            alarmSlots = FeatureStore.int(FeatureStore.PREF_ALARM_SLOTS).toDouble(),
            reminderSlots = FeatureStore.int(FeatureStore.PREF_REMINDER_SLOTS).toDouble(),
            findBand = false, // MiBand9ActiveCoordinator.supportsFindDevice = false
            manualHeartRate = false, // MiBand9ActiveCoordinator.supportsManualHeartRateMeasurement = false
        )
    }

    // ---- health monitoring --------------------------------------------------

    override val healthMonitoringPendingPush: Boolean get() = HealthSettingsService.monitoringPendingPush

    override fun getHealthMonitoring(): HealthMonitoringSettings? = HealthSettingsService.getMonitoring()?.toNitro()

    override fun refreshHealthMonitoring(): Promise<HealthMonitoringSettings?> = Promise.async {
        HealthSettingsService.refreshMonitoring()?.toNitro()
    }

    override fun setHealthMonitoring(settings: HealthMonitoringSettings): Promise<HealthMonitoringSettings> = Promise.async {
        HealthSettingsService.setMonitoring(settings.toEngine()).toNitro()
    }

    private fun HealthSettingsService.Monitoring.toNitro() = HealthMonitoringSettings(
        heartRateInterval = when (hrIntervalSec) {
            0 -> HeartRateInterval.OFF
            -1 -> HeartRateInterval.SMART
            60 -> HeartRateInterval._1M
            600 -> HeartRateInterval._10M
            1800 -> HeartRateInterval._30M
            else -> if (hrIntervalSec < 300) HeartRateInterval._1M else if (hrIntervalSec < 1200) HeartRateInterval._10M else HeartRateInterval._30M
        },
        heartRateSleepDetection = hrSleepDetection,
        sleepBreathingQuality = sleepBreathingQuality,
        heartRateHighAlertBpm = hrHighAlert.toDouble(),
        heartRateLowAlertBpm = hrLowAlert.toDouble(),
        spo2AllDay = spo2AllDay,
        spo2LowAlertPct = spo2LowAlert.toDouble(),
        stressAllDay = stressAllDay,
        stressRelaxReminder = stressRelaxReminder,
        goalNotification = goalNotification,
        secondaryGoal = if (secondaryGoal == "active_time") SecondaryGoal.ACTIVE_TIME else SecondaryGoal.STANDING_TIME,
        vitalitySevenDay = vitalitySevenDay,
        vitalityDaily = vitalityDaily,
    )

    private fun HealthMonitoringSettings.toEngine() = HealthSettingsService.Monitoring(
        hrIntervalSec = when (heartRateInterval) {
            HeartRateInterval.OFF -> 0
            HeartRateInterval.SMART -> -1
            HeartRateInterval._1M -> 60
            HeartRateInterval._10M -> 600
            HeartRateInterval._30M -> 1800
        },
        hrSleepDetection = heartRateSleepDetection,
        sleepBreathingQuality = sleepBreathingQuality,
        hrHighAlert = heartRateHighAlertBpm.toInt().coerceIn(0, 220),
        hrLowAlert = heartRateLowAlertBpm.toInt().coerceIn(0, 120),
        spo2AllDay = spo2AllDay,
        spo2LowAlert = spo2LowAlertPct.toInt().coerceIn(0, 100),
        stressAllDay = stressAllDay,
        stressRelaxReminder = stressRelaxReminder,
        goalNotification = goalNotification,
        secondaryGoal = if (secondaryGoal == SecondaryGoal.ACTIVE_TIME) "active_time" else "standing_time",
        vitalitySevenDay = vitalitySevenDay,
        vitalityDaily = vitalityDaily,
    )

    // ---- user profile -------------------------------------------------------

    override fun getUserProfile(): UserProfile? = HealthSettingsService.getProfile()?.let {
        UserProfile(
            heightCm = it.heightCm.toDouble(),
            weightKg = it.weightKg.toDouble(),
            birthYear = it.birthYear.toDouble(),
            birthMonth = it.birthMonth.toDouble(),
            birthDay = it.birthDay.toDouble(),
            gender = when (it.gender) {
                "male" -> UserGender.MALE
                "female" -> UserGender.FEMALE
                else -> UserGender.OTHER
            },
            stepGoal = it.stepGoal.toDouble(),
            calorieGoal = it.calorieGoal.toDouble(),
            standingHoursGoal = it.standingHoursGoal.toDouble(),
            activeMinutesGoal = it.activeMinutesGoal.toDouble(),
        )
    }

    override fun setUserProfile(profile: UserProfile): Promise<UserProfile> = Promise.async {
        HealthSettingsService.setProfile(
            HealthSettingsService.Profile(
                heightCm = profile.heightCm.toInt(),
                weightKg = profile.weightKg.toFloat(),
                birthYear = profile.birthYear.toInt(),
                birthMonth = profile.birthMonth.toInt(),
                birthDay = profile.birthDay.toInt(),
                gender = when (profile.gender) {
                    UserGender.MALE -> "male"
                    UserGender.FEMALE -> "female"
                    UserGender.OTHER -> "other"
                },
                stepGoal = profile.stepGoal.toInt(),
                calorieGoal = profile.calorieGoal.toInt(),
                standingHoursGoal = profile.standingHoursGoal.toInt(),
                activeMinutesGoal = profile.activeMinutesGoal.toInt(),
            ),
        )
        profile
    }

    // ---- vibration (read-only) -----------------------------------------------

    override fun getVibrationPatterns(): VibrationPatternsInfo? = SystemService.getVibrationPatterns()?.toNitro()

    override fun refreshVibrationPatterns(): Promise<VibrationPatternsInfo?> = Promise.async {
        SystemService.refreshVibrationPatterns()?.toNitro()
    }

    private fun SystemService.VibrationPatterns.toNitro() = VibrationPatternsInfo(
        assignments = assignments.map { VibrationAssignment(category = categoryOf(it.type), presetId = it.presetId.toDouble()) }.toTypedArray(),
        customPatterns = custom.map {
            VibrationCustomPattern(id = it.id.toDouble(), name = it.name, category = categoryOf(it.type))
        }.toTypedArray(),
        fetchedAt = fetchedAt.toDouble(),
    )

    /** proto VibrationType numbering. */
    private fun categoryOf(type: Int): VibrationCategory = when (type) {
        0 -> VibrationCategory.NONE
        1 -> VibrationCategory.CALL
        2 -> VibrationCategory.TASK
        3 -> VibrationCategory.ALARM
        4 -> VibrationCategory.NOTIFICATION
        5 -> VibrationCategory.STANDING
        6 -> VibrationCategory.SMS
        7 -> VibrationCategory.GOAL
        8 -> VibrationCategory.EVENT
        else -> VibrationCategory.UNKNOWN
    }

    // ---- phone status -------------------------------------------------------

    override fun isIgnoringBatteryOptimizations(): Boolean = try {
        val ctx = AppContext.context
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    } catch (_: Throwable) {
        false
    }

    override fun isBluetoothEnabled(): Boolean = try {
        AppContext.context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    } catch (_: Throwable) {
        false
    }
}
