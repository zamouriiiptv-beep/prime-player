package com.castivio.tv.probe.core

import com.castivio.tv.probe.core.Reconciliation.Completeness
import com.castivio.tv.probe.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconciliationTest {

    private val arm = ProbeArms.K3

    private fun draft(reply: OneReplyScan, walk: WalkScan): Reconciliation.Draft = Reconciliation.draft(arm, reply, walk)

    @Test
    fun a_walk_that_brings_every_film_is_complete() {
        val result = Fixtures.completeResult(arm)
        assertEquals(Completeness.COMPLETE, result.completeness)
        assertEquals(3, result.draft.walkRows)
        assertEquals(3, result.draft.walkDistinct)
        assertEquals(3, result.draft.replyDistinct)
        assertEquals(0, result.missing)
        assertEquals(0, result.extra)
        assertEquals(0, result.draft.idsInSeveralCategories)
        assertTrue(result.balanced)
    }

    @Test
    fun films_under_no_listed_category_are_unreachable_and_make_it_incomplete() {
        // Film 2 names a category the panel never listed; film 3 names none at all.
        val reply = Fixtures.reply(setOf("a"), listOf(1L to "a", 2L to "zzz", 3L to null))
        val walk = Fixtures.walk(listOf("a"), listOf(Triple(1L, "a", null)))
        val result = Reconciliation.finish(draft(reply, walk), null)
        assertEquals(2, result.draft.missingOrphans)
        assertEquals(2, result.draft.unlistedDeclaredRows)
        assertEquals(2, result.missing)
        assertEquals(Completeness.INCOMPLETE, result.completeness)
        assertTrue(result.balanced)
    }

    @Test
    fun a_category_that_failed_explains_what_is_missing_from_it_and_is_still_incomplete() {
        val reply = Fixtures.reply(setOf("a", "b"), listOf(1L to "a", 2L to "b"))
        val walk = Fixtures.walk(listOf("a", "b"), listOf(Triple(1L, "a", null)), failed = setOf("b"))
        val result = Reconciliation.finish(draft(reply, walk), null)
        assertEquals(1, result.draft.missingFailed)
        assertEquals(1, result.draft.failedCategories)
        assertEquals(Completeness.INCOMPLETE, result.completeness)
    }

    @Test
    fun a_film_listed_under_two_categories_is_counted_and_does_not_make_it_incomplete() {
        val result = Fixtures.duplicateResult(arm)
        assertEquals(2, result.draft.walkRows)
        assertEquals(1, result.draft.walkDistinct)
        assertEquals(1, result.draft.idsInSeveralCategories)
        assertEquals(1, result.draft.extraRowsFromSeveralCategories)
        assertEquals(0, result.draft.repeatsInsideCategory)
        // The second listing names another category than the one it was asked under.
        assertEquals(1, result.draft.declaredMismatchRows)
        assertEquals(Completeness.COMPLETE, result.completeness)
        assertTrue(result.balanced)
    }

    @Test
    fun a_film_repeated_inside_one_category_is_not_a_film_in_several() {
        val reply = Fixtures.reply(setOf("a"), listOf(1L to "a"))
        val walk = Fixtures.walk(listOf("a"), listOf(Triple(1L, "a", null), Triple(1L, "a", null)))
        val result = Reconciliation.finish(draft(reply, walk), null)
        assertEquals(1, result.draft.repeatsInsideCategory)
        assertEquals(0, result.draft.idsInSeveralCategories)
        assertEquals(2, result.draft.walkRows)
        assertEquals(1, result.draft.walkDistinct)
    }

    @Test
    fun a_film_repeated_inside_the_one_reply_is_counted() {
        val reply = Fixtures.reply(setOf("a"), listOf(1L to "a", 1L to "a", 2L to "a"))
        val walk = Fixtures.walk(listOf("a"), listOf(Triple(1L, "a", null), Triple(2L, "a", null)))
        val result = Reconciliation.finish(draft(reply, walk), null)
        assertEquals(3, result.draft.replyRows)
        assertEquals(2, result.draft.replyDistinct)
        assertEquals(1, result.draft.replyDuplicateRows)
        assertEquals(Completeness.COMPLETE, result.completeness)
    }

    @Test
    fun a_missing_film_waits_for_the_drift_check_and_is_neither_explained_nor_a_loss() {
        val result = Fixtures.pendingResult(arm)
        assertEquals(1, result.missingPending)
        assertEquals(0, result.missingUnexplained)
        assertFalse(result.driftKnown)
        assertEquals(Completeness.PENDING, result.completeness)
    }

    @Test
    fun a_film_that_is_gone_from_the_later_reply_was_removed_meanwhile() {
        val reply = Fixtures.reply(setOf("a"), listOf(1L to "a", 2L to "a"))
        val walk = Fixtures.walk(listOf("a"), listOf(Triple(1L, "a", null)))
        val later = Fixtures.reply(setOf("a"), listOf(1L to "a"))
        val result = Reconciliation.finish(draft(reply, walk), later)
        assertEquals(1, result.missingDrift)
        assertEquals(0, result.missingUnexplained)
        assertTrue(result.driftKnown)
        assertEquals(Completeness.COMPLETE, result.completeness)
    }

    @Test
    fun a_film_that_is_still_in_the_later_reply_and_not_in_the_walk_is_unexplained() {
        val reply = Fixtures.reply(setOf("a"), listOf(1L to "a", 2L to "a"))
        val walk = Fixtures.walk(listOf("a"), listOf(Triple(1L, "a", null)))
        val later = Fixtures.reply(setOf("a"), listOf(1L to "a", 2L to "a"))
        val result = Reconciliation.finish(draft(reply, walk), later)
        assertEquals(1, result.missingUnexplained)
        assertEquals(0, result.missingDrift)
        assertEquals(Completeness.INCOMPLETE, result.completeness)
    }

    @Test
    fun a_film_only_the_walk_brought_is_pending_then_added_meanwhile_or_unexplained() {
        val reply = Fixtures.reply(setOf("a"), listOf(1L to "a"))
        val walk = Fixtures.walk(listOf("a"), listOf(Triple(1L, "a", null), Triple(9L, "a", null)))
        val pending = Reconciliation.finish(draft(reply, walk), null)
        assertEquals(1, pending.extra)
        assertEquals(1, pending.extraPending)
        assertEquals(Completeness.PENDING, pending.completeness)

        val laterWithIt = Fixtures.reply(setOf("a"), listOf(1L to "a", 9L to "a"))
        val added = Reconciliation.finish(draft(reply, walk), laterWithIt)
        assertEquals(1, added.extraDrift)
        assertEquals(Completeness.COMPLETE, added.completeness)

        val laterWithout = Fixtures.reply(setOf("a"), listOf(1L to "a"))
        val unexplained = Reconciliation.finish(draft(reply, walk), laterWithout)
        assertEquals(1, unexplained.extraUnexplained)
        assertEquals(Completeness.INCOMPLETE, unexplained.completeness)
    }

    @Test
    fun the_arithmetic_closes_with_films_missing_and_films_extra_together() {
        val reply = Fixtures.reply(setOf("a"), listOf(1L to "a", 2L to "a", 3L to "a"))
        val walk = Fixtures.walk(listOf("a"), listOf(Triple(1L, "a", null), Triple(9L, "a", null)))
        val result = Reconciliation.finish(draft(reply, walk), null)
        assertEquals(2, result.missing)
        assertEquals(1, result.extra)
        assertEquals(2, result.draft.walkDistinct)
        assertTrue(result.balanced)
    }

    @Test
    fun the_categories_that_differ_are_named_by_id_and_biggest_difference_first() {
        val reply = Fixtures.reply(
            setOf("a", "b", "c"),
            listOf(1L to "a", 2L to "a", 3L to "a", 4L to "b", 5L to "c"),
        )
        // a is short by three, b by one, c is complete.
        val walk = Fixtures.walk(listOf("a", "b", "c"), listOf(Triple(5L, "c", null)))
        val diffs = draft(reply, walk).categoryDiffs
        assertEquals(listOf("a", "b"), diffs.map { it.category })
        assertEquals(3, diffs[0].replyRows)
        assertEquals(0, diffs[0].walkRows)
        assertEquals(-3, diffs[0].difference)
    }

    @Test
    fun no_more_than_ten_differing_categories_are_listed() {
        val categories = (1..15).map { "c$it" }
        val reply = Fixtures.reply(categories.toSet(), categories.mapIndexed { index, category -> (index + 1).toLong() to category })
        val walk = Fixtures.walk(categories, emptyList())
        assertEquals(10, draft(reply, walk).categoryDiffs.size)
    }

    @Test
    fun the_drift_counts_what_was_added_and_what_was_removed() {
        val before = Fixtures.reply(setOf("a"), listOf(1L to "a", 2L to "a", 3L to "a"))
        val after = Fixtures.reply(setOf("a"), listOf(2L to "a", 3L to "a", 4L to "a", 5L to "a"))
        val drift = Reconciliation.drift(before, after)
        assertEquals(3, drift.before)
        assertEquals(4, drift.after)
        assertEquals(2, drift.added)
        assertEquals(1, drift.removed)
    }

    @Test
    fun the_same_reply_twice_has_no_drift() {
        val reply = Fixtures.reply(setOf("a"), listOf(1L to "a", 2L to "a"))
        val drift = Reconciliation.drift(reply, reply)
        assertEquals(0, drift.added)
        assertEquals(0, drift.removed)
    }

    @Test
    fun a_big_catalogue_reconciles_to_the_row() {
        val count = 60_000
        val listed = (1..50).map { "c$it" }
        val films = (1..count).map { id -> id.toLong() to listed[id % 50] }
        val reply = Fixtures.reply(listed.toSet(), films)
        // The walk loses every thousandth film.
        val brought = films.filter { (id, _) -> id % 1_000L != 0L }.map { (id, category) -> Triple(id, category!!, null as String?) }
        val walk = Fixtures.walk(listed, brought)
        val result = Reconciliation.finish(draft(reply, walk), reply)
        assertEquals(count, result.draft.replyDistinct)
        assertEquals(60, result.missing)
        assertEquals(60, result.missingUnexplained)
        assertEquals(Completeness.INCOMPLETE, result.completeness)
        assertTrue(result.balanced)
    }
}
