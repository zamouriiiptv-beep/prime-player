package com.castivio.feature.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.castivio.core.design.components.CastivioMark
import com.castivio.core.design.components.GlassCard
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing

/**
 * "What would you like to play?" — the device's own media, four ways in.
 *
 * ## What the four are, and why they are four
 *
 * Two questions, not four: *browse everything* or *pick one file*, each for video and
 * for audio. The libraries lead in reading order and the pickers follow, which is the
 * only ranking the grid makes.
 *
 * None of the four does anything yet, and that is deliberate rather than unfinished.
 * Reading the device's media is a `MediaStore` query and picking a file is the system
 * document picker; both belong to the slice after this one, and neither is invented
 * here. What the screen ships with is the seam — each card reports its press to a
 * caller that will be `:app` when there is something to hand it, exactly as the source
 * choice's local-video card has done since it was written. A card that is present,
 * focusable, labelled and inert is honest; a card wired to a query written to make the
 * screen look finished is not.
 *
 * ## Its relationship to [SourceChoiceScreen]
 *
 * Every surface here is that screen's: the same glass container at [Radius.xl] over
 * cards at [Radius.lg], the same `glassFillStrong`, the same focus ring, the same type
 * scale, the same spacing at the same three frames. It is deliberately not a shared
 * composable — the two screens differ in their footer, their icon size and their
 * header, and a component taking three booleans to be both of them would be worse than
 * two screens that read the same tokens.
 *
 * Two things do differ, and both were decided at review:
 *
 * **The icons are [Sizing.iconXl].** 20dp was unreadable on a television, so these are
 * drawn rather than Material — see [CastivioIcons] for why the family could not simply
 * be scaled.
 *
 * **The footer is a rule, Back, a rule**, with Back on the container's true centre
 * rather than at the start. The source choice keeps its own.
 */
@Composable
internal fun MediaSourceScreen(
    onVideoLibrary: () -> Unit,
    onPickVideo: () -> Unit,
    onAudioLibrary: () -> Unit,
    onPickAudio: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(CastivioTheme.device.screenPadding),
        verticalArrangement = Arrangement.Top,
    ) {
        MediaHeading()
        Spacer(Modifier.height(TitleGap))

        // `weight(1f)` rather than a height: the container is whatever the title and
        // the screen padding leave, so the cards divide a known box and overflow is
        // structural rather than something a budget has to keep watching. There is no
        // terms sentence on this screen, so the container is the taller for it.
        MediaContainer(Modifier.weight(1f)) {
            MediaGrid(
                modifier = Modifier.weight(1f),
                onVideoLibrary = onVideoLibrary,
                onPickVideo = onPickVideo,
                onAudioLibrary = onAudioLibrary,
                onPickAudio = onPickAudio,
            )
            Spacer(Modifier.height(ContainerGap))
            ChooserFooter(onBack, ActivationTags.MEDIA_BACK)
        }
    }
}

/**
 * The title, with the mark in the opposite corner.
 *
 * The question takes the start of the row and the mark takes the end, so the two sit
 * in opposite corners in either direction — title right and mark left in Arabic, title
 * left and mark right in English. One rule, and it needs nothing direction-aware: the
 * last child of a row *is* the end, and the layout direction decides which physical
 * corner that is.
 *
 * A row rather than two stacked lines, and that is what keeps the mark free. The
 * container is `weight(1f)` and takes whatever the header leaves, so a line of its own
 * would have come out of the cards; sharing the title's 32sp line box costs nothing.
 */
@Composable
private fun MediaHeading() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MarkGap),
    ) {
        Text(
            text = stringResource(R.string.media_source_title),
            style = CastivioType.headlineMedium,
            color = CastivioTheme.colors.onBackground,
            modifier = Modifier
                .weight(1f)
                .alignByBaseline()
                .testTag(ActivationTags.MEDIA_HEADING)
                .semantics { heading() },
        )
        Wordmark(Modifier.alignByBaseline())
    }
}

/**
 * Castivio, in the startup's own ink.
 *
 * The word, the violet-into-azure fill and the tracking all come from [CastivioMark],
 * which is where `Intro.kt` keeps them; only the size is local, because the size is the
 * only part that is. `lineHeight` equal to the font size so the mark contributes no
 * leading of its own — a baseline-aligned row is as tall as its deepest descender, and
 * a default line box around a 16sp glyph hangs further below the baseline than the
 * title's does. The mockup grew its header by 3dp exactly that way, and the container
 * paid for it.
 *
 * No `contentDescription`: the application's name is in the launcher and the window
 * title already, and a wordmark read aloud before every screen is noise.
 */
