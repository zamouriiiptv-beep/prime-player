package com.castivio.core.design.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import com.castivio.core.design.theme.Palette

/**
 * What a thumbnail looks like before there is a thumbnail.
 *
 * A video the device holds has a frame somewhere, but `MediaStore` hands it over
 * asynchronously and sometimes not at all — a file it has not indexed, a codec it
 * cannot decode, a network volume that has gone away. Every library screen therefore
 * has a state where the tile is real and the picture is not, and it is the *common*
 * state for the first second of a cold library rather than an edge case.
 *
 * A grey rectangle with a broken-image glyph reads as a fault. These read as artwork:
 * two palette colours on a diagonal, chosen by the item's position so the wall varies
 * without anything random in it — the same grid redrawn is the same grid, which is
 * what makes a screenshot comparable and a scroll stable.
 *
 * In `:core:design` because it is the visual language rather than a screen's business,
 * and because invariant 1 forbids a feature from naming a colour at all.
 */
object CastivioArtwork {

    /**
     * The placeholder for the item at [index].
     *
     * `index.mod(...)` rather than `%`: a negative index is a caller's bug, and
     * `mod` gives a defined answer instead of an exception on a screen that is only
     * drawing a rectangle.
     */
    fun placeholder(index: Int): Brush {
        val pair = PAIRS[index.mod(PAIRS.size)]
        // The angle turns with the index too, so neighbours with the same pair do not
        // read as a repeat. Expressed as a start and end point rather than degrees,
        // which is what `linearGradient` takes.
        val slant = SLANTS[index.mod(SLANTS.size)]
        return Brush.linearGradient(
            colors = pair,
            start = Offset.Zero,
            end = slant,
        )
    }

    /** Palette pairs only — nothing here mixes a colour Castivio does not already use. */
    private val PAIRS = listOf(
        listOf(Palette.Azure40, Palette.Violet50),
        listOf(Palette.Violet40, Palette.Azure10),
        listOf(Palette.Azure60, Palette.Violet10),
        listOf(Palette.Violet50, Palette.Azure10),
        listOf(Palette.Azure40, Palette.Deep),
        listOf(Palette.Azure60, Palette.Violet40),
        listOf(Palette.Violet10, Palette.Azure60),
        listOf(Palette.Violet40, Palette.Azure40),
    )

    /**
     * Four directions, in the infinite-tile space `linearGradient` uses when the end
     * point is past the drawn size: the brush is clipped to the tile either way, and
     * the numbers only have to differ from each other.
     */
    private val SLANTS = listOf(
        Offset(600f, 340f),
        Offset(340f, 600f),
        Offset(600f, 0f),
        Offset(0f, 600f),
    )
}
