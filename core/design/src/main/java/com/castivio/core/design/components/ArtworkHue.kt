package com.castivio.core.design.components

/**
 * Which of the product's four hues a channel without a logo is drawn in -- see [NoLogoTile].
 *
 * Chosen from the channel's own name, so a channel keeps its colour wherever it is drawn -- the list, the picture's plate, under the
 * picture -- and across launches; a column of channels without logos is not a column of one grey; and nothing is stored. `String.hashCode`
 * is defined by the language specification (the same on every platform and every run), which is why it and not a random value, and
 * names that differ only in a number -- `DAZN BAR 1`, `DAZN BAR 2` -- fall on neighbouring hues, which is the variety wanted.
 *
 * Pure, so it is tested without a screen.
 */
object ArtworkHue {

    /** Azure, violet, green, amber: the hue roles of the palette, in that order. */
    const val COUNT = 4

    /** 0 until [COUNT]: where a name falls. A name that is blank has none and is drawn neutral by the caller. */
    fun indexOf(seed: String): Int = (seed.trim().hashCode() and Int.MAX_VALUE) % COUNT
}
