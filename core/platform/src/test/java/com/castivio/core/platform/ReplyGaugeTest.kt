package com.castivio.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a reply is while it arrives, proved with a clock the test moves.
 *
 * Plain JUnit: the gauge is pure Kotlin, so what the strip will say about a reply of tens of megabytes is checked in a second
 * and not on a phone over mobile data.
 */
class ReplyGaugeTest {

    private var now = 0L
    private val gauge = ReplyGauge { now }

    private fun later(ms: Long) {
        now += ms * 1_000_000L
    }

    private val mb = 1_048_576L

    @Test
    fun `nothing is read before a byte has come`() {
        assertNull(gauge.reading())
        gauge.started("get_vod_streams", encoding = null, declaredBytes = null)
        assertNull("headers are not bytes", gauge.reading())
    }

    @Test
    fun `bytes add up for the reply that is arriving`() {
        gauge.started("get_vod_streams", null, null)
        gauge.bytes("get_vod_streams", 64 * 1024L)
        gauge.bytes("get_vod_streams", 64 * 1024L)

        assertEquals(128 * 1024L, gauge.reading()!!.bytes)
    }

    @Test
    fun `how the reply is encoded and how long it says it is are kept as the headers said them`() {
        gauge.started("get_vod_streams", encoding = "gzip", declaredBytes = 12 * mb)
        gauge.bytes("get_vod_streams", 1L)

        val reading = gauge.reading()!!
        assertEquals("gzip", reading.encoding)
        assertEquals(12 * mb, reading.declaredBytes)
    }

    @Test
    fun `a header that says nothing is nothing, not an empty word or a length of nought`() {
        gauge.started("a", encoding = "  ", declaredBytes = -1)
        gauge.bytes("a", 10)

        val reading = gauge.reading()!!
        assertNull(reading.encoding)
        assertNull(reading.declaredBytes)
    }

    @Test
    fun `the span runs from the first byte to the latest, and does not grow while nothing comes`() {
        gauge.started("a", null, null)
        later(5_000) // the wait for the first byte is not the reply's speed
        gauge.bytes("a", 1 * mb)
        later(2_000)
        gauge.bytes("a", 1 * mb)
        val atTheSecondRead = gauge.reading()!!.spanMs
        later(60_000)

        assertEquals(2_000L, atTheSecondRead)
        assertEquals("a reader that is busy elsewhere does not slow the figure", 2_000L, gauge.reading()!!.spanMs)
    }

    @Test
    fun `the largest reply is the one that is read, and a smaller one that began later does not take its place`() {
        gauge.started("get_vod_streams", "gzip", null)
        gauge.bytes("get_vod_streams", 30 * mb)
        gauge.started("get_vod_categories", null, 2_000L)
        gauge.bytes("get_vod_categories", 2_000L)

        val reading = gauge.reading()!!
        assertEquals(30 * mb, reading.bytes)
        assertEquals("gzip", reading.encoding)
    }

    @Test
    fun `a reply asked for again starts from nothing`() {
        gauge.started("a", "gzip", 5 * mb)
        gauge.bytes("a", 3 * mb)
        gauge.started("a", null, null)

        assertNull("what is read is the one in hand, and it has not sent a byte", gauge.reading())
        gauge.bytes("a", 1 * mb)
        val reading = gauge.reading()!!
        assertEquals(1 * mb, reading.bytes)
        assertNull(reading.encoding)
    }

    @Test
    fun `bytes that arrive with no headers noted are still counted`() {
        gauge.bytes("a", 700)

        val reading = gauge.reading()!!
        assertEquals(700L, reading.bytes)
        assertNull(reading.encoding)
        assertNull(reading.declaredBytes)
    }

    @Test
    fun `a read of nothing, or of a negative number, counts for nothing`() {
        gauge.bytes("a", 0)
        gauge.bytes("a", -5)

        assertNull(gauge.reading())
    }

    @Test
    fun `clear forgets every reply`() {
        gauge.started("a", "gzip", 1L)
        gauge.bytes("a", 100)

        gauge.clear()

        assertNull(gauge.reading())
    }

    // ------------------------------------------------------------------ how it is said

    @Test
    fun `a provider that does not compress and sends in chunks is said so, as the finished figures say it`() {
        val said = ReplyReading(bytes = 31 * mb + 200 * 1024, encoding = null, declaredBytes = null, spanMs = 33_000).describe()

        assertEquals("31.2 MB so far (no Content-Encoding · chunked · 0.95 MB/s)", said)
    }

    @Test
    fun `a declared length is said as the whole the reply is on its way to`() {
        val said = ReplyReading(bytes = 10 * mb, encoding = null, declaredBytes = 40 * mb, spanMs = 4_000).describe()

        assertEquals("10.0 MB so far (no Content-Encoding · of 40.0 MB · 2.50 MB/s)", said)
    }

    @Test
    fun `a compressed reply is counted unpacked, and is said to be, beside the packed length it declared`() {
        val said = ReplyReading(bytes = 30 * mb, encoding = "gzip", declaredBytes = 9 * mb, spanMs = 10_000).describe()

        assertEquals("30.0 MB unpacked so far (gzip · packed 9.0 MB · 3.00 MB/s)", said)
    }

    @Test
    fun `a compressed reply with no declared length is unpacked and chunked`() {
        val said = ReplyReading(bytes = 5 * mb, encoding = "br", declaredBytes = null, spanMs = 100).describe()

        assertEquals("5.0 MB unpacked so far (br · chunked)", said)
    }

    @Test
    fun `identity is a body nothing was done to`() {
        val said = ReplyReading(bytes = 10 * mb, encoding = "Identity", declaredBytes = 10 * mb, spanMs = 5_000).describe()

        assertEquals("10.0 MB so far (Identity · of 10.0 MB · 2.00 MB/s)", said)
    }

    @Test
    fun `a rate is not said for a burst`() {
        val said = ReplyReading(bytes = 64 * 1024L, encoding = null, declaredBytes = null, spanMs = 120).describe()

        assertTrue(said, said.endsWith("(no Content-Encoding · chunked)"))
        assertFalse("MB/s" in said)
    }

    @Test
    fun `digits are Western whatever the locale`() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            val said = ReplyReading(bytes = 12 * mb, encoding = null, declaredBytes = null, spanMs = 3_000).describe()

            assertTrue(said, said.all { it.code < 0x0660 || it.code > 0x06FF })
            assertTrue(said, said.startsWith("12.0 MB so far"))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}
