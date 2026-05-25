/*  Copyright (C) 2026 kidneyweakx
 *  AGPL-3.0-or-later. Streaming framing helper inspired by Gadgetbridge's
 *  XiaomiBleProtocolV2.processBuffer / skipBuffer routines.
 */
package com.kidneyweakx.miband9active.xiaomi.protocol

import java.io.ByteArrayOutputStream

/**
 * Accumulates bytes from the BLE notification stream and emits whole
 * [XiaomiSppPacketV2] frames once the buffer holds enough.
 *
 * Not thread-safe — call [feed] from one coroutine only.
 */
class V2PacketAccumulator {
    private val buffer = ByteArrayOutputStream()

    fun feed(value: ByteArray): List<XiaomiSppPacketV2> {
        buffer.write(value)
        val out = mutableListOf<XiaomiSppPacketV2>()
        var done = false
        while (!done) {
            val snapshot = buffer.toByteArray()
            when (val r = XiaomiSppPacketV2.parse(snapshot)) {
                XiaomiSppPacketV2.ParseResult.Incomplete -> done = true
                is XiaomiSppPacketV2.ParseResult.Complete -> {
                    out += r.packet
                    consume(r.consumed)
                }
                XiaomiSppPacketV2.ParseResult.Invalid -> {
                    val next = XiaomiSppPacketV2.findNextPacketOffset(snapshot)
                    if (next < 0) {
                        buffer.reset()
                        done = true
                    } else {
                        consume(next)
                    }
                }
            }
        }
        return out
    }

    private fun consume(n: Int) {
        if (n <= 0) return
        val left = buffer.toByteArray().copyOfRange(n, buffer.size())
        buffer.reset()
        buffer.write(left)
    }
}
