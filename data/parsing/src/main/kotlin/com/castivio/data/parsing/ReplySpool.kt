package com.castivio.data.parsing

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A reply taken to a file before anything reads it.
 *
 * ## Why a refresh wants it
 *
 * A section's one reply is read by a parser that hands its rows to the database writer, and the parser waits
 * whenever the writer has not finished the batch before. While it waits it reads nothing from the connection, so the
 * connection stays open for as long as the writer needs: a minute and more on a phone whose writer takes a
 * millisecond a row, against the ten seconds the same reply takes to arrive. Everything a network does in that time
 * is then done to a reply that was nearly all there. On a weak one it broke at 40,000 of 54,000 rows, and the import
 * went on to ask for 833 categories one at a time -- ten minutes in which the first reply's rows were thrown away and
 * every one of them was fetched again (`LOCAL_CHANGES.md`, §105).
 *
 * Taken to a file first, the reply is over when the connection has delivered it, at its speed and with nothing
 * waiting on the database. What is read afterwards is a file, which does not break.
 *
 * ## What it is not for
 *
 * A first load. The viewer is waiting for the first category of it, and a reply that must arrive whole before its
 * first row is read would put that moment ten seconds later. A refresh has rows on the screen already; what it wants
 * is to end, and to end the same way every time.
 *
 * ## The file
 *
 * It is in [directory], which is the application's cache: the system may take it back, and nothing but this reads it.
 * It is deleted when the stream [take] returns is closed, whichever way the import ends, and the ones a killed process
 * leaves are deleted by [sweep] -- which [take] runs first, so they never pile up.
 *
 * Plain `java.io`, with the free space, the clock and the way a file is opened given, so that every way it can go wrong
 * is proved without a device.
 */
class ReplySpool(
    private val directory: File,
    private val freeBytes: () -> Long = { directory.usableSpace },
    private val clock: () -> Long = System::currentTimeMillis,
    private val openFile: (File) -> OutputStream = { FileOutputStream(it) },
) {

    /**
     * Whether the file system has room for a reply whose size is not known: [MIN_FREE_BYTES] of it, which is more
     * than the whole of any catalogue's reply. False when it cannot be told -- the caller then reads the reply as it
     * arrives, as it did before there was a spool.
     */
    fun hasRoom(): Boolean = runCatching {
        directory.mkdirs()
        directory.isDirectory && freeBytes() >= MIN_FREE_BYTES
    }.getOrDefault(false)

    /**
     * Copies [wire] to a new file until it ends, closes it, and returns a stream over the file.
     *
     * Whatever [wire] throws is thrown as it is, so that a timeout is reported as one; what the *file* throws is a
     * [SpoolWriteException], so that a full disk is not reported as a network. Either way the partial file is gone
     * and [wire] is closed. [isCancelled] is asked before every chunk: the import was left, and a download nobody
     * wants any more stops at the next piece rather than at its end.
     */
    @Throws(IOException::class)
    fun take(wire: InputStream, isCancelled: () -> Boolean = { false }): InputStream {
        var file: File? = null
        var kept = false
        try {
            sweep()
            directory.mkdirs()
            val created = File.createTempFile(PREFIX, SUFFIX, directory)
            file = created
            val out = try {
                openFile(created)
            } catch (e: IOException) {
                throw SpoolWriteException(e)
            }
            copy(wire, out, isCancelled)
            val spooled = SpooledStream(created, FileInputStream(created))
            kept = true
            return spooled
        } finally {
            runCatching { wire.close() }
            if (!kept) file?.delete()
        }
    }

    /**
     * Deletes the files a process that was killed in the middle of a download left behind: ours, by name, and older
     * than [olderThanMs] -- far longer than any download takes, so a reply in the making is never taken.
     */
    fun sweep(olderThanMs: Long = STALE_AFTER_MS) {
        val limit = clock() - olderThanMs
        val files = runCatching { directory.listFiles() }.getOrNull() ?: return
        for (file in files) {
            if (file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) && file.lastModified() < limit) {
                runCatching { file.delete() }
            }
        }
    }

    private fun copy(wire: InputStream, out: OutputStream, isCancelled: () -> Boolean) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (true) {
                if (isCancelled()) throw SpoolCancelled()
                val read = wire.read(buffer)
                if (read < 0) break
                try {
                    out.write(buffer, 0, read)
                } catch (e: IOException) {
                    throw SpoolWriteException(e)
                }
            }
            try {
                out.flush()
            } catch (e: IOException) {
                throw SpoolWriteException(e)
            }
        } finally {
            runCatching { out.close() }
        }
    }

    /** The file as a stream, which takes the file with it when it is closed. */
    private class SpooledStream(private val file: File, input: InputStream) : FilterInputStream(input) {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                super.close()
            } finally {
                file.delete()
            }
        }
    }

    companion object {
        /** The room a reply is given before it is taken: more than the whole of any catalogue's reply. */
        const val MIN_FREE_BYTES = 256L * 1024 * 1024

        /** Longer than any download takes, so a file this old is one nobody is writing. */
        const val STALE_AFTER_MS = 2L * 60 * 60 * 1000

        private const val BUFFER_BYTES = 64 * 1024
        private const val PREFIX = "reply-"
        private const val SUFFIX = ".spool"
    }
}

/** The file a reply is taken to could not be written -- the disk is full, or gone -- which is not the network's fault. */
class SpoolWriteException(cause: IOException) : IOException("the reply could not be written to its file", cause)

/** The reply was no longer wanted: the import it was for was left or cancelled while it was still arriving. */
class SpoolCancelled : IOException("the reply was no longer wanted")
