package com.castivio.data.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The hash OpenSubtitles matches on, which is the difference between finding the right
 * subtitle and finding ninety subtitles for the right film.
 *
 * ## Why this is tested against arithmetic rather than against a file
 *
 * The published reference for this algorithm is a video file with a known hash, and putting
 * a video in the repository to test a checksum is not a trade worth making. What can be
 * checked exactly, and is checked below, is every property the specification actually
 * fixes: the endianness, that the size is part of the sum, that overflow wraps rather than
 * saturates, that both windows are read, and that a file too small has no hash at all.
 *
 * Each of those is a way to get it wrong that produces a hash which is stable, plausible,
 * and matches nothing — the worst kind of failure this feature could have, because it looks
 * exactly like "there are no subtitles for this film".
 */
class SubtitleHashTest {

    /**
     * A file of zeroes hashes to its own size.
     *
     * Every word in both windows is zero, so the sum is the size and nothing else. It is the
     * one case where the answer can be written down, and it pins the rule that the size is
     * part of the sum rather than a prefix on it.
     */
    @Test
    fun `a file of zeroes hashes to its length`() {
        val size = SubtitleHash.SMALLEST
        val hash = SubtitleHash.of(size) { ByteArrayInputStream(ByteArray(SubtitleHash.WINDOW)) }

        assertEquals(size, hash)
    }

    /**
     * The words are read little-endian.
     *
     * A single 1 in the first byte of the first window is the value 1 read one way and
     * 72,057,594,037,927,936 read the other. Reading it the wrong way round produces a hash
     * that is consistent with itself and agrees with nobody, which is why this is its own
     * test rather than a line in another.
     */
    @Test
    fun `a word is read least significant byte first`() {
        val size = SubtitleHash.SMALLEST
        val first = ByteArray(SubtitleHash.WINDOW).also { it[0] = 1 }

        val hash = SubtitleHash.of(size) { at ->
            ByteArrayInputStream(if (at == 0L) first else ByteArray(SubtitleHash.WINDOW))
        }

        assertEquals("read big-endian, this would be 2^56 out", size + 1L, hash)
    }

    /** And the last window counts too, which is half the algorithm. */
    @Test
    fun `the tail window is part of the sum`() {
        val size = SubtitleHash.SMALLEST
        val tail = ByteArray(SubtitleHash.WINDOW).also { it[0] = 2 }

        val hash = SubtitleHash.of(size) { at ->
            ByteArrayInputStream(if (at == 0L) ByteArray(SubtitleHash.WINDOW) else tail)
        }

        assertEquals(size + 2L, hash)
    }

    /**
     * Overflow wraps.
     *
     * The specification adds modulo 2^64, which is what a 64-bit register does on its own —
     * so the test is that nothing has been added to "protect" against it. A file full of
     * 0xFF is 16,384 words of −1 in each window, and the answer is the size minus 32,768,
     * which is only true if the arithmetic was allowed to wrap.
     */
    @Test
    fun `overflow wraps rather than saturating`() {
        val size = SubtitleHash.SMALLEST
        val ones = ByteArray(SubtitleHash.WINDOW) { 0xFF.toByte() }
        val words = SubtitleHash.WINDOW / Long.SIZE_BYTES

        val hash = SubtitleHash.of(size) { ByteArrayInputStream(ones) }

        assertEquals(size - 2L * words, hash)
    }

    /**
     * A short file has no hash, and that is an answer rather than a failure.
     *
     * Below 128 KiB the two windows overlap and every byte between them would be counted
     * twice. The specification excludes it; the caller searches by name instead.
     */
    @Test
    fun `a file too short to hash reports none`() {
        assertNull(SubtitleHash.of(SubtitleHash.SMALLEST - 1) { empty() })
        assertNull(SubtitleHash.of(0) { empty() })
    }

    /**
     * A stream that delivers its window in pieces still hashes correctly.
     *
     * The case that matters most on a device and the easiest to get wrong: a
     * `ContentResolver` stream returns what it has, which is rarely 64 KiB in one call. A
     * single `read` would hash a fraction of the window, produce a stable wrong answer, and
     * match nothing — indistinguishable from a film nobody has subtitled.
     */
    @Test
    fun `a stream that answers in pieces is read to the end of the window`() {
        val ones = ByteArray(SubtitleHash.WINDOW) { 0xFF.toByte() }
        val whole = SubtitleHash.of(SubtitleHash.SMALLEST) { ByteArrayInputStream(ones) }
        val dribbled = SubtitleHash.of(SubtitleHash.SMALLEST) { Dribbling(ones) }

        assertEquals(whole, dribbled)
    }

    /** Two different files do not share a hash, which is the only thing it is for. */
    @Test
    fun `different content gives a different hash`() {
        val plain = SubtitleHash.of(SubtitleHash.SMALLEST) { ByteArrayInputStream(ByteArray(SubtitleHash.WINDOW)) }
        val marked = SubtitleHash.of(SubtitleHash.SMALLEST) { at ->
            ByteArrayInputStream(ByteArray(SubtitleHash.WINDOW).also { if (at == 0L) it[8] = 7 })
        }

        assertNotEquals(plain, marked)
    }

    /**
     * The wire form is sixteen lowercase hexadecimal digits, unsigned.
     *
     * Half of all valid hashes are negative `Long`s, and sending one of those as a decimal —
     * or as a fifteen-digit hex string with the leading zero dropped — matches nothing.
     */
    @Test
    fun `the wire form is padded, lowercase and unsigned`() {
        assertEquals("0000000000000001", 1L.asSubtitleHash())
        assertEquals("ffffffffffffffff", (-1L).asSubtitleHash())
        assertEquals("8e45f64bf31bee12", (-8194873140869075438L).asSubtitleHash())
    }

    private fun empty(): InputStream = ByteArrayInputStream(ByteArray(0))

    /** A stream that hands over eight bytes at a time, as a slow device's would. */
    private class Dribbling(private val source: ByteArray) : InputStream() {
        private var at = 0

        override fun read(): Int = if (at < source.size) source[at++].toInt() and 0xFF else -1

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (at >= source.size) return -1
            val given = minOf(PIECE, length, source.size - at)
            source.copyInto(into, offset, at, at + given)
            at += given
            return given
        }

        private companion object {
            const val PIECE = 8
        }
    }
}
