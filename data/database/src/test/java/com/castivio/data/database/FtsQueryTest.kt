package com.castivio.data.database

import com.castivio.domain.searchTermOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM — no Robolectric needed, and this runs in milliseconds on every
 * commit. Search runs on every keystroke, so both the sanitising and the cost
 * matter.
 */
class FtsQueryTest {

    @Test
    fun `each word becomes a prefix term`() {
        assertEquals("nova*", FtsQuery.build("nova"))
        assertEquals("nova* spo*", FtsQuery.build("nova spo"))
    }

    @Test
    fun `fts operators a user types are treated as word breaks`() {
        // Passed through raw, every one of these is a MATCH syntax error and the
        // results list would go empty on a reasonable search.
        assertEquals("mission* impossible*", FtsQuery.build("Mission: Impossible"))
        assertEquals("tom* jerry*", FtsQuery.build("tom - jerry"))
        assertEquals("nova*", FtsQuery.build("\"nova\""))
        assertEquals("news*", FtsQuery.build("news**"))
        assertEquals("cup*", FtsQuery.build("(cup)"))
        // `OR` becomes the prefix term `or*`, not the FTS operator — which is the
        // point: the user typed a word, not a query.
        assertEquals("a* or* b*", FtsQuery.build("a OR b"))
    }

    @Test
    fun `terms are case-folded to match the stored index`() {
        assertEquals("nova* sports*", FtsQuery.build("NOVA Sports"))
        assertEquals("новости*", FtsQuery.build("Новости"))
    }

    @Test
    fun `nothing searchable is null, not an empty search`() {
        // Null means "idle", which is a different screen from "no results".
        assertNull(FtsQuery.build(""))
        assertNull(FtsQuery.build("   "))
        assertNull(FtsQuery.build("..."))
        assertNull(FtsQuery.build("-"))
    }

    @Test
    fun `non-latin input tokenises the same way`() {
        assertEquals("الرياضة*", FtsQuery.build("الرياضة"))
        assertEquals("قناة* الرياضة*", FtsQuery.build("قناة الرياضة"))
    }

    @Test
    fun `whitespace around and between terms is ignored`() {
        assertEquals("nova* sports*", FtsQuery.build("  nova   sports  "))
    }
    @Test
    fun `the domain's search term means what the query builder means`() {
        // The board folds a typed search with `searchTermOf` so that two typings of one
        // search are one query; the index folds it again here. They are two copies of one
        // rule -- a word is a run of letters and digits, lower-cased -- and this is what
        // stops them drifting: whatever is typed, folding first changes nothing.
        for (typed in listOf(
            "  BeIN   Sports ", "bein, sports!", "FR| SECRET STORY HD", "Mission: Impossible",
            "\"nova\"", "news**", "a OR b", "Новости", "قناة  الجزيرة", "İstanbul TV", "---", "", "   ", "4K 24",
        )) {
            assertEquals(typed, FtsQuery.build(typed), FtsQuery.build(searchTermOf(typed)))
        }
    }
}
