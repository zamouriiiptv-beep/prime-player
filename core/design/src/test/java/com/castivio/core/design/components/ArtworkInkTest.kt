package com.castivio.core.design.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which logos get a light plate, judged on a sample of their pixels: the rule of [ArtworkInk], without a screen.
 *
 * The samples are drawn by hand to be the logos that were seen -- a coloured dot with a near-black digit, a coloured wordmark, a white
 * one -- on the 32 by 32 square the real sample is.
 */
class ArtworkInkTest {

    private val clear = 0x00000000
    private val black = 0xFF17161F.toInt()
    private val red = 0xFFEE2B47.toInt()
    private val white = 0xFFF4F4FA.toInt()
    private val blue = 0xFF2F7BFF.toInt()
    private val grey = 0xFF8D8E9E.toInt()

    /** A square of [side] by [side] clear pixels, with [paint] given each pixel's place to set. */
    private fun sample(side: Int = ArtworkInk.SAMPLE, paint: (x: Int, y: Int) -> Int?): IntArray =
        IntArray(side * side) { paint(it % side, it / side) ?: clear }

    @Test
    fun `a coloured dot with a near-black digit, as France 2 is drawn, gets a plate`() {
        // The dot is the left fifth of the sample, the digit the middle two fifths: more black than colour.
        val logo = sample { x, y ->
            when {
                y in 10..21 && x in 2..11 -> red
                y in 6..25 && x in 14..27 -> black
                else -> null
            }
        }

        assertTrue(ArtworkInk.needsPlate(logo))
    }

    @Test
    fun `a black wordmark on a clear ground gets a plate`() {
        val logo = sample { x, y -> if (y in 12..19 && x in 3..28) black else null }

        assertTrue(ArtworkInk.needsPlate(logo))
    }

    @Test
    fun `a coloured logo is left as it is, a little black in it or none`() {
        val plain = sample { x, y -> if (y in 8..23 && x in 4..27) blue else null }
        val withBlack = sample { x, y ->
            when {
                y in 8..23 && x in 4..22 -> blue
                y in 8..23 && x in 23..27 -> black
                else -> null
            }
        }

        assertFalse(ArtworkInk.needsPlate(plain))
        assertFalse(ArtworkInk.needsPlate(withBlack))
    }

    @Test
    fun `a white or a mid-grey logo is left as it is`() {
        val light = sample { x, y -> if (y in 10..21 && x in 3..28) white else null }
        val middle = sample { x, y -> if (y in 10..21 && x in 3..28) grey else null }

        assertFalse(ArtworkInk.needsPlate(light))
        assertFalse(ArtworkInk.needsPlate(middle))
    }

    @Test
    fun `a picture that fills its box brings its own ground and gets no plate`() {
        val tile = sample { _, _ -> black }
        val almost = sample { x, _ -> if (x < 30) black else null }

        assertFalse("a black tile is its own ground", ArtworkInk.needsPlate(tile))
        assertFalse("and so is one with a margin of a pixel or two", ArtworkInk.needsPlate(almost))
    }

    @Test
    fun `nothing to judge is not dark`() {
        assertFalse(ArtworkInk.needsPlate(IntArray(0)))
        assertFalse("an empty square", ArtworkInk.needsPlate(sample { _, _ -> null }))
        assertFalse("a speck", ArtworkInk.needsPlate(sample { x, y -> if (x == 0 && y == 0) black else null }))
    }

    @Test
    fun `a faint pixel is the margin, not the picture`() {
        // Alpha 0x40 is under half: it is the soft edge of an anti-aliased mark, which does not make a white logo dark.
        val soft = 0x40000000
        val logo = sample { x, y ->
            when {
                y in 10..21 && x in 4..27 -> white
                y in 8..9 || y in 22..23 -> soft
                else -> null
            }
        }

        assertFalse(ArtworkInk.needsPlate(logo))
    }

    @Test
    fun `the threshold is where it was drawn`() {
        // Every opaque pixel the same grey, with the share of them under the own-ground limit: just under the threshold is dark,
        // just over is not.
        fun grey(level: Int) = sample { x, y -> if (y in 8..23 && x in 4..27) (0xFF shl 24) or (level shl 16) or (level shl 8) or level else null }

        assertTrue(ArtworkInk.needsPlate(grey((ArtworkInk.DARK_BELOW * 255 - 3).toInt())))
        assertFalse(ArtworkInk.needsPlate(grey((ArtworkInk.DARK_BELOW * 255 + 3).toInt())))
    }
}
