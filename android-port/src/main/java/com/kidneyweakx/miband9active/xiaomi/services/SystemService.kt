/*  Copyright (C) 2023-2025 Andreas Shimokawa, José Rebelo, LuK1337, Yoran Vulker  (Gadgetbridge XiaomiSystemService)
 *  Copyright (C) 2026 Gadgetbridge contributors                                  (Gadgetbridge XiaomiVibrationManager)
 *  Copyright (C) 2026 kidneyweakx                                                (Kotlin port, slimmed)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Command type 2. Ported parts: setCurrentTime, setLanguage, handleDeviceInfo,
 *  handleBattery, find phone (CMD_FIND_PHONE), camera remote config
 *  (CMD_CAMERA_REMOTE_GET/SET), phone silent mode (43/44/45) and the read-only
 *  half of XiaomiVibrationManager (CMD_GET = 46).
 *
 *  Not ported: password, display items, widgets, wear mode (band has no
 *  pebble/necklace mode), firmware install, find watch (unsupported on
 *  Mi Band 9 Active).
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.media.AudioManager
import android.text.format.DateFormat
import android.util.Log
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.PhoneRinger
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.json.JSONArray
import org.json.JSONObject

object SystemService {
    private const val TAG = "MB9A_System"

    private const val KEY_USE_24H = "sys_use_24h"
    private const val KEY_LANGUAGE = "sys_language"
    private const val KEY_DEVICE_INFO = "sys_device_info"
    private const val KEY_CAMERA = "sys_camera_remote"
    private const val KEY_VIBRATION = "sys_vibration_patterns"
    private const val DIRTY_SYSTEM = "system_settings"
    private const val DIRTY_CAMERA = "camera_remote"

    /** XiaomiCoordinator.getSupportedLanguageSettings() */
    val SUPPORTED_LANGUAGES: List<String> = listOf(
        "auto", "ar_SA", "cs_CZ", "da_DK", "de_DE", "el_GR", "en_US", "es_ES", "fr_FR",
        "he_IL", "id_ID", "it_IT", "ja_JP", "ko_KO", "nl_NL", "nb_NO", "pl_PL", "pt_BR",
        "pt_PT", "ro_RO", "ru_RU", "sv_SE", "th_TH", "tr_TR", "uk_UA", "vi_VN", "zh_CN", "zh_TW",
    )

    data class Settings(val use24HourClock: Boolean, val language: String)
    data class DeviceInfo(val serialNumber: String, val firmware: String, val model: String)
    data class Battery(val level: Int, val charging: Boolean, val atMillis: Long)
    data class VibrationAssignment(val type: Int, val presetId: Int)
    data class VibrationPattern(val id: Int, val name: String, val type: Int)
    data class VibrationPatterns(
        val assignments: List<VibrationAssignment>,
        val custom: List<VibrationPattern>,
        val fetchedAt: Long,
    )

    private val shutterListeners = CopyOnWriteArrayList<() -> Unit>()

    // ------------------------------------------------------------ settings

    fun getSettings(): Settings {
        val p = FeatureStore.prefs
        val use24 = if (p.contains(KEY_USE_24H)) p.getBoolean(KEY_USE_24H, true)
        else DateFormat.is24HourFormat(AppContext.context) // phone's real setting
        return Settings(use24, p.getString(KEY_LANGUAGE, "auto") ?: "auto")
    }

    suspend fun setSettings(settings: Settings): Settings {
        val lang = if (settings.language in SUPPORTED_LANGUAGES) settings.language else "auto"
        val old = getSettings()
        FeatureStore.prefs.edit()
            .putBoolean(KEY_USE_24H, settings.use24HourClock)
            .putString(KEY_LANGUAGE, lang)
            .apply()
        FeatureStore.setDirty(DIRTY_SYSTEM, true)
        var ok = true
        if (old.use24HourClock != settings.use24HourClock || !BandChannel.isConnected) ok = syncClock() && ok
        if (old.language != lang || !BandChannel.isConnected) ok = setLanguage() && ok
        if (ok) FeatureStore.setDirty(DIRTY_SYSTEM, false)
        return getSettings()
    }

    /** XiaomiSystemService.setCurrentTime() */
    suspend fun syncClock(): Boolean {
        val now = GregorianCalendar.getInstance()
        val tz = TimeZone.getDefault()
        val clock = XiaomiProto.Clock.newBuilder()
            .setTime(
                XiaomiProto.Time.newBuilder()
                    .setHour(now.get(Calendar.HOUR_OF_DAY))
                    .setMinute(now.get(Calendar.MINUTE))
                    .setSecond(now.get(Calendar.SECOND))
                    .setMillisecond(now.get(Calendar.MILLISECOND))
                    .build(),
            )
            .setDate(
                XiaomiProto.Date.newBuilder()
                    .setYear(now.get(Calendar.YEAR))
                    .setMonth(now.get(Calendar.MONTH) + 1)
                    .setDay(now.get(Calendar.DATE))
                    .build(),
            )
            .setTimezone(
                XiaomiProto.TimeZone.newBuilder()
                    // offsets are in blocks of 15 min
                    .setZoneOffset(((now.get(Calendar.ZONE_OFFSET) / 1000) / 60) / 15)
                    .setDstOffset(((now.get(Calendar.DST_OFFSET) / 1000) / 60) / 15)
                    .setName(tz.id)
                    .build(),
            )
            .setIsNot24Hour(!getSettings().use24HourClock)
            .build()
        return BandChannel.send(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_CLOCK) {
            setSystem(XiaomiProto.System.newBuilder().setClock(clock).build())
        }
    }

    /** XiaomiSystemService.setLanguage(): "auto" -> phone locale; always lower-cased on the wire. */
    suspend fun setLanguage(): Boolean {
        var localeString = getSettings().language
        if (localeString == "auto") {
            val language = Locale.getDefault().language
            var country = Locale.getDefault().country
            if (country.isNullOrEmpty()) country = language // upstream: "sometimes country is null, guess it"
            localeString = language + "_" + country.uppercase(Locale.ROOT)
        }
        val code = localeString.lowercase(Locale.ROOT)
        Log.i(TAG, "set language $code")
        return BandChannel.send(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_LANGUAGE) {
            setSystem(XiaomiProto.System.newBuilder().setLanguage(XiaomiProto.Language.newBuilder().setCode(code)))
        }
    }

    // ------------------------------------------------------------ device info / battery

    fun getDeviceInfo(): DeviceInfo? {
        val o = FeatureStore.getJson(KEY_DEVICE_INFO) ?: return null
        return DeviceInfo(o.optString("serial"), o.optString("firmware"), o.optString("model"))
    }

    suspend fun requestDeviceInfo(): DeviceInfo? {
        val reply = BandChannel.request(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_DEVICE_INFO)
        if (reply != null && reply.hasSystem() && reply.system.hasDeviceInfo()) storeDeviceInfo(reply.system.deviceInfo)
        return getDeviceInfo()
    }

    /** Resolves null when not connected / no answer: never a cached or guessed level. */
    suspend fun requestBattery(): Battery? {
        val reply = BandChannel.request(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_BATTERY) ?: return null
        if (!reply.hasSystem() || !reply.system.hasPower() || !reply.system.power.hasBattery()) return null
        val b = reply.system.power.battery
        // convertBatteryStateFromRawValue: 1 charging, 2/3 normal
        return Battery(b.level, b.hasState() && b.state == 1, System.currentTimeMillis())
    }

    private fun storeDeviceInfo(info: XiaomiProto.DeviceInfo) {
        Log.i(TAG, "device info fw=${info.firmware} model=${info.model}")
        FeatureStore.putJson(
            KEY_DEVICE_INFO,
            JSONObject().put("serial", info.serialNumber).put("firmware", info.firmware).put("model", info.model),
        )
    }

    // ------------------------------------------------------------ camera remote

    /** Band-reported (or pending user) value, null if unknown. */
    fun getCameraEnabled(): Boolean? {
        val p = FeatureStore.prefs
        return if (p.contains(KEY_CAMERA)) p.getBoolean(KEY_CAMERA, false) else null
    }

    suspend fun refreshCamera(): Boolean? {
        val reply = BandChannel.request(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_CAMERA_REMOTE_GET)
        if (reply != null && reply.hasSystem() && reply.system.hasCamera()) onCameraConfig(reply.system.camera)
        return getCameraEnabled()
    }

    /** XiaomiSystemService.setCameraRemoteConfig() */
    suspend fun setCameraEnabled(enabled: Boolean): Boolean {
        FeatureStore.prefs.edit().putBoolean(KEY_CAMERA, enabled).apply()
        FeatureStore.setDirty(DIRTY_CAMERA, true)
        if (pushCamera(enabled)) FeatureStore.setDirty(DIRTY_CAMERA, false)
        return enabled
    }

    private suspend fun pushCamera(enabled: Boolean): Boolean =
        BandChannel.send(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_CAMERA_REMOTE_SET) {
            setSystem(XiaomiProto.System.newBuilder().setCamera(XiaomiProto.Camera.newBuilder().setEnabled(enabled)))
        }

    private fun onCameraConfig(camera: XiaomiProto.Camera) {
        FeatureStore.setFeature(FeatureStore.FEAT_CAMERA_REMOTE, true)
        if (!FeatureStore.isDirty(DIRTY_CAMERA)) {
            FeatureStore.prefs.edit().putBoolean(KEY_CAMERA, camera.enabled).apply()
        }
    }

    fun addShutterListener(listener: () -> Unit): () -> Unit {
        shutterListeners += listener
        return { shutterListeners -= listener }
    }

    // ------------------------------------------------------------ vibration (read-only)

    fun getVibrationPatterns(): VibrationPatterns? {
        val o = FeatureStore.getJson(KEY_VIBRATION) ?: return null
        val a = o.optJSONArray("a") ?: JSONArray()
        val c = o.optJSONArray("c") ?: JSONArray()
        return VibrationPatterns(
            assignments = (0 until a.length()).map { i ->
                val e = a.getJSONObject(i)
                VibrationAssignment(e.optInt("t"), e.optInt("p"))
            },
            custom = (0 until c.length()).map { i ->
                val e = c.getJSONObject(i)
                VibrationPattern(e.optInt("id"), e.optString("n"), e.optInt("t"))
            },
            fetchedAt = o.optLong("at"),
        )
    }

    suspend fun refreshVibrationPatterns(): VibrationPatterns? {
        val reply = BandChannel.request(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_VIBRATION_PATTERNS_GET)
        if (reply != null && reply.hasSystem() && reply.system.hasVibrationPatterns()) {
            onVibrationPatterns(reply.system.vibrationPatterns)
        }
        return getVibrationPatterns()
    }

    /** XiaomiVibrationManager.handlePatterns() */
    private fun onVibrationPatterns(p: XiaomiProto.VibrationPatterns) {
        // a partial response (the echo after add/remove) lacks the mapping; don't clobber the cache
        if (p.notificationTypeCount == 0) return
        val a = JSONArray()
        p.notificationTypeList.forEach { a.put(JSONObject().put("t", it.notificationType).put("p", it.preset)) }
        val c = JSONArray()
        p.customVibrationPatternList.forEach {
            c.put(JSONObject().put("id", it.id).put("n", it.name).put("t", it.type))
        }
        FeatureStore.putJson(
            KEY_VIBRATION,
            JSONObject().put("a", a).put("c", c).put("at", System.currentTimeMillis()),
        )
    }

    // ------------------------------------------------------------ lifecycle

    /** XiaomiSystemService.initialize() subset (device info + battery are requested by the transport). */
    suspend fun onConnected() {
        if (FeatureStore.isDirty(DIRTY_SYSTEM)) {
            // clock was already sent by DeviceFeatures.initialize(); language is only pushed on change upstream
            if (setLanguage()) FeatureStore.setDirty(DIRTY_SYSTEM, false)
        }
        if (FeatureStore.isDirty(DIRTY_CAMERA)) {
            val enabled = getCameraEnabled()
            if (enabled != null && pushCamera(enabled)) FeatureStore.setDirty(DIRTY_CAMERA, false)
        } else {
            BandChannel.send(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_CAMERA_REMOTE_GET)
        }
        BandChannel.send(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_VIBRATION_PATTERNS_GET)
    }

    /** XiaomiSystemService.handleCommand() subset. Must not block: runs on the incoming collector. */
    fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            SystemCommands.CMD_DEVICE_INFO ->
                if (cmd.hasSystem() && cmd.system.hasDeviceInfo()) storeDeviceInfo(cmd.system.deviceInfo)

            SystemCommands.CMD_FIND_PHONE -> if (cmd.hasSystem()) {
                Log.i(TAG, "find phone: ${cmd.system.findDevice}")
                if (cmd.system.findDevice == 0) PhoneRinger.start() else PhoneRinger.stop()
            }

            SystemCommands.CMD_CAMERA_REMOTE_GET ->
                if (cmd.hasSystem() && cmd.system.hasCamera()) onCameraConfig(cmd.system.camera)

            SystemCommands.CMD_CAMERA_REMOTE_SET -> Log.d(TAG, "camera remote set ack, status=${cmd.status}")

            SystemCommands.CMD_SILENT_MODE_GET -> DeviceFeatures.launch { sendPhoneSilentMode(isPhoneSilent()) }

            SystemCommands.CMD_SILENT_MODE_SET_FROM_WATCH ->
                if (cmd.hasSystem() && cmd.system.hasPhoneSilentModeSet()) {
                    setPhoneSilent(cmd.system.phoneSilentModeSet.phoneSilentMode.silent)
                }

            SystemCommands.CMD_VIBRATION_PATTERNS_GET ->
                if (cmd.hasSystem() && cmd.system.hasVibrationPatterns()) onVibrationPatterns(cmd.system.vibrationPatterns)

            SystemCommands.CMD_BATTERY, SystemCommands.CMD_DEVICE_STATE_GET, SystemCommands.CMD_DEVICE_STATE,
            SystemCommands.CMD_CLOCK, SystemCommands.CMD_LANGUAGE -> Unit // owned by HybridBandLink / acks

            else -> {
                // Upstream has no shutter handler for Xiaomi. Log everything unknown so the
                // real camera-remote event can be identified on hardware.
                Log.i(TAG, "unhandled system subtype=${cmd.subtype} status=${cmd.status} payload=${cmd.toByteArray().toHex()}")
                if (cmd.hasSystem() && cmd.system.hasCamera() && getCameraEnabled() == true) {
                    shutterListeners.forEach { runCatching { it() } }
                }
            }
        }
    }

    // ------------------------------------------------------------ phone silent mode (SilentMode.java)

    /** Default pref "normal_silent": silent when the ringer is quieter than NORMAL. */
    private fun isPhoneSilent(): Boolean {
        val am = AppContext.context.getSystemService(AudioManager::class.java) ?: return false
        return am.ringerMode < AudioManager.RINGER_MODE_NORMAL
    }

    private fun setPhoneSilent(silent: Boolean) {
        val am = AppContext.context.getSystemService(AudioManager::class.java) ?: return
        try {
            am.ringerMode = if (silent) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
        } catch (t: SecurityException) {
            // Needs notification-policy access when it would toggle DnD.
            Log.w(TAG, "cannot change ringer mode without notification policy access", t)
        }
    }

    private suspend fun sendPhoneSilentMode(silent: Boolean) {
        BandChannel.send(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_SILENT_MODE_SET_FROM_PHONE) {
            setSystem(
                XiaomiProto.System.newBuilder().setPhoneSilentModeSet(
                    XiaomiProto.PhoneSilentModeSet.newBuilder().setPhoneSilentMode(
                        XiaomiProto.PhoneSilentMode.newBuilder().setSilent(silent),
                    ),
                ),
            )
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
