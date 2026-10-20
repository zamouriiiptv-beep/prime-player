package com.castivio.feature.home

import com.castivio.domain.MediaKind
import com.castivio.domain.SectionLoad
import com.castivio.domain.SectionRun

/**
 * What a section's card on Home says about its load, beside what the database holds.
 *
 * The card's headline -- the count, "not downloaded yet", "no films" -- is the database's,
 * and stays the database's. This is the line under it, and it is only ever one of these.
 * Decided here, in plain Kotlin, so every state the approved drawing shows
 * (`design/mockups/home-loading.html`) is a unit test rather than a device session.
 */
sealed interface CardLoad {

    /** Nothing to say: the card shows what is stored, or that nothing is. */
    data object Idle : CardLoad

    /**
     * Asked for, behind another section's load.
     *
     * @param hinted the card was pressed while it waits, and says when it will open.
     */
    data class Waiting(val hinted: Boolean = false) : CardLoad

    /**
     * Arriving.
     *
     * @param items rows committed so far.
     * @param percent categories finished of the whole, where the importer can count them;
     *   null where it cannot -- one reply carrying a whole section names no total until it
     *   ends -- and the card counts [items] instead. Never a timer.
     * @param refresh the section already has rows: they stay on the card, and it opens.
     * @param hinted the card was pressed before its first load ended, and says when it opens.
     */
    data class Arriving(
        val items: Int,
        val percent: Int?,
        val refresh: Boolean,
        val hinted: Boolean = false,
    ) : CardLoad

    /**
     * Arrived moments ago: the card says so for a few seconds, then goes back to its count.
     *
     * @param seconds how long the load took, for the builds that print it.
     */
    data class Arrived(val items: Int, val seconds: Long?) : CardLoad

    /**
     * The last load failed, and waits for a press to be asked again.
     *
     * @param refresh there are rows under it, so the section still opens with them.
     */
    data class Failed(val refresh: Boolean) : CardLoad
}

/**
 * The line a card shows for its section's latest [run].
 *
 * @param onDevice the section has rows here, or is marked as loaded with none.
 * @param justArrived its load ended moments ago and the card still says so.
 * @param hinted its card was pressed while it was still on its way.
 */
internal fun cardLoad(
    run: SectionRun?,
    onDevice: Boolean,
    justArrived: Boolean,
    hinted: Boolean,
): CardLoad = when (val load = run?.load) {
    is SectionLoad.Queued -> CardLoad.Waiting(hinted = hinted && !onDevice)
    is SectionLoad.Loading -> CardLoad.Arriving(
        items = load.items,
        percent = load.percent(),
        refresh = onDevice,
        hinted = hinted && !onDevice,
    )
    is SectionLoad.Failed -> CardLoad.Failed(refresh = onDevice)
    is SectionLoad.Done -> if (justArrived) CardLoad.Arrived(load.items, run.elapsedMs?.let(::wholeSeconds)) else CardLoad.Idle
    // Ready: marked, nothing fetched. NoSource: Home says that on its own. Null: not asked.
    is SectionLoad.Ready, is SectionLoad.NoSource, null -> CardLoad.Idle
}

/**
 * Categories finished of the whole, in whole percent, or null where there is no whole.
 *
 * Rounded down, so a section is never "100%" while its last category is still arriving.
 */
internal fun SectionLoad.Loading.percent(): Int? {
    val done = groupsDone ?: return null
    if (groups <= 0) return null
    return (done.toLong() * 100 / groups).toInt().coerceIn(0, 100)
}

private fun wholeSeconds(ms: Long): Long = (ms + 500) / 1_000

/** What a press on a section's card does. */
enum class Press {
    /** The section is here: open it. */
    Open,

    /** The section's rows are here but its mark was forgotten: open it, and fetch behind it. */
    OpenAndFetch,

    /** The section is not here: ask for it, and stay on Home to watch its card. */
    Fetch,

    /** The section is on its way: stay, and let the card say when it will open. */
    Hint,
}

/**
 * The press, decided from three facts and nothing else.
 *
 * @param onDevice the section has rows, or was loaded and turned out empty.
 * @param marked the section is marked as loaded for this subscription.
 * @param arriving a load of it is waiting or running.
 */
internal fun pressOf(onDevice: Boolean, marked: Boolean, arriving: Boolean): Press = when {
    onDevice -> if (!marked && !arriving) Press.OpenAndFetch else Press.Open
    arriving -> Press.Hint
    else -> Press.Fetch
}

/**
 * The sections whose load ended between two answers of the queue: done now, and not done
 * before -- including one that went from nothing to done between two looks.
 */
internal fun arrivedBetween(before: Map<MediaKind, SectionRun>, after: Map<MediaKind, SectionRun>): Set<MediaKind> =
    after.filterValues { it.load is SectionLoad.Done }
        .keys
        .filterTo(LinkedHashSet()) { before[it]?.load !is SectionLoad.Done }
