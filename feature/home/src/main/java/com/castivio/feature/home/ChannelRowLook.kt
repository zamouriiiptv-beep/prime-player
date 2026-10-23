package com.castivio.feature.home

/**
 * What a channel row says, from the two facts it is told.
 *
 * The remote is on the row, or it is not; the player has a picture of the row's stream, or it
 * has not. They are separate facts with separate answers, and this is the one place that says
 * so: the violet surface and the ring around it follow the remote, and the playing mark follows
 * the player. Neither reads the other, so moving the remote never moves the mark and a stream
 * starting never moves the violet.
 */
internal data class RowLook(
    /** The violet under the row, and the ink that is legible on it. */
    val surface: Boolean,
    /** The ring that says "focused", which does not depend on the colour being told apart. */
    val ring: Boolean,
    /** The disc that says "this is the one playing". */
    val mark: Boolean,
)

internal fun rowLook(focused: Boolean, playing: Boolean): RowLook =
    RowLook(surface = focused, ring = focused, mark = playing)

/**
 * Whether this row is the one the player is showing.
 *
 * Two things must hold, so that no more than one row can ever say yes. The player has a picture
 * of this row's stream -- the player's answer, by address, which is what a request carries -- and
 * this row is the one that was pressed to start it. The second is what keeps a stream the
 * provider lists twice, in one category or under two names, from marking both rows: the address
 * is the same on both and the row that was pressed is only one.
 *
 * Neither fact is the playing state: the player says that, and with nothing playing it says
 * null, which no row matches. The pressed row only chooses *which* of the rows with the player's
 * address is the one -- and when it cannot (nothing pressed on this board), no row is marked
 * rather than two.
 */
internal fun isPlayingRow(
    channelId: String,
    streamUrl: String,
    pressedId: String?,
    playingUrl: String?,
): Boolean = playingUrl != null && pressedId == channelId && streamUrl == playingUrl
