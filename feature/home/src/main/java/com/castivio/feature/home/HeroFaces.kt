package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.ProviderArtwork
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.components.ltrToken
import com.castivio.core.design.theme.CastivioMetrics
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Sizing

/*
 * The two other faces of the live channels' card -- see [HeroFace] -- as drawn and approved in `design/mockups/home-resume-ar.png`
 * (the channel watched last) and `design/mockups/home-resume-idle-ar.png` (a subscription whose channels are not downloaded).
 *
 * **Only the card's content differs.** Its glass, its edge, its radius, its focus and its press are the card's own, in
 * `HomeScreen.kt`; every colour here is a token the board already uses -- the violet that selects a channel on the board,
 * the aqua the board draws a programme's bar in, the muted and the strong inks, the azure of an unfetched section's sentence.
 * Nothing on this card is a new colour.
 */

/**
 * A subscription whose live channels have never been downloaded: the glyph, what to do, and the button that does it.
 *
 * The card's own press is the button's: pressed, the section is asked for and loads on this card, which becomes its loading
 * face. The button is the card's drawing of that press and not a second control, so a remote has one stop on the card.
 */
@Composable
internal fun NotLoadedFace(
    name: String,
    sentence: String,
    sentenceInk: Color,
    icon: ImageVector,
    hue: Color,
    frame: CastivioMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    BoxWithConstraints(modifier.fillMaxSize().padding(frame.chipPad)) {
        val glyph = maxHeight * NOT_LOADED_GLYPH_SHARE
        Column(Modifier.fillMaxSize()) {
            HeroHeading(
                start = name,
                end = remember(sentence, sentenceInk) { buildAnnotatedString { withStyle(SpanStyle(color = sentenceInk)) { append(sentence) } } },
                frame = frame,
            )
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                GradientGlyph(icon = icon, hue = hue, size = glyph)
                Spacer(Modifier.height(frame.chipPad))
                Text(
                    text = stringResource(R.string.home_load_hero_title),
                    style = castivioTitleStyle(frame.fsLabel),
                    color = colors.onBackgroundStrong,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.home_load_hero_detail),
                    style = castivioBodyStyle(frame.fsChip),
                    color = colors.onBackgroundMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HeroButton(
                label = stringResource(R.string.home_load_hero_button),
                icon = Icons.Rounded.Download,
                frame = frame,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The channel watched last: its logo and name, what the guide says is on it with how far in it is, the few channels before it,
 * and the button that carries on.
 *
 * What is drawn is [Resume]'s, ready: the hours are text and the bar a fraction, so nothing is worked out here. A channel the
 * guide holds nothing for shows its logo and name and no programme -- a line the device does not hold is not drawn.
 */
@Composable
internal fun ContinueFace(
    resume: Resume,
    name: String,
    sentence: String,
    sentenceInk: Color,
    frame: CastivioMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    BoxWithConstraints(modifier.fillMaxSize().padding(frame.chipPad)) {
        val logo = maxHeight * CONTINUE_LOGO_SHARE
        val plateHeight = maxHeight * CONTINUE_PLATE_SHARE
        Column(Modifier.fillMaxSize()) {
            val quiet = colors.onBackgroundMuted
            HeroHeading(
                start = stringResource(R.string.home_resume_label),
                end = remember(name, sentence, sentenceInk, quiet) {
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = quiet)) {
                            append(name)
                            append(" · ")
                        }
                        withStyle(SpanStyle(color = sentenceInk)) { append(sentence) }
                    }
                },
                frame = frame,
            )
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ProviderArtwork(
                    url = resume.channel.artworkUrl,
                    // The name is drawn under it: a screen reader that announced the logo as well would say the channel twice.
                    description = null,
                    modifier = Modifier.size(logo),
                    seed = resume.channel.title,
                )
                Spacer(Modifier.height(frame.chipPad / 2))
                Text(
                    text = resume.channel.title,
                    style = castivioTitleStyle(frame.fsLabel).copy(textDirection = TextDirection.Content),
                    color = colors.onBackgroundStrong,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val programme = resume.programme
                val startText = resume.from
                val endText = resume.to
                if (programme != null && startText != null && endText != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(frame.chipPad / 2, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = programme.title,
                            style = castivioBodyStyle(frame.fsBody).copy(textDirection = TextDirection.Content),
                            color = colors.onBackgroundStrong,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = "·",
                            style = castivioBodyStyle(frame.fsBody),
                            color = colors.onBackgroundMuted,
                            maxLines = 1,
                        )
                        Text(
                            text = stringResource(R.string.channels_time_range, ltrToken(startText), ltrToken(endText)),
                            style = castivioBodyStyle(frame.fsBody),
                            color = colors.onBackgroundMuted,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(frame.chipPad / 2))
                    ProgrammeBar(fraction = resume.progress)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(frame.chipPad / 2), modifier = Modifier.weight(1f)) {
                    for (earlier in resume.earlier) {
                        ProviderArtwork(
                            url = earlier.artworkUrl,
                            description = null,
                            modifier = Modifier.size(width = plateHeight * PLATE_ASPECT, height = plateHeight),
                            seed = earlier.title,
                        )
                    }
                }
                HeroButton(
                    label = stringResource(R.string.home_resume_go),
                    icon = Icons.Rounded.PlayArrow,
                    frame = frame,
                )
            }
        }
    }
}

/**
 * The line over a card's face: one word at its start and, at its end, what the section says -- on one line each, the end kept
 * whole and the start cut where the language is long.
 */
@Composable
private fun HeroHeading(
    start: String,
    end: AnnotatedString,
    frame: CastivioMetrics,
) {
    val colors = CastivioTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(frame.chipPad)) {
        Text(
            text = start,
            style = castivioBodyStyle(frame.fsBody),
            color = colors.onBackgroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = end,
            style = castivioBodyStyle(frame.fsBody),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * The card's drawing of its press: the violet that selects a channel on the board, and the ink that is legible on it.
 *
 * Not focusable and not clickable -- the card is the control, and it already carries the press and the focus ring.
 */
@Composable
private fun HeroButton(
    label: String,
    icon: ImageVector,
    frame: CastivioMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Row(
        modifier
            .height(frame.chip)
            .clip(RoundedCornerShape(frame.radius / 2))
            .background(colors.secondary)
            .padding(horizontal = frame.chipPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(frame.chipPad / 2, Alignment.CenterHorizontally),
    ) {
        Text(
            text = label,
            style = castivioTitleStyle(frame.fsBody),
            color = colors.onSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(icon, contentDescription = null, tint = colors.onSecondary, modifier = Modifier.size(Sizing.iconSm))
    }
}

/**
 * How far into what is on the channel is, in the aqua the channels' board draws the same bar in -- from its own start edge to
 * its right, as a clock reads, in every language: a timeline is not mirrored.
 */
@Composable
private fun ProgrammeBar(fraction: Float, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val pill = RoundedCornerShape(percent = 50)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier.fillMaxWidth().height(PROGRAMME_BAR).clip(pill).background(colors.divider),
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(pill).background(colors.live),
            )
        }
    }
}

/**
 * The section's glyph, drawn in the azure-to-violet ramp the live card's watermark and the wordmark share.
 *
 * `Icon` paints a vector in one colour, so the ramp is applied after the fact on its own layer -- see `HeroCard`, which does the
 * same at the size of the whole panel.
 */
@Composable
private fun GradientGlyph(icon: ImageVector, hue: Color, size: Dp) {
    val colors = CastivioTheme.colors
    val ramp = Brush.linearGradient(listOf(colors.discGlyph(hue), colors.hueViolet))
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = colors.onBackgroundStrong,
        modifier = Modifier
            .size(size)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(ramp, blendMode = BlendMode.SrcIn)
            },
    )
}

/** The glyph of the invitation: a share of the card's height, so it keeps its proportion on every surface. */
private const val NOT_LOADED_GLYPH_SHARE = 0.27f

/** The resumed channel's logo and the earlier channels' plates, as shares of the card's height. */
private const val CONTINUE_LOGO_SHARE = 0.285f
private const val CONTINUE_PLATE_SHARE = 0.09f

/** A plate is this much wider than it is tall, as the board's logo slot is. */
private const val PLATE_ASPECT = 2.3f

private val PROGRAMME_BAR = 4.dp
