package com.castivio.data.subtitles

import java.io.IOException
import java.io.InputStream

/**
 * OpenSubtitles' file hash, which is what makes a search find the *right* subtitle.
 *
 * ## Why a hash and not the film's name
 *
 * A name search returns every subtitle anyone ever uploaded for that title: the cinema cut
 * and the extended one, the version with three minutes of adverts at the front, the one
 * timed to a 25fps broadcast and the one timed to 23.976. Every one of them is the right
 * film and all but one are out of step with the copy in front of the viewer, by anything
 * from half a second to two minutes. There is no way to tell them apart from a list.
 *
 * The hash identifies the *file*, so a match is a subtitle somebody has already watched
 * against these exact bytes. It is the difference between "here are ninety subtitles for
 * this film" and "here is the one that fits". The name is the fallback, for the copy nobody
 * has uploaded a subtitle against, and its results are the ones that need the sync control.
 *
 * ## The algorithm, and why it is this odd
 *
 * The file's size, plus every 64-bit little-endian word in the first 64 KiB, plus every one
 * in the last 64 KiB, all added with unsigned overflow ignored. It reads 128 KiB rather than
 * the whole file — which is the point: a two-hour film is four gigabytes, and a hash that
 * read all of it would be a spinner for a minute on a phone.
 *
 * It comes from the original OpenSubtitles specification and is fixed. Every detail below
 * is load-bearing:
 *
 *  - **Little-endian**, always, whatever the machine.
 *  - **Overflow is wrapped, not saturated**, which is what Kotlin's `Long` addition does
 *    already — the sum is a 64-bit register, and the specification's "modulo 2^64" is
 *    exactly that.
 *  - **The size is part of the sum**, not just a prefix on it.
 *  - **A file under 128 KiB has no hash.** Its two windows would overlap and every byte in
 *    the middle would be counted twice, so the specification excludes it and so does this.
 */
object SubtitleHash {

    /** 64 KiB, from the specification. Not a tuning parameter; changing it changes the hash. */
    const val WINDOW = 64 * 1024

    /** Below this the two windows would overlap, so there is no hash to compute. */
    const val SMALLEST = 2L * WINDOW

    /**
     * The hash of a file of [sizeBytes], given a way to read it.
     *
     * Null for a file too small to hash — an ordinary answer for a short clip, not a
     * failure, and the caller falls back to searching by name.
     *
     * [openAt] is given a byte offset and must return a stream positioned there. A function
     * rather than a `File` because the player's sources are `content://` URIs from
     * `MediaStore`, which have no path: they are opened through a `ContentResolver`, and a
     * hash that demanded a `java.io.File` could not be computed for the very files this
     * feature exists for.
     */
    @Throws(IOException::class)
    fun of(sizeBytes: Long, openAt: (Long) -> InputStream): Long? {
        if (sizeBytes < SMALLEST) return null

        var hash = sizeBytes
        hash += sumWindow(openAt(0L))
        hash += sumWindow(openAt(sizeBytes - WINDOW))
        return hash
    }

    /**
     * Every 64-bit little-endian word in the next 64 KiB, added together.
     *
     * The stream is read to the end of the window rather than trusted to deliver it in one
     * call: a `ContentResolver` stream returns what it has, which for a file on a slow
     * device is rarely the whole 64 KiB at once. A partial read here would produce a hash
     * that is wrong in a way nothing detects — the search would simply never match, and the
     * feature would look broken rather than misconfigured.
     */
    private fun sumWindow(stream: InputStream): Long = stream.use { input ->
        val buffer = ByteArray(WINDOW)
        var filled = 0
        while (filled < WINDOW) {
            val read = input.read(buffer, filled, WINDOW - filled)
            if (read < 0) break
            filled += read
        }

        var sum = 0L
        var at = 0
        // Whole words only. A window that came up short is a file that shrank under us or a
        // stream that ended early; the trailing bytes are dropped rather than padded,
        // because padding would invent a hash rather than fail to produce one.
        while (at + Long.SIZE_BYTES <= filled) {
            sum += word(buffer, at)
            at += Long.SIZE_BYTES
        }
        sum
    }

    /** Eight bytes, least significant first, as the specification requires everywhere. */
    private fun word(buffer: ByteArray, at: Int): Long {
        var value = 0L
        for (byte in 7 downTo 0) {
            value = (value shl 8) or (buffer[at + byte].toLong() and 0xFF)
        }
        return value
    }
}

/**
 * The hash as OpenSubtitles wants it in a query: sixteen lowercase hexadecimal digits.
 *
 * `toHexString` on a negative `Long` is what this exists to avoid. The hash is an unsigned
 * 64-bit quantity and Kotlin's `Long` is signed, so half of all valid hashes are negative
 * numbers whose decimal form is meaningless to the API. Padded to sixteen because a hash
 * with a small top byte would otherwise be sent short and match nothing.
 */
fun Long.asSubtitleHash(): String = java.lang.String.format("%016x", this)
