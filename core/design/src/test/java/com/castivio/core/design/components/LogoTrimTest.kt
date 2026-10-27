package com.castivio.core.design.components

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.Inflater
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

    /** A picture's size and the alpha of each pixel, row by row. */
    private class Alpha(val width: Int, val height: Int, private val values: ByteArray) {
        fun at(x: Int, y: Int): Int = values[y * width + x].toInt() and 0xFF
    }

    /**
     * Reads the alpha of an 8-bit RGBA PNG. `javax.imageio` is not part of what an Android module compiles against, and
     * a unit test has no bitmap decoder, so the file is read as the format has it: chunks, one deflate stream, and a
     * filter byte before every row.
     */
    private fun alphaOf(file: File): Alpha {
        val bytes = ByteBuffer.wrap(file.readBytes())
        bytes.position(SIGNATURE_LENGTH)
        var width = 0
        var height = 0
        val data = ByteArrayOutputStream()
        while (bytes.remaining() > CHUNK_OVERHEAD) {
            val length = bytes.int
            val type = ByteArray(TYPE_LENGTH).also { bytes.get(it) }.toString(Charsets.US_ASCII)
            val body = ByteArray(length).also { bytes.get(it) }
            bytes.position(bytes.position() + CRC_LENGTH)
            when (type) {
                "IHDR" -> {
                    val header = ByteBuffer.wrap(body)
                    width = header.int
                    height = header.int
                    assertEquals("8 bits a channel", 8, header.get().toInt())
                    assertEquals("RGBA", COLOUR_TYPE_RGBA, header.get().toInt())
                    header.get()
                    header.get()
                    assertEquals("not interlaced", 0, header.get().toInt())
                }
                "IDAT" -> data.write(body)
            }
        }
        val stride = width * CHANNELS
        val raw = ByteArray((stride + 1) * height)
        Inflater().run {
            setInput(data.toByteArray())
            assertEquals(raw.size, inflate(raw))
            end()
        }
        val rows = Array(height) { ByteArray(stride) }
        for (y in 0 until height) {
            val filter = raw[y * (stride + 1)].toInt()
            for (i in 0 until stride) {
                val value = raw[y * (stride + 1) + 1 + i].toInt() and 0xFF
                val left = if (i >= CHANNELS) rows[y][i - CHANNELS].toInt() and 0xFF else 0
                val up = if (y > 0) rows[y - 1][i].toInt() and 0xFF else 0
                val upLeft = if (y > 0 && i >= CHANNELS) rows[y - 1][i - CHANNELS].toInt() and 0xFF else 0
                rows[y][i] = when (filter) {
                    0 -> value
                    1 -> value + left
                    2 -> value + up
                    3 -> value + (left + up) / 2
                    4 -> value + paeth(left, up, upLeft)
                    else -> error("a PNG filter of $filter")
                }.toByte()
            }
        }
        val alpha = ByteArray(width * height)
        for (y in 0 until height) for (x in 0 until width) alpha[y * width + x] = rows[y][x * CHANNELS + ALPHA_CHANNEL]
        return Alpha(width, height, alpha)
    }

    private fun paeth(left: Int, up: Int, upLeft: Int): Int {
        val estimate = left + up - upLeft
        val fromLeft = kotlin.math.abs(estimate - left)
        val fromUp = kotlin.math.abs(estimate - up)
        val fromUpLeft = kotlin.math.abs(estimate - upLeft)
        return when {
            fromLeft <= fromUp && fromLeft <= fromUpLeft -> left
            fromUp <= fromUpLeft -> up
            else -> upLeft
        }
    }

    /** The share of each side the picture's visible part spans, by the alpha the picture carries. */
    private fun visibleShare(): Pair<Double, Double> {
        val image = alphaOf(logo)
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.at(x, y) > VISIBLE_ALPHA) {
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
        val image = alphaOf(logo)

        assertEquals(true, image.width >= image.height)
    }

    private companion object {
        /** An alpha above the glow's faintest edge, which is not the mark. */
        const val VISIBLE_ALPHA = 30

        const val SIGNATURE_LENGTH = 8
        const val TYPE_LENGTH = 4
        const val CRC_LENGTH = 4
        const val CHUNK_OVERHEAD = 4 + TYPE_LENGTH + CRC_LENGTH
        const val COLOUR_TYPE_RGBA = 6
        const val CHANNELS = 4
        const val ALPHA_CHANNEL = 3

        /** The share of each side the television must span: all of it but the pad a trim leaves. */
        const val FILLS = 0.95
    }
}
