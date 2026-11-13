package com.castivio.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The kernel's texts read into figures, on the shapes a device writes them. */
class ProcParsersTest {

    @Test
    fun `a thread's scheduler accounts are its run time and its wait for a processor`() {
        val stat = ProcParsers.schedStat("47450123456 12300000000 9812\n")

        assertEquals(SchedStat(runNanos = 47_450_123_456L, waitNanos = 12_300_000_000L), stat)
    }

    @Test
    fun `a scheduler line that is not three numbers is no reading`() {
        assertNull(ProcParsers.schedStat(""))
        assertNull(ProcParsers.schedStat("12"))
        assertNull(ProcParsers.schedStat("run wait slices"))
        assertNull(ProcParsers.schedStat("-5 10 3"))
    }

    @Test
    fun `a process's storage accounts are read by name and not by position`() {
        val text = """
            rchar: 3000000000
            wchar: 2000000000
            syscr: 4100
            syscw: 190000
            read_bytes: 312000000
            write_bytes: 801000000
            cancelled_write_bytes: 0
        """.trimIndent()

        assertEquals(
            ProcIo(readBytes = 312_000_000L, writeBytes = 801_000_000L, readCalls = 4_100L, writeCalls = 190_000L),
            ProcParsers.io(text),
        )
    }

    @Test
    fun `an io file without the bytes is no reading`() {
        assertNull(ProcParsers.io(""))
        assertNull(ProcParsers.io("rchar: 10\nwchar: 20"))
        assertNull(ProcParsers.io("read_bytes: x\nwrite_bytes: y"))
    }

    @Test
    fun `calls that the kernel did not count are none`() {
        val io = ProcParsers.io("read_bytes: 10\nwrite_bytes: 20")

        assertEquals(ProcIo(10L, 20L, 0L, 0L), io)
    }

    @Test
    fun `what a load did to the storage is the difference of two readings, in megabytes and calls`() {
        val before = ProcIo(readBytes = 10_000_000L, writeBytes = 100_000_000L, readCalls = 100L, writeCalls = 1_000L)
        val after = ProcIo(readBytes = 322_000_000L, writeBytes = 901_000_000L, readCalls = 4_200L, writeCalls = 191_000L)

        assertEquals("read 312 MB (4,100 calls) · written 801 MB (190,000 calls)", ProcParsers.describe(before, after))
    }

    @Test
    fun `a reading that went backwards says nothing negative`() {
        val before = ProcIo(readBytes = 500L, writeBytes = 500L, readCalls = 5L, writeCalls = 5L)
        val after = ProcIo(readBytes = 100L, writeBytes = 100L, readCalls = 1L, writeCalls = 1L)

        assertEquals("read 0 MB (0 calls) · written 0 MB (0 calls)", ProcParsers.describe(before, after))
    }
}
