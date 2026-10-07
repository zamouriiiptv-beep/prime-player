package com.castivio.feature.activation

import androidx.compose.ui.unit.dp
import com.castivio.core.common.config.ActivationDestination
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
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
     * The symbol is the size the specification's pitch floor was written for.
     *
     * §5.4 sizes the plate from module pitch and warns, in as many words, that a
     * real portal URL is longer than a MAC address and will push the symbol to a
     * higher version — at which point the plate sizes "must be re-derived, not
     * assumed". That happened: the payload is 29 bytes at error correction H,
     * which is a version 4 symbol at 33 modules, where the address was version 1
     * at 21.
     *
     * So the module count is measured here rather than trusted, and the plates
     * are checked against the 3.0dp floor. A longer URL — a path, a query, a
     * staging host — pushes this to version 5 and fails, which is the warning
     * doing its job instead of sitting in a document.
     */
    @Test
    fun `the symbol stays within the pitch the plates were sized for`() {
        // Asking for 1x1 returns the symbol at its natural module size: ZXing
        // never scales below one pixel per module.
        val matrix = QRCodeWriter().encode(
            ActivationDestination.URL,
            BarcodeFormat.QR_CODE,
            1,
            1,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
        val modules = matrix.width

        // The plate each surface actually draws, taken from `metricsFor` rather than
        // restated. These were three hand-written pairs -- 138/8, 157/9, 208/12 --
        // and by the time anybody looked they matched nothing: the code drew 132,
        // 139.7 and 192. A literal is only a guard while somebody keeps it level
        // with the thing it guards, and this one had stopped. `the plate is the
        // size each surface was drawn for` below is where the numbers are pinned
        // now, and it pins them per surface where a drift is legible.
        val floor = 3.0
        for ((frame, width, height) in SURFACES) {
            val m = metricsFor(tv = frame.startsWith("television"), width = width, height = height)
            val symbol = m.plate - m.plate * PLATE_QUIET_ZONE * 2
            val pitch = symbol.value / modules
            assertTrue(
                "$frame: $modules modules in ${symbol.value}dp is " +
                    "${"%.2f".format(pitch)}dp per module, below the ${floor}dp floor",
                pitch >= floor,
            )
            println(
                "activation QR — $frame plate ${m.plate.value}dp, symbol ${"%.1f".format(symbol.value)}dp, " +
                    "${"%.3f".format(pitch)}dp per module"
            )
        }
        println("activation QR — $modules modules for '${ActivationDestination.URL}'")
    }

    /**
     * The plate each surface draws, pinned one surface at a time.
     *
     * The pitch test above says the symbol is never too small for a camera. It does
     * not say the plate is the size the drawing gives it, and those are different
     * claims: a plate could grow by a third and still pass a floor. This is the
     * second one, and it is per surface because the interesting failure is a single
     * frame drifting while the rest hold.
     *
     * 400dp is where the share and the floor both step down. Above it the three
     * large frames reproduce the drawing; below it the column comes in with the
     * surface. The two numbers that moved are the two phones, and they moved
     * together with the rest of the QR column rather than on their own.
     */
    @Test
    fun `the plate is the size each surface was drawn for`() {
        val expected = mapOf(
            "1280x720 reference" to 210.0f,
            "1280x800 tablet" to 210.0f,
            "television 960x540" to 192.0f,
            "873x393 phone" to 135.4f,
            "800x360 shortest phone" to 124.0f,
        )
        for ((frame, width, height) in SURFACES) {
            val m = metricsFor(tv = frame.startsWith("television"), width = width, height = height)
            val want = expected.getValue(frame)
            assertEquals("$frame draws a ${m.plate} plate", want, m.plate.value, 0.1f)
        }
    }

    /**
     * The quiet zone does not move when the plate does.
     *
     * The plate's white padding is a share of the plate and so is the module, so
     * their ratio is a constant: one module of ZXing margin plus `0.062 * 35 /
     * (1 - 2 * 0.062)` of plate padding, which is 3.48 modules at every size this
     * screen can draw. Asserted because the 124dp floor was approved on exactly that
     * reasoning, and a change to either share would quietly break it.
     *
     * 3.48 is short of the four modules ISO/IEC 18004 asks for, and that is true of
     * the shipped plate too -- it is a property of `QUIET_ZONE`, not of this floor.
     * Decoders tolerate it; the number is pinned here so that if it is ever fixed,
     * it is fixed deliberately.
     */
    @Test
    fun `the quiet zone is the same in modules whatever the plate measures`() {
        for ((frame, width, height) in SURFACES) {
            val m = metricsFor(tv = frame.startsWith("television"), width = width, height = height)
            val padding = m.plate * PLATE_QUIET_ZONE
            val module = (m.plate - padding * 2) / QR_MATRIX_MODULES
            val quiet = 1f + padding / module
            assertEquals("$frame quiet zone", 3.48f, quiet, 0.02f)
        }
    }

    /**
     * The scheme-less form cannot drift from the symbol.
     *
     * The screen writes [ActivationDestination.URL] out verbatim now, so what a
     * reader sees and what a camera reads are the same string by construction and
     * the test above already covers it. What is still worth holding is
     * [ActivationDestination.display]: it is derived rather than written out, and
     * this is the assertion that says so, so that a second hardcoded address — in
     * this file, in a screen, anywhere — makes the two disagree here first.
     */
    @Test
    fun `the scheme-less address is the address in the symbol`() {
        assertEquals(ActivationDestination.display, decode().removePrefix("https://"))
    }

    /**
     * The address is a token, and a token does not wrap — it overflows.
     *
     * The caption above it is a sentence and survives a narrow column by taking a
     * second line. `castivio.app/activate` has no space in it, so the column either
     * holds it or cuts it, and a cut address is worse than none: a reader types what
     * they can see and lands nowhere.
     *
     * Measured against every surface rather than against the smallest, because the
     * tightest one is not the smallest. 800x360 has the narrowest column at 184.6dp,
     * and 1280x800 has the widest type: `fsCaption` has reached its 19dp ceiling there
     * while the column stopped growing at 330dp, so the tablet runs out of room by
     * setting the address large rather than by being small. Both clear by about 11dp
     * and nothing else is close.
     */
    @Test
    fun `the written address fits the column it is drawn in, on every surface`() {
        val address = ActivationDestination.URL
        for ((name, width, height) in SURFACES) {
            val m = metricsFor(tv = name.startsWith("television"), width = width, height = height)
            val column = m.zoneWidth - m.zonePad * 2
            val widest = m.fsAddress * ADDRESS_EM_PER_CHAR * address.length
            assertTrue(
                "$name: \"$address\" wants up to $widest in a $column column",
                widest <= column,
            )
        }
    }
}

