/*  Copyright (C) 2023-2026 Andreas Shimokawa, José Rebelo, Yoran Vulker   (Gadgetbridge Xiaomi V2 protocol + auth)
 *  Copyright (C) 2026 kidneyweakx                                          (Kotlin port + coroutine rewrite)
 *
 * mi-band-9-active — a slim Mi Band 9 Active companion app
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
 *  V2-only BLE driver for Mi Band 9 Active. Replaces Gadgetbridge's
 *  BtLEQueue + TransactionBuilder + XiaomiBleProtocolV2 + XiaomiAuthService
 *  with plain BluetoothGatt callbacks (serialised on one HandlerThread), a
 *  frame-ordered write queue, and Flow-based state/event streams.
 *
 *  Process-wide singleton (owned by DriverHolder): [state], [incoming] and
 *  [activityChunks] live as long as the process so subscribers never go
 *  stale across reconnects. Every new link resets the per-connection state
 *  (sequence counter, packet accumulator, auth session) like
 *  XiaomiBleProtocolV2.reset() + XiaomiAuthService.startEncryptedHandshake().
 *
 *  Power: no scanning, no wake locks, no foreground service. After an
 *  unexpected drop we re-arm a *passive* `connectGatt(autoConnect = true)`
 *  (the controller-side background connection Gadgetbridge also relies on via
 *  BluetoothGatt.connect(), BtLEQueue.handleDisconnected) while the process
 *  lives. docs/POWER.md.
 */
package com.kidneyweakx.miband9active.xiaomi.protocol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.protobuf.ByteString
import com.kidneyweakx.miband9active.xiaomi.auth.XiaomiAuthSession
import com.kidneyweakx.miband9active.xiaomi.services.SystemCommands
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

private const val TAG = "MiBand9BleDriver"
private const val POWER_TAG = "MB9A_POWER"

class MiBand9BleDriver(context: Context) {

    private val appContext: Context = context.applicationContext

    sealed class State {
        data object Disconnected : State()
        data object Connecting : State()
        data object Authenticating : State()
        data object Connected : State()
        data class Error(val message: String) : State()
    }

    data class IncomingCommand(val type: Int, val subtype: Int, val command: XiaomiProto.Command)

    /** Latest battery reading reported by the band (CMD_BATTERY / device state). */
    data class BatteryReading(val level: Int, val charging: Boolean, val atMillis: Long)

    // ------------------------------------------------------------- streams

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingCommand>(extraBufferCapacity = 256)
    val incoming: SharedFlow<IncomingCommand> = _incoming.asSharedFlow()

