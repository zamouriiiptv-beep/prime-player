package com.castivio.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A long list read a window at a time: the window of a row is read when the row is asked for, held until
 * it is the least recently used of more than the ceiling, and no more than 300 rows are ever in memory.
 */
class WindowedRowsTest {

    /** A list of [size] numbers, read by window, with every read recorded. */
    private class Numbers(val size: Int) {
        val reads = mutableListOf<Pair<Int, Int>>()
        suspend fun read(offset: Int, limit: Int): Page<Int> {
            reads += offset to limit
            return Page((offset until minOf(offset + limit, size)).toList(), offset, size)
        }
    }

    private fun rows(size: Int, windowSize: Int = 60, maxWindows: Int = 5): Pair<Numbers, WindowedRows<Int>> {
        val numbers = Numbers(size)
        return numbers to WindowedRows(windowSize, maxWindows, numbers::read)
    }

    @Test
    fun `a row is unknown until its window has been read`() = runTest {
        val (_, list) = rows(1_000)

        assertNull(list.rowAt(130))
        assertNull("and the length is not known either", list.total)

        assertTrue("a window arrived", list.ensure(130))

        assertEquals(130, list.rowAt(130))
        assertEquals("the window it is in is whole", 120, list.rowAt(120))
        assertEquals(179, list.rowAt(179))
        assertNull("the next one is not there", list.rowAt(180))
        assertEquals(1_000, list.total)
    }

    @Test
    fun `a window is read from where it starts, one window at a time`() = runTest {
        val (numbers, list) = rows(1_000)

        list.ensure(130)

        assertEquals(listOf(120 to 60), numbers.reads)
    }

    @Test
    fun `a window already held is not read again`() = runTest {
        val (numbers, list) = rows(1_000)

        list.ensure(130)
        val again = list.ensure(121)

        assertFalse("nothing new arrived", again)
        assertEquals(1, numbers.reads.size)
    }

    @Test
    fun `no more rows than the ceiling are ever held`() = runTest {
        val (_, list) = rows(10_000)

        for (window in 0 until 40) list.ensure(window * 60)

        assertEquals("five windows of sixty", 300, list.rowsHeld)
    }

    @Test
    fun `the least recently used window is the one let go`() = runTest {
        val (_, list) = rows(10_000, maxWindows = 3)
        for (window in 0..2) list.ensure(window * 60)

        // The first is looked at again, so it is the second that is now the oldest.
        assertEquals(0, list.rowAt(0))
        list.ensure(3 * 60)

        assertEquals("the one looked at is kept", 0, list.rowAt(0))
        assertNull("the one that was not is gone", list.rowAt(60))
        assertEquals(180, list.rowAt(180))
    }

    @Test
    fun `the window just read is never the one let go`() = runTest {
        val (_, list) = rows(10_000, maxWindows = 1)

        list.ensure(0)
        list.ensure(600)

        assertNull(list.rowAt(0))
        assertEquals(600, list.rowAt(600))
    }

    @Test
    fun `a row past the end, or before the start, reads nothing`() = runTest {
        val (numbers, list) = rows(100)
        list.ensure(0)
        val readsBefore = numbers.reads.size

        assertFalse(list.ensure(100))
        assertFalse(list.ensure(5_000))
        assertFalse(list.ensure(-1))

        assertEquals(readsBefore, numbers.reads.size)
        assertNull(list.rowAt(100))
        assertNull(list.rowAt(-1))
    }

    @Test
    fun `the last window is short and says so`() = runTest {
        val (_, list) = rows(130)

        list.ensure(129)

        assertEquals(129, list.rowAt(129))
        assertNull(list.rowAt(130))
        assertEquals(130, list.total)
    }

    @Test
    fun `a list that holds nothing has a length of nothing`() = runTest {
        val (_, list) = rows(0)

        list.ensure(0)

        assertEquals(0, list.total)
        assertNull(list.rowAt(0))
    }
}
