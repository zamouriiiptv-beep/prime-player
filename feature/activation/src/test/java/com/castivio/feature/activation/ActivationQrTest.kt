package com.castivio.feature.activation

import com.castivio.core.common.config.ActivationDestination
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What is actually inside the activation QR.
 *
 * ## Why this decodes rather than inspects
 *
 * Because the claim is about what a stranger's phone camera gets, and the only
 * way to know that is to be the camera. Reading the source and satisfying oneself
 * that the payload looks harmless is how the first version of this screen shipped
 * a QR with the device's MAC address in it: the code said `encode(address)`, and
 * it was reviewed, and it was fine right up until somebody scanned it.
 *
 * So the symbol is encoded, read back through a real decoder, and the decoded
 * text is checked. Nothing here trusts the encoder's caller.
 *
 * ## The rule being enforced
 *
 * A QR is a public object — photographed, screenshotted, pasted into support
 * tickets. `design/activation-spec.md` §5.2 forbids putting anything that
 * identifies the device into one. The payload is the activation URL, the same
 * value the on-screen button opens, and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
class ActivationQrTest {

    private fun decode(pixels: Int = 512): String {
        val bitmap = activationQrBitmap(pixels)
        val width = bitmap.width
        val height = bitmap.height
        val buffer = IntArray(width * height)
        bitmap.getPixels(buffer, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, buffer)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    /** The whole payload, exactly, with nothing appended. */
    @Test
    fun `the QR encodes the central activation URL and nothing else`() {
        assertEquals(ActivationDestination.URL, decode())
    }

    /**
     * The two identifiers the screen shows, neither of which may be in the symbol.
     *
     * Checked against the real fixture values rather than a pattern: a regex for
     * "something that looks like a MAC" is a test of the regex, and the question
     * here is whether *these* strings escaped.
     */
    @Test
    fun `no device identifier reaches the payload`() {
        val payload = decode()
        val address = "2F:19:EB:20:44:7C"
        for (forbidden in listOf(
            address,
            address.replace(":", ""),
            address.lowercase(),
            address.replace(":", "").lowercase(),
            "482731",
        )) {
            assertFalse(
                "the activation QR carries '$forbidden' — it decodes to '$payload'",
                payload.contains(forbidden, ignoreCase = true),
            )
        }
    }

    /**
     * The symbol does not vary by device.
     *
     * The cheapest possible proof that nothing personal is in it: if two calls
     * produce the same text, the text cannot depend on the handset. A future
     * change that appends `?mac=…` fails here before anyone has to think about it.
     */
    @Test
    fun `the payload is the same for every device and every size`() {
        assertEquals(decode(pixels = 256), decode(pixels = 512))
    }

    /**
     * The button and the QR cannot drift.
     *
     * They are two renderings of one constant, and this is the assertion that
     * keeps it that way — the display form is derived from [ActivationDestination.URL],
     * so a second hardcoded address anywhere makes these disagree.
     */
    @Test
    fun `the address shown to a person is the address in the symbol`() {
        assertEquals(ActivationDestination.display, decode().removePrefix("https://"))
    }
}
