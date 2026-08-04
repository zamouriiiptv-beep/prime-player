package com.castivio.feature.activation

import android.graphics.Bitmap
import android.graphics.Color
import com.castivio.core.common.config.ActivationDestination
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The activation QR: one address, and nothing else in it.
 *
 * ## What it carries, stated exactly
 *
 * `ActivationDestination.URL`, character for character. Not the MAC address, not
 * the device key, not an identifier or a hash or a query parameter derived from
 * either. The payload does not depend on the device at all — two Castivio
 * installations produce byte-identical symbols, which is the cheapest possible
 * proof that nothing personal is in one.
 *
 * That is a rule and not a default. A QR is a public object: it is photographed,
 * it lands in screenshots, it gets pasted into support tickets. A device
 * identifier inside one is a credential published to everyone who can see the
 * screen. `ActivationTest` decodes the symbol this produces and fails if either
 * identifier appears anywhere in it — the guarantee is checked, not asserted.
 *
 * The user types their own MAC and key into the page after it opens. That is a
 * step, and the step is the point.
 *
 * ## What this replaced, twice
 *
 * First a ZXing encoder pointed at the raw MAC address, which is precisely the
 * thing forbidden above. Then a drawn fixture with no payload at all, correct but
 * useless: a symbol nobody can scan is decoration in the shape of a control, and
 * the screen tells a user to scan it.
 *
 * The encoder is back because there is now something safe to put in it. See
 * `design/activation-spec.md` §5.
 *
 * `RGB_565` rather than `ARGB_8888`: two colours, so half the memory buys nothing
 * lost, and on a stick with a gigabyte of RAM every avoided allocation counts.
 * Black on white regardless of theme, because a camera does not care about the
 * palette and inverting it for a dark interface is a well-meaning change that
 * stops it working.
 */
internal fun activationQrBitmap(pixels: Int): Bitmap = qrBitmap(ActivationDestination.URL, pixels)

/**
 * @param payload what goes in the symbol. Parameterised for the test that decodes
 *   it, and called with one argument everywhere else — a second call site with a
 *   different string is the defect this file exists to prevent.
 */
internal fun qrBitmap(payload: String, pixels: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        pixels,
        pixels,
        mapOf(
            // A URL this short fits at H with room to spare, and H is what
            // survives a television's reflections and a phone camera held at an
            // angle across a room.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            // ZXing's default is 4 modules. The plate already sets the symbol in
            // white with its own padding, so a second quiet zone inside the image
            // shrinks the modules for nothing.
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
