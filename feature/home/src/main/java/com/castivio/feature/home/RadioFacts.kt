package com.castivio.feature.home

import com.castivio.domain.Channel
import com.castivio.domain.RadioPhase
import com.castivio.domain.RadioStatus
import java.util.Locale

/**
 * What the radio says, from what the provider actually sent and nothing else.
 *
 * A panel sends a radio station as a name, a logo, a number and the category it is filed under. There is
 * no country, genre, bitrate or codec in that, so none is drawn: a fact that was not in the data is not made
 * up to fill a card. Pure, so what is said and what is left unsaid are tested without a screen.
 */

/** The times the sleep timer offers, in minutes: a short list a remote can walk, and one of them is always near enough. */
internal val SLEEP_MINUTES: List<Int> = listOf(15, 30, 45, 60, 90)

/** When a timer set for [minutes] at [nowMs] ends, on the clock [nowMs] is read from. */
internal fun sleepDeadlineMs(nowMs: Long, minutes: Int): Long = nowMs + minutes * MILLIS_PER_MINUTE

/**
 * How much of a timer is left, or null when none is running. Never negative: a timer that ended is nought
 * left, and the caller is the one that acts on it.
 */
internal fun sleepRemainingMs(deadlineMs: Long?, nowMs: Long): Long? =
    deadlineMs?.let { (it - nowMs).coerceAtLeast(0L) }

/**
 * `29:41`, or `1:05:00` from an hour: what is left of a timer, as a clock.
 *
 * Rounded up to the second, so a timer on its last half-second still says `0:01` and not a `0:00` that
 * would be read as having ended. Western digits and a colon in every language, as the board's own clock is --
 * the caller keeps it whole in a right-to-left line with `ltrToken`.
 */
internal fun remainingText(remainingMs: Long): String {
    val seconds = ((remainingMs.coerceAtLeast(0L) + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND)
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val rest = seconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, rest)
    }
}

/**
 * The station [by] places from [index] in a list of [count], or null when there is none there.
 *
 * Null at either end and not a wrap: a list that is paged has no end the screen knows, so "the station after
 * the last one it holds" is not the first, and the control is drawn off rather than sending the viewer
 * to the top of a list they did not ask for.
 */
internal fun stationStep(index: Int, by: Int, count: Int): Int? {
    if (index < 0) return null
    val target = index + by
    return target.takeIf { it in 0 until count }
}

/**
 * Where the station [by] places from the one with [currentId] sits in a list of [count], or null when the current one is
 * not in the list or there is none there. [idAt] answers a position's station id without loading anything.
 *
 * Asked of the list that is open and no other: the station after this one is the next card the viewer can see, so a
 * station that is not in the open list -- the viewer has gone to another category since pressing it -- has no
 * neighbours, and the controls for them are not drawn.
 */
internal fun neighbourOf(currentId: String, by: Int, count: Int, idAt: (Int) -> String?): Int? {
    val index = (0 until count).firstOrNull { idAt(it) == currentId } ?: return null
    return stationStep(index, by, count)
}

/**
 * Whether a station's card says which category it is filed under.
 *
 * Only on the lists that mix categories -- all of them, the favourites, the stations played last, a search --
 * where it is the one thing that tells two stations of one name apart. Inside a category it is that category,
 * said already by the rail and the band.
 */
internal fun BrowseState.mixesCategories(): Boolean = selectedGroup == null

/** The station's category, by name, for the second line of its card and the plate; null for one filed under none. */
internal fun Channel.categoryIn(names: Map<String, String>): String? = groupId?.let { names[it] }

/**
 * What the plate says the sound of [streamUrl] is doing, from the player's last report and the viewer's own hold.
 *
 * The player's report lags a press by a moment, and a plate that waited for it would show a station still playing
 * after Pause and a new station as nothing at all. So the viewer's hold is believed at once, a station the player
 * has not yet spoken of is on its way, and only a failure outranks the hold: a stream that failed is silence the
 * viewer did not choose, and the control on it asks again.
 */
internal fun radioPhaseOf(streamUrl: String, status: RadioStatus, paused: Boolean): RadioPhase = when {
    status.url != streamUrl -> if (paused) RadioPhase.PAUSED else RadioPhase.OPENING
    status.phase == RadioPhase.FAILED -> RadioPhase.FAILED
    paused -> RadioPhase.PAUSED
    status.phase == RadioPhase.IDLE -> RadioPhase.OPENING
    else -> status.phase
}

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
