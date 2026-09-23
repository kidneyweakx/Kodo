/*
 * Copyright (C) 2024 José Rebelo, Yoran Vulker (Gadgetbridge)
 *
 * mi-band-9-active — a slim Mi Band 9 Active companion app
 * Copyright (C) 2026 kidneyweakx
 *
 * Portions ported from Gadgetbridge (AGPL-3.0-or-later) — see NOTICE.md
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 */
package com.kidneyweakx.miband9active.xiaomi.notifications

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Notification icon → raw pixel buffer in the format the band asks for.
 * Straight port of XiaomiBitmapUtils.convertToPixelFormat and friends.
 *
 * The band states pixel format AND size in NotificationIconRequest; the upload
 * is interpreted as exactly size×size×bpp, so we must never substitute our own
 * size (the old "always 24×24" code produced buffers the band could not parse).
 */
object IconConverter {
    const val PIXEL_FORMAT_RGB_565_LE = 0
    const val PIXEL_FORMAT_RGB_565_BE = 1
    const val PIXEL_FORMAT_XRGB_8888_LE = 2
    const val PIXEL_FORMAT_ARGB_8888_LE = 3
    const val PIXEL_FORMAT_ARGB_8565_LE = 7
    const val PIXEL_FORMAT_ABGR_8565_LE = 8

    fun pixelFormatName(pixelFormat: Int): String = when (pixelFormat) {
        PIXEL_FORMAT_RGB_565_LE -> "RGB_565_LE"
        PIXEL_FORMAT_RGB_565_BE -> "RGB_565_BE"
        PIXEL_FORMAT_XRGB_8888_LE -> "XRGB_8888_LE"
        PIXEL_FORMAT_ARGB_8888_LE -> "ARGB_8888_LE"
        PIXEL_FORMAT_ARGB_8565_LE -> "ARGB_8565_LE"
        PIXEL_FORMAT_ABGR_8565_LE -> "ABGR_8565_LE"
        else -> "UNKNOWN"
    }

    /** @return null for an unknown pixel format (upstream logs + returns null too). */
    fun convertToPixelFormat(pixelFormat: Int, drawable: Drawable, width: Int, height: Int): ByteArray? {
        val encode: (Bitmap) -> ByteArray = when (pixelFormat) {
            // Upstream's convertToRgb565B also passes littleEndian=true; mirrored
            // as-is since that is what has been validated against real bands.
            PIXEL_FORMAT_RGB_565_LE, PIXEL_FORMAT_RGB_565_BE -> { b -> toRgb565(b, littleEndian = true) }
            PIXEL_FORMAT_XRGB_8888_LE, PIXEL_FORMAT_ARGB_8888_LE -> { b -> toArgb8888(b) }
            PIXEL_FORMAT_ARGB_8565_LE -> { b -> toArgb8565(b, swapChannels = false) }
            PIXEL_FORMAT_ABGR_8565_LE -> { b -> toArgb8565(b, swapChannels = true) }
            else -> return null
        }
        val bitmap = fit(drawable, width, height)
        return try { encode(bitmap) } finally { bitmap.recycle() }
    }

    private fun fit(drawable: Drawable, width: Int, height: Int): Bitmap {
        val originalBounds = drawable.copyBounds()
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        drawable.bounds = originalBounds
        return result
    }

    private fun pixels(bitmap: Bitmap): IntArray {
        val out = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(out, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return out
    }

    fun toRgb565(bitmap: Bitmap, littleEndian: Boolean): ByteArray {
        val px = pixels(bitmap)
        val buffer = ByteBuffer.allocate(px.size * 2)
        if (littleEndian) buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (pixel in px) {
            val r = (pixel shr 19) and 0x1f
            val g = (pixel shr 10) and 0x3f
            // Upstream uses `pixel & 0x1f` here (low 5 bits of the blue byte → wrong
            // blue). Its own 8565 path uses `>> 3`; we use the correct top 5 bits.
            val b = (pixel shr 3) and 0x1f
            buffer.putShort(((r shl 11) or (g shl 5) or b).toShort())
        }
        return buffer.array()
    }

    /** RGB565 (LE uint16) followed by the alpha byte — "int24" per pixel, as upstream. */
    fun toArgb8565(bitmap: Bitmap, swapChannels: Boolean): ByteArray {
        val px = pixels(bitmap)
        val buffer = ByteBuffer.allocate(px.size * 3).order(ByteOrder.LITTLE_ENDIAN)
        for (pixel in px) {
            val a = (pixel ushr 24) and 0xff
            val r = (pixel shr 19) and 0x1f
            val g = (pixel shr 10) and 0x3f
            val b = (pixel shr 3) and 0x1f
            val hi = if (swapChannels) b else r
            val lo = if (swapChannels) r else b
            buffer.putShort(((hi shl 11) or (g shl 5) or lo).toShort())
            buffer.put(a.toByte())
        }
        return buffer.array()
    }

    fun toArgb8888(bitmap: Bitmap): ByteArray {
        val px = pixels(bitmap)
        val buffer = ByteBuffer.allocate(px.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (pixel in px) buffer.putInt(pixel)
        return buffer.array()
    }
}
