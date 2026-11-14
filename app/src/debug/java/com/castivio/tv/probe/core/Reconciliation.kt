package com.castivio.tv.probe.core

import kotlin.math.abs

/**
 * Whether the walk brought every film the one reply held -- and, when it did not, why.
 *
 * ## A difference is not a loss
 *
 * Two lists of the same section can differ in several ways that lose nothing, and one that loses everything, and a
 * count cannot tell them apart. So every difference is put under its cause before any word like "lost" is used:
 *
 *  - **under no listed category** -- the reply holds the film, and its category is blank or is not in the list the
 *    panel gave. The walk asks only for listed categories and cannot reach it. This is the one that is a real
 *    loss by construction.
 *  - **its category's request failed** -- the walk did not get that category at all.
 *  - **removed or added meanwhile** -- the catalogue moved between the downloads. Told by the drift check: the one
 *    reply, downloaded again at the end. A film missing from the walk that is also missing from the later reply
 *    was removed; one extra in the walk that the later reply has was added.
 *  - **unexplained** -- everything else. Only this is called a loss.
 *
 * It also counts the repeats, because the stored id of a film comes from its `stream_id` alone: a film listed
 * under two categories is one row in the database, and which category it ends up in is whichever write is last.
 * With several workers that is not a fixed order.
 *
 * Both steps are pure and work on the two ids and two categories of each film, nothing more.
 */
object Reconciliation {

    enum class Completeness {
        /** Nothing is missing that is not explained, and nothing is unreachable. */
        COMPLETE,

        /** Nothing is wrong yet, but some differences are waiting for the drift check to be told apart. */
        PENDING,

        INCOMPLETE,
    }

    data class CategoryDiff(
        val category: String,
        val replyRows: Int,
        val walkRows: Int,
    ) {
        val difference: Int get() = walkRows - replyRows
    }

    /** How far the catalogue moved between the first one reply and the last. */
    data class Drift(
        val before: Int,
        val after: Int,
        val added: Int,
        val removed: Int,
    )

    /** The comparison of one walk with the one reply, before the drift check can explain what moved. */
    class Draft internal constructor(
        val arm: ProbeArm,
        val replyRows: Int,
        val replyDistinct: Int,
        val replyDuplicateRows: Int,
        val replyRowsWithoutId: Int,
        val listedCategories: Int,
        val unlistedDeclaredRows: Int,
        val walkRows: Int,
        val walkDistinct: Int,
        val walkRowsWithoutId: Int,
        val repeatsInsideCategory: Int,
        val idsInSeveralCategories: Int,
        val extraRowsFromSeveralCategories: Int,
        val declaredMismatchRows: Int,
        val failedCategories: Int,
        val missingOrphans: Int,
        val missingFailed: Int,
        internal val missingListed: LongArray,
        internal val extra: LongArray,
        val categoryDiffs: List<CategoryDiff>,
    )

    class Result(
        val arm: ProbeArm,
        val draft: Draft,
        val driftKnown: Boolean,
        val missingDrift: Int,
        val missingUnexplained: Int,
        val missingPending: Int,
        val extraDrift: Int,
        val extraUnexplained: Int,
        val extraPending: Int,
    ) {
        /** Films in the reply that the walk did not bring, by any cause. */
        val missing: Int get() = draft.missingOrphans + draft.missingFailed + draft.missingListed.size

        /** Films the walk brought that the reply does not hold. */
        val extra: Int get() = draft.extra.size

        /** The arithmetic closes: films in the walk = films in the reply - missing + extra. A check on this code, not on the data. */
        val balanced: Boolean get() = draft.walkDistinct == draft.replyDistinct - missing + extra

        val completeness: Completeness
            get() = when {
                draft.missingOrphans > 0 ||
                    draft.missingFailed > 0 ||
                    missingUnexplained > 0 ||
                    extraUnexplained > 0 -> Completeness.INCOMPLETE
                missingPending > 0 || extraPending > 0 -> Completeness.PENDING
                else -> Completeness.COMPLETE
            }
    }

