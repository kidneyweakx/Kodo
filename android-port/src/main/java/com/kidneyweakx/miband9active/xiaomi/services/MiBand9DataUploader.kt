/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo            (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                                      (Kotlin port)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  XiaomiDataUploadService (command type 22): uploads watchfaces, firmware
 *  and notification icons over the band's plaintext DATA channel.
 *
 *    1. phone -> 22/0 DataUploadRequest { type, md5(bytes), size }
 *    2. band  -> 22/0 DataUploadAck { unknown2 (0 = ok), resumePosition, chunkSize? }
 *    3. payload = [0x00][type][md5 16B][size u32 LE][bytes from resumePosition][crc32 u32 LE]
 *       (crc32 over everything before it), split into parts of (chunkSize - 4)
 *       bytes, each prefixed with [totalParts u16 LE][currentPart u16 LE];
 *       chunkSize defaults to 2048 when the band omits it (Mi Band 8/9).
 *
 *  Only one upload runs at a time (upstream refuses a second one; we queue).
 */
package com.kidneyweakx.miband9active.xiaomi.services

import android.util.Log
import com.google.protobuf.ByteString
import com.kidneyweakx.miband9active.xiaomi.protocol.MiBand9BleDriver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

object MiBand9DataUploader {
    private const val TAG = "MB9A_Upload"

    const val COMMAND_TYPE = 22
    const val CMD_UPLOAD_START = 0

    const val TYPE_WATCHFACE: Byte = 16
    const val TYPE_FIRMWARE: Byte = 32
    const val TYPE_RPK: Byte = 64
    const val TYPE_NOTIFICATION_ICON: Byte = 50

    private const val DEFAULT_CHUNK_SIZE = 2048
    private const val ACK_TIMEOUT_MS = 15_000L

    private val mutex = Mutex()

    /**
     * @param onProgress 0..100 as parts are handed to the transport.
     * @return true if every part was written; false on band rejection / timeout / disconnect.
     */
    suspend fun upload(
        driver: MiBand9BleDriver,
        type: Byte,
        bytes: ByteArray,
        onProgress: (Int) -> Unit = {},
    ): Boolean = mutex.withLock {
        val md5 = MessageDigest.getInstance("MD5").digest(bytes)
        val request = XiaomiProto.Command.newBuilder()
            .setType(COMMAND_TYPE)
            .setSubtype(CMD_UPLOAD_START)
            .setDataUpload(
                XiaomiProto.DataUpload.newBuilder().setDataUploadRequest(
                    XiaomiProto.DataUploadRequest.newBuilder()
                        .setType(type.toInt() and 0xFF)
                        .setMd5Sum(ByteString.copyFrom(md5))
                        .setSize(bytes.size),
                ),
            )
            .build()

        // Subscribe BEFORE sending — the ack can arrive faster than a late collector.
        val ack = coroutineScope {
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(ACK_TIMEOUT_MS) {
                    driver.incoming.first { it.type == COMMAND_TYPE && it.subtype == CMD_UPLOAD_START }
                }
            }
            try {
                driver.sendCommand(request)
            } catch (t: Throwable) {
                waiter.cancel()
                throw t
            }
            waiter.await()
        }
        if (ack == null) {
            Log.w(TAG, "no upload ack within ${ACK_TIMEOUT_MS}ms")
            return@withLock false
        }
        val dataAck = ack.command.dataUpload.dataUploadAck
        Log.d(TAG, "upload start: unknown2=${dataAck.unknown2} resume=${dataAck.resumePosition} chunk=${dataAck.chunkSize}")
        if (dataAck.unknown2 != 0) {
            Log.w(TAG, "Unexpected response")
            return@withLock false
        }
        val chunkSize = if (dataAck.hasChunkSize()) dataAck.chunkSize else DEFAULT_CHUNK_SIZE
        val resumePosition = dataAck.resumePosition.coerceIn(0, bytes.size)

        // type + md5 + size + bytes + crc32
        val buf1 = ByteBuffer.allocate(2 + 16 + 4 + bytes.size - resumePosition).order(ByteOrder.LITTLE_ENDIAN)
        buf1.put(0.toByte())
        buf1.put(type)
        buf1.put(md5)
        buf1.putInt(bytes.size)
        buf1.put(bytes, resumePosition, bytes.size - resumePosition)
        val framed = buf1.array()
        val crc = CRC32().apply { update(framed) }.value.toInt()
        val payload = ByteBuffer.allocate(framed.size + 4).order(ByteOrder.LITTLE_ENDIAN)
            .put(framed)
            .putInt(crc)
            .array()

        val partSize = chunkSize - 4 // 2 + 2 at beginning of each for total and progress
        if (partSize <= 0) return@withLock false
        val totalParts = (payload.size + partSize - 1) / partSize
        var i = 0
        while (i * partSize < payload.size) {
            val currentPart = i + 1
            val start = i * partSize
            val end = minOf(currentPart * partSize, payload.size)
            val chunk = ByteBuffer.allocate(4 + end - start).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(totalParts.toShort())
                .putShort(currentPart.toShort())
                .put(payload, start, end - start)
                .array()
            try {
                driver.sendData(chunk)
            } catch (t: Throwable) {
                Log.w(TAG, "part $currentPart/$totalParts failed", t)
                return@withLock false
            }
            onProgress(Math.round((100.0f * currentPart) / totalParts))
            i++
        }
        true
    }
}
