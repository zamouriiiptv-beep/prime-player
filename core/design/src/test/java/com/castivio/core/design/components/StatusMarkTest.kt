package com.castivio.core.design.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gap between a condition's mark and its sentence, which every state shares.
 *
 * ## Why consistency is structural here rather than asserted state by state
 *
 * Because there is one composable. The licence screen's ten conditions — trial,
 * trial ended, annual active, annual expired, lifetime, revoked, verification
 * needed, unreadable, unavailable, no licence — are ten sentences handed to the
 * same [StatusLine], and the header's chip is the same [StatusChip] in both
 * screens. A per-state test would be ten copies of one claim about one `Row`.
 *
 * What can drift is the *number*, in two ways: the two composables could be
 * given different gaps, or a change to the spacing scale could move whichever
 * token they happened to read. Both are closed by there being a single constant
 * that belongs to this file, and this asserts what that constant is allowed to
 * be.
 */
class StatusMarkTest {

    /**
     * Six to eight dp, which is the window the design asks for.
     *
     * A window rather than the exact number, for the reason the sibling focus
     * test gives: the number is a choice inside the window and the window is the
     * requirement. Eight becoming seven should not fail a test; eight becoming
     * four should, and did — four was the value, and against a 6dp mark it read
     * as the dot being attached to the first letter rather than as punctuation
     * in front of it.
     */
    @Test
    fun `the mark's gap is inside the window the design calls for`() {
        assertTrue(
            "the mark sits $MARK_GAP from its sentence, outside the 6-8dp window",
            MARK_GAP >= 6.dp && MARK_GAP <= 8.dp,
        )
    }

    // -- The count inside the sentence --------------------------------------

    /**
     * The numeral is found where the language put it, not where English would.
     *
     * The badge is one plural resource now, so the number is at the front in
     * English and Japanese, in the middle in Arabic and Turkish, and nowhere
     * near the end in any of them. This is the property that lets one string
     * serve all thirty-seven.
     */
    @Test
    fun `the numeral is emphasised wherever the sentence puts it`() {
        for (sentence in listOf("7-day trial", "Testversion für 7 Tage", "7日間の試用")) {
            val annotated = emphasiseNumber(sentence, 7)
            val at = sentence.indexOf("7")
            val spans = annotated.spanStyles
            assertEquals("$sentence: expected exactly one emphasised span", 1, spans.size)
            assertEquals("$sentence: the emphasis is at the wrong offset", at, spans[0].start)
            assertEquals(at + 1, spans[0].end)
            assertEquals(FontWeight.SemiBold, spans[0].item.fontWeight)
        }
    }

    /**
     * A sentence whose numeral is not there is drawn plain, not drawn wrong.
     *
     * Arabic's `one` form is "ليوم واحد" — one day, spelled out, with no digit
     * in it at all. A bold at a guessed offset would land on a letter; no bold
     * is invisible. The right failure is the invisible one.
     */
    @Test
    fun `a sentence with no numeral in it is left unemphasised`() {
        val annotated = emphasiseNumber("نسخة تجريبية ليوم واحد", 1)
        assertEquals(0, annotated.spanStyles.size)
    }

    /**
     * The tint is optional and, when absent, leaves the text's own colour alone.
     *
     * `Color.Unspecified` rather than a default of the primary: a span that
     * silently recoloured everything it touched would make every future caller
     * of this function opt out of a decision it never asked to make.
     */
    @Test
    fun `the tint is applied only when one is asked for`() {
        assertEquals(
            Color.Unspecified,
            emphasiseNumber("7-day trial", 7).spanStyles[0].item.color,
        )
        assertEquals(
            Color.Red,
            emphasiseNumber("7-day trial", 7, Color.Red).spanStyles[0].item.color,
        )
    }
}