    fun draft(arm: ProbeArm, reply: OneReplyScan, walk: WalkScan): Draft {
        val r = reply.entries
        val w = walk.entries
        val replyIds = SortedIds.of(r.idsCopy())
        val walkIds = SortedIds.of(w.idsCopy())

        // How the walk's rows repeat: inside one category's reply, or across several categories.
        val categoryCode = HashMap<String, Int>()
        for ((position, category) in walk.listed.withIndex()) {
            if (!categoryCode.containsKey(category)) categoryCode[category] = position + 1
        }
        val keys = LongArray(w.size)
        for (row in 0 until w.size) {
            val rank = walkIds.indexOf(w.id(row))
            val asked = w.requested(row)
            val code = if (asked == null) 0 else (categoryCode[asked] ?: 0)
            keys[row] = (rank.toLong() shl 32) or code.toLong()
        }
        keys.sort()
        var repeatsInside = 0
        var severalIds = 0
        var severalExtraRows = 0
        var start = 0
        while (start < keys.size) {
            val rank = keys[start] ushr 32
            var end = start
            var categories = 0
            var previous = -1L
            while (end < keys.size && (keys[end] ushr 32) == rank) {
                val code = keys[end] and CODE_MASK
                if (code != previous) {
                    categories++
                    previous = code
                } else {
                    repeatsInside++
                }
                end++
            }
            if (categories > 1) {
                severalIds++
                severalExtraRows += categories - 1
            }
            start = end
        }

        // What the reply held that the walk did not bring, and under which cause it falls.
        val listedSet = reply.listed
        val seen = BooleanArray(replyIds.size)
        val missingListed = LongList()
        var orphans = 0
        var failedMissing = 0
        var unlistedRows = 0
        for (row in 0 until r.size) {
            val declared = r.declared(row)
            val isListed = declared != null && declared in listedSet
            if (!isListed) unlistedRows++
            val id = r.id(row)
            val rank = replyIds.indexOf(id)
            if (seen[rank]) continue
            seen[rank] = true
            if (id in walkIds) continue
            when {
                !isListed -> orphans++
                declared != null && declared in walk.failed -> failedMissing++
                else -> missingListed.add(id)
            }
        }

        // What the walk brought that the reply did not hold.
        val extra = LongList()
        for (index in 0 until walkIds.size) {
            val id = walkIds.at(index)
            if (id !in replyIds) extra.add(id)
        }

        // Rows per listed category, in the reply and in the walk, and the rows that name another category than the one asked.
        val replyCount = HashMap<String, Int>()
        for (row in 0 until r.size) {
            val category = r.declared(row)
            if (category != null && category in listedSet) replyCount[category] = (replyCount[category] ?: 0) + 1
        }
        val walkCount = HashMap<String, Int>()
        var mismatch = 0
        for (row in 0 until w.size) {
            val asked = w.requested(row)
            val named = w.declared(row)
            if (asked != null) walkCount[asked] = (walkCount[asked] ?: 0) + 1
            if (asked != null && named != null && named != asked) mismatch++
        }
        val universe = LinkedHashSet<String>()
        universe.addAll(listedSet)
        universe.addAll(walk.listed)
        val diffs = ArrayList<CategoryDiff>()
        for (category in universe) {
            val inReply = replyCount[category] ?: 0
            val inWalk = walkCount[category] ?: 0
            if (inReply != inWalk) diffs += CategoryDiff(category, inReply, inWalk)
        }
        diffs.sortByDescending { abs(it.difference) }

        return Draft(
            arm = arm,
            replyRows = r.size,
            replyDistinct = replyIds.size,
            replyDuplicateRows = r.size - replyIds.size,
            replyRowsWithoutId = reply.rowsWithoutId,
            listedCategories = listedSet.size,
            unlistedDeclaredRows = unlistedRows,
            walkRows = w.size,
            walkDistinct = walkIds.size,
            walkRowsWithoutId = walk.rowsWithoutId,
            repeatsInsideCategory = repeatsInside,
            idsInSeveralCategories = severalIds,
            extraRowsFromSeveralCategories = severalExtraRows,
            declaredMismatchRows = mismatch,
            failedCategories = walk.failed.size,
            missingOrphans = orphans,
            missingFailed = failedMissing,
            missingListed = missingListed.toArray(),
            extra = extra.toArray(),
            categoryDiffs = diffs.take(MAX_DIFFS),
        )
    }

    /**
     * The draft, told what moved meanwhile by [drift] -- the one reply downloaded again at the end -- or, with null,
     * still waiting for it: those differences are then pending, neither explained nor called losses.
     */
    fun finish(draft: Draft, drift: OneReplyScan?): Result {
        val later = drift?.let { SortedIds.of(it.entries.idsCopy()) }
        var missingDrift = 0
        var missingUnexplained = 0
        var missingPending = 0
        for (id in draft.missingListed) {
            when {
                later == null -> missingPending++
                id !in later -> missingDrift++
                else -> missingUnexplained++
            }
        }
        var extraDrift = 0
        var extraUnexplained = 0
        var extraPending = 0
        for (id in draft.extra) {
            when {
                later == null -> extraPending++
                id in later -> extraDrift++
                else -> extraUnexplained++
            }
        }
        return Result(
            arm = draft.arm,
            draft = draft,
            driftKnown = later != null,
            missingDrift = missingDrift,
            missingUnexplained = missingUnexplained,
            missingPending = missingPending,
            extraDrift = extraDrift,
            extraUnexplained = extraUnexplained,
            extraPending = extraPending,
        )
    }

    fun drift(before: OneReplyScan, after: OneReplyScan): Drift {
        val first = SortedIds.of(before.entries.idsCopy())
        val last = SortedIds.of(after.entries.idsCopy())
        var added = 0
        for (index in 0 until last.size) if (last.at(index) !in first) added++
        var removed = 0
        for (index in 0 until first.size) if (first.at(index) !in last) removed++
        return Drift(first.size, last.size, added, removed)
    }

    private const val CODE_MASK = 0xFFFFFFFFL
    private const val MAX_DIFFS = 10
}
