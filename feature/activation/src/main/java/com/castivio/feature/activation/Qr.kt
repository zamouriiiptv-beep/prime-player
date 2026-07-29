package com.castivio.feature.activation

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * A QR code as a bitmap.
 *
 * `RGB_565` rather than `ARGB_8888`: the image is two colours, so half the memory buys
 * nothing lost, and on a stick with a gigabyte of RAM every avoided allocation counts.
 *
 * Black on white regardless of theme — a code has to be scannable by a camera, and a
 * camera does not care about the palette. Inverting it for a dark interface is a
 * well-meaning change that stops it working.
 */
internal fun qrBitmap(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(EncodeHintType.MARGIN to QUIET_ZONE),
    )

    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }

    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

/** Modules of white margin. One is the minimum a scanner tolerates. */
private const val QUIET_ZONE = 1