    /** Raw decrypted ACTIVITY-channel (raw 5) payloads — file bytes, NOT protobuf. */
    private val _activityChunks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 2048)
    val activityChunks: SharedFlow<ByteArray> = _activityChunks.asSharedFlow()

    private val _battery = MutableStateFlow<BatteryReading?>(null)
    val battery: StateFlow<BatteryReading?> = _battery.asStateFlow()

    private val _deviceInfo = MutableStateFlow<XiaomiProto.DeviceInfo?>(null)
    val deviceInfo: StateFlow<XiaomiProto.DeviceInfo?> = _deviceInfo.asStateFlow()

    // ------------------------------------------------ GATT thread + link state

    /** All BluetoothGattCallback invocations and GATT lifecycle ops run here, serialised. */
    private val gattThread = HandlerThread("MB9A-gatt").also { it.start() }
    private val gattHandler = Handler(gattThread.looper)

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var txChar: BluetoothGattCharacteristic? = null
    @Volatile private var maxWriteSize = DEFAULT_WRITE_CHUNK
    @Volatile private var authSession: XiaomiAuthSession? = null
    /** True only after CMD_AUTH succeeded on the current link. Gates every encrypted send. */
    @Volatile private var sessionReady = false
    /** Bumped on every link open/close; stale queued frames and callbacks are dropped. */
    @Volatile private var linkGen = 0
    private var linkUp = false
    private var servicesRequested = false
    private val accumulator = V2PacketAccumulator()
    private val sequenceCounter = AtomicInteger(0)
    private val sendLock = Any()

    // --------------------------------------------------- target + reconnect policy

    @Volatile private var targetDevice: BluetoothDevice? = null
    @Volatile private var targetKey: ByteArray? = null
    /** Armed after a successful auth; cleared by user disconnect / auth rejection / target change. */
    @Volatile private var autoReconnect = false
    @Volatile private var passive = false
    @Volatile private var passiveFailures = 0
    private var retriedTransient = false

    private val attemptLock = Any()
    /** Waiter for the current *active* connect attempt (pair/connect/ensureConnected). */
    @Volatile private var attempt: CompletableDeferred<Unit>? = null

    // ------------------------------------------------------------- write queue

    private class OutFrame(val gen: Int, val bytes: ByteArray)

    private val writeQueue = Channel<OutFrame>(capacity = Channel.UNLIMITED)
    private val writeAck = Channel<Int>(capacity = Channel.CONFLATED)

    init {
        scope.launch { pumpWrites() }
        registerAdapterReceiver()
    }

    // ================================================================ public API

    fun isConnected(): Boolean = sessionReady && _state.value is State.Connected

    /** Address of the band the driver is (or was last) targeting. */
    val targetAddress: String? get() = targetDevice?.address

    /**
     * Start a direct (active) connection + V2 auth to [device] with [authKey16].
     * Non-suspending; observe [state] or use [connectAndAwait].
     */
    fun connect(device: BluetoothDevice, authKey16: ByteArray) {
        startAttempt(device, authKey16, keepReconnecting = false)
    }

    /**
     * Connect + authenticate, resolving once [State.Connected]. Throws
     * [BandLinkException] (message starts with the error code) on failure; on
     * failure the GATT client is fully closed.
     *
     * [keepReconnecting]: the key is already known-good (stored band) — on
     * failure, fall back to a passive autoConnect so the band reconnects by
     * itself when it comes back in range. Never set for a first-time pair.
     */
    suspend fun connectAndAwait(
        device: BluetoothDevice,
        authKey16: ByteArray,
        timeoutMs: Long = 30_000,
        keepReconnecting: Boolean = false,
    ) {
        require(authKey16.size == 16) { "auth key must be 16 bytes" }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw BandLinkException(BandLinkException.BT_OFF, "no Bluetooth adapter on this device")
        val enabled = try { adapter.isEnabled } catch (e: SecurityException) { throw BandLinkException.from(e) }
        if (!enabled) throw BandLinkException(BandLinkException.BT_OFF, "Bluetooth is turned off")

        val d = startAttempt(device, authKey16, keepReconnecting)
        val ok = withTimeoutOrNull(timeoutMs) { d.await(); true }
        if (ok == true) return
        // Timed out. Classify by how far we got, then tear down.
        val detail = if (linkUpSeenForAttempt) {
            "band connected but never completed the handshake (is it paired with Mi Fitness / another app?)"
        } else {
            "band never answered (out of range, asleep, or connected to another app)"
        }
        val ex = BandLinkException(BandLinkException.TIMEOUT, detail)
        failAttemptIfCurrent(d, ex)
        throw ex
    }

    /** Suspend until connected or [timeoutMs] elapses. Returns true when connected. */
    suspend fun awaitConnected(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { state.first { it is State.Connected }; true } ?: false

    /** User-initiated disconnect. Disarms passive auto-reconnect. */
    fun disconnect() {
        Log.i(TAG, "disconnect() requested by user")
        autoReconnect = false
        val a = takeAttempt()
        a?.completeExceptionally(BandLinkException(BandLinkException.GATT, "disconnect requested"))
        gattHandler.post {
            gattHandler.removeCallbacksAndMessages(RECONNECT_TOKEN)
            closeGatt("user disconnect")
            _state.value = State.Disconnected
        }
    }

    /** Same as [disconnect]; the singleton itself is never torn down. */
    fun close() = disconnect()

    /** Forget the current target entirely (used by forget()). */
    fun forgetTarget() {
        disconnect()
        targetDevice = null
        targetKey = null
        _battery.value = null
        _deviceInfo.value = null
    }

    /** Send a Command frame on the encrypted PROTOBUF channel. Dropped (logged) if not authenticated. */
    suspend fun sendCommand(type: Int, subtype: Int, body: XiaomiProto.Command.Builder.() -> Unit = {}) {
        val builder = XiaomiProto.Command.newBuilder().setType(type).setSubtype(subtype)
        builder.body()
        sendCommand(builder.build())
    }

    suspend fun sendCommand(command: XiaomiProto.Command) {
        if (command.type == AUTH_COMMAND_TYPE) {
            // XiaomiBleProtocolV2.sendCommand: auth commands go on Channel.Authentication (raw 1, plaintext).
            enqueueData(command.toByteArray(), XiaomiChannel.PROTOBUF, encrypt = false, requireSession = false)
        } else {
            enqueueData(command.toByteArray(), XiaomiChannel.PROTOBUF, encrypt = true, requireSession = true)
        }
    }

    /** Plaintext DATA-channel write for chunked file uploads. */
    suspend fun sendData(bytes: ByteArray) {
        enqueueData(bytes, XiaomiChannel.DATA, encrypt = false, requireSession = true)
    }

    // ============================================================ attempt control

    @Volatile private var linkUpSeenForAttempt = false

    private fun sameTarget(device: BluetoothDevice, key: ByteArray): Boolean =
        targetDevice?.address.equals(device.address, ignoreCase = true) &&
            targetKey?.contentEquals(key) == true

    private fun startAttempt(device: BluetoothDevice, key: ByteArray, keepReconnecting: Boolean): CompletableDeferred<Unit> {
        synchronized(attemptLock) {
            val same = sameTarget(device, key)
            if (same && isConnected()) {
                return CompletableDeferred(Unit)
            }
            val cur = attempt
            if (cur != null && !cur.isCompleted) {
                if (same) return cur
                cur.completeExceptionally(BandLinkException(BandLinkException.GATT, "superseded by a new connect request"))
            }
            if (!same) {
                // New band or new key: never auto-reconnect with stale credentials.
                autoReconnect = false
                passiveFailures = 0
            }
            if (keepReconnecting) autoReconnect = true
            targetDevice = device
            targetKey = key.copyOf()
            val d = CompletableDeferred<Unit>()
            attempt = d
            linkUpSeenForAttempt = false
            retriedTransient = false
            gattHandler.post {
                gattHandler.removeCallbacksAndMessages(RECONNECT_TOKEN)
                val g = gatt
                if (same && g != null && linkUp) {
                    // A passive link to the same band is already up and mid-handshake: adopt it.
                    Log.i(TAG, "startAttempt: adopting in-progress link to ${device.address}")
                    passive = false
                    linkUpSeenForAttempt = true
                } else {
                    openGatt(device, autoConnect = false)
                }
            }
            _state.value = State.Connecting
            return d
        }
    }

    private fun takeAttempt(): CompletableDeferred<Unit>? = synchronized(attemptLock) {
        val a = attempt
        attempt = null
        a?.takeIf { !it.isCompleted }
    }

    private fun failAttemptIfCurrent(d: CompletableDeferred<Unit>, ex: BandLinkException) {
        val mine = synchronized(attemptLock) {
            if (attempt === d) { attempt = null; true } else false
        }
        if (!mine) return
        d.completeExceptionally(ex)
        gattHandler.post {
            closeGatt("attempt failed: ${ex.message}")
            _state.value = State.Error(ex.message ?: ex.code)
            maybeSchedulePassive(ex)
        }
    }

    // ============================================================ GATT lifecycle (gattHandler thread)

    @SuppressLint("MissingPermission")
    private fun openGatt(device: BluetoothDevice, autoConnect: Boolean) {
        closeGatt("reopen")
        passive = autoConnect
        servicesRequested = false
        linkUp = false
        resetLinkState()
        Log.i(TAG, "openGatt(${device.address}, autoConnect=$autoConnect)")
        if (autoConnect) Log.i(POWER_TAG, "arming passive autoConnect reconnect to ${device.address}")
        val g = try {
            device.connectGatt(
                appContext,
                autoConnect,
                callback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                gattHandler,
            )
        } catch (e: SecurityException) {
            failLink(BandLinkException.from(e))
            return
        } catch (t: Throwable) {
            failLink(BandLinkException(BandLinkException.GATT, "connectGatt threw: ${t.message}", t))
            return
        }
        if (g == null) {
            failLink(BandLinkException(BandLinkException.GATT, "connectGatt returned null"))
            return
        }
        gatt = g
        if (!autoConnect) _state.value = State.Connecting
    }

    /** Close the current GATT client (always close → Android caps concurrent clients at ~30). */
    @SuppressLint("MissingPermission")
    private fun closeGatt(reason: String) {
        val g = gatt
        gatt = null
        txChar = null
        linkUp = false
        servicesRequested = false
        resetLinkState()
        gattHandler.removeCallbacksAndMessages(WATCHDOG_TOKEN)
        if (g != null) {
            Log.i(TAG, "closeGatt: $reason")
            try { g.disconnect() } catch (_: Throwable) {}
            try { g.close() } catch (_: Throwable) {}
        }
    }

    /** XiaomiBleProtocolV2.reset(): per-link counters, buffers, auth session. */
    private fun resetLinkState() {
        synchronized(sendLock) {
            linkGen++
            sequenceCounter.set(0)
            sessionReady = false
            authSession = null
            maxWriteSize = DEFAULT_WRITE_CHUNK
        }
        accumulator.reset()
        while (writeAck.tryReceive().isSuccess) Unit
    }

    /** Fail the current link (active attempt or passive), then maybe re-arm passive reconnect. */
    private fun failLink(ex: BandLinkException) {
        Log.w(TAG, "failLink: ${ex.message}")
        closeGatt("failLink")
        if (ex.code == BandLinkException.AUTH_REJECTED) autoReconnect = false
        val a = takeAttempt()
        _state.value = State.Error(ex.message ?: ex.code)
        a?.completeExceptionally(ex)
        maybeSchedulePassive(ex)
    }

    private fun maybeSchedulePassive(ex: BandLinkException?) {
        if (!autoReconnect) return
        if (ex != null && (ex.code == BandLinkException.AUTH_REJECTED || ex.code == BandLinkException.PERMISSION)) return
        passiveFailures++
        val delayMs = (PASSIVE_BASE_DELAY_MS shl (passiveFailures - 1).coerceAtMost(7)).coerceAtMost(PASSIVE_MAX_DELAY_MS)
        schedulePassive(delayMs)
    }

    private fun schedulePassive(delayMs: Long) {
        val device = targetDevice ?: return
        gattHandler.removeCallbacksAndMessages(RECONNECT_TOKEN)
        Log.i(TAG, "scheduling passive reconnect in ${delayMs}ms")
        postDelayed(RECONNECT_TOKEN, delayMs) {
            if (autoReconnect && gatt == null && attempt?.isCompleted != false && isAdapterEnabled()) {
                openGatt(device, autoConnect = true)
                if (gatt != null) _state.value = State.Disconnected
            }
        }
    }

    private fun onLinkLost(status: Int) {
        val wasReady = sessionReady
        val wasLinkUp = linkUp
        val wasPassive = passive
        closeGatt("link lost status=$status")
        val a = attempt
        if (a != null && !a.isCompleted) {
            // Active attempt failed before auth completed.
            if (!wasLinkUp && status in TRANSIENT_STATUSES && !retriedTransient) {
                // Classic status 133 on the first connectGatt: close + retry once.
                retriedTransient = true
                val device = targetDevice ?: return
                Log.w(TAG, "transient connect failure status=$status — retrying once")
                gattHandler.postDelayed({ if (attempt === a && !a.isCompleted) openGatt(device, autoConnect = false) }, 600)
                return
            }
            val ex = if (!wasLinkUp && status in TRANSIENT_STATUSES) {
                BandLinkException(BandLinkException.TIMEOUT, "band did not accept the connection (status=$status) — out of range or connected to another app?")
            } else if (!wasLinkUp) {
                BandLinkException(BandLinkException.GATT, "connection failed (status=$status)")
            } else {
                BandLinkException(BandLinkException.GATT, "link dropped during handshake (status=$status)")
            }
            failLink(ex)
            return
        }
        Log.i(TAG, "link lost (status=$status wasReady=$wasReady passive=$wasPassive)")
        _state.value = State.Disconnected
        if (autoReconnect) {
            if (wasReady) {
                passiveFailures = 0
                // BtLEQueue.handleDisconnected: healthy drop → immediate re-connect;
                // stack errors → give the stack time to settle.
                schedulePassive(if (status in UNHEALTHY_STATUSES) 5_000L else 1_000L)
            } else {
                maybeSchedulePassive(null)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
            if (g !== gatt) {
                Log.d(TAG, "ignoring callback from stale GATT client")
                try { g.close() } catch (_: Throwable) {}
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                linkUp = true
                linkUpSeenForAttempt = true
                resetLinkState()
                _state.value = State.Connecting
                armWatchdog()
                guarded {
                    // XiaomiBleProtocolV2.initializeDevice: requestMtu(512) → notify → session config.
                    if (!g.requestMtu(512)) {
                        Log.w(TAG, "requestMtu returned false — discovering services directly")
                        discoverServices(g)
                    } else {
                        // Some stacks never call back onMtuChanged; don't stall.
                        postDelayed(WATCHDOG_TOKEN, 3_000L) { if (g === gatt && !servicesRequested) discoverServices(g) }
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                onLinkLost(status)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged mtu=$mtu status=$status")
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                maxWriteSize = calcMaxWriteChunk(mtu)
            }
            if (!servicesRequested) discoverServices(g)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.i(TAG, "onServicesDiscovered status=$status services=${g.services?.size ?: 0}")
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failLink(BandLinkException(BandLinkException.GATT, "service discovery failed (status=$status)"))
                return
            }
            val service = g.getService(XiaomiUuids.SERVICE_V2)
            val rx = service?.getCharacteristic(XiaomiUuids.V2_CHARACTERISTIC_RX)
            val tx = service?.getCharacteristic(XiaomiUuids.V2_CHARACTERISTIC_TX)
            if (service == null || rx == null || tx == null) {
                Log.e(TAG, "V2 service/characteristics missing. Services: ${g.services?.map { it.uuid }}")
                // Not a V2 band (or wrong device) — don't loop reconnecting to it.
                autoReconnect = false
                failLink(BandLinkException(BandLinkException.GATT, "Xiaomi V2 service not found on this device"))
                return
            }
            txChar = tx
            guarded {
                g.setCharacteristicNotification(rx, true)
                val ccc = rx.getDescriptor(XiaomiUuids.CCC_DESCRIPTOR)
                if (ccc == null) {
                    Log.w(TAG, "RX has no CCC descriptor — starting session directly")
                    startSession()
                    return@guarded
                }
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(ccc)
                }
                if (!ok) failLink(BandLinkException(BandLinkException.GATT, "could not enable RX notifications"))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite uuid=${descriptor.uuid} status=$status")
            if (g !== gatt || descriptor.uuid != XiaomiUuids.CCC_DESCRIPTOR) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failLink(BandLinkException(BandLinkException.GATT, "enabling RX notifications failed (status=$status)"))
                return
            }
            // Only now is the stack free for our first characteristic write.
            startSession()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            writeAck.trySend(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (g !== gatt || characteristic.uuid != XiaomiUuids.V2_CHARACTERISTIC_RX) return
            onIncomingBytes(value)
        }

        @Deprecated("API <33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (g !== gatt || characteristic.uuid != XiaomiUuids.V2_CHARACTERISTIC_RX) return
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            onIncomingBytes(data.copyOf())
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(g: BluetoothGatt) {
        if (servicesRequested) return
        servicesRequested = true
        guarded {
            if (!g.discoverServices()) failLink(BandLinkException(BandLinkException.GATT, "discoverServices() refused"))
        }
    }

    /** Handshake watchdog: a link that never finishes auth is torn down (passive links re-armed). */
    private fun armWatchdog() {
        val gen = linkGen
        postDelayed(WATCHDOG_TOKEN, HANDSHAKE_TIMEOUT_MS) {
            if (gatt != null && !sessionReady && linkGen == gen) {
                failLink(BandLinkException(BandLinkException.TIMEOUT, "band connected but never completed the handshake"))
            }
        }
    }

    /** Handler.postDelayed(Runnable, Object, long) is API 28; postAtTime with a token works on our minSdk 26. */
    private fun postDelayed(token: Any, delayMs: Long, block: () -> Unit) {
        gattHandler.postAtTime(Runnable { block() }, token, SystemClock.uptimeMillis() + delayMs)
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            failLink(BandLinkException.from(e))
        } catch (t: Throwable) {
            failLink(BandLinkException(BandLinkException.GATT, t.message ?: t.javaClass.simpleName, t))
        }
    }

    // ============================================================ incoming (gattHandler thread)

    private fun onIncomingBytes(bytes: ByteArray) {
        for (p in accumulator.feed(bytes)) {
            try {
                handlePacket(p)
            } catch (t: Throwable) {
                Log.e(TAG, "handlePacket threw", t)
            }
        }
    }

    private fun handlePacket(packet: XiaomiSppPacketV2) {
        when (packet) {
            is XiaomiSppPacketV2.Ack -> Log.v(TAG, "← ack seq=${packet.sequenceNumber}")
            is XiaomiSppPacketV2.SessionConfig -> {
                // XiaomiBleProtocolV2.processPacket L314-318: any session config → startEncryptedHandshake().
                Log.i(TAG, "← session config opCode=${packet.opCode}")
                startEncryptedHandshake()
            }
            is XiaomiSppPacketV2.Data -> {
                try {
                    val plain = decryptIncoming(packet)
                    if (plain != null) {
                        when (packet.channel) {
                            XiaomiChannel.PROTOBUF -> handleCommandBytes(plain)
                            // Upstream: Channel.Activity → activityFetcher.addChunk(payload) (raw file bytes).
                            XiaomiChannel.ACTIVITY -> {
                                if (!_activityChunks.tryEmit(plain)) {
                                    Log.e(TAG, "activityChunks buffer full — dropped ${plain.size}B chunk")
                                }
                            }
                            else -> Log.w(TAG, "unhandled data packet on channel ${packet.channel}")
                        }
                    }
                } finally {
                    // Upstream acks AFTER handling (processPacket L321-327), even if handling threw.
                    sendAck(packet.sequenceNumber)
                }
            }
        }
    }

    private fun decryptIncoming(packet: XiaomiSppPacketV2.Data): ByteArray? {
        if (packet.opCode != XiaomiSppPacketV2.Data.OPCODE_SEND_ENCRYPTED) return packet.payload
        val sess = authSession
        if (sess == null || !sess.encryptionInitialised) {
            Log.w(TAG, "encrypted packet before keys were derived — dropping")
            return null
        }
        return sess.decryptV2(packet.payload)
    }

    private fun handleCommandBytes(plain: ByteArray) {
        val cmd = try {
            XiaomiProto.Command.parseFrom(plain)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to parse ${plain.size}B as protobuf Command", t)
            return
        }
        Log.d(TAG, "← cmd type=${cmd.type} subtype=${cmd.subtype}")
        if (cmd.type == AUTH_COMMAND_TYPE) {
            handleAuthCommand(cmd)
            return
        }
        if (cmd.type == SystemCommands.COMMAND_TYPE) trackSystemState(cmd)
        if (!_incoming.tryEmit(IncomingCommand(cmd.type, cmd.subtype, cmd))) {
            Log.w(TAG, "incoming buffer full — dropped cmd ${cmd.type}/${cmd.subtype}")
        }
    }

    /** Mirrors the bits of XiaomiSystemService.handleCommand the link layer itself cares about. */
    private fun trackSystemState(cmd: XiaomiProto.Command) {
        if (!cmd.hasSystem()) return
        val sys = cmd.system
        when (cmd.subtype) {
            SystemCommands.CMD_BATTERY -> if (sys.hasPower() && sys.power.hasBattery()) {
                val b = sys.power.battery
                val prev = _battery.value
                val charging = if (b.hasState()) chargingFromRaw(b.state) ?: prev?.charging ?: false else prev?.charging ?: false
                _battery.value = BatteryReading(b.level, charging, System.currentTimeMillis())
                Log.i(TAG, "battery ${b.level}% charging=$charging")
            }
            SystemCommands.CMD_DEVICE_INFO -> if (sys.hasDeviceInfo()) {
                _deviceInfo.value = sys.deviceInfo
                Log.i(TAG, "device info fw=${sys.deviceInfo.firmware} model=${sys.deviceInfo.model}")
            }
            SystemCommands.CMD_DEVICE_STATE_GET -> if (sys.hasBasicDeviceState()) {
                val s = sys.basicDeviceState
                val prev = _battery.value
                val level = if (s.hasBatteryLevel()) s.batteryLevel else prev?.level
                if (level != null) _battery.value = BatteryReading(level, s.isCharging, System.currentTimeMillis())
            }
            SystemCommands.CMD_DEVICE_STATE -> if (sys.hasDeviceState() && sys.deviceState.hasChargingState()) {
                val prev = _battery.value
                val charging = chargingFromRaw(sys.deviceState.chargingState)
                if (prev != null && charging != null) _battery.value = prev.copy(charging = charging, atMillis = System.currentTimeMillis())
            }
        }
    }

    // ============================================================ auth (XiaomiAuthService port)

    /** XiaomiAuthService.startEncryptedHandshake: fresh nonce every time. */
    private fun startEncryptedHandshake() {
        val key = targetKey ?: run {
            Log.e(TAG, "session config received but no auth key set")
            return
        }
        val sess = XiaomiAuthSession(key)
        synchronized(sendLock) {
            sessionReady = false
            authSession = sess
        }
        _state.value = State.Authenticating
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(AUTH_COMMAND_TYPE)
            .setSubtype(CMD_NONCE)
            .setAuth(
                XiaomiProto.Auth.newBuilder().setPhoneNonce(
                    XiaomiProto.PhoneNonce.newBuilder().setNonce(ByteString.copyFrom(sess.phoneNonce)).build(),
                ).build(),
            )
            .build()
        Log.i(TAG, "→ auth step 1 (phone nonce)")
        enqueueDataNow(cmd.toByteArray(), XiaomiChannel.PROTOBUF, encrypt = false)
    }

    private fun startSession() {
        _state.value = State.Authenticating
        val req = XiaomiSppPacketV2.SessionConfig(
            seq = 0, // XiaomiBleProtocolV2.initializeDevice hardcodes 0; doesn't bump the counter
            opCode = XiaomiSppPacketV2.SessionConfig.OPCODE_START_SESSION_REQUEST,
        )
        Log.i(TAG, "→ START_SESSION_REQUEST")
        enqueueRaw(req.encode(null))
    }

    private fun handleAuthCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CMD_NONCE -> {
                if (!cmd.hasAuth() || !cmd.auth.hasWatchNonce()) {
                    failLink(
                        BandLinkException(
                            BandLinkException.AUTH_REJECTED,
                            "band refused the handshake (status=${cmd.status}, authStatus=${cmd.auth.status})",
                        ),
                    )
                    return
                }
                val sess = authSession ?: return
                val wn = cmd.auth.watchNonce
                val watchNonce = wn.nonce.toByteArray()
                if (!sess.installWatchNonce(watchNonce, wn.hmac.toByteArray())) {
                    failLink(BandLinkException(BandLinkException.AUTH_REJECTED, "watch HMAC mismatch — the auth key does not belong to this band"))
                    return
                }
                val deviceInfo = XiaomiProto.AuthDeviceInfo.newBuilder()
                    .setUnknown1(0)
                    .setPhoneApiLevel(Build.VERSION.SDK_INT.toFloat())
                    .setPhoneName(Build.MODEL ?: "Android")
                    .setUnknown3(224)
                    .setRegion(regionCode())
                    .build()
                val step3 = XiaomiProto.AuthStep3.newBuilder()
                    .setEncryptedNonces(ByteString.copyFrom(sess.phoneAck(watchNonce)))
                    .setEncryptedDeviceInfo(ByteString.copyFrom(sess.encryptV1(deviceInfo.toByteArray(), 0)))
                    .build()
                val out = XiaomiProto.Command.newBuilder()
                    .setType(AUTH_COMMAND_TYPE)
                    .setSubtype(CMD_AUTH)
                    .setAuth(XiaomiProto.Auth.newBuilder().setAuthStep3(step3).build())
                    .build()
                Log.i(TAG, "→ auth step 2 (AuthStep3)")
                enqueueDataNow(out.toByteArray(), XiaomiChannel.PROTOBUF, encrypt = false)
            }
            CMD_AUTH -> {
                // XiaomiAuthService.handleCommand: CMD_AUTH reply == authenticated (encrypted from now on).
                Log.i(TAG, "authenticated (status=${cmd.status})")
                onAuthSuccess()
            }
            CMD_SEND_USERID -> {
                // Plaintext user-id handshake is SPP/V1-only; we never start it.
                if (cmd.auth.status != 1) {
                    failLink(BandLinkException(BandLinkException.AUTH_REJECTED, "band rejected user-id auth (status=${cmd.auth.status})"))
                } else {
                    Log.w(TAG, "unexpected plaintext auth success on V2 — ignoring")
                }
            }
            else -> Log.w(TAG, "unknown auth subtype ${cmd.subtype}")
        }
    }

    private fun onAuthSuccess() {
        val sess = authSession
        if (sess == null || !sess.encryptionInitialised) {
            failLink(BandLinkException(BandLinkException.AUTH_REJECTED, "auth reply without key exchange"))
            return
        }
        gattHandler.removeCallbacksAndMessages(WATCHDOG_TOKEN)
        sessionReady = true
        passive = false
        passiveFailures = 0
        autoReconnect = true
        _state.value = State.Connected
        takeAttempt()?.complete(Unit)
        Log.i(POWER_TAG, "band session established")
        scope.launch { runPostAuthInit() }
    }

    /**
     * Phase 2, trimmed to what this band needs: XiaomiSupport.onAuthSuccess
     * (L404-416) → systemService.setCurrentTime() + XiaomiSystemService.initialize()
     * (device info, device state, battery). User info / health configs need
     * real user profile data we don't collect yet, so they're skipped rather
     * than faked.
     */
    private suspend fun runPostAuthInit() {
        try {
            sendCommand(buildSetTimeCommand())
            sendCommand(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_DEVICE_INFO)
            sendCommand(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_DEVICE_STATE_GET)
            sendCommand(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_BATTERY)
        } catch (t: Throwable) {
            Log.w(TAG, "post-auth init failed", t)
        }
    }

    /** XiaomiSystemService.setCurrentTime. */
    fun buildSetTimeCommand(): XiaomiProto.Command {
        val now = Calendar.getInstance()
        val tz = java.util.TimeZone.getDefault()
        val is24h = android.text.format.DateFormat.is24HourFormat(appContext)
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
                    .setZoneOffset(now.get(Calendar.ZONE_OFFSET) / 1000 / 60 / 15)
                    .setDstOffset(now.get(Calendar.DST_OFFSET) / 1000 / 60 / 15)
                    .setName(tz.id)
                    .build(),
            )
            .setIsNot24Hour(!is24h)
            .build()
        return XiaomiProto.Command.newBuilder()
            .setType(SystemCommands.COMMAND_TYPE)
            .setSubtype(SystemCommands.CMD_CLOCK)
            .setSystem(XiaomiProto.System.newBuilder().setClock(clock).build())
            .build()
    }

    private fun regionCode(): String {
        val lang = Locale.getDefault().language
        return (if (lang.length >= 2) lang.substring(0, 2) else "EN").uppercase(Locale.ROOT)
    }

    // ============================================================ outgoing

    private fun sendAck(seq: Int) {
        enqueueRaw(XiaomiSppPacketV2.Ack(seq).encode(null))
    }

    private fun enqueueRaw(frame: ByteArray) {
        synchronized(sendLock) {
            writeQueue.trySend(OutFrame(linkGen, frame))
        }
    }

    /** Used from the GATT thread during the handshake (no session requirement). */
    private fun enqueueDataNow(payload: ByteArray, channel: XiaomiChannel, encrypt: Boolean) {
        synchronized(sendLock) {
            val opCode = if (encrypt) XiaomiSppPacketV2.Data.OPCODE_SEND_ENCRYPTED else XiaomiSppPacketV2.Data.OPCODE_SEND_PLAINTEXT
            val frame = XiaomiSppPacketV2.Data(nextSeq(), channel, opCode, payload).encode(if (encrypt) authSession else null)
            writeQueue.trySend(OutFrame(linkGen, frame))
        }
    }

    private fun enqueueData(payload: ByteArray, channel: XiaomiChannel, encrypt: Boolean, requireSession: Boolean) {
        synchronized(sendLock) {
            if (requireSession && !sessionReady) {
                // XiaomiBleProtocolV2.sendCommand logs + returns when not ready; never encrypt with un-derived keys.
                Log.w(TAG, "dropping ${payload.size}B on $channel — band not connected/authenticated")
                return
            }
            // Seq assignment + enqueue under one lock → frames hit the air in seq order.
            enqueueDataNow(payload, channel, encrypt)
        }
    }

    private fun nextSeq(): Int = sequenceCounter.getAndIncrement() and 0xFF

    @SuppressLint("MissingPermission")
    private suspend fun pumpWrites() {
        for (frame in writeQueue) {
            if (frame.gen != linkGen) continue // queued for a link that no longer exists
            val g = gatt
            val tx = txChar
            if (g == null || tx == null) {
                Log.w(TAG, "dropping ${frame.bytes.size}B frame — no GATT link")
                continue
            }
            val chunkSize = maxWriteSize
            var off = 0
            while (off < frame.bytes.size) {
                if (frame.gen != linkGen) break
                val end = minOf(off + chunkSize, frame.bytes.size)
                if (!writeChunk(g, tx, frame.bytes.copyOfRange(off, end))) {
                    // A partially written frame is garbage to the band; drop the rest of it.
                    Log.w(TAG, "chunk write failed — dropping rest of ${frame.bytes.size}B frame")
                    break
                }
                off = end
            }
        }
    }

    /**
     * One ATT write, serialised: waits for onCharacteristicWrite before the
     * next one (the stack returns BUSY otherwise) and retries immediate
     * BUSY/refusals instead of silently dropping the chunk.
     */
    @SuppressLint("MissingPermission")
    private suspend fun writeChunk(g: BluetoothGatt, tx: BluetoothGattCharacteristic, chunk: ByteArray): Boolean {
        val props = tx.properties
        // Band's V2 TX is PROPERTY_WRITE (with response); NO_RESPONSE writes get silently dropped.
        val writeType = if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        for (attemptNo in 1..WRITE_RETRIES) {
            if (g !== gatt) return false
            while (writeAck.tryReceive().isSuccess) Unit
            val accepted = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(tx, chunk, writeType) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    tx.writeType = writeType
                    @Suppress("DEPRECATION")
                    tx.value = chunk
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(tx)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "write: missing BLUETOOTH_CONNECT", e)
                return false
            } catch (t: Throwable) {
                Log.w(TAG, "write threw", t)
                false
            }
            if (!accepted) {
                Log.d(TAG, "write refused (busy?) attempt $attemptNo/$WRITE_RETRIES")
                delay(WRITE_RETRY_BACKOFF_MS * attemptNo)
                continue
            }
            val status = withTimeoutOrNull(WRITE_ACK_TIMEOUT_MS) { writeAck.receive() }
            return when {
                status == null -> {
                    // No-response writes may legitimately not be acked on old stacks.
                    if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) true
                    else { Log.w(TAG, "no onCharacteristicWrite within ${WRITE_ACK_TIMEOUT_MS}ms"); false }
                }
                status == BluetoothGatt.GATT_SUCCESS -> true
                else -> { Log.w(TAG, "onCharacteristicWrite status=$status"); false }
            }
        }
        return false
    }

    // ============================================================ adapter on/off

    private fun isAdapterEnabled(): Boolean = try {
        appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerAdapterReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> gattHandler.post {
                        val a = takeAttempt()
                        closeGatt("Bluetooth turned off")
                        gattHandler.removeCallbacksAndMessages(RECONNECT_TOKEN)
                        _state.value = State.Disconnected
                        a?.completeExceptionally(BandLinkException(BandLinkException.BT_OFF, "Bluetooth was turned off"))
                    }
                    BluetoothAdapter.STATE_ON -> gattHandler.post {
                        if (autoReconnect && gatt == null) schedulePassive(1_000L)
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not register adapter-state receiver", t)
        }
    }

    companion object {
        private const val AUTH_COMMAND_TYPE = 1
        private const val CMD_SEND_USERID = 5
        private const val CMD_NONCE = 26
        private const val CMD_AUTH = 27

        /** ATT default MTU 23 − 3 until the MTU exchange completes. */
        private const val DEFAULT_WRITE_CHUNK = 20
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L
        private const val WRITE_RETRIES = 5
        private const val WRITE_RETRY_BACKOFF_MS = 30L
        private const val WRITE_ACK_TIMEOUT_MS = 3_000L
        private const val PASSIVE_BASE_DELAY_MS = 5_000L
        private const val PASSIVE_MAX_DELAY_MS = 10 * 60_000L

        private val RECONNECT_TOKEN = Any()
        private val WATCHDOG_TOKEN = Any()

        /** 133 GATT_ERROR, 147 GATT_CONNECTION_TIMEOUT, 8 CONN_TIMEOUT, 62 CONN_FAILED_ESTABLISHMENT. */
        private val TRANSIENT_STATUSES = setOf(0x85, 0x93, 0x08, 0x3E)
        /** BtLEQueue.handleDisconnected "unhealthy" statuses. */
        private val UNHEALTHY_STATUSES = setOf(0x81, 0x85, 0x08, 0x05, 0x0F, 0x93)

        /** AbstractBTLEDeviceSupport.calcMaxWriteChunk. */
        fun calcMaxWriteChunk(mtu: Int): Int = minOf(512, maxOf(23, mtu) - 3)

        /** XiaomiSystemService.convertBatteryStateFromRawValue: 1 charging, 2/3 not charging. */
        private fun chargingFromRaw(raw: Int): Boolean? = when (raw) {
            1 -> true
            2, 3 -> false
            else -> null
        }
    }
}
