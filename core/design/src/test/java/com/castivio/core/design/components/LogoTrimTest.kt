package com.castivio.core.design.components

import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The band's mark is a picture of the television and nothing else.
 *
 * It read small on a handset because the picture was a portrait canvas with the television in its middle third: drawn
 * as tall as the band, the television was 38% of that height. A child cannot be taller than the band it stands in, so
 * the mark can only be made larger by taking the empty ground out of the picture -- and this keeps it out. If a new
 * logo is dropped in with margins, this fails, and the band's mark is small again.
 */
class LogoTrimTest {

    private val logo = File("src/main/res/drawable-nodpi/castivio_logo.png")

    /** The share of each side the picture's visible part spans, by the alpha the picture carries. */
    private fun visibleShare(): Pair<Double, Double> {
        val image = ImageIO.read(logo)
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val alpha = image.getRGB(x, y) ushr 24
            if (alpha > VISIBLE_ALPHA) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return (right - left + 1).toDouble() / image.width to (bottom - top + 1).toDouble() / image.height
    }

    @Test
    fun `the mark's picture is there to be read`() {
        assertTrue("run from the module: ${logo.absolutePath}", logo.exists())
    }

    @Test
    fun `the television fills the picture, with no empty ground around it`() {
        val (across, down) = visibleShare()

        assertTrue("the television spans $across of the picture's width", across >= FILLS)
        assertTrue("the television spans $down of the picture's height", down >= FILLS)
    }

    @Test
    fun `the picture is wider than it is tall, so the mark's width is no more than its height asks`() {
        val image = ImageIO.read(logo)

        assertEquals(true, image.width >= image.height)
    }

    private companion object {
        /** An alpha above the glow's faintest edge, which is not the mark. */
        const val VISIBLE_ALPHA = 30

        /** The share of each side the television must span: all of it but the pad a trim leaves. */
        const val FILLS = 0.95
    }
}
