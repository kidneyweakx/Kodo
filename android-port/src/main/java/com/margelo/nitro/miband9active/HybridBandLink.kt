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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log
import com.kidneyweakx.miband9active.AppContext
import com.kidneyweakx.miband9active.DriverHolder
import com.kidneyweakx.miband9active.xiaomi.auth.XiaomiCrypto
import com.kidneyweakx.miband9active.xiaomi.protocol.MiBand9BleDriver
import com.margelo.nitro.core.Promise
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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
        Log.i(TAG, "scan() requested durationMs=$durationMs")
        val ctx = AppContext.context
        val mgr = ctx.getSystemService(BluetoothManager::class.java)
        if (mgr == null) {
            Log.e(TAG, "scan() abort: BluetoothManager is null")
            return@async emptyArray<DiscoveredBand>()
        }
        val adapter: BluetoothAdapter? = mgr.adapter
        if (adapter == null) {
            Log.e(TAG, "scan() abort: BluetoothAdapter is null (no BT hardware?)")
            return@async emptyArray<DiscoveredBand>()
        }
        Log.i(TAG, "scan() adapter.isEnabled=${adapter.isEnabled} state=${adapter.state}")
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "scan() abort: BluetoothLeScanner is null (BT off?)")
            return@async emptyArray<DiscoveredBand>()
        }

        val results = mutableListOf<DiscoveredBand>()
        val seenAddresses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val totalSeen = java.util.concurrent.atomic.AtomicInteger(0)
        val deferred = CompletableDeferred<Unit>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                totalSeen.incrementAndGet()
                val addr = result.device?.address ?: "??"
                val deviceName = result.device?.name
                val recordName = result.scanRecord?.deviceName
                val name = deviceName ?: recordName
                // First time we see this MAC, log it once with full diagnostic.
                if (seenAddresses.add(addr)) {
                    Log.d(
                        TAG,
                        "scan hit mac=$addr device.name=$deviceName scanRecord.name=$recordName rssi=${result.rssi}",
                    )
                }
                if (name == null) return
                if (!BAND_NAME_REGEX.containsMatchIn(name)) return
                val band = DiscoveredBand(
                    id = result.device.address,
                    name = name,
                    rssi = result.rssi.toDouble(),
                )
                if (results.none { it.id == band.id }) {
                    Log.i(TAG, "scan MATCHED Mi Band: name=$name mac=$addr rssi=${result.rssi}")
                    results += band
                    scanListeners.forEach { it(band) }
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.d(TAG, "scan onBatchScanResults size=${results.size}")
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "scan onScanFailed errorCode=$errorCode (see ScanCallback.SCAN_FAILED_*)")
                deferred.complete(Unit)
            }
        }

        _connectionState = ConnectionState.SCANNING
        notifyConnectionState()

        // Mirror Gadgetbridge's DiscoveryActivityV2.startDiscovery (lines
        // 307–323): a band that's already bonded + connected to *another*
        // app (e.g. Gadgetbridge, Mi Fitness) does not advertise, so plain
        // BLE scan never sees it. Pre-populate from the system bonded list
        // so the user can still pick it.
        try {
            val bonded = adapter.bondedDevices ?: emptySet()
            Log.i(TAG, "scan pre-populate: ${bonded.size} system-bonded devices")
            for (device in bonded) {
                val devName = device.name
                Log.d(TAG, "scan bonded mac=${device.address} name=$devName")
                if (devName == null) continue
                if (!BAND_NAME_REGEX.containsMatchIn(devName)) continue
                if (seenAddresses.add(device.address)) {
                    val band = DiscoveredBand(
                        id = device.address,
                        name = devName,
                        rssi = 0.0,
                    )
                    Log.i(TAG, "scan PRE-ADDED bonded Mi Band: $devName mac=${device.address}")
                    results += band
                    scanListeners.forEach { it(band) }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "scan bondedDevices threw SecurityException — missing BLUETOOTH_CONNECT?", e)
        }

        // Match Gadgetbridge's DiscoveryActivityV2.startBTLEDiscovery:
        //   - null filter (Mi Band 9 Active doesn't advertise SERVICE_V2 UUID in
        //     the scan record, so filtering by it returns 0 hits)
        //   - LOW_LATENCY + AGGRESSIVE match for snappy discovery
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()

        Log.i(TAG, "scan startScan(null filter, LOW_LATENCY/AGGRESSIVE) for ${durationMs}ms")
        try {
            scanner.startScan(null, settings, cb)
        } catch (e: SecurityException) {
            Log.e(TAG, "scan startScan SecurityException — missing BLUETOOTH_SCAN at runtime?", e)
            _connectionState = ConnectionState.DISCONNECTED
            notifyConnectionState()
            return@async emptyArray<DiscoveredBand>()
        } catch (e: Throwable) {
            Log.e(TAG, "scan startScan threw", e)
            _connectionState = ConnectionState.DISCONNECTED
            notifyConnectionState()
            return@async emptyArray<DiscoveredBand>()
        }
        kotlinx.coroutines.delay(durationMs)
        try { scanner.stopScan(cb) } catch (t: Throwable) {
            Log.w(TAG, "scan stopScan threw (probably already stopped)", t)
        }

        Log.i(
            TAG,
            "scan complete: totalAdvertisements=${totalSeen.get()} uniqueDevices=${seenAddresses.size} matched=${results.size}",
        )
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
        Log.i(TAG, "pair() device=$deviceId authKey.length=${options.authKey.length}")
        val authKey = XiaomiCrypto.parseAuthKey(options.authKey)
            ?: throw IllegalArgumentException("Auth key must be 32 hex chars (with or without 0x prefix)")

        val mgr = AppContext.context.getSystemService(BluetoothManager::class.java)
            ?: throw IllegalStateException("Bluetooth manager unavailable")
        val device = mgr.adapter?.getRemoteDevice(deviceId)
            ?: throw IllegalStateException("Device $deviceId not reachable via adapter")

        // If a previous pair attempt is in flight, tear it down first so we
        // don't end up with two GATT connections fighting for the band.
        driver?.let {
            Log.w(TAG, "pair(): tearing down previous driver before re-pair")
            it.disconnect()
            it.close()
        }

        val drv = MiBand9BleDriver(AppContext.context, authKey)
        driver = drv
        DriverHolder.current = drv
        observeDriver(drv)
        drv.connect(device)

        // Wait until driver reports Connected or Error, but cap at 30s so the
        // UI never hangs forever on a silent auth stall.
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
        val final = try {
            withTimeout(30_000) { connectedDeferred.await() }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "pair() timed out after 30s — driver state=${drv.state.value}")
            drv.disconnect()
            throw IllegalStateException("Pair timed out (band may be paired with another app)")
        }
        Log.i(TAG, "pair() final driver state = $final")
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
        DriverHolder.current = null
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
            DriverHolder.current = driver
        }
        driver!!.connect(device)
    }

    override fun disconnect() {
        driver?.disconnect()
    }

    /** Enable/disable the WorkManager-driven 30-min background sync. */
    fun setPeriodicSync(enabled: Boolean, intervalMinutes: Long = 30) {
        if (enabled) {
            com.kidneyweakx.miband9active.sync.MiBand9PeriodicSyncWorker.enable(AppContext.context, intervalMinutes)
        } else {
            com.kidneyweakx.miband9active.sync.MiBand9PeriodicSyncWorker.disable(AppContext.context)
        }
    }

    override fun syncSince(sinceIso: String): Promise<Double> = Promise.async {
        val drv = driver ?: throw IllegalStateException("Not connected")
        val fetcher = com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileFetcher()
        val filesParsed = java.util.concurrent.atomic.AtomicInteger(0)
        val pendingFiles = java.util.concurrent.ConcurrentLinkedDeque<com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileId>()
        val currentFileDone = kotlinx.coroutines.CompletableDeferred<Unit>().apply { complete(Unit) }
        val activeFile = java.util.concurrent.atomic.AtomicReference<com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileId?>(null)
        val activeDone = java.util.concurrent.atomic.AtomicReference(currentFileDone)
        val pastDone = kotlinx.coroutines.CompletableDeferred<Unit>()

        fetcher.onFile = { parsed ->
            when (parsed) {
                is com.kidneyweakx.miband9active.xiaomi.activity.ParsedActivityFile.DailySamples -> {
                    com.kidneyweakx.miband9active.SampleStore.persistActivity(parsed.samples)
                    filesParsed.incrementAndGet()
                }
                is com.kidneyweakx.miband9active.xiaomi.activity.ParsedActivityFile.Sleep -> {
                    val dayIso = parsed.fileId.timestamp.toInstant().toString().substring(0, 10)
                    com.kidneyweakx.miband9active.SampleStore.persistSleep(
                        dayIso,
                        parsed.sleep.summary,
                        parsed.sleep.stages,
                    )
                    filesParsed.incrementAndGet()
                }
                is com.kidneyweakx.miband9active.xiaomi.activity.ParsedActivityFile.Workout -> {
                    com.kidneyweakx.miband9active.SampleStore.persistWorkout(parsed.fileId, parsed.fields)
                    filesParsed.incrementAndGet()
                }
                is com.kidneyweakx.miband9active.xiaomi.activity.ParsedActivityFile.Unknown -> Unit
            }
            // Mark the active file's chunk-stream as drained.
            activeDone.get().takeIf { !it.isCompleted }?.complete(Unit)
        }

        // Subscribe to ACTIVITY-channel chunks BEFORE sending any request so
        // we don't drop the first packet.
        val chunkCollector = scope.launch {
            drv.activityChunks.collect { chunk ->
                fetcher.addChunk(chunk)
            }
        }

        // Subscribe to PROTOBUF-channel responses (type=8) for the file-id
        // list. Mirrors XiaomiHealthService.handleActivityFetchResponse.
        val fileIdCollector = scope.launch {
            drv.incoming.collect { msg ->
                if (msg.type != com.kidneyweakx.miband9active.xiaomi.services.HealthCommands.COMMAND_TYPE) return@collect
                when (msg.subtype) {
                    com.kidneyweakx.miband9active.xiaomi.services.HealthCommands.CMD_ACTIVITY_FETCH_TODAY,
                    com.kidneyweakx.miband9active.xiaomi.services.HealthCommands.CMD_ACTIVITY_FETCH_PAST -> {
                        val raw = msg.command.health.activityRequestFileIds.toByteArray()
                        Log.i(TAG, "syncSince: got ${raw.size / 7} file IDs (subtype=${msg.subtype})")
                        if (raw.size % 7 == 0) {
                            val buf = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            while (buf.position() < buf.limit()) {
                                val id = com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileId.from(buf)
                                if (id.timestamp.time != 0L || id.version != 0) {
                                    pendingFiles.addLast(id)
                                }
                            }
                        }
                        if (msg.subtype == com.kidneyweakx.miband9active.xiaomi.services.HealthCommands.CMD_ACTIVITY_FETCH_PAST) {
                            pastDone.complete(Unit)
                        }
                    }
                }
            }
        }

        try {
            // 1) Ask the band for today's file IDs.
            sendFetchToday(drv)

            // 2) Drain the queue. We process today's IDs as they arrive; if no
            //    more files are pending after a short wait, also kick the past
            //    fetch. Cap at 120s total.
            val deadline = System.currentTimeMillis() + 120_000L
            var requestedPast = false
            while (System.currentTimeMillis() < deadline) {
                val fileId = pendingFiles.pollFirst()
                if (fileId != null) {
                    activeFile.set(fileId)
                    val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                    activeDone.set(done)
                    Log.i(TAG, "syncSince: requesting file $fileId")
                    requestRecordedData(drv, fileId)
                    val finished = try { kotlinx.coroutines.withTimeoutOrNull(15_000) { done.await() } } catch (_: Throwable) { null }
                    if (finished == null) {
                        Log.w(TAG, "syncSince: timeout waiting for file $fileId chunks; skipping")
                    }
                    ackRecordedData(drv, fileId)
                    continue
                }
                // No pending files at this instant. If we haven't asked for
                // past data yet, do so now.
                if (!requestedPast) {
                    Log.i(TAG, "syncSince: today's queue drained, requesting past")
                    requestedPast = true
                    sendFetchPast(drv)
                    // Wait briefly for PAST response to populate queue.
                    kotlinx.coroutines.withTimeoutOrNull(3_000) { pastDone.await() }
                    continue
                }
                // Past was requested and queue is still empty → all done.
                break
            }
        } finally {
            chunkCollector.cancel()
            fileIdCollector.cancel()
        }
        Log.i(TAG, "syncSince: finished, filesParsed=${filesParsed.get()}")
        filesParsed.get().toDouble()
    }

    private suspend fun sendFetchToday(drv: MiBand9BleDriver) {
        drv.sendCommand(
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command.newBuilder()
                .setType(com.kidneyweakx.miband9active.xiaomi.services.HealthCommands.COMMAND_TYPE)
                .setSubtype(com.kidneyweakx.miband9active.xiaomi.services.HealthCommands.CMD_ACTIVITY_FETCH_TODAY)
                .setHealth(
                    nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Health.newBuilder()
                        .setActivitySyncRequestToday(
                            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.ActivitySyncRequestToday
                                .newBuilder().setUnknown1(0).build(),
                        ),
                )
                .build(),
        )
    }

    private suspend fun sendFetchPast(drv: MiBand9BleDriver) {
        drv.sendCommand(
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command.newBuilder()
                .setType(com.kidneyweakx.miband9active.xiaomi.services.HealthCommands.COMMAND_TYPE)
                .setSubtype(com.kidneyweakx.miband9active.xiaomi.services.HealthCommands.CMD_ACTIVITY_FETCH_PAST)
                .build(),
        )
    }

    private suspend fun requestRecordedData(
        drv: MiBand9BleDriver,
        fileId: com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileId,
    ) {
        drv.sendCommand(
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command.newBuilder()
                .setType(com.kidneyweakx.miband9active.xiaomi.services.HealthCommands.COMMAND_TYPE)
                .setSubtype(com.kidneyweakx.miband9active.xiaomi.services.HealthCommands.CMD_ACTIVITY_FETCH_REQUEST)
                .setHealth(
                    nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Health.newBuilder()
                        .setActivityRequestFileIds(
                            com.google.protobuf.ByteString.copyFrom(fileId.toBytes()),
                        ),
                )
                .build(),
        )
    }

    /** Tell the band we got this file so it can purge it from on-band flash. */
    private suspend fun ackRecordedData(
        drv: MiBand9BleDriver,
        fileId: com.kidneyweakx.miband9active.xiaomi.activity.XiaomiActivityFileId,
    ) {
        drv.sendCommand(
            nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Command.newBuilder()
                .setType(com.kidneyweakx.miband9active.xiaomi.services.HealthCommands.COMMAND_TYPE)
                .setSubtype(com.kidneyweakx.miband9active.xiaomi.services.HealthCommands.CMD_ACTIVITY_FETCH_ACK)
                .setHealth(
                    nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto.Health.newBuilder()
                        .setActivitySyncAckFileIds(
                            com.google.protobuf.ByteString.copyFrom(fileId.toBytes()),
                        ),
                )
                .build(),
        )
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
        // System events: battery + find_phone, both come on (type=2). Mirrors
        // XiaomiSystemService.handleCommand.
        scope.launch {
            drv.incoming.collect { msg ->
                if (msg.type != com.kidneyweakx.miband9active.xiaomi.services.SystemCommands.COMMAND_TYPE) return@collect
                when (msg.subtype) {
                    com.kidneyweakx.miband9active.xiaomi.services.SystemCommands.CMD_BATTERY -> {
                        val battery = msg.command.system.power.battery
                        val percent = battery.level.toDouble()
                        val charging = battery.state == 1
                        val info = BatteryInfo(
                            percent = percent,
                            charging = charging,
                            updatedAt = java.time.Instant.now().toString(),
                        )
                        _battery = info
                        Log.i(TAG, "battery update: $percent% charging=$charging")
                        batteryListeners.forEach { it(info) }
                    }
                    com.kidneyweakx.miband9active.xiaomi.services.SystemCommands.CMD_FIND_PHONE -> {
                        if (msg.command.hasSystem()) {
                            val op = msg.command.system.findDevice
                            Log.i(TAG, "find phone op=$op (0=start)")
                            if (op == 0) {
                                com.kidneyweakx.miband9active.PhoneRinger.start()
                            } else {
                                com.kidneyweakx.miband9active.PhoneRinger.stop()
                            }
                        }
                    }
                }
            }
        }
    }

    /** Trigger a fresh battery query (idempotent). */
    fun requestBattery() {
        val drv = driver ?: return
        scope.launch {
            try {
                drv.sendCommand(
                    com.kidneyweakx.miband9active.xiaomi.services.SystemCommands.COMMAND_TYPE,
                    com.kidneyweakx.miband9active.xiaomi.services.SystemCommands.CMD_BATTERY,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "requestBattery failed", t)
            }
        }
    }

    private fun notifyConnectionState() {
        connectionListeners.forEach { it(_connectionState) }
    }

    companion object {
        private const val TAG = "HybridBandLink"
        // Mi Band 9 Active advertises as "Xiaomi Band 9 Active XXXX" (no
        // "Smart" in the advertised name on this model). Keep loose so we
        // also match the Gadgetbridge naming variant.
        private val BAND_NAME_REGEX = Regex("^Xiaomi( Smart)? Band 9 Active [0-9A-Fa-f]{4}$")
    }
}
