package com.castivio.data.playlist

import com.castivio.domain.PlaylistCopies
import java.io.File
import java.io.FilterReader
import java.io.IOException
import java.io.Reader
import java.io.Writer

/**
 * What the importer asks of the playlists this device keeps: the copy to read a section
 * from, a new one to write while a download is read, and -- through [PlaylistCopies] --
 * forgetting one. Storage, behind an interface like every platform dependency here.
 */
interface KeptPlaylists : PlaylistCopies {

    /** [sourceId]'s copy, open for reading, or null when this device keeps none. */
    fun open(sourceId: String): Reader?

    /**
     * A copy of [sourceId]'s playlist, written as the download is read.
     *
     * It replaces the copy kept so far only when [PlaylistCopy.keep] is called -- after the
     * import read the playlist to its end -- so a download that fails or is cancelled
     * leaves the previous copy exactly as it was.
     */
    fun begin(sourceId: String): PlaylistCopy
}

/**
 * The playlists this device keeps, one file per subscription, in [directory].
 *
 * Plain files, and plain Kotlin: the directory is the only thing the platform decides --
 * the app's cache, which the system may empty when storage runs short. That is the right
 * place for it. A copy is a saving on the next section's wait and nothing more: when it
 * is gone, the next section downloads the playlist again, which is what every section did
 * before there was a copy.
 *
 * Named by the subscription's id, which is a hash of the link and never the link: the
 * file's name says nothing about the account, and its content is what the catalogue
 * already holds -- every row's stream URL.
 *
 * Read and written by one load at a time: the section queue runs one, so nothing here
 * locks.
 */
class PlaylistCopyStore(private val directory: File) : KeptPlaylists {

    override fun open(sourceId: String): Reader? {
        val file = fileOf(sourceId)
        if (!file.isFile) return null
        return try {
            file.inputStream().reader(Charsets.UTF_8)
        } catch (e: IOException) {
            null
        }
    }

    override fun begin(sourceId: String): PlaylistCopy = PlaylistCopy(part = partOf(sourceId), target = fileOf(sourceId))

    override suspend fun discard(sourceId: String) {
        fileOf(sourceId).delete()
        partOf(sourceId).delete()
    }

    private fun fileOf(sourceId: String) = File(directory, nameOf(sourceId) + SUFFIX)

    private fun partOf(sourceId: String) = File(directory, nameOf(sourceId) + SUFFIX + PART)

    /**
     * The id as a file name. Ids are hex hashes and pass through as they are; anything else
     * is spelled out in hex, so no id can name a path outside [directory].
     */
    private fun nameOf(sourceId: String): String =
        if (sourceId.isNotEmpty() && sourceId.all { it.isLetterOrDigit() && it.code < 128 }) {
            sourceId
        } else {
            sourceId.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        }

    private companion object {
        const val SUFFIX = ".m3u"

        /** A copy still being written: never read, and replaced by the next one begun. */
        const val PART = ".part"
    }
}

/**
 * A playlist being copied as it is read.
 *
 * [tee] hands the import a reader that writes every character it reads into the copy, so
 * the playlist is downloaded once and parsed once, and the copy costs no second pass.
 *
 * A copy that cannot be written is given up, quietly: a full disk is a reason for the next
 * section to download again, never for this one to fail.
 */
class PlaylistCopy internal constructor(private val part: File, private val target: File) {

    private var out: Writer? = null
    private var failed = false

    /**
     * [source], with everything read from it written into the copy too.
     *
     * Every way of moving past a character goes through [read], so nothing the import
     * passes over is missing from the copy; and it cannot be marked, because a reset would
     * read characters a second time and write them twice.
     */
    fun tee(source: Reader): Reader = object : FilterReader(source) {
        override fun read(): Int {
            val c = super.read()
            if (c >= 0) write(charArrayOf(c.toChar()), 0, 1)
            return c
        }

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            val n = super.read(buffer, offset, length)
            if (n > 0) write(buffer, offset, n)
            return n
        }

        override fun skip(n: Long): Long {
            val scratch = CharArray(n.coerceIn(1L, SKIP_CHUNK.toLong()).toInt())
            var left = n
            while (left > 0) {
                val read = read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
                if (read < 0) break
                left -= read
            }
            return n - left
        }

        override fun markSupported(): Boolean = false

        override fun mark(readAheadLimit: Int) {
            throw IOException("a copied playlist cannot be marked")
        }

        override fun reset() {
            throw IOException("a copied playlist cannot be reset")
        }
    }

    /**
     * Keeps the copy in place of the one before it: the playlist was read to its end.
     *
     * @return whether a copy is now kept. False when it could not be written or moved into
     *   place -- and the previous copy, if there was one, is then gone too, because it is no
     *   longer the playlist the catalogue was just read from.
     */
    fun keep(): Boolean {
        val written = out
        out = null
        val kept = written != null && !failed && try {
            written.close()
            // rename(2) replaces the target in one step, so a reader never sees half a copy.
            part.renameTo(target) || (target.delete() && part.renameTo(target))
        } catch (e: IOException) {
            false
        }
        if (!kept) {
            part.delete()
            target.delete()
        }
        return kept
    }

    /**
     * Throws this copy away, and leaves the one before it: the download failed, was
     * cancelled, or was not needed -- a provider that answered "not modified" said the
     * copy already kept is the playlist.
     */
    fun drop() {
        runCatching { out?.close() }
        out = null
        part.delete()
    }

    /** Opened on the first character, so an answer with no body leaves no file behind. */
    private fun write(buffer: CharArray, offset: Int, length: Int) {
        if (failed) return
        try {
            val writer = out ?: openPart().also { out = it }
            writer.write(buffer, offset, length)
        } catch (e: IOException) {
            failed = true
            runCatching { out?.close() }
            out = null
            part.delete()
        }
    }

    private fun openPart(): Writer {
        part.parentFile?.mkdirs()
        return part.outputStream().bufferedWriter(Charsets.UTF_8)
    }

    private companion object {
        /** What [tee]'s `skip` reads at a time on its way past. */
        const val SKIP_CHUNK = 8_192
    }
}
