/*  Copyright (C) 2023-2024 Andreas Shimokawa, José Rebelo            (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                                      (Kotlin port)
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 *
 *  Drives data uploads (watchface bitmaps, firmware blobs, notification
 *  icons) over the band's plaintext DATA channel.
 *
 *  Wire layout for the encoded payload:
 *    [0x00] [type uint8] [md5 16 bytes] [size uint32 LE] [bytes] [crc32 uint32 LE]
 *  Then split into chunks of `chunkSize - 4` bytes (chunkSize defaults to
 *  2048 when the band doesn't override). Each chunk is prefixed with
 *  `[total uint16 LE] [current uint16 LE]`.
 *
 *  Translated from `XiaomiDataUploadService.java`.
 */
package com.kidneyweakx.miband9active.xiaomi.services

import com.google.protobuf.ByteString
import com.kidneyweakx.miband9active.xiaomi.protocol.MiBand9BleDriver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlinx.coroutines.flow.first
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto

object MiBand9DataUploader {

    const val COMMAND_TYPE = 22
    const val CMD_UPLOAD_START = 0

    const val TYPE_WATCHFACE: Byte = 16
    const val TYPE_FIRMWARE: Byte = 32
    const val TYPE_RPK: Byte = 64
    const val TYPE_NOTIFICATION_ICON: Byte = 50

    /**
     * @param onProgress invoked with 0..100 ints as parts are sent.
     * @return true if upload completed, false on band-side rejection.
     */
    suspend fun upload(
        driver: MiBand9BleDriver,
        type: Byte,
        bytes: ByteArray,
        onProgress: (Int) -> Unit = {},
    ): Boolean {
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
        driver.sendCommand(request)

        // Wait for the band's ack telling us the chunk size and resume offset.
        val ack = driver.incoming.first { it.type == COMMAND_TYPE && it.subtype == CMD_UPLOAD_START }
        val dataAck = ack.command.dataUpload.dataUploadAck
        if (dataAck.unknown2 != 0) return false
        val chunkSize = if (dataAck.hasChunkSize()) dataAck.chunkSize else 2048
        val resumePosition = dataAck.resumePosition

        // Build the framed payload.
        val tail = bytes.copyOfRange(resumePosition, bytes.size)
        val framed = ByteBuffer.allocate(2 + 16 + 4 + tail.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(0)
            .put(type)
            .put(md5)
            .putInt(bytes.size)
            .put(tail)
            .array()
        val crc = CRC32().run { update(framed); value }.toInt()
        val withCrc = ByteBuffer.allocate(framed.size + 4).order(ByteOrder.LITTLE_ENDIAN)
            .put(framed)
            .putInt(crc)
            .array()

        val partSize = (chunkSize - 4).coerceAtLeast(64)
        val totalParts = ((withCrc.size + partSize - 1) / partSize).coerceAtLeast(1)
        var index = 0
        var sent = 0
        while (sent < withCrc.size) {
            val end = minOf(sent + partSize, withCrc.size)
            val current = index + 1
            val chunk = ByteBuffer.allocate(4 + end - sent).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(totalParts.toShort())
                .putShort(current.toShort())
                .put(withCrc, sent, end - sent)
                .array()
            driver.sendData(chunk)
            sent = end
            index++
            onProgress(((100L * current) / totalParts).toInt())
        }
        return true
    }
}
