package com.castivio.core.design.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cut, measured in characters rather than pixels.
 *
 * [middleElide] takes its measurement as a function precisely so this file exists:
 * the arithmetic that decides what a viewer reads is tested here, on a JVM, in
 * milliseconds, and the font is the only part left needing a device.
 *
 * A budget below is a character count standing in for a width. The one that matters
 * is **23**, which is what the owner's handset actually affords: 2340×1080 at
 * density 2.8125 is 833×385dp, the list column takes 337.2dp of it, and after two
 * 10dp insets, three 10dp gaps, a 30dp number plate, a 26dp logo and an 18.5dp
 * quality tag the name is left 186.8dp — about 23 upper-case characters at 7.9dp
 * each, as measured off the screenshot that started this.
 */
class MiddleEllipsisTest {

    // --------------------------------------------------------------- the two names

    /**
     * **The photograph.**
     *
     * Sixty-one channels in one category, all named for the bouquet and numbered at
     * the end. Twenty-three characters of shared prefix into a field that fits
     * twenty-three characters: every row on the owner's screen read
     * `US: CINEMANIA HOLLYWOOD …` and the only thing separating channel 50 from
     * channel 61 was the number plate the player put there, not the name the
     * provider wrote.
     *
     * This is that row, and the assertion is the whole point of the component: what
     * survives is both ends.
     */
    @Test
    fun `the name the owner photographed keeps the part that identifies it`() {
        val name = "US: CINEMANIA HOLLYWOOD PREMIERE 5"

        val shown = middleElide(name) { it.length <= 23 }

        assertEquals("US: CINEMANI…PREMIERE 5", shown)
    }

    /**
     * **A long name, at the length the worst providers ship.**
     *
     * Sixty-one characters, with the discriminator — the frame rate and the channel
     * index — at the very end, which is where providers put it and where a tail
     * ellipsis destroys it.
     */
    @Test
    fun `a sixty-one character name loses its middle and keeps both ends`() {
        val name = "US: CINEMANIA HOLLYWOOD PREMIERE ULTRA HD MAIN EVENT 60FPS 51"
        assertEquals("the fixture is the length this test is named for", 61, name.length)

        val shown = middleElide(name) { it.length <= 24 }

        assertEquals("US: CINEMANI…NT 60FPS 51", shown)
        assertTrue("the family is still legible", shown.startsWith("US: CINEMANI"))
        assertTrue("the member is still legible", shown.endsWith("60FPS 51"))
    }

    /**
     * **A short name is not a problem to be solved.**
     *
     * Fifteen characters into a field that fits twenty-three. Nothing is measured
     * beyond the first question, nothing is cut, and the string that comes back is
     * the one that went in — the same instance, which is what makes this free for
     * the majority of rows.
     */
    @Test
    fun `a fifteen character name is returned untouched`() {
        val name = "US: BEIN SPORTS"
        assertEquals("the fixture is the length this test is named for", 15, name.length)

        val shown = middleElide(name) { it.length <= 23 }

        assertSame(name, shown)
    }

    // ---------------------------------------------------------------------- both directions

    /**
     * **The cut is logical, not visual.**
     *
     * An Arabic name is cut at its logical head and tail exactly as a Latin one is,
     * and the renderer puts the tail on the left because that is what the tail means
     * in that direction. Nothing here reasons about sides — which is the reason it
     * is correct in both, and the reason `Modifier.padding(start =)` is the only
     * kind of padding this repository allows.
     *
     * The layout direction reaches the real measurement through the text measurer
     * the composable remembers, which takes it from the composition; it is in the
     * cache key beside the style and the density so a mirrored surface never reads a
     * string measured for the other one.
     */
    @Test
    fun `an arabic name loses its middle and keeps its logical ends`() {
        val name = "AR: قناة سينمانيا هوليوود بريمير الترا اتش دي الحدث الرئيسي 5"
        assertEquals("the fixture is the length of the latin one", 61, name.length)

        val shown = middleElide(name) { it.length <= 24 }

        assertTrue("the logical head survives", shown.startsWith("AR: قناة سين"))
        assertTrue(
            "the logical tail survives, and with it the channel's number",
            shown.endsWith("الرئيسي 5"),
        )
        assertEquals("one ellipsis, in the middle", 1, shown.count { it == '…' })
    }

    // ------------------------------------------------------------------- what may not break

    /**
     * **A `Char` is half of an emoji.**
     *
     * Providers decorate names, and a name cut through the middle of a surrogate
     * pair renders as the replacement glyph — a defect that appears only on the
     * providers that decorate, which is most of them. The cut positions come from
     * `BreakIterator`, so they fall between clusters and never inside one.
     *
     * Swept over every width the string can be given rather than a chosen one,
     * because a break like this survives a test suite by living at one length.
     */
    @Test
    fun `no width cuts a surrogate pair in half`() {
        val name = "US: FUN 😀 HOLLYWOOD PREMIERE 5"

        for (budget in 0..name.length) {
            val shown = middleElide(name) { it.length <= budget }
            assertFalse(
                "budget $budget broke a pair in ${shown.toCodePointList()}",
                shown.hasLoneSurrogate(),
            )
        }
    }

    /**
     * **Whatever comes back, fits.**
     *
     * The search is a binary one over a measurement that is only nearly monotonic —
     * trimming the space either side of the join can make a wider cut draw the same
     * width as a narrower one. That is harmless, and this is the assertion that says
     * so: over every width, the string handed to the row is a string the row can
     * hold. The single exception is a slot too narrow for one ellipsis, which no
     * string can answer.
     */
    @Test
    fun `the result fits every width that can hold an ellipsis`() {
        val names = listOf(
            "US: CINEMANIA HOLLYWOOD PREMIERE 5",
            "US: CINEMANIA HOLLYWOOD PREMIERE ULTRA HD MAIN EVENT 60FPS 51",
            "AR: قناة سينمانيا هوليوود بريمير الترا اتش دي الحدث الرئيسي 5",
            "US: BEIN SPORTS",
            "4K",
            "",
        )

        for (name in names) {
            for (budget in 1..name.length + 1) {
                val shown = middleElide(name) { it.length <= budget }
                assertTrue(
                    "'$name' at $budget drew ${shown.length}: '$shown'",
                    shown.length <= budget,
                )
            }
        }
    }

    /**
     * **A slot with no room left says so with the one glyph that means it.**
     *
     * Below one character there is no honest string, so the ellipsis is drawn and
     * the row clips it. Returning the full name instead would push the quality tag
     * and the number plate off their own row, which is a broken layout rather than a
     * broken word.
     */
    @Test
    fun `a slot too narrow for anything draws the bare ellipsis`() {
        val shown = middleElide("US: CINEMANIA HOLLYWOOD PREMIERE 5") { it.length <= 0 }

        assertEquals("…", shown)
    }

    // ------------------------------------------------------------------------- helpers

    /** True when a high surrogate stands without its low, or the reverse. */
    private fun String.hasLoneSurrogate(): Boolean {
        var i = 0
        while (i < length) {
            val c = this[i]
            when {
                c.isHighSurrogate() ->
                    if (i + 1 >= length || !this[i + 1].isLowSurrogate()) return true else i += 2
                c.isLowSurrogate() -> return true
                else -> i++
            }
        }
        return false
    }

    /** Readable in a failure message, where a broken pair prints as a question mark. */
    private fun String.toCodePointList(): String =
        map { it.code.toString(16) }.joinToString(" ", "[", "]")
}
