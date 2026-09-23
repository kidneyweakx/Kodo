/*  Copyright (C) 2024 Yoran Vulker                        (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                          (Kotlin port)
 *
 *  V2 framing for the BLE channel used by Mi Band 9 Active.
 *  Layout (little-endian):
 *    [0..1] preamble 0xA5A5
 *    [2]    packet type (low nibble)
 *    [3]    sequence number (uint8)
 *    [4..5] payload length (uint16)
 *    [6..7] CRC-16/ARC of payload (uint16)
 *    [8..]  payload
 *
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.kidneyweakx.miband9active.xiaomi.protocol

import com.kidneyweakx.miband9active.xiaomi.auth.XiaomiAuthSession
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class XiaomiChannel(val raw: Int) {
    UNKNOWN(-1),
    /** Encrypted; auth handshake + commands. */
    PROTOBUF(1),
    /** Plaintext. */
    DATA(2),
    /** Encrypted; activity file downloads. */
    ACTIVITY(5),
    ;
    companion object {
        fun fromRaw(raw: Int): XiaomiChannel = values().firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

sealed class XiaomiSppPacketV2(val packetType: Int, val sequenceNumber: Int) {

    protected abstract fun payloadBytes(auth: XiaomiAuthSession?): ByteArray

    fun encode(auth: XiaomiAuthSession?): ByteArray {
        val payload = payloadBytes(auth)
        val out = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(PREAMBLE)
        out.put((packetType and 0x0F).toByte())
        out.put((sequenceNumber and 0xFF).toByte())
        out.putShort(payload.size.toShort())
        out.putShort(crc16Arc(payload).toShort())
        out.put(payload)
        return out.array()
    }

    class Ack(seq: Int) : XiaomiSppPacketV2(PACKET_TYPE_ACK, seq) {
        override fun payloadBytes(auth: XiaomiAuthSession?): ByteArray = ByteArray(0)
    }

    class SessionConfig(
        seq: Int,
        val opCode: Int,
    ) : XiaomiSppPacketV2(PACKET_TYPE_SESSION_CONFIG, seq) {
        override fun payloadBytes(auth: XiaomiAuthSession?): ByteArray = byteArrayOf(
            opCode.toByte(),
            // KEY_VERSION = 01.00.00
            KEY_VERSION.toByte(), 0x03, 0x00, 0x01, 0x00, 0x00,
            // KEY_MAX_PACKET_SIZE = 0xFC00
            KEY_MAX_PACKET_SIZE.toByte(), 0x02, 0x00, 0x00, 0xFC.toByte(),
            // KEY_TX_WIN = 32
            KEY_TX_WIN.toByte(), 0x02, 0x00, 0x20, 0x00,
            // KEY_SEND_TIMEOUT = 10000ms
            KEY_SEND_TIMEOUT.toByte(), 0x02, 0x00, 0x10, 0x27,
        )

        companion object {
            const val OPCODE_START_SESSION_REQUEST = 1
            const val OPCODE_START_SESSION_RESPONSE = 2
            const val OPCODE_STOP_SESSION_REQUEST = 3
            const val OPCODE_STOP_SESSION_RESPONSE = 4

            private const val KEY_VERSION = 1
            private const val KEY_MAX_PACKET_SIZE = 2
            private const val KEY_TX_WIN = 3
            private const val KEY_SEND_TIMEOUT = 4

            fun decode(seq: Int, payload: ByteArray): SessionConfig? {
                if (payload.isEmpty()) return null
                // The first byte is the opcode; we don't currently parse the TLV
                // body — Gadgetbridge logs the values but doesn't act on them.
                val opCode = payload[0].toInt() and 0xFF
                return SessionConfig(seq, opCode)
            }
        }
    }

    class Data(
        seq: Int,
        val channel: XiaomiChannel,
        val opCode: Int,
        val payload: ByteArray,
    ) : XiaomiSppPacketV2(PACKET_TYPE_DATA, seq) {

        fun decryptedPayload(auth: XiaomiAuthSession?): ByteArray =
            if (opCode == OPCODE_SEND_ENCRYPTED && auth != null) auth.decryptV2(payload) else payload

        override fun payloadBytes(auth: XiaomiAuthSession?): ByteArray {
            val body = if (opCode == OPCODE_SEND_ENCRYPTED) {
                requireNotNull(auth) { "auth session required for encrypted V2 payload" }.encryptV2(payload)
            } else payload
            return ByteBuffer.allocate(2 + body.size)
                .put((channel.raw and 0x0F).toByte())
                .put((opCode and 0xFF).toByte())
                .put(body)
                .array()
        }

        companion object {
            const val OPCODE_SEND_PLAINTEXT = 1
            const val OPCODE_SEND_ENCRYPTED = 2

            fun opCodeFor(channel: XiaomiChannel): Int = when (channel) {
                XiaomiChannel.PROTOBUF, XiaomiChannel.ACTIVITY -> OPCODE_SEND_ENCRYPTED
                XiaomiChannel.DATA -> OPCODE_SEND_PLAINTEXT
                XiaomiChannel.UNKNOWN -> -1
            }

            fun decode(seq: Int, payload: ByteArray): Data? {
                if (payload.size < 2) return null
                val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                val raw = buf.get().toInt() and 0x0F
                val opCode = buf.get().toInt() and 0xFF
                val rest = ByteArray(buf.remaining()).also { buf.get(it) }
                return Data(seq, XiaomiChannel.fromRaw(raw), opCode, rest)
            }
        }
    }

    sealed class ParseResult {
        data object Incomplete : ParseResult()
        data class Complete(val packet: XiaomiSppPacketV2, val consumed: Int) : ParseResult()
        /**
         * Header was valid and the full frame is buffered, but the frame is
         * unusable (CRC mismatch / unknown type / undecodable body). Skip the
         * whole declared frame — XiaomiBleProtocolV2.processPacket (L311-338)
         * returns Complete(packetSize) when decode() yields null.
         */
        data class Skip(val consumed: Int) : ParseResult()
        /** Buffer does not start with the preamble — resync. */
        data object Invalid : ParseResult()
    }

    companion object {
        const val PACKET_TYPE_ACK = 1
        const val PACKET_TYPE_SESSION_CONFIG = 2
        const val PACKET_TYPE_DATA = 3

        val PREAMBLE = byteArrayOf(0xA5.toByte(), 0xA5.toByte())

        fun parse(bytes: ByteArray): ParseResult {
            if (bytes.size < 8) return ParseResult.Incomplete
            if (bytes[0] != PREAMBLE[0] || bytes[1] != PREAMBLE[1]) return ParseResult.Invalid

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.get(ByteArray(2)) // preamble
            val packetType = buf.get().toInt() and 0x0F
            val seq = buf.get().toInt() and 0xFF
            val payloadLen = buf.short.toInt() and 0xFFFF
            val givenCrc = buf.short.toInt() and 0xFFFF

            if (buf.remaining() < payloadLen) return ParseResult.Incomplete

            val payload = ByteArray(payloadLen).also { buf.get(it) }
            val frameLen = 8 + payloadLen
            if (crc16Arc(payload) != givenCrc) return ParseResult.Skip(frameLen)

            val packet = when (packetType) {
                PACKET_TYPE_ACK -> Ack(seq)
                PACKET_TYPE_SESSION_CONFIG -> SessionConfig.decode(seq, payload) ?: return ParseResult.Skip(frameLen)
                PACKET_TYPE_DATA -> Data.decode(seq, payload) ?: return ParseResult.Skip(frameLen)
                else -> return ParseResult.Skip(frameLen)
            }
            return ParseResult.Complete(packet, frameLen)
        }

        /**
         * Locate the next (possibly partial) preamble inside [buffer], returning
         * its offset or -1. A lone 0xA5 as the very last byte counts: its
         * second preamble byte may arrive in the next notification, and
         * dropping it would lose the whole next frame.
         */
        fun findNextPacketOffset(buffer: ByteArray, after: Int = 1): Int {
            var i = after
            while (i < buffer.size) {
                if (buffer[i] == PREAMBLE[0] && (i == buffer.size - 1 || buffer[i + 1] == PREAMBLE[1])) return i
                i++
            }
            return -1
        }

        /** CRC-16/ARC (poly 0x8005, init 0, no xor, refin, refout). */
        private fun crc16Arc(payload: ByteArray): Int {
            var crc = 0
            for (byte in payload) {
                for (j in 0 until 8) {
                    crc = crc shl 1
                    val bit = ((crc shr 16) and 1) xor ((byte.toInt() shr j) and 1)
                    if (bit == 1) crc = crc xor 0x8005
                }
            }
            return (Integer.reverse(crc) ushr 16) and 0xFFFF
        }
    }
}
