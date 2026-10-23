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
 * Whether a press on this row is the second on its channel, which makes the picture large.
 *
 * The first press on a channel plays it into the preview; a press on the channel that is already
 * in the preview is the viewer asking for it bigger -- the same act the picture's own press
 * performs. Two facts decide it: this row was the one pressed, and the player has been asked for
 * its stream. Asked rather than drawn, so a second press made while the first is still opening
 * expands the stream that is opening instead of opening it again.
 *
 * Not a fact about the player alone, because a stream the provider lists twice has two rows with
 * one address and only the row that was pressed is the one in the picture. And not a fact about
 * the press alone, because a channel pressed on an earlier visit has no player any more: with
 * nothing asked for there is nothing to expand, and the press plays it, as the first one does.
 */
internal fun isExpandingPress(
    channelId: String,
    streamUrl: String,
    pressedId: String?,
    askedUrl: String?,
): Boolean = askedUrl != null && pressedId == channelId && streamUrl == askedUrl

/**
 * Whether this row's name scrolls to be read whole, instead of standing cut to fit.
 *
 * Two rows do: the one the remote is on, and the one that was pressed. The second is for a thumb:
 * a touch never gives a row focus, so a list read by touch would never scroll a name and every
 * name too long for its row would stay cut. A press is what a viewer does to a channel they want,
 * and the name of that channel is the one they want in full -- the way a channel list in the
 * TV apps people already use scrolls the name of the row that was pressed.
 *
 * Independent of the picture: the row pressed scrolls at once, not when the player has a frame,
 * and one whose stream never opens still shows the name it was asked for. It is also not the
 * playing mark -- that follows the player -- so a stream listed twice scrolls the one pressed.
 *
 * The category rail asks the same question of its entries, with [pressed] meaning the bouquet that
 * is open: a press on a bouquet is what opens it, and a thumb reaches no other way to read a name
 * that is cut.
 */
internal fun nameScrolls(focused: Boolean, pressed: Boolean): Boolean = focused || pressed

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
