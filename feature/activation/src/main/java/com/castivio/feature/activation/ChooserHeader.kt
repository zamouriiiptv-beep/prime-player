package com.castivio.feature.activation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import com.castivio.core.design.components.CastivioBackChip
import com.castivio.core.design.components.CastivioClock
import com.castivio.core.design.components.CastivioThemeSwitchChip
import com.castivio.core.design.components.CastivioHeader
import com.castivio.core.design.components.CastivioHeaderTitle
import com.castivio.core.design.components.CastivioLockup
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioGlyphGlow
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.theme.CastivioTheme

/**
 * The header every step of this flow wears: mark, name, question, and the way back.
 *
 * ## Why one function and not five
 *
 * Five screens in this module put a title at the top and a way back somewhere near it,
 * and until now each did it differently: the source choice used `CastivioHeader` with a
 * Back chip, the media chooser and the library scaffold drew a title with a corner
 * wordmark and put Back in a footer of their own, and the saved-subscriptions step drew
 * a bare title and a full-width button at the bottom. Three headers, two footers, and
 * one wordmark at three sizes — for one row that says the same thing on all five.
 *
 * A reader moving between them saw the brand change size and Back change place. Nobody
 * can point at that; everybody feels it.
 *
 * ## What the row does about direction
 *
 * The lockup is at the same **physical** edge in every language — the brand is not
 * language, and a signature that reassembles itself per locale is two signatures — so
 * `CastivioHeader` pins the row left to right and lets each element's own text run in
 * its own direction. Back therefore keeps the far end whatever the language, which is
 * where a thumb reaches and where an eye returning to the screen lands.
 *
 * @param m the frame's numbers. [SourceMetrics] rather than a bare frame because these
 *   screens already carry one, and it is the frame with delegating names on it.
 * ## The sentence under the name
 *
 * Optional, and it goes through [CastivioHeader] rather than being placed after it.
 * A sibling under the header is centred on its parent; the title is centred on the
 * row and clamped off it when a translation is long. Those are the same point until
 * they are not, and "the sentence is under the title" has to be true in both cases
 * or it is not a rule, it is a coincidence that held while nobody translated the
 * screen.
 *
 * It is set a step quieter than a card's description — the description ink is
 * for prose attached to the thing it describes, and this is under all four of them.
 * At the same weight it would compete with the four sentences it sits above, which
 * is the opposite of what a subtitle is for.
 *
 * @param headingTag tagged per screen, so a layout test can say *this* screen's title
 *   is above *this* screen's content rather than that some heading exists. With a
 *   [glyph] it tags the mark and the name together: that pair is what the row centres
 *   and clamps, so it is what a test of centring has to measure.
 * @param subtitle the sentence, or nothing. The header grows only when there is one.
 * @param glyph the mark of the chooser card that opened this screen, drawn beside the
 *   name in [hue] -- or nothing, which is the chooser itself. See [GlyphTitle].
 * @param badge a word on the mark's corner, for a mark that is a file of one kind.
 */
@Composable
internal fun ChooserHeader(
    m: SourceMetrics,
    title: String,
    headingTag: String,
    backTag: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleTag: String? = null,
    glyph: ImageVector? = null,
    hue: Color? = null,
    badge: String? = null,
) {
    CastivioHeader(
        height = m.header,
        gap = m.headGap,
        modifier = modifier.fillMaxWidth(),
        subtitleHeight = m.subBand,
        subtitle = subtitle?.let { line ->
            {
                Text(
                    text = line,
                    style = castivioBodyStyle(m.fsDetail),
                    color = CastivioTheme.colors.onBackgroundMuted,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = subtitleTag?.let { Modifier.testTag(it) } ?: Modifier,
                )
            }
        },
        lockup = {
            CastivioLockup(
                markSize = m.brand,
                wordSize = (m.fsTitle.value * WORD_RATIO).sp,
                modifier = Modifier.testTag(ActivationTags.HEADER_MARK),
            )
        },
        title = {
            if (glyph == null || hue == null) {
                CastivioHeaderTitle(
                    text = title,
                    style = castivioTitleStyle(m.fsTitle),
                    color = CastivioTheme.colors.onBackgroundStrong,
                    modifier = Modifier.testTag(headingTag),
                )
            } else {
                GlyphTitle(
                    m = m,
                    glyph = glyph,
                    hue = hue,
                    badge = badge,
                    modifier = Modifier.testTag(headingTag),
                ) {
                    CastivioHeaderTitle(
                        text = title,
                        style = castivioTitleStyle(m.fsTitle),
                        color = CastivioTheme.colors.onBackgroundStrong,
                    )
                }
            }
        },
        chips = {
            // The row is pinned left to right so the control keeps its edge; the words
            // inside each chip are handed the reader's own direction back, because what
            // a chip *says* is language and where it sits is not.
            //
            // Two chips now, in the order the design settled on: the theme first and
            // Back second, so Back keeps the outer end it holds on every screen in the
            // product. A control that moves aside to make room for a new one is a
            // control the reader has to look for.
            //
            // The clock leads them, which is the order Home already draws: the time,
            // then the controls, then the way out at the far end. It is the same
            // component Home draws, not a second one — a clock written twice is two
            // tick rates and two answers to what the bidirectional algorithm does to
            // `16/09/2026`.
            //
            // It costs the title width rather than the chips': `CastivioHeader`
            // measures both ends at what they ask for and gives the title what is
            // between, and the title is fitted text that yields. So the longest
            // translation on the shortest frame loses a step of type, and no control
            // loses any of its hit area.
            val reading = LocalLayoutDirection.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(m.chipsGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CastivioClock(
                        fsTime = m.fsTitle,
                        fsDate = m.fsBadge,
                        modifier = Modifier.testTag(ActivationTags.HEADER_CLOCK),
                    )
                    CastivioThemeSwitchChip(
                        chip = m.back,
                        touchTarget = m.frame.touchTarget,
                        fontSize = m.fsBack,
                        toLighter = stringResource(R.string.action_theme_lighter),
                        toDarker = stringResource(R.string.action_theme_darker),
                        modifier = Modifier.testTag(ActivationTags.HEADER_THEME),
                    )
                    CompositionLocalProvider(LocalLayoutDirection provides reading) {
                        CastivioBackChip(
                            chip = m.back,
                            touchTarget = m.frame.touchTarget,
                            pad = m.backPad,
                            fontSize = m.fsBack,
                            label = stringResource(R.string.action_back),
                            onClick = onBack,
                            modifier = Modifier.testTag(backTag),
                        )
                    }
                }
            }
        },
    )
}

