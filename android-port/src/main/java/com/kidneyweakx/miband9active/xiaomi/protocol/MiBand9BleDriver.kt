/*  Copyright (C) 2024 Yoran Vulker, Andreas Shimokawa, José Rebelo     (Gadgetbridge V2 protocol)
 *  Copyright (C) 2026 kidneyweakx                                       (Kotlin port + Coroutine rewrite)
 *
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  V2-only BLE driver for Mi Band 9 Active. Skips the Gadgetbridge
 *  TransactionBuilder + GBDevice scaffolding; uses Android's BluetoothGatt
 *  callbacks directly, with a coroutine-based send queue and a Flow-based
 *  state/event stream.
 *
 *  This is the "needle's eye" — everything else (Notifications, Health
 *  exports, Camera, GPS push, Weather push, Calendar) routes through
 *  [sendCommand]. There is no other dispatch path.
 */
package com.kidneyweakx.miband9active.xiaomi.protocol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.protobuf.ByteString
import com.kidneyweakx.miband9active.xiaomi.auth.XiaomiAuthSession
import com.kidneyweakx.miband9active.xiaomi.services.SystemCommands
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeout
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

private const val TAG = "MiBand9BleDriver"

class MiBand9BleDriver(
    private val context: Context,
    private val authKey16: ByteArray,
) {

    enum class Auth { NONE, IN_PROGRESS, OK, FAILED }

    sealed class State {
        data object Disconnected : State()
        data object Connecting : State()
        data object Authenticating : State()
        data object Connected : State()
        data class Error(val message: String) : State()
    }

    data class IncomingCommand(val type: Int, val subtype: Int, val command: XiaomiProto.Command)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var gattScope: Job? = null

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingCommand>(extraBufferCapacity = 64)
    val incoming: SharedFlow<IncomingCommand> = _incoming.asSharedFlow()

    private val _activityChunks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val activityChunks: SharedFlow<ByteArray> = _activityChunks.asSharedFlow()

    private val authSession = XiaomiAuthSession(authKey16)

    private val packetAccumulator = V2PacketAccumulator()
    // Counter for Data packets only. Matches Gadgetbridge V2:
    //   - SessionConfig START_SESSION_REQUEST seq is hardcoded 0 (doesn't bump)
    //   - Data packets use getAndIncrement() starting from 0
    private val sequenceCounter = AtomicInteger(0)
    private val pendingAcks = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val writeChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    /** Buffered to 1: pumpWrites awaits this after each WRITE_TYPE_DEFAULT write. */
    private val writeAck = Channel<Int>(capacity = 1)

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var maxWriteSize = 244

    /** Connect, run V2 session config + auth, then resolve State.Connected. */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (gattScope != null) {
            Log.w(TAG, "connect() called but gattScope is non-null — ignoring re-entry")
            return
        }
        Log.i(TAG, "connect() device=${device.address} authKey=${authKey16.size}B")
        _state.value = State.Connecting

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestMtu(512)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _state.value = State.Disconnected
                    cleanup()
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                Log.i(TAG, "onMtuChanged mtu=$mtu status=$status (maxWriteSize=${(mtu - 3).coerceAtLeast(23)})")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    maxWriteSize = (mtu - 3).coerceAtLeast(23)
                }
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                Log.i(TAG, "onServicesDiscovered status=$status services=${g.services?.size ?: 0}")
                val service = g.getService(XiaomiUuids.SERVICE_V2)
                val rx = service?.getCharacteristic(XiaomiUuids.V2_CHARACTERISTIC_RX)
                val tx = service?.getCharacteristic(XiaomiUuids.V2_CHARACTERISTIC_TX)
                if (service == null || rx == null || tx == null) {
                    Log.e(TAG, "V2 service or characteristics missing (service=$service rx=$rx tx=$tx). All services: ${g.services?.map { it.uuid }}")
                    _state.value = State.Error("V2 service or characteristics missing")
                    return
                }
                txChar = tx
                gatt = g
                g.setCharacteristicNotification(rx, true)
                val ccc = rx.getDescriptor(XiaomiUuids.CCC_DESCRIPTOR)
                if (ccc != null) {
                    Log.d(TAG, "writeDescriptor(CCC, ENABLE_NOTIFICATION_VALUE)")
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(ccc)
                    }
                } else {
                    Log.w(TAG, "RX has no CCC descriptor — notifications won't be enabled")
                    // No descriptor to wait on — kick auth immediately.
                    _state.value = State.Authenticating
                    Log.i(TAG, "→ Authenticating, kicking startSessionAndAuth()")
                    startSessionAndAuth()
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                Log.d(TAG, "onDescriptorWrite uuid=${descriptor.uuid} status=$status")
                // CCC notification subscribe ack — only NOW is the GATT stack
                // free to accept our first characteristic write. Firing the
                // session start before this returns ERROR_GATT_WRITE_REQUEST_BUSY (rc=201)
                // and the band never sees the request.
                if (descriptor.uuid == XiaomiUuids.CCC_DESCRIPTOR && status == BluetoothGatt.GATT_SUCCESS) {
                    if (_state.value !is State.Authenticating &&
                        _state.value !is State.Connected) {
                        _state.value = State.Authenticating
                        Log.i(TAG, "→ Authenticating (after CCC ack), kicking startSessionAndAuth()")
                        startSessionAndAuth()
                    }
                }
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                Log.d(TAG, "onCharacteristicWrite status=$status uuid=${characteristic.uuid}")
                writeAck.trySend(status)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                Log.d(TAG, "rx ${value.size}B: ${value.take(16).joinToString(" ") { "%02x".format(it) }}${if (value.size > 16) "…" else ""}")
                onIncomingBytes(value)
            }

            @Deprecated("API <33")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: return
                Log.d(TAG, "rx(legacy) ${data.size}B")
                onIncomingBytes(data)
            }
        }

        @Suppress("DEPRECATION")
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, cb)
        }

        // Pump the outbound queue.
        gattScope = scope.launch { pumpWrites() }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}
        cleanup()
    }

    private fun cleanup() {
        gattScope?.cancel(); gattScope = null
        gatt = null
        txChar = null
        packetAccumulator.let { /* readers re-init on reconnect */ }
        pendingAcks.values.forEach { it.complete(Unit) }
        pendingAcks.clear()
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    // --------------------------------------------------------------------- I/O

    private fun onIncomingBytes(bytes: ByteArray) {
        val packets = packetAccumulator.feed(bytes)
        for (p in packets) handlePacket(p)
    }

    private fun handlePacket(packet: XiaomiSppPacketV2) {
        when (packet) {
            is XiaomiSppPacketV2.Ack -> {
                Log.d(TAG, "← Ack seq=${packet.sequenceNumber}")
                pendingAcks.remove(packet.sequenceNumber)?.complete(Unit)
            }
            is XiaomiSppPacketV2.SessionConfig -> {
                Log.i(TAG, "← SessionConfig opCode=${packet.opCode} seq=${packet.sequenceNumber}")
                if (packet.opCode == XiaomiSppPacketV2.SessionConfig.OPCODE_START_SESSION_RESPONSE) {
                    sendPhoneNonce()
                }
            }
            is XiaomiSppPacketV2.Data -> {
                sendAck(packet.sequenceNumber)
                val plain = packet.decryptedPayload(authSession)
                val cmd = runCatching { XiaomiProto.Command.parseFrom(plain) }.getOrNull()
                if (cmd == null) {
                    Log.w(TAG, "← Data seq=${packet.sequenceNumber} channel=${packet.channel} but Command.parseFrom failed (${plain.size}B)")
                    return
                }
                Log.d(TAG, "← Data seq=${packet.sequenceNumber} channel=${packet.channel} cmd type=${cmd.type} subtype=${cmd.subtype}")
                onCommandReceived(cmd, packet.channel)
            }
        }
    }

    private fun sendAck(seq: Int) {
        scope.launch { writeRawFrame(XiaomiSppPacketV2.Ack(seq).encode(null)) }
    }

    private fun startSessionAndAuth() {
        scope.launch {
            // 1) START_SESSION_REQUEST — Gadgetbridge hardcodes seq=0 here.
            val req = XiaomiSppPacketV2.SessionConfig(
                seq = 0,
                opCode = XiaomiSppPacketV2.SessionConfig.OPCODE_START_SESSION_REQUEST,
            )
            Log.i(TAG, "→ START_SESSION_REQUEST seq=${req.sequenceNumber}")
            writeRawFrame(req.encode(null))
        }
    }

    private fun sendPhoneNonce() {
        scope.launch {
            // 2) Send phone nonce in a PROTOBUF Data packet (still plaintext).
            val cmd = XiaomiProto.Command.newBuilder()
                .setType(1)        // auth
                .setSubtype(26)    // CMD_NONCE
                .setAuth(
                    XiaomiProto.Auth.newBuilder()
                        .setPhoneNonce(
                            XiaomiProto.PhoneNonce.newBuilder()
                                .setNonce(ByteString.copyFrom(authSession.phoneNonce))
                                .build(),
                        )
                        .build(),
                )
                .build()
            sendRaw(cmd.toByteArray(), XiaomiChannel.PROTOBUF, encrypt = false)
        }
    }

    private fun onCommandReceived(cmd: XiaomiProto.Command, channel: XiaomiChannel) {
        if (channel == XiaomiChannel.ACTIVITY) {
            _activityChunks.tryEmit(cmd.toByteArray())
            return
        }
        // Auth handshake stages?
        if (cmd.type == 1 && cmd.subtype == 26 && cmd.auth.hasWatchNonce()) {
            val wn = cmd.auth.watchNonce
            val watchNonce = wn.nonce.toByteArray()
            val watchHmac = wn.hmac.toByteArray()
            val ok = authSession.installWatchNonce(watchNonce, watchHmac)
            if (!ok) {
                _state.value = State.Error("auth: watch hmac mismatch")
                return
            }
            // Build AuthStep3: encrypted device info + ack of nonces.
            val deviceInfo = XiaomiProto.AuthDeviceInfo.newBuilder()
                .setUnknown1(0)
                .setPhoneApiLevel(Build.VERSION.SDK_INT.toFloat())
                .setPhoneName(Build.MODEL ?: "phone")
                .setUnknown3(224)
                .setRegion(Locale.getDefault().language.uppercase(Locale.ROOT).take(2))
                .build()
            val step3 = XiaomiProto.AuthStep3.newBuilder()
                .setEncryptedNonces(ByteString.copyFrom(authSession.phoneAck(watchNonce)))
                .setEncryptedDeviceInfo(ByteString.copyFrom(authSession.encryptV1(deviceInfo.toByteArray())))
                .build()
            val cmdOut = XiaomiProto.Command.newBuilder()
                .setType(1)
                .setSubtype(27) // CMD_AUTH
                .setAuth(XiaomiProto.Auth.newBuilder().setAuthStep3(step3).build())
                .build()
            scope.launch { sendRaw(cmdOut.toByteArray(), XiaomiChannel.PROTOBUF, encrypt = false) }
            return
        }
        if (cmd.type == 1 && (cmd.subtype == 27 || cmd.subtype == 5)) {
            _state.value = State.Connected
            // proactively ask for device info + battery now
            scope.launch {
                sendCommand(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_DEVICE_INFO)
                sendCommand(SystemCommands.COMMAND_TYPE, SystemCommands.CMD_BATTERY)
            }
            return
        }
        _incoming.tryEmit(IncomingCommand(cmd.type, cmd.subtype, cmd))
    }

    /** Send a Command frame on the encrypted PROTOBUF channel. */
    suspend fun sendCommand(type: Int, subtype: Int, body: XiaomiProto.Command.Builder.() -> Unit = {}) {
        val builder = XiaomiProto.Command.newBuilder().setType(type).setSubtype(subtype)
        builder.body()
        sendRaw(builder.build().toByteArray(), XiaomiChannel.PROTOBUF, encrypt = true)
    }

    suspend fun sendCommand(command: XiaomiProto.Command) {
        sendRaw(command.toByteArray(), XiaomiChannel.PROTOBUF, encrypt = true)
    }

    /** Plaintext data-channel write for chunked file uploads. */
    suspend fun sendData(bytes: ByteArray) {
        sendRaw(bytes, XiaomiChannel.DATA, encrypt = false)
    }

    private suspend fun sendRaw(payload: ByteArray, channel: XiaomiChannel, encrypt: Boolean) {
        val opCode = if (encrypt) XiaomiSppPacketV2.Data.OPCODE_SEND_ENCRYPTED
            else XiaomiSppPacketV2.Data.OPCODE_SEND_PLAINTEXT
        val data = XiaomiSppPacketV2.Data(
            seq = nextSeq(),
            channel = channel,
            opCode = opCode,
            payload = payload,
        )
        writeRawFrame(data.encode(if (encrypt) authSession else null))
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeRawFrame(frame: ByteArray) {
        // Split into ATT-MTU sized chunks.
        var off = 0
        while (off < frame.size) {
            val end = minOf(off + maxWriteSize, frame.size)
            val chunk = frame.copyOfRange(off, end)
            writeChannel.send(chunk)
            off = end
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun pumpWrites() {
        for (chunk in writeChannel) {
            val g = gatt
            val tx = txChar
            if (g == null || tx == null) {
                Log.w(TAG, "pumpWrites: dropping ${chunk.size}B chunk (gatt=${g != null} txChar=${tx != null})")
                continue
            }
            // Pick the write type that the characteristic actually supports.
            // Mi Band 9 Active's V2 TX (0000005f) is declared PROPERTY_WRITE
            // (write-with-response); writing it as WRITE_NO_RESPONSE makes
            // the stack silently drop frames → band never sees our request →
            // auth stalls forever in Authenticating.
            val props = tx.properties
            val writeType = when {
                (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ->
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ->
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            // Drain any stale ack before dispatching this write so we never
            // satisfy a new wait with a previous chunk's callback.
            while (writeAck.tryReceive().isSuccess) Unit
            try {
                Log.d(
                    TAG,
                    "tx ${chunk.size}B writeType=$writeType props=0x${"%02x".format(props)}: ${chunk.take(16).joinToString(" ") { "%02x".format(it) }}${if (chunk.size > 16) "…" else ""}",
                )
                if (Build.VERSION.SDK_INT >= 33) {
                    val rc = g.writeCharacteristic(tx, chunk, writeType)
                    if (rc != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "writeCharacteristic returned non-success rc=$rc")
                        continue
                    }
                } else {
                    @Suppress("DEPRECATION")
                    tx.value = chunk
                    @Suppress("DEPRECATION")
                    tx.writeType = writeType
                    @Suppress("DEPRECATION")
                    val ok = g.writeCharacteristic(tx)
                    if (!ok) {
                        Log.w(TAG, "writeCharacteristic returned false")
                        continue
                    }
                }
                // WRITE_TYPE_DEFAULT must be ack'd by onCharacteristicWrite
                // before the next write can be issued, otherwise the stack
                // returns GATT_BUSY. Wait up to 2s per chunk.
                if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                    val ack = try {
                        kotlinx.coroutines.withTimeoutOrNull(2_000) { writeAck.receive() }
                    } catch (_: Throwable) {
                        null
                    }
                    if (ack == null) {
                        Log.w(TAG, "no onCharacteristicWrite ack within 2s — band may have dropped")
                    } else if (ack != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "onCharacteristicWrite status=$ack (non-success)")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "write failed", t)
            }
        }
    }

    private fun nextSeq(): Int = sequenceCounter.getAndIncrement() and 0xFF

    companion object {
        @Suppress("unused")
        fun bluetoothManager(context: Context): BluetoothManager? =
            context.getSystemService(BluetoothManager::class.java)
    }
}
