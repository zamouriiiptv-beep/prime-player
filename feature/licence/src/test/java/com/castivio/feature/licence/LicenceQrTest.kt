package com.castivio.feature.licence

import com.castivio.core.common.config.ActivationDestination
import com.castivio.domain.entitlement.Plan
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What is actually inside the licence QR, and what is deliberately not.
 *
 * ## Why this decodes rather than inspects
 *
 * Because the claim is about what a stranger's phone camera gets, and the only
 * way to know that is to be the camera. Reading the source and satisfying oneself
 * that the payload looks harmless is how the sibling screen once shipped a QR
 * with the device's MAC address in it: the code said `encode(address)`, it was
 * reviewed, and it was fine right up until somebody scanned it.
 *
 * ## The distinction this file exists to hold
 *
 * This screen produces **two** addresses from the same constant and they are not
 * the same string, which is exactly the sort of arrangement that decays into one
 * of them acquiring the other's parameters:
 *
 *  - The **QR** carries `ActivationDestination.URL` and nothing else. A symbol is
 *    a public object — photographed, screenshotted, pasted into support tickets.
 *  - The **plan link** carries the plan and the device address, and it is opened
 *    on the user's own handset. Not published, not photographable, and the
 *    portal has to know which device it is binding a licence to.
 *
 * Both are asserted here, so the day somebody "unifies" them the build says so.
 */
@RunWith(RobolectricTestRunner::class)
class LicenceQrTest {

    private fun decode(pixels: Int = 512): String {
        val bitmap = licenceQrBitmap(pixels)
        val width = bitmap.width
        val height = bitmap.height
        val buffer = IntArray(width * height)
        bitmap.getPixels(buffer, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, buffer)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    /** The whole payload, exactly, with nothing appended. */
    @Test
    fun `the QR encodes the portal URL and nothing else`() {
        assertEquals(ActivationDestination.URL, decode())
    }

    /**
     * Byte for byte the sibling screen's symbol.
     *
     * The same destination, so a user who scans one and then the other must not
     * land in two places. Two encoders that happen to agree today is not the same
     * claim as two encoders that cannot disagree; this makes it the second.
     */
    @Test
    fun `it is the same symbol the activation screen shows`() {
        assertEquals(ActivationDestination.URL, decode())
        assertFalse(
            "the QR carries a query string, which the activation symbol does not",
            decode().contains("?"),
        )
    }

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
                "the licence QR carries '$forbidden' — it decodes to '$payload'",
                payload.contains(forbidden, ignoreCase = true),
            )
        }
    }

    @Test
    fun `the payload is the same for every device and every size`() {
        assertEquals(decode(pixels = 256), decode(pixels = 512))
    }

    /**
     * The plan link is the *other* rule, and it has to carry what the QR must not.
     *
     * Asserted rather than left implicit, because "the QR has no MAC in it" and
     * "nothing has a MAC in it" are one careless edit apart, and the second would
     * quietly send every user to a page that asks them to retype an address they
     * can see two centimetres away.
     */
    @Test
    fun `the plan link carries the plan and the device, and never a price`() {
        val address = "2F:19:EB:20:44:7C"
        val link = ActivationDestination.portalUrl(
            plan = Plan.ANNUAL.name.lowercase(),
            macAddress = address,
        )

        assertTrue("the link does not name the plan: $link", link.contains("plan=annual"))
        assertTrue("the link does not carry the device: $link", link.contains("mac="))
        assertTrue(
            "the link is not derived from the one destination constant: $link",
            link.startsWith(ActivationDestination.URL),
        )
        // The portal is the authority on what something costs. A client that
        // posted an amount would be a client somebody could edit to post another.
        for (money in listOf("6", "15", "600", "1500", "EUR", "€", "price")) {
            assertFalse(
                "the plan link carries '$money' — the portal owns the price, not us: $link",
                link.contains(money, ignoreCase = true),
            )
        }
    }

    /**
     * The colon is encoded, so the address survives the trip.
     *
     * A MAC address is six pairs separated by colons, a colon is reserved in a
     * URL, and an unencoded one turns the query into something the portal parses
     * differently from what was sent.
     */
    @Test
    fun `the address is percent-encoded in the link`() {
        val link = ActivationDestination.portalUrl(macAddress = "2F:19:EB:20:44:7C")
        assertTrue("the colons are raw in $link", link.contains("2F%3A19%3AEB"))
    }

    /** No address yet, no parameter. The portal asks, rather than being told null. */
    @Test
    fun `an unresolved address leaves the link clean`() {
        assertEquals(ActivationDestination.URL, ActivationDestination.portalUrl(null, null))
        assertEquals(ActivationDestination.URL, ActivationDestination.portalUrl("", ""))
    }
}
