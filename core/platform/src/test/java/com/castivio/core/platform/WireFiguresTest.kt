package com.castivio.core.platform

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the wire lines say, and that they say it only when it is so.
 *
 * Pure: the figures are given, so a line can be checked without a connection. The point of each case is a
 * *reading* the owner will make from the strip -- compressed or not, waiting on the connection or not -- so each
 * is pinned by the words that carry that reading, not by the whole sentence.
 */
class WireFiguresTest {

    private val megabyte = 1_048_576L

    private fun figures(
        protocols: List<String> = listOf("h2"),
        asked: List<String> = listOf("gzip"),
        encodings: List<String> = emptyList(),
        encodedCalls: Int = 0,
        declaredCalls: Int = 1,
        declaredBytes: Long = 6 * megabyte,
        metered: Int = 1,
        decodedBytes: Long = 18 * megabyte,
        sniffedGzipCalls: Int = 0,
        setupMs: Long = 50,
        firstByteMs: Long = 1_200,
        transferMs: Long = 20_000,
        blockedMs: Long = 19_000,
    ) = WireFigures(
        protocols, asked, encodings, encodedCalls, declaredCalls, declaredBytes, metered, decodedBytes,
        sniffedGzipCalls, setupMs, firstByteMs, transferMs, blockedMs,
    )

    private fun text(f: WireFigures, wire: Long = 6 * megabyte, calls: Int = 1) =
        f.describe(wire, calls).joinToString("\n")

    @Test
    fun `a provider that compresses is read from the encoding it answered with and the size it saved`() {
        val line = text(figures(encodings = listOf("gzip"), encodedCalls = 1))

        assertTrue(line, "asked gzip · answered gzip (1/1)" in line)
        assertTrue(line, "6.0 MB on the wire → 18.0 MB decoded ×3.0" in line)
    }

    @Test
    fun `a provider that does not compress says so, and the ratio is one`() {
        val line = text(figures(declaredBytes = 18 * megabyte), wire = 18 * megabyte)

        assertTrue(line, "answered with no Content-Encoding" in line)
        assertTrue(line, "18.0 MB on the wire → 18.0 MB decoded ×1.0" in line)
    }

    @Test
    fun `gzip with no header is named as the app having unpacked it`() {
        val line = text(figures(sniffedGzipCalls = 1))

        assertTrue(line, "answered with no Content-Encoding" in line)
        assertTrue(line, "gzip in the body with no header (1)" in line)
    }

    @Test
    fun `an identity encoding is named but is not a compressed call`() {
        val line = text(figures(encodings = listOf("identity"), encodedCalls = 0))

        assertTrue(line, "answered identity" in line)
        assertFalse(line, "answered identity (" in line)
    }

    @Test
    fun `a reply that gave no length is chunked`() {
        val line = text(figures(declaredCalls = 0, declaredBytes = 0))

        assertTrue(line, "length not declared (chunked)" in line)
        assertFalse(line, "declared 0.0 MB" in line)
    }

    @Test
    fun `reads blocked against the body tells whose wait it is`() {
        val connection = text(figures(transferMs = 20_000, blockedMs = 19_000))
        val reader = text(figures(transferMs = 20_000, blockedMs = 4_000))

        assertTrue(connection, "body 20.00 (reads blocked 95%)" in connection)
        assertTrue(reader, "body 20.00 (reads blocked 20%)" in reader)
    }

    @Test
    fun `the rate is the wire over the body's time`() {
        val line = text(figures(transferMs = 20_000), wire = 6 * megabyte)

        assertTrue(line, "0.30 MB/s" in line)
    }

    @Test
    fun `a body that was not metered claims no decoded size and no blocked share`() {
        val line = text(figures(metered = 0, decodedBytes = 0, blockedMs = 0))

        assertTrue(line, "decoded not metered" in line)
        assertFalse(line, "reads blocked" in line)
    }

    @Test
    fun `two lines, the wire and then the time, and the time carries its unit once`() {
        val lines = figures().describe(6 * megabyte, 1)

        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("wire: h2 · asked gzip"))
        assertTrue(lines[1], lines[1].startsWith("wire time (s): setup 0.05 · first byte 1.20 · body 20.00"))
    }

    @Test
    fun `figures of several actions are added and their names joined once`() {
        val a = figures(protocols = listOf("h2"), encodings = listOf("gzip"), encodedCalls = 1, metered = 1, decodedBytes = 3 * megabyte)
        val b = figures(protocols = listOf("h2", "http/1.1"), encodings = listOf("gzip"), encodedCalls = 2, metered = 1, decodedBytes = 5 * megabyte)

        val sum = a + b

        assertEquals(listOf("h2", "http/1.1"), sum.protocols)
        assertEquals(listOf("gzip"), sum.encodings)
        assertEquals(3, sum.encodedCalls)
        assertEquals(2, sum.metered)
        assertEquals(8 * megabyte, sum.decodedBytes)
        assertEquals(a.firstByteMs + b.firstByteMs, sum.firstByteMs)
    }

    @Test
    fun `digits are western in any locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            val line = text(figures(encodings = listOf("gzip"), encodedCalls = 1))

            assertTrue(line, line.none { it in '٠'..'٩' || it in '۰'..'۹' })
            assertTrue(line, "×3.0" in line)
        } finally {
            Locale.setDefault(before)
        }
    }

    @After
    fun restore() {
        Locale.setDefault(Locale.getDefault())
    }
}
