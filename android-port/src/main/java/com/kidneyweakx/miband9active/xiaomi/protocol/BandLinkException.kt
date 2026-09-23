/*
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
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
 */
package com.kidneyweakx.miband9active.xiaomi.protocol

/**
 * Connection / pairing failure with a machine-readable [code] prefix.
 *
 * The message is always `"<CODE>: <detail>"` and [toString] returns just the
 * message: fbjni builds the JS Error text from `Throwable.toString()`, which
 * would otherwise prepend the Java class name and break the "message starts
 * with the code" contract the onboarding UI relies on.
 *
 * Extends [IllegalStateException] so callers of `DriverHolder.ensureConnected`
 * that catch ISE keep working.
 */
class BandLinkException(
    val code: String,
    detail: String,
    cause: Throwable? = null,
) : IllegalStateException("$code: $detail", cause) {

    override fun toString(): String = message ?: code

    companion object {
        const val AUTH_KEY_INVALID = "AUTH_KEY_INVALID"
        const val AUTH_REJECTED = "AUTH_REJECTED"
        const val BT_OFF = "BT_OFF"
        const val PERMISSION = "PERMISSION"
        const val TIMEOUT = "TIMEOUT"
        const val GATT = "GATT"
        const val NOT_PAIRED = "NOT_PAIRED"

        /** Wrap any throwable into a coded exception (SecurityException → PERMISSION). */
        fun from(t: Throwable): BandLinkException = when (t) {
            is BandLinkException -> t
            is SecurityException -> BandLinkException(PERMISSION, t.message ?: "Bluetooth permission missing", t)
            else -> BandLinkException(GATT, t.message ?: t.javaClass.simpleName, t)
        }
    }
}
