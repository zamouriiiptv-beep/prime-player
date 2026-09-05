package com.castivio.feature.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.castivio.core.design.components.CastivioFittedText
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioDescriptionColor
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.castivioStage

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
 * ## It is now the same screen as [SourceChoiceScreen], with different cards in it
 *
 * It always claimed to be — "the same glass, the same type scale, the same spacing" —
 * and it was not, because it said so in prose rather than by reading the same numbers.
 * It drew its own heading from a title and a corner wordmark, its own footer with Back
 * centred between two rules, and its spacing from `isTv ? lg : sm`, which is two
 * frames where the design has four. A television and a handset were the only screens
 * it had ever been asked about.
 *
 * So the three things a screen does not own now come from where they are defined:
 *
 * - the **stage** and the **type steps** from [com.castivio.core.design.theme.CastivioFrame],
 *   chosen by the measured height of this surface;
 * - the **header** from `CastivioHeader` — the lockup at the same physical edge in
 *   every language, the question beside it, Back at the far end;
 * - the **card geometry** from [SourceMetrics], because these four cards and the
 *   source choice's four are drawn to one figure on each of the four frames, and two
 *   tables that agree by hand agree until one of them is edited.
 *
 * What went with the local heading is the local footer. Back is one control and it now
 * sits where the reader last saw it — in the header, at the row's outer end — rather
 * than in a band at the bottom that existed only to hold it.
 *
 * ## What is still this screen's own
 *
 * The card: an icon leading a pair of lines, rather than the source choice's disc,
 * description and chevron. The icons are [Sizing.iconXl] because 20dp was unreadable
 * on a television, and they are drawn rather than Material — see
 * [com.castivio.core.design.icons.CastivioIcons] for why the family could not simply
 * be scaled.
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
    val tv = CastivioTheme.device.isTv
    BoxWithConstraints(modifier.fillMaxSize()) {
        val m = sourceMetricsFor(tv = tv, available = maxHeight)

        Column(
            Modifier
                .fillMaxSize()
                .castivioStage(m.frame)
                .testTag(ActivationTags.MEDIA_CONTAINER),
        ) {
            ChooserHeader(
                m = m,
                title = stringResource(R.string.media_source_title),
                headingTag = ActivationTags.MEDIA_HEADING,
                backTag = ActivationTags.MEDIA_BACK,
                onBack = onBack,
            )
            Spacer(Modifier.height(m.bandTop))

            // Weighted, so the grid is what the header leaves rather than what it asks
            // for. A weighted child cannot push its siblings off the screen, which
            // makes overflow structural instead of arithmetic.
            MediaGrid(
                m = m,
                modifier = Modifier.weight(1f),
                onVideoLibrary = onVideoLibrary,
                onPickVideo = onPickVideo,
                onAudioLibrary = onAudioLibrary,
                onPickAudio = onPickAudio,
            )
        }
    }
}

/**
 * Two by two, equal in both directions.
 *
 * Bounded by the stage, so the two rows divide a known height and come out equal
 * without an intrinsic pass. Reading order is source order, which is also focus order:
 * a D-pad moves along a row and then down between them, and the direction resolves
 * which physical way "along" is.
 */
@Composable
private fun MediaGrid(
    m: SourceMetrics,
    modifier: Modifier,
    onVideoLibrary: () -> Unit,
    onPickVideo: () -> Unit,
    onAudioLibrary: () -> Unit,
    onPickAudio: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.gridGap)) {
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(m.gridGap),
        ) {
            MediaCard(
                m = m,
                icon = CastivioIcons.VideoLibrary,
                title = stringResource(R.string.media_video_library_title),
                detail = stringResource(R.string.media_video_library_detail),
                onClick = onVideoLibrary,
                tag = ActivationTags.MEDIA_VIDEO_LIBRARY,
            )
            MediaCard(
                m = m,
                icon = CastivioIcons.VideoFile,
                title = stringResource(R.string.media_video_pick_title),
                detail = stringResource(R.string.media_video_pick_detail),
                onClick = onPickVideo,
                tag = ActivationTags.MEDIA_VIDEO_PICK,
            )
        }
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(m.gridGap),
        ) {
            MediaCard(
                m = m,
                icon = CastivioIcons.AudioLibrary,
                title = stringResource(R.string.media_audio_library_title),
                detail = stringResource(R.string.media_audio_library_detail),
                onClick = onAudioLibrary,
                tag = ActivationTags.MEDIA_AUDIO_LIBRARY,
            )
            MediaCard(
                m = m,
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
 * size: a 32dp glyph on the title's line box would have set that line's height.
 * Centred on the text block instead, it costs the card nothing.
 *
 * The description wraps to [SourceMetrics.detailLines] and no further — four lines on
 * a television, three elsewhere — and it wraps rather than being cut, because a
 * sentence that ends in an ellipsis in one language and not in another is a layout
 * that stopped being checked in the other thirty-six.
 *
 * One node, one label, one target: a screen reader announces the title and the
 * description as a single item, and the icon carries no description of its own because
 * the card above it already says the whole thing.
 */
@Composable
private fun RowScope.MediaCard(
    m: SourceMetrics,
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
        shape = RoundedCornerShape(m.radius),
        fill = SolidColor(colors.glassFillStrong),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(m.cardPad),
            horizontalArrangement = Arrangement.spacedBy(m.cardGap),
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
                verticalArrangement = Arrangement.spacedBy(m.cardPad * TEXT_GAP),
            ) {
                // Fitted rather than clipped, for the reason the source choice's card
                // gives: a name that ends in an ellipsis is a name the reader cannot
                // match to the thing it names.
                CastivioFittedText(
                    text = title,
                    style = castivioChipStyle(m.fsCard),
                    color = colors.onBackground,
                )
                Text(
                    text = detail,
                    style = castivioBodyStyle(m.fsDetail),
                    color = castivioDescriptionColor,
                    maxLines = m.detailLines,
                )
            }
        }
    }
}

/** A card's name against its description, as a fraction of the card's own padding. */
private const val TEXT_GAP = 0.5f

