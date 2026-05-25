/*  Copyright (C) 2024 José Rebelo            (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx            (Kotlin port)
 *  AGPL-3.0-or-later.
 */
package com.kidneyweakx.miband9active.xiaomi.activity

import java.nio.ByteBuffer

/**
 * Bit-packed sample stream reader. Each row in the daily-details file is
 * preceded by a nibble-based header that flags which fields the row actually
 * carries; this class walks that header and consumes the bytes the header
 * says exist.
 *
 * Translated 1:1 from `XiaomiComplexActivityParser.java` in Gadgetbridge.
 */
class XiaomiComplexActivityParser(private val header: ByteArray, private val buf: ByteBuffer) {

    private var currentGroup = -1
    private var currentGroupBits = 0
    private var currentVal = 0

    fun reset() {
        currentGroup = -1
        currentGroupBits = 0
        currentVal = 0
    }

    /** Begins the next group of nBits. Returns whether the group exists. */
    fun nextGroup(nBits: Int): Boolean {
        currentGroup++
        if (currentGroup >= header.size * 2) {
            // Defensive consume so the caller's loop terminates.
            consume(nBits)
            return false
        }
        if ((currentNibble() and 8) == 0) {
            // Group absent → do not consume.
            return false
        }
        currentGroupBits = nBits
        currentVal = consume(nBits)
        return (currentNibble() and 8) != 0
    }

    private fun consume(nBits: Int): Int = when (nBits) {
        8 -> buf.get().toInt() and 0xFF
        16 -> buf.short.toInt() and 0xFFFF
        32 -> buf.int
        else -> throw IllegalArgumentException("Unsupported nBits=$nBits")
    }

    private fun currentNibble(): Int {
        val byte = header[currentGroup / 2].toInt() and 0xFF
        return if (currentGroup % 2 == 0) (byte and 0xF0) shr 4 else byte and 0x0F
    }

    fun hasFirst(): Boolean = isValid(0)
    fun hasSecond(): Boolean = isValid(1)
    fun hasThird(): Boolean = isValid(2)

    fun isValid(idx: Int): Boolean {
        require(idx in 0..2) { "idx out of range: $idx" }
        return (currentNibble() and (1 shl (2 - idx))) != 0
    }

    fun get(idx: Int, nBits: Int): Int {
        val shift = currentGroupBits - idx - nBits
        return (currentVal and (((1 shl nBits) - 1) shl shift)) ushr shift
    }
}