@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    val size = MarkSize
    Text(
        text = CastivioMark.TEXT,
        style = CastivioType.labelSmall.copy(
            fontSize = size,
            lineHeight = size,
            fontWeight = FontWeight.Bold,
            letterSpacing = CastivioMark.TRACKING_RATIO.em,
            // Left to right in pixels whatever the layout direction, which is what the
            // startup draws: the violet end is on the left on every screen Castivio
            // signs, in both languages.
            brush = Brush.horizontalGradient(CastivioMark.colours),
        ),
        maxLines = 1,
        modifier = modifier,
    )
}

/** The glass the four choices and the way back sit inside. The source choice's. */
@Composable
private fun MediaContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ActivationTags.MEDIA_CONTAINER),
        shape = RoundedCornerShape(Radius.xl),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(ContainerPadding),
            content = content,
        )
    }
}

/**
 * Two by two, equal in both directions.
 *
 * Bounded by the container, so the two rows divide a known height and come out equal
 * without an intrinsic pass. Reading order is source order, which is also focus order:
 * a D-pad moves along a row and then down between them, and the direction resolves
 * which physical way "along" is.
 */
@Composable
private fun MediaGrid(
    modifier: Modifier,
    onVideoLibrary: () -> Unit,
    onPickVideo: () -> Unit,
    onAudioLibrary: () -> Unit,
    onPickAudio: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GridGap)) {
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
        ) {
            MediaCard(
                icon = CastivioIcons.VideoLibrary,
                title = stringResource(R.string.media_video_library_title),
                detail = stringResource(R.string.media_video_library_detail),
                onClick = onVideoLibrary,
                tag = ActivationTags.MEDIA_VIDEO_LIBRARY,
            )
            MediaCard(
                icon = CastivioIcons.VideoFile,
                title = stringResource(R.string.media_video_pick_title),
                detail = stringResource(R.string.media_video_pick_detail),
                onClick = onPickVideo,
                tag = ActivationTags.MEDIA_VIDEO_PICK,
            )
        }
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
        ) {
            MediaCard(
                icon = CastivioIcons.AudioLibrary,
                title = stringResource(R.string.media_audio_library_title),
                detail = stringResource(R.string.media_audio_library_detail),
                onClick = onAudioLibrary,
                tag = ActivationTags.MEDIA_AUDIO_LIBRARY,
            )
            MediaCard(
                icon = CastivioIcons.AudioFile,
                title = stringResource(R.string.media_mp3_pick_title),
                detail = stringResource(R.string.media_mp3_pick_detail),
                onClick = onPickAudio,
                tag = ActivationTags.MEDIA_MP3_PICK,
            )
        }
    }
}

/**
 * One card, drawn four times.
 *
 * The icon leads the pair of lines rather than sitting on the first of them, which is
 * the one structural difference from the source choice's card and follows from the
 * size: a 32dp glyph on a 24sp line box would have set the title's line height.
 * Centred on the text block instead, it costs the card nothing — the words are 48dp and
 * the glyph is 32.
 *
 * One node, one label, one target: a screen reader announces the title and the
 * description as a single item, and the icon carries no description of its own because
 * the card above it already says the whole thing.
 */
@Composable
private fun RowScope.MediaCard(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
    tag: String,
) {
    val colors = CastivioTheme.colors

    InteractiveGlassCard(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .testTag(tag)
            .semantics(mergeDescendants = true) { contentDescription = "$title. $detail" },
        fill = SolidColor(colors.glassFillStrong),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(CardPadding),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.onBackground,
                modifier = Modifier.size(Sizing.iconXl),
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(text = title, style = CastivioType.titleLarge, color = colors.onBackground)
                Text(
                    text = detail,
                    style = CastivioType.bodySmall,
                    color = colors.onBackgroundVariant,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------------ tokens */

/**
 * The mark's size: 16sp on a handset, 19sp on a television.
 *
 * Larger than the source choice's, which is a difference the two screens carry on
 * purpose after review rather than by accident — the corner mark there was judged too
 * quiet to read. If the two are ever unified this is the figure to unify on.
 */
private val MarkSize: TextUnit
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) 19.sp else 16.sp

/** Mark to title. */
private val MarkGap: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.xl else Spacing.lg

/** Title to container. */
private val TitleGap: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.lg else Spacing.sm

/** The container's own inset, all round. */
private val ContainerPadding: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.lg else Spacing.sm

/** Grid to the footer, inside the container. */
private val ContainerGap: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.lg else Spacing.xs

/** Between the cards, across and down alike — a grid spaced two ways is two rows. */
private val GridGap: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.lg else Spacing.sm

/** Inside a card. The source choice's figures, so the two sets of cards match. */
private val CardPadding: PaddingValues
    @Composable @ReadOnlyComposable get() = if (CastivioTheme.device.isTv) {
        PaddingValues(horizontal = Spacing.xxl, vertical = Spacing.xl)
    } else {
        PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)
    }
