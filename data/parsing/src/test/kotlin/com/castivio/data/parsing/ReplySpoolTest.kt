package com.castivio.data.parsing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

/**
 * A reply taken to a file: every way it can end, proved with files and fake wires and no device.
 *
 * The claim is the one the class makes: whatever happens to the download -- it finishes, the network breaks, the disk
 * fills, the import is left -- no file stays behind, the wire is closed, and each failure is reported as what it was.
 */
class ReplySpoolTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = folder.newFolder("replies")
    }

    private fun spool(
        freeBytes: Long = Long.MAX_VALUE,
        now: Long = NOW,
        open: (File) -> OutputStream = { FileOutputStream(it) },
    ) = ReplySpool(directory, freeBytes = { freeBytes }, clock = { now }, openFile = open)

    private fun reply(size: Int): ByteArray = ByteArray(size) { (it * 31 + it / 251).toByte() }

    private fun left(): List<String> = directory.list().orEmpty().sorted()

    /** A wire that says when it was closed. */
    private class WatchedWire(data: ByteArray) : FilterInputStream(ByteArrayInputStream(data)) {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    /** A wire that delivers [data] in small pieces and then fails with [failure]. */
    private class BrokenWire(private val data: ByteArray, private val failure: IOException) : InputStream() {
        private var position = 0
        var closed = false

        override fun read(): Int = throw UnsupportedOperationException("read in pieces")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= data.size) throw failure
            val n = minOf(length, data.size - position, PIECE)
            System.arraycopy(data, position, buffer, offset, n)
            position += n
            return n
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `a reply comes back whole, larger than a buffer, and its file goes when it is closed`() {
        val sent = reply(3 * 1024 * 1024 + 17)
        val stream = spool().take(ByteArrayInputStream(sent))

        assertEquals("one file while the reply is being read", 1, left().size)
        assertArrayEquals(sent, stream.readBytes())
        stream.close()
        assertEquals("no file once it is closed", emptyList<String>(), left())
    }

    @Test
    fun `an empty reply is an empty stream, and still leaves nothing`() {
        val stream = spool().take(ByteArrayInputStream(ByteArray(0)))

        assertEquals(-1, stream.read())
        stream.close()
        assertEquals(emptyList<String>(), left())
    }

    @Test
    fun `closing the stream twice is harmless`() {
        val stream = spool().take(ByteArrayInputStream(reply(100)))

        stream.close()
        stream.close()
        assertEquals(emptyList<String>(), left())
    }

    @Test
    fun `the wire is closed once it has been copied`() {
        val wire = WatchedWire(reply(1000))

        spool().take(wire).close()

        assertTrue(wire.closed)
    }

    @Test
    fun `a wire that breaks leaves no file, and its own exception stands`() {
        val timeout = SocketTimeoutException("timeout")
        val wire = BrokenWire(reply(200_000), timeout)

        try {
            spool().take(wire)
            fail("a broken wire is a broken reply")
        } catch (e: IOException) {
            assertSame("the network's exception, not a wrapper of it", timeout, e)
        }

        assertEquals("the part that arrived is not kept", emptyList<String>(), left())
        assertTrue("and the connection is let go", wire.closed)
    }

    @Test
    fun `a download nobody wants any more stops at the next piece and leaves no file`() {
        val wire = BrokenWire(reply(10 * PIECE), IOException("never reached"))
        var asked = 0

        try {
            spool().take(wire, isCancelled = { ++asked > 3 })
            fail("a cancelled download is not a reply")
        } catch (e: SpoolCancelled) {
            // the import was left
        }

        assertEquals("it stopped, it did not read to the end", 4, asked)
        assertEquals(emptyList<String>(), left())
        assertTrue(wire.closed)
    }

    @Test
    fun `a file that cannot be written is the file's failure, not the network's, and leaves nothing`() {
        val full = IOException("No space left on device")
        val wire = WatchedWire(reply(100_000))

        try {
            spool(open = {
                object : OutputStream() {
                    override fun write(b: Int) = throw full
                    override fun write(b: ByteArray, off: Int, len: Int) = throw full
                }
            }).take(wire)
            fail("a full disk is a failed reply")
        } catch (e: SpoolWriteException) {
            assertSame("what the disk said is kept", full, e.cause)
        }

        assertEquals(emptyList<String>(), left())
        assertTrue(wire.closed)
    }

    @Test
    fun `a file that cannot be opened is the file's failure too`() {
        val wire = WatchedWire(reply(10))

        try {
            spool(open = { throw IOException("read-only file system") }).take(wire)
            fail("no file, no reply")
        } catch (e: SpoolWriteException) {
            assertEquals("read-only file system", e.cause?.message)
        }

        assertEquals(emptyList<String>(), left())
        assertTrue(wire.closed)
    }

    @Test
    fun `there is room for a reply only at the floor and above it`() {
        assertFalse(spool(freeBytes = ReplySpool.MIN_FREE_BYTES - 1).hasRoom())
        assertTrue(spool(freeBytes = ReplySpool.MIN_FREE_BYTES).hasRoom())
        assertTrue(spool(freeBytes = Long.MAX_VALUE).hasRoom())
    }

    @Test
    fun `a directory that cannot be made has no room, and asking does not throw`() {
        val file = folder.newFile("not-a-directory")
        val under = ReplySpool(File(file, "replies"), freeBytes = { Long.MAX_VALUE })

        assertFalse(under.hasRoom())
    }

    @Test
    fun `a sweep takes the old files of its own name and nothing else`() {
        val old = NOW - 3 * 60 * 60 * 1000
        val stale = File(directory, "reply-1.spool").apply { writeText("x"); setLastModified(old) }
        val recent = File(directory, "reply-2.spool").apply { writeText("x"); setLastModified(NOW - 1000) }
        val other = File(directory, "other.txt").apply { writeText("x"); setLastModified(old) }
        val almost = File(directory, "reply-3.txt").apply { writeText("x"); setLastModified(old) }

        spool().sweep()

        assertFalse(stale.exists())
        assertTrue("a download that is still going is not taken", recent.exists())
        assertTrue("what is not ours is not touched", other.exists())
        assertTrue("not even when it starts like ours", almost.exists())
    }

    @Test
    fun `a download begins with a sweep, so a killed process's files do not pile up`() {
        File(directory, "reply-9.spool").apply { writeText("left by a process that was killed"); setLastModified(NOW - 5 * 60 * 60 * 1000) }

        spool().take(ByteArrayInputStream(reply(10))).close()

        assertEquals(emptyList<String>(), left())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val PIECE = 8 * 1024
    }
}
