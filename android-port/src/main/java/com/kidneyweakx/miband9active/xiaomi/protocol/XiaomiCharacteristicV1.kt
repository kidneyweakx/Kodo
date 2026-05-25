/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo  (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                          (Kotlin port)
 *
 *  Translated from XiaomiCharacteristicV1.java. Differences vs. upstream:
 *  - No TransactionBuilder. The caller supplies a `writeRaw(bytes)` lambda
 *    which the Kotlin BLE driver implements with `BluetoothGatt.writeCharacteristic`.
 *  - Timeouts use a Kotlin coroutine scope rather than a Handler.
 *  - Encryption is delegated to [XiaomiAuthSession].
 *
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.kidneyweakx.miband9active.xiaomi.protocol

import com.kidneyweakx.miband9active.xiaomi.auth.XiaomiAuthSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class XiaomiCharacteristicV1(
    private val auth: XiaomiAuthSession?,
    private val writeRaw: (ByteArray) -> Unit,
    private val onPlainPayload: (ByteArray) -> Unit,
    isEncryptedTransport: Boolean,
    private val incrementNonce: Boolean = true,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var isEncrypted: Boolean = isEncryptedTransport
        set(value) { field = value }

    // ATT overhead is 3 bytes; default is for MTU=247.
    @Volatile var maxWriteSize: Int = 244
    private var maxWriteSizeForCurrent: Int = 244

    private var numChunks = 0
    private val receivedChunks = HashMap<Int, ByteArray>()

    private val payloadQueue: ArrayDeque<Payload> = ArrayDeque()
    private var waitingAck = false
    private var sendingChunked = false
    private var currentPayload: Payload? = null

    private val encryptedIndex = AtomicInteger(1) // 0 belongs to auth service
    private var timeoutJob: Job? = null

    fun setMtu(mtu: Int) {
        // 3 bytes ATT overhead.
        maxWriteSize = (mtu - 3).coerceAtLeast(23)
    }

    fun reset() {
        numChunks = 0
        encryptedIndex.set(1)
        receivedChunks.clear()
        payloadQueue.clear()
        waitingAck = false
        sendingChunked = false
        currentPayload = null
        cancelTimeout()
    }

    fun dispose() {
        cancelTimeout()
        scope.cancel()
    }

    fun enqueue(bytes: ByteArray, taskName: String = "cmd", onComplete: ((Boolean) -> Unit)? = null) {
        payloadQueue.add(Payload(taskName, bytes, onComplete))
        sendNext()
    }

    fun onNotify(value: ByteArray) {
        val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val chunk = buf.short.toInt() and 0xFFFF

        if (chunk != 0) {
            // chunked payload (data direction: band → phone)
            if (chunk > numChunks) return
            val payload = ByteArray(buf.remaining()).also { buf.get(it) }
            receivedChunks[chunk] = payload
            rescheduleTimeout()
            if (receivedChunks.size == numChunks) {
                cancelTimeout()
                sendChunkEndAck()
                val plain = reassemble()
                if (plain.isNotEmpty()) {
                    val delivered = if (isEncrypted && auth?.encryptionInitialised == true) auth.decryptV1(plain) else plain
                    onPlainPayload(delivered)
                }
                numChunks = 0
                receivedChunks.clear()
            }
            return
        }

        val type = buf.get().toInt() and 0xFF
        when (type) {
            0 -> {
                val encFlag = buf.get().toInt() and 0xFF
                val expected = if (isEncrypted) 1 else 0
                if (encFlag != expected) return
                numChunks = buf.short.toInt() and 0xFFFF
                receivedChunks.clear()
                sendChunkStartAck()
            }
            1 -> handleChunkedAck(buf)
            2 -> handleSingle(buf)
            3 -> handleAck(buf)
        }
    }

    // ------------------------------------------------------------------- inbound

    private fun handleChunkedAck(buf: ByteBuffer) {
        val subtype = buf.get().toInt() and 0xFF
        when (subtype) {
            0 -> { // chunked end
                currentPayload?.onComplete?.invoke(true)
                currentPayload = null
                sendingChunked = false
                sendNext()
            }
            1 -> { // chunked start (band ready to receive)
                val payload = currentPayload ?: return
                val chunkSize = maxWriteSizeForCurrent - 2
                var i = 0
                while (i * chunkSize < payload.bytesToSend.size) {
                    sendChunk(i, chunkSize)
                    i++
                }
            }
            2 -> { // chunked nack
                currentPayload?.onComplete?.invoke(false)
                currentPayload = null
                sendingChunked = false
                sendNext()
            }
            5 -> { // retransmit missing chunks
                val remaining = ByteArray(buf.remaining()).also { buf.get(it) }
                if (remaining.isEmpty()) return
                val rb = ByteBuffer.wrap(remaining).order(ByteOrder.LITTLE_ENDIAN)
                val payload = currentPayload ?: return
                val chunkSize = maxWriteSizeForCurrent - 2
                while (rb.remaining() >= 2) {
                    val missing = rb.short.toInt() and 0xFFFF
                    sendChunk(missing - 1, chunkSize)
                }
            }
        }
    }

    private fun handleSingle(buf: ByteBuffer) {
        sendAck()
        val encFlag = buf.get().toInt() and 0xFF
        val tail = ByteArray(buf.remaining()).also { buf.get(it) }
        val plain = if (encFlag == 1 && auth?.encryptionInitialised == true) auth.decryptV1(tail) else tail
        onPlainPayload(plain)
    }

    private fun handleAck(buf: ByteBuffer) {
        val result = buf.get().toInt()
        currentPayload?.onComplete?.invoke(result == 0)
        currentPayload = null
        waitingAck = false
        sendNext()
    }

    private fun reassemble(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (i in 1..numChunks) {
            val piece = receivedChunks[i] ?: return ByteArray(0)
            out.write(piece)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------- outbound

    private fun sendNext() {
        if (waitingAck || sendingChunked) return
        val next = payloadQueue.pollFirst() ?: return
        currentPayload = next

        val encrypt = isEncrypted && auth?.encryptionInitialised == true
        if (encrypt) {
            val counter = if (incrementNonce) encryptedIndex.get() else 0
            next.bytesToSend = auth!!.let { it.encryptV1(next.bytes) }
            if (incrementNonce) encryptedIndex.set(counter + 1)
        }
        maxWriteSizeForCurrent = maxWriteSize

        if (shouldChunk(next.bytesToSend)) {
            if (encrypt && incrementNonce) {
                val withCounter = ByteBuffer.allocate(2 + next.bytesToSend.size).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((encryptedIndex.get() - 1).toShort())
                    .put(next.bytesToSend)
                    .array()
                next.bytesToSend = withCounter
            }
            sendingChunked = true
            val header = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0)
                .put(0.toByte())
                .put(if (encrypt) 1 else 0)
                .putShort(Math.ceil(next.bytesToSend.size.toDouble() / (maxWriteSizeForCurrent - 2)).toInt().toShort())
                .array()
            writeRaw(header)
        } else {
            // Single packet path.
            val cmdLen = (if (encrypt) 6 else 4) + next.bytesToSend.size
            val out = ByteBuffer.allocate(cmdLen).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0)
                .put(2.toByte())
                .put(if (encrypt) 1.toByte() else 2.toByte())
            if (encrypt) {
                val ctr = if (incrementNonce) (encryptedIndex.getAndIncrement() - 1) else 0
                out.putShort(ctr.toShort())
            }
            out.put(next.bytesToSend)
            waitingAck = true
            writeRaw(out.array())
        }
    }

    private fun sendChunk(index: Int, chunkSize: Int) {
        val payload = currentPayload ?: return
        val start = index * chunkSize
        val end = minOf((index + 1) * chunkSize, payload.bytesToSend.size)
        val out = ByteArray(2 + end - start)
        val seq = (index + 1).toShort()
        out[0] = (seq.toInt() and 0xFF).toByte()
        out[1] = ((seq.toInt() shr 8) and 0xFF).toByte()
        System.arraycopy(payload.bytesToSend, start, out, 2, end - start)
        writeRaw(out)
    }

    private fun shouldChunk(payload: ByteArray): Boolean {
        if (!isEncrypted) return true
        return payload.size + 6 > maxWriteSizeForCurrent
    }

    private fun sendAck() = writeRaw(PAYLOAD_ACK)
    private fun sendChunkStartAck() = writeRaw(byteArrayOf(0, 0, 1, 1))
    private fun sendChunkEndAck() = writeRaw(byteArrayOf(0, 0, 1, 0))

    private fun cancelTimeout() { timeoutJob?.cancel(); timeoutJob = null }
    private fun rescheduleTimeout() {
        cancelTimeout()
        timeoutJob = scope.launch {
            delay(TIMEOUT_MS)
            requestMissingChunks()
        }
    }

    private fun requestMissingChunks() {
        if (numChunks == 0) return
        val missing = (1..numChunks).filter { !receivedChunks.containsKey(it) }
        if (missing.isEmpty()) return
        val take = minOf(missing.size, (maxWriteSize - 4) / 2)
        val bb = ByteBuffer.allocate(4 + take * 2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)
            .put(1.toByte())   // CHUNKED_ACK
            .put(5.toByte())   // subtype = missing chunks
        for (i in 0 until take) bb.putShort(missing[i].toShort())
        writeRaw(bb.array())
    }

    private data class Payload(
        val taskName: String,
        val bytes: ByteArray,
        val onComplete: ((Boolean) -> Unit)?,
    ) {
        var bytesToSend: ByteArray = bytes
    }

    companion object {
        private val PAYLOAD_ACK = byteArrayOf(0, 0, 3, 0)
        private const val TIMEOUT_MS = 5_000L
    }
}
