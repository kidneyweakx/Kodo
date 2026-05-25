/*  Copyright (C) 2024 Yoran Vulker                       (Gadgetbridge)
 *  Copyright (C) 2026 kidneyweakx                          (Kotlin port, slimmed)
 *
 *  Notification icon → 24×24 RGB565 / ARGB8565 byte buffers, the format Mi
 *  Band 9 Active accepts. Larger icons waste BLE airtime and flash on the
 *  band; we always downscale.
 *
 *  AGPL-3.0-or-later. See LICENSE, NOTICE.md.
 */
package com.kidneyweakx.miband9active.xiaomi.notifications

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import java.nio.ByteBuffer
import java.nio.ByteOrder

object IconConverter {
    const val DEFAULT_SIZE = 24

    /** Render a Drawable into an [size × size] ARGB_8888 bitmap. */
    fun fit(drawable: Drawable, size: Int = DEFAULT_SIZE): Bitmap {
        val originalBounds = drawable.copyBounds()
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(result).also { drawable.setBounds(0, 0, size, size); drawable.draw(it) }
        drawable.bounds = originalBounds
        return result
    }

    /** Convert ARGB_8888 → little-endian RGB565 byte buffer. */
    fun toRgb565(bitmap: Bitmap): ByteArray {
        val buf = ByteBuffer.allocate(bitmap.width * bitmap.height * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val px = bitmap.getPixel(x, y)
                val r = Color.red(px) shr 3
                val g = Color.green(px) shr 2
                val b = Color.blue(px) shr 3
                buf.putShort(((r shl 11) or (g shl 5) or b).toShort())
            }
        }
        return buf.array()
    }

    /** ARGB_8565: alpha byte + RGB565 (little endian per pixel). */
    fun toArgb8565(bitmap: Bitmap): ByteArray {
        val buf = ByteBuffer.allocate(bitmap.width * bitmap.height * 3).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val px = bitmap.getPixel(x, y)
                val a = Color.alpha(px)
                val r = Color.red(px) shr 3
                val g = Color.green(px) shr 2
                val b = Color.blue(px) shr 3
                buf.put(a.toByte())
                buf.putShort(((r shl 11) or (g shl 5) or b).toShort())
            }
        }
        return buf.array()
    }
}
