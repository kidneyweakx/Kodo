/*
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
 * Wires the Nitro `HybridBandLink` spec to the process-wide
 * [MiBand9BleDriver] (DriverHolder.driver). The paired band lives in
 * [BandStore] so it survives app restarts; activity sync is delegated to
 * [ActivitySync].
 */
package com.margelo.nitro.miband9active

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.BandStore
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.PhoneRinger
import com.kidneyweakx.miband9active.sync.ActivitySync
import com.kidneyweakx.miband9active.sync.MiBand9PeriodicSyncWorker
import com.kidneyweakx.miband9active.xiaomi.auth.XiaomiCrypto
import com.kidneyweakx.miband9active.xiaomi.protocol.BandLinkException
import com.kidneyweakx.miband9active.xiaomi.protocol.MiBand9BleDriver
import com.kidneyweakx.miband9active.xiaomi.services.SystemCommands
import com.margelo.nitro.core.Promise
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class HybridBandLink : HybridHybridBandLinkSpec() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val driver: MiBand9BleDriver get() = DriverHolder.driver

    @Volatile private var _connectionState: ConnectionState = ConnectionState.DISCONNECTED
    @Volatile private var scanning = false

    private val connectionListeners = CopyOnWriteArrayList<(ConnectionState) -> Unit>()
    private val batteryListeners = CopyOnWriteArrayList<(BatteryInfo) -> Unit>()
    private val scanListeners = CopyOnWriteArrayList<(DiscoveredBand) -> Unit>()
    private val syncListeners = CopyOnWriteArrayList<(SyncProgress) -> Unit>()

    private class ActiveScan(
        val scanner: BluetoothLeScanner,
        val callback: ScanCallback,
        val done: CompletableDeferred<Unit>,
    )

    @Volatile private var activeScan: ActiveScan? = null

    init {
        // The driver singleton outlives any Hybrid instance; subscribe once here.
        scope.launch {
            driver.state.collect { st ->
                _connectionState = mapState(st)
                if (!scanning) notifyConnectionState()
            }
        }
        scope.launch {
            driver.battery.collect { reading ->
                val info = reading?.toBatteryInfo() ?: return@collect
                batteryListeners.forEach { safeInvoke { it(info) } }
            }
        }
        scope.launch {
            // XiaomiSystemService.handleCommand CMD_FIND_PHONE.
            driver.incoming.collect { msg ->
                if (msg.type != SystemCommands.COMMAND_TYPE || msg.subtype != SystemCommands.CMD_FIND_PHONE) return@collect
                if (!msg.command.hasSystem()) return@collect
                val op = msg.command.system.findDevice
                Log.i(TAG, "find phone op=$op (0=start)")
                if (op == 0) PhoneRinger.start() else PhoneRinger.stop()
            }
        }
    }

    // ------------------------------------------------------------------ state

    override val connectionState: ConnectionState
        get() = if (scanning) ConnectionState.SCANNING else _connectionState

    override val currentBand: Variant_NullType_PairedBand
        get() = BandStore.load()?.let { Variant_NullType_PairedBand.create(it.toPairedBand()) } ?: Defaults.BAND

    override val battery: Variant_NullType_BatteryInfo
        get() = driver.battery.value?.let { Variant_NullType_BatteryInfo.create(it.toBatteryInfo()) } ?: Defaults.BATTERY

    // ------------------------------------------------------------------ scan

    @SuppressLint("MissingPermission")
    override fun scan(options: BandLinkScanOptions): Promise<Array<DiscoveredBand>> = Promise.async {
        val durationMs = (options.durationMs ?: 12_000.0).toLong().coerceIn(1_000L, 15_000L)
        val adapter = requireEnabledAdapter()
        val scanner = adapter.bluetoothLeScanner
            ?: throw BandLinkException(BandLinkException.BT_OFF, "BLE scanner unavailable (Bluetooth off?)")

        // Stop any previous scan first.
        stopScanInternal()

        val results = java.util.Collections.synchronizedList(mutableListOf<DiscoveredBand>())
        val seen = ConcurrentHashMap.newKeySet<String>()
        val done = CompletableDeferred<Unit>()

        fun offer(address: String, name: String?, rssi: Int) {
            if (name == null || !BAND_NAME_REGEX.containsMatchIn(name)) return
            if (!seen.add(address)) return
            val band = DiscoveredBand(id = address, name = name, rssi = rssi.toDouble())
            Log.i(TAG, "scan matched $name $address rssi=$rssi")
            results += band
            scanListeners.forEach { safeInvoke { it(band) } }
        }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val deviceName = try { result.device?.name } catch (_: SecurityException) { null }
                val name = deviceName ?: result.scanRecord?.deviceName
                offer(result.device.address, name, result.rssi)
            }
            override fun onBatchScanResults(batch: MutableList<ScanResult>) {
                batch.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "scan failed errorCode=$errorCode")
                done.complete(Unit)
            }
        }

        // DiscoveryActivityV2.startDiscovery: a band bonded + connected to another
        // app doesn't advertise, so pre-populate from the system bonded list.
        try {
            adapter.bondedDevices?.forEach { offer(it.address, it.name, 0) }
        } catch (e: SecurityException) {
            throw BandLinkException.from(e)
        }

        // The stored band may be connected to *us* right now (not advertising either).
        BandStore.load()?.let { stored -> if (driver.isConnected()) offer(stored.id, stored.name, 0) }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // foreground onboarding only, ≤15s (docs/POWER.md)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()
        try {
            scanner.startScan(null, settings, cb)
        } catch (e: SecurityException) {
            throw BandLinkException.from(e)
        }
        Log.i("MB9A_POWER", "BLE scan started for ${durationMs}ms")
        val scan = ActiveScan(scanner, cb, done)
        activeScan = scan
        scanning = true
        notifyConnectionState()
        try {
            withTimeoutOrNull(durationMs) { done.await() }
        } finally {
            if (activeScan === scan) activeScan = null
            try { scanner.stopScan(cb) } catch (_: Throwable) {}
            scanning = false
            notifyConnectionState()
            Log.i("MB9A_POWER", "BLE scan stopped, matched=${results.size}")
        }
        synchronized(results) { results.toTypedArray() }
    }

    override fun stopScan() {
        stopScanInternal()
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        val s = activeScan ?: return
        activeScan = null
        try { s.scanner.stopScan(s.callback) } catch (_: Throwable) {}
        // Resolves the pending scan() promise early with results so far.
        s.done.complete(Unit)
    }

    // ------------------------------------------------------------------ pairing

    override fun pair(deviceId: String, options: BandLinkPairOptions): Promise<PairedBand> = Promise.async {
        val keyHex = XiaomiCrypto.normalizeAuthKeyHex(options.authKey)
            ?: throw BandLinkException(BandLinkException.AUTH_KEY_INVALID, "auth key must be 32 hex characters")
        val key = XiaomiCrypto.parseAuthKey(keyHex)
            ?: throw BandLinkException(BandLinkException.AUTH_KEY_INVALID, "auth key must be 32 hex characters")
        stopScanInternal()
        val device = resolveDevice(deviceId)
        Log.i(TAG, "pair() ${device.address}")
        try {
            driver.connectAndAwait(device, key, PAIR_TIMEOUT_MS)
        } catch (e: Exception) {
            val coded = if (e is kotlinx.coroutines.CancellationException) throw e else BandLinkException.from(e)
            Log.w(TAG, "pair() failed: ${coded.message}")
            // Nothing was persisted. If a *different* band is stored, go back to it.
            val previous = BandStore.load()
            if (previous != null && !previous.id.equals(device.address, ignoreCase = true)) {
                scope.launch { runCatching { DriverHolder.ensureConnected(CONNECT_TIMEOUT_MS) } }
            }
            throw coded
        }
        val advertised = try { device.name } catch (_: SecurityException) { null }
        val name = advertised ?: "Mi Band 9 Active"
        val stored = BandStore.StoredBand(
            id = device.address,
            name = name,
            authKeyHex = keyHex,
            pairedAtIso = Instant.now().toString(),
        )
        BandStore.save(stored)
        if (BOND_AFTER_PAIR) createBondBestEffort(device)
        stored.toPairedBand()
    }

    override fun forget(): Promise<Unit> = Promise.async {
        val stored = BandStore.load()
        stopScanInternal()
        try {
            MiBand9PeriodicSyncWorker.disable(AppContext.context)
        } catch (t: Throwable) {
            Log.w(TAG, "disable periodic sync failed", t)
        }
        driver.forgetTarget()
        BandStore.clear()
        stored?.let { removeBondBestEffort(it.id) }
    }

    // ------------------------------------------------------------------ session

    override fun connect(): Promise<Unit> = Promise.async {
        DriverHolder.ensureConnected(CONNECT_TIMEOUT_MS)
        Unit
    }

    override fun disconnect() {
        driver.disconnect()
    }

    override fun setPeriodicSync(enabled: Boolean, intervalMinutes: Double) {
        val minutes = intervalMinutes.toLong().coerceAtLeast(MIN_PERIODIC_MINUTES)
        if (enabled) {
            MiBand9PeriodicSyncWorker.enable(AppContext.context, minutes)
        } else {
            MiBand9PeriodicSyncWorker.disable(AppContext.context)
        }
    }

    override fun requestBattery() {
        val drv = DriverHolder.current ?: return
        scope.launch {
            try {
                drv.sendCommand(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_BATTERY)
            } catch (t: Throwable) {
                Log.w(TAG, "requestBattery failed", t)
            }
        }
    }

    // ------------------------------------------------------------------ sync

    override fun syncSince(sinceIso: String): Promise<Double> = Promise.async {
        runSync().toDouble()
    }

    override fun fetchTodaySummary(): Promise<Variant_NullType_HealthDailySummary> = Promise.async {
        runSync()
        // Read what the sync persisted; null when the band had nothing for today.
        HybridHealthStore().getDailySummary(LocalDate.now().toString())
    }

    private suspend fun runSync(): Int = syncMutex.withLock {
        val startedAt = System.currentTimeMillis().toDouble()
        emitProgress("connecting", 0.0, startedAt)
        val drv = DriverHolder.ensureConnected(CONNECT_TIMEOUT_MS)
        val count = ActivitySync.run(drv) { phase, progress -> emitProgress(phase, progress, startedAt) }
        emitProgress("done", 1.0, startedAt)
        count
    }

    private fun emitProgress(phase: String, progress: Double, startedAt: Double) {
        val ev = SyncProgress(phase = phase, progress = progress.coerceIn(0.0, 1.0), startedAt = startedAt)
        syncListeners.forEach { safeInvoke { it(ev) } }
    }

    // ------------------------------------------------------------------ listeners

    override fun onConnectionStateChange(listener: (state: ConnectionState) -> Unit): () -> Unit {
        connectionListeners += listener
        safeInvoke { listener(connectionState) }
        return { connectionListeners -= listener }
    }

    override fun onBatteryChange(listener: (battery: BatteryInfo) -> Unit): () -> Unit {
        batteryListeners += listener
        driver.battery.value?.let { reading -> safeInvoke { listener(reading.toBatteryInfo()) } }
        return { batteryListeners -= listener }
    }

    override fun onScanResult(listener: (band: DiscoveredBand) -> Unit): () -> Unit {
        scanListeners += listener
        return { scanListeners -= listener }
    }

    override fun onSyncProgress(listener: (event: SyncProgress) -> Unit): () -> Unit {
        syncListeners += listener
        return { syncListeners -= listener }
    }

    // ------------------------------------------------------------------ internals

    private fun notifyConnectionState() {
        val st = connectionState
        connectionListeners.forEach { safeInvoke { it(st) } }
    }

    private inline fun safeInvoke(block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "listener threw", t) }
    }

    private fun requireEnabledAdapter(): BluetoothAdapter {
        val adapter = AppContext.context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw BandLinkException(BandLinkException.BT_OFF, "no Bluetooth adapter on this device")
        if (!adapter.isEnabled) throw BandLinkException(BandLinkException.BT_OFF, "Bluetooth is turned off")
        return adapter
    }

    private fun resolveDevice(deviceId: String): BluetoothDevice {
        val adapter = requireEnabledAdapter()
        return try {
            adapter.getRemoteDevice(deviceId.uppercase())
        } catch (e: IllegalArgumentException) {
            throw BandLinkException(BandLinkException.GATT, "invalid Bluetooth address '$deviceId'", e)
        }
    }

    /**
     * XiaomiCoordinator.getBondingStyle() = BONDING_STYLE_REQUIRE_KEY, which
     * DiscoveryActivityV2 follows with BondingUtil.initiateCorrectBonding →
     * createBond(). We bond *after* auth succeeds and never block pairing on it.
     */
    @SuppressLint("MissingPermission")
    private fun createBondBestEffort(device: BluetoothDevice) {
        try {
            if (device.bondState == BluetoothDevice.BOND_NONE) {
                val started = device.createBond()
                Log.i(TAG, "createBond(${device.address}) started=$started")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "createBond failed", t)
        }
    }

    /** BondingUtil.UnpairClassic: reflection removeBond() on a bonded device. */
    @SuppressLint("MissingPermission")
    private fun removeBondBestEffort(address: String) {
        try {
            val adapter = AppContext.context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            val device = adapter.getRemoteDevice(address)
            if (device.bondState == BluetoothDevice.BOND_NONE) return
            val removed = device.javaClass.getMethod("removeBond").invoke(device)
            Log.i(TAG, "removeBond($address) -> $removed")
        } catch (t: Throwable) {
            Log.w(TAG, "removeBond failed", t)
        }
    }

    private fun mapState(st: MiBand9BleDriver.State): ConnectionState = when (st) {
        MiBand9BleDriver.State.Disconnected -> ConnectionState.DISCONNECTED
        MiBand9BleDriver.State.Connecting -> ConnectionState.CONNECTING
        MiBand9BleDriver.State.Authenticating -> ConnectionState.AUTHENTICATING
        MiBand9BleDriver.State.Connected -> ConnectionState.CONNECTED
        is MiBand9BleDriver.State.Error -> ConnectionState.ERROR
    }

    private fun MiBand9BleDriver.BatteryReading.toBatteryInfo() = BatteryInfo(
        percent = level.toDouble(),
        charging = charging,
        updatedAt = Instant.ofEpochMilli(atMillis).toString(),
    )

    private fun BandStore.StoredBand.toPairedBand() = PairedBand(
        id = id,
        name = name,
        authKey = authKeyHex,
        pairedAt = pairedAtIso,
    )

    companion object {
        private const val TAG = "HybridBandLink"
        private const val PAIR_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val MIN_PERIODIC_MINUTES = 30L

        /** Mirror upstream REQUIRE_KEY → bond. Flip off if a device shows SMP trouble. */
        private const val BOND_AFTER_PAIR = true

        /** One sync at a time across all HybridBandLink calls. */
        private val syncMutex = Mutex()

        // Mi Band 9 Active advertises as "Xiaomi Smart Band 9 Active XXXX"
        // (sometimes without "Smart").
        private val BAND_NAME_REGEX = Regex("^Xiaomi( Smart)? Band 9 Active [0-9A-Fa-f]{4}$")
    }
}
