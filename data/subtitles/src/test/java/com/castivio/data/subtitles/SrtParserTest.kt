package com.castivio.data.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SubRip, as the files actually arrive.
 *
 * Every case below is something a real subtitle site serves, and a parser that handled only
 * the tidy shape would silently drop captions rather than fail — a film with half its
 * dialogue missing and nothing to say why. So the tests are mostly malformity: a byte-order
 * mark, Windows line endings, missing indices, both decimal separators, no blank line
 * between cues, and markup this player does not honour.
 */
class SrtParserTest {

    @Test
    fun `an ordinary file parses`() {
        val track = parse(
            """
            1
            00:00:01,000 --> 00:00:03,500
            مرحبًا

            2
            00:00:04,000 --> 00:00:06,000
            كيف حالك؟
            """.trimIndent(),
        )

        assertEquals(2, track.cues.size)
        assertEquals(1_000L, track.cues[0].fromMs)
        assertEquals(3_500L, track.cues[0].toMs)
        assertEquals(listOf("مرحبًا"), track.cues[0].lines)
        assertEquals(listOf("كيف حالك؟"), track.cues[1].lines)
    }

    /**
     * A byte-order mark does not swallow the first caption.
     *
     * The commonest defect in a hand-written SRT parser and the least visible: the mark
     * attaches to the first index line, which then does not parse as a number — and a parser
     * that keyed on the index would lose exactly one caption, the first, on a large fraction
     * of files.
     */
    @Test
    fun `a byte-order mark does not cost the first caption`() {
        val track = parse("﻿1\n00:00:01,000 --> 00:00:02,000\nأهلًا\n")

        assertEquals(1, track.cues.size)
        assertEquals(listOf("أهلًا"), track.cues[0].lines)
    }

    /** Windows line endings, which are the majority of the SRT files in existence. */
    @Test
    fun `carriage returns are not part of the words`() {
        val track = parse("1\r\n00:00:01,000 --> 00:00:02,000\r\nhello\r\n")

        assertEquals(listOf("hello"), track.cues.single().lines)
    }

    /** The index is decoration. A file with none still parses, because the timing identifies. */
    @Test
    fun `a file with no indices parses`() {
        val track = parse(
            """
            00:00:01,000 --> 00:00:02,000
            one

            00:00:03,000 --> 00:00:04,000
            two
            """.trimIndent(),
        )

        assertEquals(2, track.cues.size)
    }

    /** Both separators mean the same thing, and both are common. */
    @Test
    fun `a full stop is as good as a comma in a timestamp`() {
        val track = parse("00:00:01.250 --> 00:00:02.750\nhello\n")

        assertEquals(1_250L, track.cues.single().fromMs)
        assertEquals(2_750L, track.cues.single().toMs)
    }

    /** A timing line ends the cue before it, whether or not a blank line separates them. */
    @Test
    fun `a missing blank line does not merge two captions`() {
        val track = parse(
            """
            00:00:01,000 --> 00:00:02,000
            one
            00:00:03,000 --> 00:00:04,000
            two
            """.trimIndent(),
        )

        assertEquals(2, track.cues.size)
        assertEquals(listOf("one"), track.cues[0].lines)
        assertEquals(listOf("two"), track.cues[1].lines)
    }

    /** Two lines of dialogue stay two lines: where a caption breaks is the author's decision. */
    @Test
    fun `a caption of two lines keeps both`() {
        val track = parse("00:00:01,000 --> 00:00:02,000\n- Who is it?\n- It is me.\n")

        assertEquals(listOf("- Who is it?", "- It is me."), track.cues.single().lines)
    }

    /**
     * Markup is removed rather than rendered.
     *
     * Castivio draws captions in the viewer's chosen size and colour; a source's italics
     * would fight that, and `{\an8}` would move words to the top of the screen over the
     * title bar — the source deciding placement, which is the thing the whole design says
     * it may not do.
     */
    @Test
    fun `tags are stripped`() {
        val track = parse("00:00:01,000 --> 00:00:02,000\n{\\an8}<i>whispering</i>\n")

        assertEquals(listOf("whispering"), track.cues.single().lines)
    }

    /** An hour is an hour, and a three-digit hour does not break the clock. */
    @Test
    fun `hours minutes and seconds all count`() {
        val track = parse("01:02:03,004 --> 01:02:05,000\nlate\n")

        assertEquals(3_723_004L, track.cues.single().fromMs)
    }

    /**
     * A cue with a broken timing line is dropped, and the rest of the file is not.
     *
     * Dropped rather than shown at an invented time: a caption that could appear at any
     * moment is worse than one that is missing. And the file goes on parsing, because one
     * bad line in fifteen hundred is not a reason to lose the other 1,499.
     */
    @Test
    fun `a broken timing line costs its own caption and no other`() {
        val track = parse(
            """
            00:00:01,000 --> 00:00:02,000
            good

            not a time --> nor is this
            orphaned

            00:00:05,000 --> 00:00:06,000
            also good
            """.trimIndent(),
        )

        assertEquals(2, track.cues.size)
        assertEquals(listOf("good"), track.cues[0].lines)
        assertEquals(listOf("also good"), track.cues[1].lines)
    }

    /** A cue that ends before it starts is a broken line, not a caption shown backwards. */
    @Test
    fun `a cue that ends before it begins is dropped`() {
        val track = parse("00:00:05,000 --> 00:00:01,000\nbackwards\n")

        assertTrue(track.cues.isEmpty())
    }

    /** Cues out of order are sorted, because the lookup binary-searches them. */
    @Test
    fun `cues are put in order`() {
        val track = parse(
            """
            00:00:05,000 --> 00:00:06,000
            second

            00:00:01,000 --> 00:00:02,000
            first
            """.trimIndent(),
        )

        assertEquals(listOf("first"), track.cues[0].lines)
        assertEquals(listOf("second"), track.cues[1].lines)
    }

    /* ------------------------------------------------------------------- the lookup */

    /**
     * The caption for a moment, and nothing between them.
     *
     * The gap matters as much as the hit: a lookup that returned the nearest cue rather than
     * the covering one would leave the last line of dialogue on screen through every silence
     * in the film.
     */
    @Test
    fun `the lookup answers inside a cue and not between two`() {
        val track = parse(
            """
            00:00:01,000 --> 00:00:02,000
            one

            00:00:05,000 --> 00:00:06,000
            two
            """.trimIndent(),
        )

        assertEquals(listOf("one"), track.at(1_500)?.lines)
        assertEquals(listOf("one"), track.at(1_000)?.lines)
        assertEquals(listOf("one"), track.at(2_000)?.lines)
        assertNull("a caption stayed on screen through the silence", track.at(3_000))
        assertEquals(listOf("two"), track.at(5_500)?.lines)
        assertNull(track.at(0))
        assertNull(track.at(10_000))
    }

    /** And an empty track answers nothing rather than throwing on its own emptiness. */
    @Test
    fun `an empty track answers nothing`() {
        assertNull(SubtitleTrack(emptyList()).at(1_000))
    }

    private fun parse(text: String) = SrtParser.parse(text.reader().buffered())
}
