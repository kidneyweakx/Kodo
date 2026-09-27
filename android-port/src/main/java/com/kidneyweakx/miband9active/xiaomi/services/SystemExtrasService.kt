/*  Copyright (C) 2023-2025 Andreas Shimokawa, José Rebelo, LuK1337, Yoran Vulker  (Gadgetbridge XiaomiSystemService,
 *                                                                                 XiaomiNotificationService)
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
 *
 * The parts of XiaomiSystemService / XiaomiNotificationService that the
 * first port left out, gated exactly like upstream (the FEAT_* flag is set
 * only once the band answered the GET):
 *
 *   - device state (2,78 / 2,79): worn / asleep / charging, live only —
 *     handleBasicDeviceState / handleDeviceState / handleWearingState /
 *     handleSleepDetectionState. FEAT_DEVICE_ACTIONS.
 *   - band lock password (2,9 get / 2,21 set): setPassword / handlePassword,
 *     PasswordCapabilityImpl.Mode.NUMBERS_6. FEAT_PASSWORD.
 *   - display items (2,29 get / 2,30 set): setDisplayItems / handleDisplayItems
 *     incl. the settings item + "more" section. FEAT_DISPLAY_ITEMS.
 *   - screen on for notifications (7,6 get / 7,7 set):
 *     XiaomiNotificationService.setScreenOnOnNotifications.
 *     FEAT_SCREEN_ON_ON_NOTIFICATIONS.
 *
 * Settings follow the FeatureStore pattern: persisted getter (null =
 * unknown), a setter that pushes now or marks dirty and pushes on the next
 * connect, and the band's answer never overwrites a pending user change.
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.util.Log
import com.kidneyweakx.miband9active.xiaomi.notifications.NotificationCmd
import java.util.concurrent.CopyOnWriteArrayList
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.json.JSONArray
import org.json.JSONObject

object SystemExtrasService {
    private const val TAG = "MB9A_SystemExtras"

    const val FEAT_DEVICE_ACTIONS = "feat_device_actions"
    const val FEAT_PASSWORD = "feat_password"
    const val FEAT_DISPLAY_ITEMS = "feat_display_items"
    const val FEAT_SCREEN_ON_ON_NOTIFICATIONS = "feat_screen_on_on_notifications"

    private const val KEY_PASSWORD = "sys_password"
    private const val KEY_DISPLAY_ITEMS = "sys_display_items"
    private const val KEY_SCREEN_ON = "notif_screen_on"
    private const val DIRTY_PASSWORD = "password"
    private const val DIRTY_DISPLAY_ITEMS = "display_items"
    private const val DIRTY_SCREEN_ON = "screen_on_on_notifications"

    /** Code of the "more" separator in [setDisplayItems] (upstream sortable-list marker). */
    const val MORE_MARKER = "more"

    // ================================================================ device state (live)

    data class DeviceState(
        val charging: Boolean?,
        val worn: Boolean?,
        val asleep: Boolean?,
        val updatedAt: Long,
    )

    @Volatile var deviceState: DeviceState? = null
        private set
    private val stateListeners = CopyOnWriteArrayList<(DeviceState) -> Unit>()

    fun addDeviceStateListener(listener: (DeviceState) -> Unit): () -> Unit {
        stateListeners += listener
        return { stateListeners -= listener }
    }

    private fun updateState(charging: Boolean?, worn: Boolean?, asleep: Boolean?) {
        val prev = deviceState
        val next = DeviceState(
            charging = charging ?: prev?.charging,
            worn = worn ?: prev?.worn,
            asleep = asleep ?: prev?.asleep,
            updatedAt = System.currentTimeMillis(),
        )
        deviceState = next
        if (prev == null || prev.charging != next.charging || prev.worn != next.worn || prev.asleep != next.asleep) {
            Log.i(TAG, "device state charging=${next.charging} worn=${next.worn} asleep=${next.asleep}")
            stateListeners.forEach { runCatching { it(next) } }
        }
    }

    /** Live state is meaningless without a link: clear it instead of showing stale "worn". */
    fun onDisconnected() {
        deviceState = null
    }

    /** Battery replies also carry the charger state; keep the device-state view consistent. */
    fun onBatteryCharging(charging: Boolean) {
        if (deviceState != null && deviceState?.charging != charging) updateState(charging, null, null)
    }

    private fun onBasicDeviceState(s: XiaomiProto.BasicDeviceState) {
        FeatureStore.setFeature(FEAT_DEVICE_ACTIONS, true)
        updateState(s.isCharging, s.isWorn, s.isUserAsleep)
    }

    private fun onDeviceState(s: XiaomiProto.DeviceState) {
        val charging = if (s.hasChargingState()) when (s.chargingState) { 1 -> true; 2, 3 -> false; else -> null } else null
        val worn = if (s.hasWearingState()) when (s.wearingState) { 1 -> true; 2 -> false; else -> null } else null
        val asleep = if (s.hasSleepState()) when (s.sleepState) { 1 -> true; 2 -> false; else -> null } else null
        updateState(charging, worn, asleep)
    }

    // ================================================================ password (NUMBERS_6)

    data class PasswordState(val enabled: Boolean, val hasPassword: Boolean)

    fun getPassword(): PasswordState? {
        val o = FeatureStore.getJson(KEY_PASSWORD) ?: return null
        return PasswordState(o.optBoolean("enabled"), o.optString("pw").isNotEmpty())
    }

    suspend fun refreshPassword(): PasswordState? {
        val reply = BandChannel.request(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_PASSWORD_GET)
        if (reply != null && reply.hasSystem() && reply.system.hasPassword()) onPassword(reply.system.password)
        return getPassword()
    }

    /**
     * [password] null = keep the stored one (needed to disable the lock).
     * Throws IllegalArgumentException when no valid 6-digit password is known.
     */
    suspend fun setPassword(enabled: Boolean, password: String?): PasswordState {
        val stored = FeatureStore.getJson(KEY_PASSWORD)?.optString("pw").orEmpty()
        val pw = password ?: stored
        require(PASSWORD_REGEX.matches(pw)) { "password must be exactly 6 digits" }
        FeatureStore.putJson(KEY_PASSWORD, JSONObject().put("enabled", enabled).put("pw", pw))
        FeatureStore.setDirty(DIRTY_PASSWORD, true)
        if (pushPassword()) FeatureStore.setDirty(DIRTY_PASSWORD, false)
        return getPassword()!!
    }

    private suspend fun pushPassword(): Boolean {
        val o = FeatureStore.getJson(KEY_PASSWORD) ?: return false
        val pw = o.optString("pw")
        if (!PASSWORD_REGEX.matches(pw)) return false
        Log.i(TAG, "set password enabled=${o.optBoolean("enabled")}")
        return BandChannel.send(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_PASSWORD_SET) {
            setSystem(
                XiaomiProto.System.newBuilder().setPassword(
                    XiaomiProto.Password.newBuilder()
                        .setState(if (o.optBoolean("enabled")) 2 else 1)
                        .setPassword(pw),
                ),
            )
        }
    }

    private fun onPassword(p: XiaomiProto.Password) {
        FeatureStore.setFeature(FEAT_PASSWORD, true)
        if (FeatureStore.isDirty(DIRTY_PASSWORD)) return
        val prevPw = FeatureStore.getJson(KEY_PASSWORD)?.optString("pw").orEmpty()
        FeatureStore.putJson(
            KEY_PASSWORD,
            JSONObject().put("enabled", p.state == 2).put("pw", if (p.hasPassword()) p.password else prevPw),
        )
    }

    private val PASSWORD_REGEX = Regex("^[0-9]{6}$")

    // ================================================================ display items

    data class DisplayItem(
        val code: String,
        val name: String,
        val enabled: Boolean,
        val inMoreSection: Boolean,
        val isSettings: Boolean,
    )

    data class DisplayItems(val items: List<DisplayItem>, val fetchedAt: Long)

    fun getDisplayItems(): DisplayItems? {
        val o = FeatureStore.getJson(KEY_DISPLAY_ITEMS) ?: return null
        val arr = o.optJSONArray("items") ?: return null
        val items = (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            DisplayItem(
                code = e.optString("code"),
                name = e.optString("name"),
                enabled = e.optBoolean("enabled"),
                inMoreSection = e.optBoolean("more"),
                isSettings = e.optBoolean("settings"),
            )
        }
        return DisplayItems(items, o.optLong("at"))
    }

    private fun storeDisplayItems(items: List<DisplayItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject().put("code", it.code).put("name", it.name).put("enabled", it.enabled)
                    .put("more", it.inMoreSection).put("settings", it.isSettings),
            )
        }
        FeatureStore.putJson(KEY_DISPLAY_ITEMS, JSONObject().put("items", arr).put("at", System.currentTimeMillis()))
    }

    suspend fun refreshDisplayItems(): DisplayItems? {
        val reply = BandChannel.request(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_DISPLAY_ITEMS_GET)
        if (reply != null && reply.hasSystem() && reply.system.hasDisplayItems()) onDisplayItems(reply.system.displayItems)
        return getDisplayItems()
    }

    /**
     * XiaomiSystemService.setDisplayItems. [enabledCodes] in display order;
     * codes after [MORE_MARKER] go to the "More" section. Unknown codes are
     * ignored; the band's settings item is always kept enabled.
     */
    suspend fun setDisplayItems(enabledCodes: List<String>): DisplayItems {
        val current = getDisplayItems() ?: throw IllegalStateException("display items unknown — connect the band first")
        val byCode = current.items.associateBy { it.code }
        val settingsCode = current.items.firstOrNull { it.isSettings }?.code
        val enabled = enabledCodes.filter { it == MORE_MARKER || byCode.containsKey(it) }.distinct().toMutableList()
        if (settingsCode != null && settingsCode !in enabled) {
            val moreAt = enabled.indexOf(MORE_MARKER)
            if (moreAt >= 0) enabled.add(moreAt, settingsCode) else enabled.add(settingsCode)
        }
        val out = mutableListOf<DisplayItem>()
        var inMore = false
        for (code in enabled) {
            if (code == MORE_MARKER) { inMore = true; continue }
            val it = byCode.getValue(code)
            out += DisplayItem(it.code, it.name, enabled = true, inMoreSection = inMore, isSettings = code == settingsCode)
        }
        current.items.filter { it.code !in enabled }.forEach {
            out += DisplayItem(it.code, it.name, enabled = false, inMoreSection = false, isSettings = it.isSettings)
        }
        storeDisplayItems(out)
        FeatureStore.setDirty(DIRTY_DISPLAY_ITEMS, true)
        if (pushDisplayItems()) FeatureStore.setDirty(DIRTY_DISPLAY_ITEMS, false)
        return getDisplayItems()!!
    }

    private suspend fun pushDisplayItems(): Boolean {
        val items = getDisplayItems()?.items ?: return false
        if (items.isEmpty()) return false
        val b = XiaomiProto.DisplayItems.newBuilder()
        items.forEach {
            val di = XiaomiProto.DisplayItem.newBuilder().setCode(it.code).setName(it.name).setUnknown5(1)
            if (!it.enabled) di.setDisabled(true)
            if (it.enabled && it.inMoreSection) di.setInMoreSection(true)
            if (it.isSettings) di.setIsSettings(1)
            b.addDisplayItem(di)
        }
        return BandChannel.send(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_DISPLAY_ITEMS_SET) {
            setSystem(XiaomiProto.System.newBuilder().setDisplayItems(b))
        }
    }

    /** XiaomiSystemService.handleDisplayItems: main (enabled, not more) first, then more, then disabled. */
    private fun onDisplayItems(d: XiaomiProto.DisplayItems) {
        FeatureStore.setFeature(FEAT_DISPLAY_ITEMS, d.displayItemCount > 0)
        if (FeatureStore.isDirty(DIRTY_DISPLAY_ITEMS) || d.displayItemCount == 0) return
        val all = d.displayItemList.map {
            DisplayItem(
                code = it.code,
                name = it.name,
                enabled = !it.disabled,
                inMoreSection = !it.disabled && it.inMoreSection,
                isSettings = it.isSettings == 1,
            )
        }
        storeDisplayItems(
            all.filter { it.enabled && !it.inMoreSection } +
                all.filter { it.enabled && it.inMoreSection } +
                all.filter { !it.enabled },
        )
    }

    // ================================================================ screen on for notifications

    fun getScreenOnOnNotifications(): Boolean? {
        val p = FeatureStore.prefs
        return if (p.contains(KEY_SCREEN_ON)) p.getBoolean(KEY_SCREEN_ON, true) else null
    }

    suspend fun refreshScreenOnOnNotifications(): Boolean? {
        val reply = BandChannel.request(NotificationCmd.TYPE, NotificationCmd.SCREEN_ON_ON_NOTIFICATIONS_GET)
        if (reply != null && reply.hasNotification()) onScreenOn(reply.notification)
        return getScreenOnOnNotifications()
    }

    suspend fun setScreenOnOnNotifications(enabled: Boolean): Boolean {
        FeatureStore.prefs.edit().putBoolean(KEY_SCREEN_ON, enabled).apply()
        FeatureStore.setDirty(DIRTY_SCREEN_ON, true)
        if (pushScreenOn(enabled)) FeatureStore.setDirty(DIRTY_SCREEN_ON, false)
        return enabled
    }

    private suspend fun pushScreenOn(enabled: Boolean): Boolean =
        BandChannel.send(NotificationCmd.TYPE, NotificationCmd.SCREEN_ON_ON_NOTIFICATIONS_SET) {
            setNotification(XiaomiProto.Notification.newBuilder().setScreenOnOnNotifications(enabled))
        }

    private fun onScreenOn(n: XiaomiProto.Notification) {
        if (!n.hasScreenOnOnNotifications()) return
        FeatureStore.setFeature(FEAT_SCREEN_ON_ON_NOTIFICATIONS, true)
        if (!FeatureStore.isDirty(DIRTY_SCREEN_ON)) {
            FeatureStore.prefs.edit().putBoolean(KEY_SCREEN_ON, n.screenOnOnNotifications).apply()
        }
    }

    // ================================================================ lifecycle

    /** XiaomiSystemService.initialize() / XiaomiNotificationService.initialize() subset. */
    suspend fun onConnected() {
        // (2,78) device state is requested by the transport right after auth.
        if (FeatureStore.isDirty(DIRTY_PASSWORD)) {
            if (pushPassword()) FeatureStore.setDirty(DIRTY_PASSWORD, false)
        } else {
            BandChannel.send(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_PASSWORD_GET)
        }
        if (FeatureStore.isDirty(DIRTY_DISPLAY_ITEMS)) {
            if (pushDisplayItems()) FeatureStore.setDirty(DIRTY_DISPLAY_ITEMS, false)
        } else {
            BandChannel.send(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_DISPLAY_ITEMS_GET)
        }
        val screenOn = getScreenOnOnNotifications()
        if (screenOn != null && FeatureStore.isDirty(DIRTY_SCREEN_ON)) {
            if (pushScreenOn(screenOn)) FeatureStore.setDirty(DIRTY_SCREEN_ON, false)
        } else {
            BandChannel.send(NotificationCmd.TYPE, NotificationCmd.SCREEN_ON_ON_NOTIFICATIONS_GET)
        }
    }

    /** System (type 2) subtypes owned here. Returns true when handled. Must not block. */
    fun handleSystemCommand(cmd: XiaomiProto.Command): Boolean {
        val sys = if (cmd.hasSystem()) cmd.system else null
        when (cmd.subtype) {
            SystemCommands.CMD_DEVICE_STATE_GET -> {
                if (sys != null && sys.hasBasicDeviceState()) onBasicDeviceState(sys.basicDeviceState)
            }
            SystemCommands.CMD_DEVICE_STATE -> {
                if (sys != null && sys.hasDeviceState()) onDeviceState(sys.deviceState)
            }
            SystemCommands.CMD_PASSWORD_GET -> {
                if (sys != null && sys.hasPassword()) onPassword(sys.password)
            }
            SystemCommands.CMD_PASSWORD_SET -> Log.d(TAG, "password set ack, status=${cmd.status}")
            SystemCommands.CMD_DISPLAY_ITEMS_GET -> {
                if (sys != null && sys.hasDisplayItems()) onDisplayItems(sys.displayItems)
            }
            SystemCommands.CMD_DISPLAY_ITEMS_SET -> Log.d(TAG, "display items set ack, status=${cmd.status}")
            else -> return false
        }
        return true
    }

    /** Notification (type 7) subtypes owned here (the rest belong to NotificationForwarder). */
    fun handleNotificationCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            NotificationCmd.SCREEN_ON_ON_NOTIFICATIONS_GET -> if (cmd.hasNotification()) onScreenOn(cmd.notification)
            NotificationCmd.SCREEN_ON_ON_NOTIFICATIONS_SET -> Log.d(TAG, "screen-on set ack, status=${cmd.status}")
        }
    }
}
