package com.castivio.core.design.components

/**
 * How long a picture a provider shipped is waited for, and what is believed of one that did not come.
 *
 * ## The wait the tile used to be
 *
 * The no-logo tile ([NoLogoTile]) was drawn for a channel with no address, and for one whose address *failed*; while a request was in
 * flight the slot was empty, deliberately, so that a tile which appeared and was at once replaced by the picture did not flicker down a
 * list. For an address that answers, the request ends in a moment and that was right. For one that does not -- a host that is down, or
 * that takes the connection and says nothing -- failing is the client's own limit: twelve seconds to connect, twenty between packets. A
 * channel whose logo pointed at such a host showed an empty slot for that long, and again every time its row came back to the screen,
 * because a failure was not remembered; and its request held one of the five a host is allowed at once, so the pictures queued behind
 * it waited as well.
 *
 * ## Three numbers, and how they stand to one another
 *
 *  - [TILE_AFTER_MS]: the tile is drawn when no picture has come in this long. The request goes on beside it, and a picture that comes
 *    later replaces it -- so a slow logo is a tile and then a logo, once, and a quick one (from the cache, from a host that answers) is
 *    never preceded by a tile at all.
 *  - [CALL_TIMEOUT_MS]: a logo request is given up at this, the lookup, the connection and the transfer together. A logo is some
 *    kilobytes asked for by a screen that is being drawn; what takes longer than this is not coming. It is less than the client's own
 *    limits, which stay as they are for everything else the application asks.
 *  - [FAILURE_MEMORY_MS]: an address that failed is not asked for again for this long, and its tile is drawn at once -- see
 *    [ArtworkFailures]. Long enough for a viewer to scroll a list and back, short enough that a provider who mended a logo is believed
 *    again in the same sitting.
 *
 * The tile has to come before the request is given up, or the wait is pointless, and the memory has to outlast the request, or nothing
 * is remembered: [TILE_AFTER_MS] is less than [CALL_TIMEOUT_MS], which is less than [FAILURE_MEMORY_MS]. A test holds the order.
 */
object ArtworkPolicy {

    /** How long the slot is left empty for a picture that is on its way. */
    const val TILE_AFTER_MS = 1_500L

    /** How long a logo request may take, whole. */
    const val CALL_TIMEOUT_MS = 6_000L

    /** How long an address that failed is believed to have failed: ten minutes. */
    const val FAILURE_MEMORY_MS = 10 * 60 * 1_000L

    /** How many failed addresses are kept: more than a list shows, and some tens of kilobytes at the most. */
    const val FAILURES_KEPT = 512
}

/**
 * What a logo's slot shows, decided without a screen -- so that the rule, which is the whole of the difference between a tile that
 * comes in a second and one that came in twenty, is held by a test and not by a look at a list.
 */
object ArtworkSlot {

    /** The address to ask for, or null where there is none: a blank one is no address. */
    fun address(url: String?): String? = url?.takeIf { it.isNotBlank() }

    /**
     * Whether the no-logo tile is drawn now.
     *
     * Always for no address and for an address that failed. For one that is still on its way, only once the wait is out -- and then
     * only until it has arrived: the picture replaces the tile, which is not left under a logo that has a transparent ground.
     *
     * @param address what [address] made of the provider's own.
     * @param failed the request ended without a picture, or the address is one that did within [ArtworkPolicy.FAILURE_MEMORY_MS].
     * @param arrived the picture has come.
     * @param waitedOut [ArtworkPolicy.TILE_AFTER_MS] has passed since the slot asked for it.
     */
    fun showsTile(address: String?, failed: Boolean, arrived: Boolean, waitedOut: Boolean): Boolean =
        address == null || failed || (waitedOut && !arrived)
}
