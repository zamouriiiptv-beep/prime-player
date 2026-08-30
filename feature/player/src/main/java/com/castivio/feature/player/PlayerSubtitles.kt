package com.castivio.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing

/**
 * The captions, drawn by Castivio.
 *
 * ## Why this file exists at all
 *
 * There was no such layer, and so there were no subtitles — on any source, in either
 * engine's primary path, at any point in this product's life. The sheet listed the text
 * tracks the container declared, selecting one did genuinely select it, the decoder
 * decoded it, and the words went nowhere: `PlayerView` is what ordinarily carries a
 * `SubtitleView`, and this screen deliberately does not use `PlayerView`. A control that
 * works perfectly and produces nothing visible is the hardest kind of defect to report,
 * which is roughly how long this one lasted.
 *
 * ## Where a caption is allowed to be
 *
 * Not where the source says. A cue carries a position and an anchor authored for a cinema
 * screen or a television set, and honouring them on a phone puts words over the controls
 * the viewer is reaching for. The viewer's own setting decides, and the source's opinion
 * is dropped at the engine boundary — see `PlaybackEngine.cues`.
 *
 * The one thing that does move a caption is the chrome. When the controls are up, the
 * bottom band carries a timeline and a row of buttons, and a caption sitting under them is
 * a caption nobody can read; so it lifts, and drops again when they hide. That is the
 * behaviour of every player that has thought about it, and its absence is the reason
 * subtitles and controls fight in the ones that have not.
 *
 * ## It takes no touches
 *
 * Nothing here is clickable and nothing here consumes a pointer. A caption that swallowed
 * a tap would be a caption that stops the controls appearing, at the exact moment there
 * are words on screen and the viewer wants to pause and read them.
 */
@Composable
internal fun BoxScope.SubtitleLayer(state: PlayerState) {
    val lines = state.cues
    if (lines.isEmpty()) return

    val style = state.subtitleStyle
    val colors = CastivioTheme.colors
    val ink = when (style.ink) {
        SubtitleInk.White -> colors.subtitleInk
        SubtitleInk.Amber -> colors.subtitleInkWarm
    }
    val backdrop = when (style.backdrop) {
        SubtitleBackdrop.None, SubtitleBackdrop.Shadow -> Color.Transparent
        SubtitleBackdrop.Soft -> colors.subtitleBackdropSoft
        SubtitleBackdrop.Solid -> colors.subtitleBackdropSolid
    }

    // The outline the words keep when there is no box behind them. Not decoration: white
    // on a white frame is invisible, and a viewer who switched the backdrop off asked for
    // less obstruction rather than for unreadable words.
    val outlined = style.backdrop == SubtitleBackdrop.None ||
        style.backdrop == SubtitleBackdrop.Shadow

    Column(
        Modifier
            .align(style.place.alignment())
            .fillMaxWidth()
            .padding(horizontal = SIDE_MARGIN, vertical = inset(state))
            .testTag(PlayerTags.CAPTIONS),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        for (line in lines) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(backdrop)
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
            ) {
                Text(
                    text = line,
                    style = style.size.type().withOutline(outlined, colors.subtitleShadow),
                    color = ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * How far the caption sits from the edge it is anchored to.
 *
 * The chrome is the only thing on this screen that can be in a caption's way. With the
 * controls up the bottom band is a timeline, a tools row and — on live — a programme
 * strip; a caption under all of that is a caption nobody reads, so it lifts clear and
 * drops back when they hide.
 *
 * Nothing lifts a caption anchored to the top: the title bar is one row and a caption
 * clear of it is clear of it whether the chrome is up or not.
 */
@Composable
private fun inset(state: PlayerState): Dp {
    // What the chrome takes at the bottom, which is not one figure: a television's controls
    // are built on a 56dp target and a handset's on 48dp, and the row heights follow.
    val chrome = if (CastivioTheme.device.isTv) CLEAR_OF_TV_CHROME else CLEAR_OF_CHROME
    return when (state.subtitleStyle.place) {
        SubtitlePlace.Top -> EDGE_MARGIN
        SubtitlePlace.Raised -> if (state.controls) chrome + RAISE else RAISE
        SubtitlePlace.Bottom -> if (state.controls) chrome else EDGE_MARGIN
    }
}

private fun SubtitlePlace.alignment(): Alignment = when (this) {
    SubtitlePlace.Bottom, SubtitlePlace.Raised -> Alignment.BottomCenter
    SubtitlePlace.Top -> Alignment.TopCenter
}

/**
 * The four sizes, as type.
 *
 * `internal` so a test can assert they differ and increase. Robolectric measures every
 * `Text` at the same height whatever its style — `PlayerLayoutTest` says so at length — so
 * "a bigger setting makes a bigger caption" is not a claim a layout test on this runner can
 * make, and asserting it there would produce a test that passes for the wrong reason.
 */
internal fun SubtitleSize.type(): TextStyle = when (this) {
    SubtitleSize.Small -> CastivioType.subtitleSmall
    SubtitleSize.Medium -> CastivioType.subtitleMedium
    SubtitleSize.Large -> CastivioType.subtitleLarge
    SubtitleSize.Huge -> CastivioType.subtitleHuge
}

/** A dark halo around the glyphs, so a bright frame cannot swallow them. */
private fun TextStyle.withOutline(outlined: Boolean, colour: Color): TextStyle =
    if (!outlined) this else copy(shadow = Shadow(colour, Offset.Zero, HALO))

private val SIDE_MARGIN = 32.dp

/** The ordinary resting place: a caption's own margin from the edge of the picture. */
private val EDGE_MARGIN = 28.dp

/**
 * Clear of the timeline and the tools row, with air above them.
 *
 * The two rows are a touch target each plus the gap between them and the safe inset below
 * — about 128dp on a handset — and a caption resting exactly on that would touch the
 * timeline. The figure has room in it on purpose.
 */
private val CLEAR_OF_CHROME = 148.dp

/** The same on a television, where every row is built on a 56dp target rather than 48. */
private val CLEAR_OF_TV_CHROME = 196.dp

/** How much higher Raised sits: above a film's own burnt-in text along the bottom edge. */
private val RAISE = 68.dp

private const val HALO = 6f
