package com.castivio.feature.licence

import android.graphics.Bitmap
import android.graphics.Color
import com.castivio.core.common.config.ActivationDestination
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The licence screen's QR: the portal address, and nothing else in it.
 *
 * Byte for byte the same payload the activation screen's symbol carries —
 * `ActivationDestination.URL` — because it is the same destination and a user
 * who scanned one and then the other must not land in two places.
 *
 * **Not the link the plan cards open.** That one carries the plan and the device
 * address, and it is a private navigation on the user's own handset. A QR is a
 * public object: photographed, screenshotted, pasted into support tickets. A
 * device identifier inside one is a credential published to everyone who can see
 * the screen, and `LicenceQrTest` decodes this symbol to prove neither is there.
 */
internal fun licenceQrBitmap(pixels: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        ActivationDestination.URL,
        BarcodeFormat.QR_CODE,
        pixels,
        pixels,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )

    val width = matrix.width
    val height = matrix.height
    val buffer = IntArray(width * height)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            buffer[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).apply {
        setPixels(buffer, 0, width, 0, 0, width, height)
    }
}
