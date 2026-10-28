package com.castivio.data.networking

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Counts what passes through a stream and how long its reader sat inside reads, and says so once, when the stream
 * ends or is closed.
 *
 * **Temporary, for development.** Put round an Xtream reply's body only while the development instruments are on
 * (see `XtreamHttpApi.metered`), to answer two questions the event listener cannot: how large the reply is once it
 * has been unpacked, and whether the time a reply takes is spent waiting for the connection or somewhere after it.
 * It is a pass-through. It returns exactly what the stream beneath it returns, in the same pieces, and adds nothing
 * to it, drops nothing from it and keeps nothing of it: two clock readings and an addition per read.
 *
 * [timed] is for the stream directly on the connection: the time inside its reads is time spent waiting on the
 * network (and unpacking, when the provider compressed). Round an already-decoded stream it would time the
 * decoder, which is not the question, so that one is left untimed.
 *
 * [onDone] is called at most once, with the bytes handed on and the nanoseconds spent inside reads: at the end of
 * the stream, or at [close] if the reader stopped first. A reader that is cancelled still reports what it got.
 */
internal class MeteredInputStream(
    delegate: InputStream,
    private val timed: Boolean,
    private val onDone: (bytes: Long, readNanos: Long) -> Unit,
) : FilterInputStream(delegate) {

    private var bytes = 0L
    private var readNanos = 0L
    private var reported = false

    override fun read(): Int {
        val began = if (timed) System.nanoTime() else 0L
        val value = `in`.read()
        if (timed) readNanos += System.nanoTime() - began
        if (value >= 0) bytes++ else report()
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val began = if (timed) System.nanoTime() else 0L
        val count = `in`.read(buffer, offset, length)
        if (timed) readNanos += System.nanoTime() - began
        if (count > 0) bytes += count else if (count < 0) report()
        return count
    }

    override fun skip(count: Long): Long {
        val began = if (timed) System.nanoTime() else 0L
        val skipped = `in`.skip(count)
        if (timed) readNanos += System.nanoTime() - began
        if (skipped > 0) bytes += skipped
        return skipped
    }

    override fun close() {
        try {
            report()
        } finally {
            super.close()
        }
    }

    private fun report() {
        if (reported) return
        reported = true
        onDone(bytes, readNanos)
    }
}
