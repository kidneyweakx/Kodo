/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Wires the Nitro `HybridBandLink` spec to our [MiBand9BleDriver] engine.
 *  Lifecycle: one driver instance lives for the duration of a paired session.
 *  scan() / pair() / connect() / syncSince() / disconnect() are the public
 *  surface JS uses through Nitro.
 */
package com.margelo.nitro.miband9active

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.xiaomi.auth.XiaomiCrypto
import com.kidneyweakx.miband9active.xiaomi.protocol.MiBand9BleDriver
import com.kidneyweakx.miband9active.xiaomi.protocol.XiaomiUuids
import com.margelo.nitro.core.Promise
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class HybridBandLink : HybridHybridBandLinkSpec() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var driver: MiBand9BleDriver? = null
    private var stateJob: Job? = null

    @Volatile private var _connectionState: ConnectionState = ConnectionState.DISCONNECTED
    @Volatile private var _battery: BatteryInfo? = null
    @Volatile private var _band: PairedBand? = null

    private val connectionListeners = CopyOnWriteArrayList<(ConnectionState) -> Unit>()
    private val batteryListeners = CopyOnWriteArrayList<(BatteryInfo) -> Unit>()
    private val scanListeners = CopyOnWriteArrayList<(DiscoveredBand) -> Unit>()
    private val syncListeners = CopyOnWriteArrayList<(SyncProgress) -> Unit>()

    override val connectionState: ConnectionState get() = _connectionState
    override val currentBand: Variant_NullType_PairedBand
        get() = _band?.let { Variant_NullType_PairedBand.create(it) } ?: Defaults.BAND
    override val battery: Variant_NullType_BatteryInfo
        get() = _battery?.let { Variant_NullType_BatteryInfo.create(it) } ?: Defaults.BATTERY

    @SuppressLint("MissingPermission")
    override fun scan(options: BandLinkScanOptions): Promise<Array<DiscoveredBand>> = Promise.async {
        val durationMs = (options.durationMs ?: 12_000.0).toLong().coerceIn(1_000L, 15_000L)
        val ctx = AppContext.context
        val mgr = ctx.getSystemService(BluetoothManager::class.java)
            ?: return@async emptyArray<DiscoveredBand>()
        val adapter: BluetoothAdapter? = mgr.adapter
        val scanner = adapter?.bluetoothLeScanner ?: return@async emptyArray<DiscoveredBand>()

        val results = mutableListOf<DiscoveredBand>()
        val deferred = CompletableDeferred<Unit>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device?.name ?: return
                if (!BAND_NAME_REGEX.containsMatchIn(name)) return
                val band = DiscoveredBand(
                    id = result.device.address,
                    name = name,
                    rssi = result.rssi.toDouble(),
                )
                if (results.none { it.id == band.id }) {
                    results += band
                    scanListeners.forEach { it(band) }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                deferred.complete(Unit)
            }
        }

        _connectionState = ConnectionState.SCANNING
        notifyConnectionState()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(XiaomiUuids.SERVICE_V2))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()

        scanner.startScan(listOf(filter), settings, cb)
        kotlinx.coroutines.delay(durationMs)
        try { scanner.stopScan(cb) } catch (_: Throwable) {}

        _connectionState = ConnectionState.DISCONNECTED
        notifyConnectionState()
        results.toTypedArray()
    }

    override fun stopScan() {
        // The active scan completes by timeout in [scan]; explicit stop is a no-op
        // here because we don't share scanner state outside that coroutine.
    }

    @SuppressLint("MissingPermission")
    override fun pair(deviceId: String, options: BandLinkPairOptions): Promise<PairedBand> = Promise.async {
        val authKey = XiaomiCrypto.parseAuthKey(options.authKey)
            ?: throw IllegalArgumentException("Auth key must be 32 hex chars (with or without 0x prefix)")

        val mgr = AppContext.context.getSystemService(BluetoothManager::class.java)
            ?: throw IllegalStateException("Bluetooth manager unavailable")
        val device = mgr.adapter?.getRemoteDevice(deviceId)
            ?: throw IllegalStateException("Device $deviceId not reachable via adapter")

        val drv = MiBand9BleDriver(AppContext.context, authKey)
        driver = drv
        observeDriver(drv)
        drv.connect(device)

        // Wait until driver reports Connected or Error.
        val connectedDeferred = CompletableDeferred<MiBand9BleDriver.State>()
        scope.launch {
            drv.state.collect { st ->
                if (st is MiBand9BleDriver.State.Connected ||
                    st is MiBand9BleDriver.State.Error ||
                    st is MiBand9BleDriver.State.Disconnected) {
                    if (!connectedDeferred.isCompleted) connectedDeferred.complete(st)
                }
            }
        }
        val final = connectedDeferred.await()
        if (final !is MiBand9BleDriver.State.Connected) {
            throw IllegalStateException("Pair failed: $final")
        }

        val band = PairedBand(
            id = deviceId,
            name = device.name ?: "Mi Band 9 Active",
            authKey = options.authKey,
            pairedAt = java.time.Instant.now().toString(),
        )
        _band = band
        band
    }

    override fun forget(): Promise<Unit> = Promise.async {
        driver?.disconnect()
        driver?.close()
        driver = null
        _band = null
        _battery = null
    }

    override fun connect(): Promise<Unit> = Promise.async {
        val band = _band ?: throw IllegalStateException("No paired band")
        val authKey = XiaomiCrypto.parseAuthKey(band.authKey)
            ?: throw IllegalStateException("Stored auth key invalid")
        val mgr = AppContext.context.getSystemService(BluetoothManager::class.java) ?: return@async
        val device = mgr.adapter?.getRemoteDevice(band.id) ?: return@async
        if (driver == null) {
            driver = MiBand9BleDriver(AppContext.context, authKey).also { observeDriver(it) }
        }
        driver!!.connect(device)
    }

    override fun disconnect() {
        driver?.disconnect()
    }

    override fun syncSince(sinceIso: String): Promise<Double> = Promise.async {
        // TODO: drive XiaomiActivityFileFetcher via driver.activityChunks and
        //       replay XiaomiHealthService get-activity commands. Stub returns 0
        //       so the JS side gets a deterministic answer.
        0.0
    }

    override fun fetchTodaySummary(): Promise<Variant_NullType_HealthDailySummary> = Promise.async {
        Defaults.SUMMARY
    }

    override fun onConnectionStateChange(listener: (state: ConnectionState) -> Unit): () -> Unit {
        connectionListeners += listener
        listener(_connectionState)
        return { connectionListeners -= listener }
    }

    override fun onBatteryChange(listener: (battery: BatteryInfo) -> Unit): () -> Unit {
        batteryListeners += listener
        _battery?.let(listener)
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

    // --------------------------------------------------------------- internals

    private fun observeDriver(drv: MiBand9BleDriver) {
        stateJob?.cancel()
        stateJob = scope.launch {
            drv.state.collect { st ->
                _connectionState = when (st) {
                    MiBand9BleDriver.State.Disconnected -> ConnectionState.DISCONNECTED
                    MiBand9BleDriver.State.Connecting -> ConnectionState.CONNECTING
                    MiBand9BleDriver.State.Authenticating -> ConnectionState.AUTHENTICATING
                    MiBand9BleDriver.State.Connected -> ConnectionState.CONNECTED
                    is MiBand9BleDriver.State.Error -> ConnectionState.ERROR
                }
                notifyConnectionState()
            }
        }
    }

    private fun notifyConnectionState() {
        connectionListeners.forEach { it(_connectionState) }
    }

    companion object {
        private val BAND_NAME_REGEX = Regex("^Xiaomi( Smart)? Band 9 Active [0-9A-F]{4}$")
    }
}