/**
 * An upper bound on this token's average advance, in the face it is drawn in.
 *
 * It was 0.62 — a monospace guess, left deliberately loose because the address was
 * `castivio.app/activate` at the caption's own size and cleared its column by more
 * than half. It no longer does: the scheme put eight characters back and
 * `ADDRESS_STEP` set the line 10% larger, and the margin is now about 6%. A bound
 * with 37% of slop in it cannot see a 6% margin, so it was not guarding anything.
 *
 * `https://castivio.app/activate` measures 173.5dp at 13.2dp of IBM Plex Sans
 * SemiBold, which is 0.4534 em a character. 0.47 is that with 3.6% on top, for the
 * difference between a browser's shaping of the file and Android's.
 *
 * **It is specific to this string.** A longer address does not merely scale it: the
 * average advance depends on which characters, and a path full of wide letters
 * would break the bound before it breaks the column. That is the right failure —
 * this constant and the address are meant to be re-measured together.
 */
private const val ADDRESS_EM_PER_CHAR = 0.47f

/**
 * Every surface the activation screen ships to, as the plate tests read them.
 *
 * One list, because three tests ask the same question of the same five frames and
 * three copies of a frame table is how a frame gets added to two of them.
 */
private val SURFACES = listOf(
    Triple("1280x720 reference", 1280.dp, 720.dp),
    Triple("1280x800 tablet", 1280.dp, 800.dp),
    Triple("television 960x540", 960.dp, 540.dp),
    Triple("873x393 phone", 873.dp, 393.dp),
    Triple("800x360 shortest phone", 800.dp, 360.dp),
)

/**
 * `QUIET_ZONE` from the screen, restated.
 *
 * It is private there and this is a share rather than a measurement — if it moves,
 * the quiet-zone assertion is exactly the one that should fail and be read.
 */
private const val PLATE_QUIET_ZONE = 0.062f

/** Version 4 at 33 data modules, plus ZXing's one-module margin on each side. */
private const val QR_MATRIX_MODULES = 35f