/**
 * A screen's name with the mark of the card that opened it.
 *
 * ## Why the mark comes with the user
 *
 * The chooser tells its four ways in apart by a glyph in a hue, and the screen a card
 * opens used to keep only the card's words. Carried across, the mark is the thread
 * between the choice and the place it led: violet for Xtream, azure for a playlist,
 * amber for a portal, green for the subscriptions already saved -- the four hues the
 * chooser already spends, in the same order.
 *
 * It is bare and lit rather than in a disc. A disc is a card's mark; a header has the
 * lockup at one end and the chips at the other, and a third round object between them
 * would read as a fourth control.
 *
 * ## Which side it is on
 *
 * Left of the name, in both languages. The header row is pinned left to right -- the
 * lockup holds the same physical edge everywhere -- and the mark is part of that row's
 * picture, not of the sentence, so the pair is laid out left to right and the name is
 * handed the reader's direction back for its own words. `HEADER_GLYPH` is tagged so a
 * test can hold it there.
 *
 * ## Why the pair is what centres
 *
 * The row centres its title slot and clamps it against the ends. The pair is the slot,
 * so it is the pair that is centred and the pair that yields: when a long name meets a
 * short frame, the name's fitted text gives up a step and the mark keeps its size.
 */
@Composable
private fun GlyphTitle(
    m: SourceMetrics,
    glyph: ImageVector,
    hue: Color,
    badge: String?,
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
) {
    val colors = CastivioTheme.colors
    val size = m.fsTitle * TITLE_GLYPH
    val reading = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(m.fsTitle * TITLE_GLYPH_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(size).testTag(ActivationTags.HEADER_GLYPH)) {
                Icon(
                    imageVector = glyph,
                    contentDescription = null, // the name beside it says it
                    tint = colors.discGlyph(hue),
                    modifier = Modifier
                        .size(size)
                        .castivioGlyphGlow(hue, colors.discTop * TITLE_GLOW, TITLE_GLOW_REACH),
                )
                if (badge != null) {
                    // The saved row's kind pill, at the mark's own scale. Silent: the
                    // name beside it already says the word, and a reader that said it
                    // twice would be reading the drawing rather than the screen.
                    Text(
                        text = badge,
                        style = castivioChipStyle(size * BADGE_TYPE),
                        color = colors.onBackground,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = size * BADGE_OUT, y = size * BADGE_DROP)
                            .background(hue.copy(alpha = BADGE_STRONG), RoundedCornerShape(size * BADGE_CORNER))
                            .padding(horizontal = size * BADGE_PAD)
                            .clearAndSetSemantics { },
                    )
                }
            }
            CompositionLocalProvider(LocalLayoutDirection provides reading) { title() }
        }
    }
}

/** The wordmark against the question — the activation screen's figure, unchanged. */
private const val WORD_RATIO = 0.80f

/* The mark beside the name, as multiples of the name's own type step, so it is the same
 * picture on a phone and on a television: the size, the air before the name, and how
 * far past a disc's own light the halo is pushed -- further than a field's glyph,
 * because this one is read from across the room. */
private const val TITLE_GLYPH = 1.35f
private const val TITLE_GLYPH_GAP = 0.5f
private const val TITLE_GLOW = 1.3f
private const val TITLE_GLOW_REACH = 1.1f

/* The badge on the mark's corner, as fractions of the mark: its type, how far it
 * stands out past the mark's edge and below it, its corner and its side padding. */
private const val BADGE_TYPE = 0.25f
private const val BADGE_OUT = 0.14f
private const val BADGE_DROP = 0.04f
private const val BADGE_CORNER = 0.1f
private const val BADGE_PAD = 0.05f
