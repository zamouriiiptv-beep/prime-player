package com.castivio.data.networking

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The meter is a pass-through that counts, and only that.
 *
 * What is pinned is the promise the instrument is allowed to exist on: the bytes the reader gets are the bytes
 * the stream had, in the pieces it had them, and the count is reported once, whichever way the stream ends.
 */
class MeteredInputStreamTest {

    private class Report {
        val bytes = ArrayList<Long>()
        val nanos = ArrayList<Long>()
        val callback: (Long, Long) -> Unit = { b, n -> bytes += b; nanos += n }
    }

    private val data = ByteArray(10_000) { (it % 251).toByte() }

    @Test
    fun `hands on exactly what the stream held`() {
        val report = Report()
        val metered = MeteredInputStream(ByteArrayInputStream(data), timed = true, onDone = report.callback)

        val got = metered.readBytes()

        assertArrayEquals(data, got)
        assertEquals(listOf(data.size.toLong()), report.bytes)
    }

    @Test
    fun `single byte reads are counted like block reads`() {
        val report = Report()
        val metered = MeteredInputStream(ByteArrayInputStream(data, 0, 100), timed = false, onDone = report.callback)

        var count = 0
        while (metered.read() >= 0) count++

        assertEquals(100, count)
        assertEquals(listOf(100L), report.bytes)
    }

    @Test
    fun `reports once, at the end, and not again at close`() {
        val report = Report()
        val metered = MeteredInputStream(ByteArrayInputStream(data), timed = true, onDone = report.callback)

        metered.readBytes()
        metered.close()
        metered.close()

        assertEquals(1, report.bytes.size)
    }

    @Test
    fun `a reader that stops early still reports what it got when it closes`() {
        val report = Report()
        val metered = MeteredInputStream(ByteArrayInputStream(data), timed = true, onDone = report.callback)

        metered.read(ByteArray(300), 0, 300)
        metered.close()

        assertEquals(listOf(300L), report.bytes)
    }

    @Test
    fun `an untimed meter reports no read time`() {
        val report = Report()
        MeteredInputStream(ByteArrayInputStream(data), timed = false, onDone = report.callback).use { it.readBytes() }

        assertEquals(listOf(0L), report.nanos)
    }

    @Test
    fun `a timed meter counts the time spent inside reads`() {
        val report = Report()
        val slow = object : InputStream() {
            private val inner = ByteArrayInputStream(data)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                Thread.sleep(20)
                return inner.read(b, off, minOf(len, 2_500))
            }
        }
        MeteredInputStream(slow, timed = true, onDone = report.callback).use { it.readBytes() }

        // Four blocks of 2,500 and the end: five reads of 20 ms, minus the scheduler's generosity.
        assertTrue("${report.nanos}", report.nanos.single() >= 80_000_000L)
    }

    @Test
    fun `skipped bytes are counted as bytes handed on`() {
        val report = Report()
        val metered = MeteredInputStream(ByteArrayInputStream(data), timed = false, onDone = report.callback)

        metered.skip(1_000)
        metered.readBytes()

        assertEquals(listOf(data.size.toLong()), report.bytes)
    }

    @Test
    fun `progress is told for every read, with how many, and adds up to what was handed on`() {
        val report = Report()
        val told = ArrayList<Long>()
        val metered = MeteredInputStream(ByteArrayInputStream(data), timed = false, onProgress = { told += it }, onDone = report.callback)

        val block = ByteArray(4_000)
        while (metered.read(block, 0, block.size) >= 0) Unit

        assertEquals("three reads of the data's ten thousand: 4,000 + 4,000 + 2,000", listOf(4_000L, 4_000L, 2_000L), told)
        assertEquals(data.size.toLong(), told.sum())
        assertEquals(listOf(data.size.toLong()), report.bytes)
    }

    @Test
    fun `a byte read alone is a progress of one, and skipped bytes are progress too`() {
        val told = ArrayList<Long>()
        val metered = MeteredInputStream(ByteArrayInputStream(data), timed = false, onProgress = { told += it }, onDone = { _, _ -> })

        metered.read()
        metered.skip(500)

        assertEquals(listOf(1L, 500L), told)
    }

    @Test
    fun `the end of the stream is not progress`() {
        val told = ArrayList<Long>()
        val metered = MeteredInputStream(ByteArrayInputStream(ByteArray(0)), timed = false, onProgress = { told += it }, onDone = { _, _ -> })

        assertEquals(-1, metered.read())
        assertEquals(-1, metered.read(ByteArray(10), 0, 10))

        assertTrue("$told", told.isEmpty())
    }

    @Test
    fun `a read that was asked for nothing and returned nothing is not progress`() {
        val told = ArrayList<Long>()
        val metered = MeteredInputStream(ByteArrayInputStream(data), timed = false, onProgress = { told += it }, onDone = { _, _ -> })

        assertEquals(0, metered.read(ByteArray(10), 0, 0))

        assertTrue("$told", told.isEmpty())
    }

    @Test
    fun `a meter with nobody to tell hands on the same bytes`() {
        val metered = MeteredInputStream(ByteArrayInputStream(data), timed = true, onDone = { _, _ -> })

        assertArrayEquals(data, metered.readBytes())
    }
}
