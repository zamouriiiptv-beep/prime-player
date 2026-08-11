package com.castivio.feature.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing

/**
 * "What did your provider give you?"
 *
 * The question is asked in the user's terms rather than ours. Nobody arrives here
 * knowing they want "Xtream Codes"; they arrive holding an e-mail, and the two options
 * are described by what that e-mail looks like — a server and a password, or one long
 * link. The protocol names stay as titles because that is what the e-mail calls them.
 *
 * Two cards, no third. Local files are deliberately absent: they were cut from this
 * screen because a route almost nobody takes still costs everybody a decision.
 *
 * ## Two layouts, because a television is not a tall screen
 *
 * Stacked, the two cards plus the header plus Back came to more than the 444dp a
 * television leaves inside its overscan, and the step was inside
 * `verticalScroll`. So it scrolled: the title clipped at the top or Back clipped
 * at the bottom, and which one depended on where the user had left the scroll.
 * A remote pressing *down* moved the page rather than the focus.
 *
 * On a television the cards go side by side instead. They are a choice between
 * two, and stacking them spent the vertical axis — the scarce one on a 16:9
 * frame — while the horizontal axis sat empty. `Row` also gives the remote the
 * gesture the hand expects: left and right between the options, down to Back.
 *
 * Everywhere else the composition is unchanged. A phone in portrait has vertical
 * room and no horizontal room, which is the opposite problem, and two cards
 * abreast at 170dp would be the fix becoming the bug.
 *
 * ## Centred as a group, rather than pinned to three edges
 *
 * Pinning the header to the top, Back to the bottom and stretching the cards
 * between them was the first fix, and it did end the scroll. It also bought the
 * fit with two holes — one under the header, one over Back — and cards stretched
 * to 244dp to fill what was left. Two lines of type in a 244dp pane read as a
 * pane that lost something.
 *
 * So the cards are the size of what is in them, the gaps are one `Spacing.xxl`
 * each, and `Alignment.CenterVertically` puts the whole group in the middle. The
 * leftover is the same leftover either way; it is now an even margin above and
 * below instead of two gaps inside the composition. Emptiness at the edge of a
 * page is margin. The same emptiness between two elements is a hole.
 *
 * ## No height is written down, and no intrinsic measurement either
 *
 * Each card is the height of what is in it, the row is the height of the taller
 * one, and the arrangement centres the group. There is no `Spacer`, no frame
 * table and no number for a longer translation to invalidate — it makes the
 * cards taller and the margins narrower, in that order, rather than pushing Back
 * off the screen. 116dp of headroom over the content as it stands, which
 * `activation-source.html?stress=1` spends on purpose to check.
 *
 * This was `height(IntrinsicSize.Min)` with `fillMaxHeight` on both cards, which
 * is the Compose idiom for making siblings as tall as the tallest. It was
 * measured out rather than argued out: rendered both ways, in Arabic and in
 * English, the two frames are byte-identical — both cards come to 146dp either
 * way, because both hold a title and one line. The idiom was doing nothing that
 * the content was not already doing, and an intrinsic pass is a second
 * measurement of the whole subtree that fails at runtime rather than at compile
 * time when something in it does not support one. Nothing pays for that here.
 *
 * **What it costs.** Where a translation wraps the detail to two lines, that card
 * is 166dp and the other stays 146 — measured, not guessed. Their titles still
 * line up, because a `Row` aligns to the top; their lower edges do not. If that
 * shows up as a defect in a real language, the fix is to put the intrinsic back,
 * not to invent something else.
 *
 * @param onBack what Back does. It lives inside this screen rather than beside it
 *   because it is part of the composition being fitted: the column that measures
 *   the header and the cards has to measure Back too, or it is fitting two
 *   thirds of a screen.
 */
@Composable
internal fun SourceChoiceScreen(
    onXtream: () -> Unit,
    onPlaylist: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!CastivioTheme.device.isTv) {
        // Unchanged: the same column, at the same `Spacing.xl`, that the step's
        // own wrapper used to supply around this screen and Back.
        Column(
            modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            Heading()
            SourceCard(
                title = stringResource(R.string.source_xtream_title),
                detail = stringResource(R.string.source_xtream_detail),
                onClick = onXtream,
                modifier = Modifier.fillMaxWidth().testTag(ActivationTags.SOURCE_XTREAM),
            )
            SourceCard(
                title = stringResource(R.string.source_m3u_title),
                detail = stringResource(R.string.source_m3u_detail),
                onClick = onPlaylist,
                modifier = Modifier.fillMaxWidth().testTag(ActivationTags.SOURCE_M3U),
            )
            BackButton(onBack, Modifier.testTag(ActivationTags.SOURCE_BACK))
        }
        return
    }

    Column(
        modifier
            .fillMaxSize()
            // The screen owns the viewport now, so it owns its overscan too --
            // `ActivationSurface` applies no padding in its fixed frame, for the
            // same reason the address screen pads itself.
            .padding(Spacing.tvOverscan),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxl, Alignment.CenterVertically),
    ) {
        Heading()

        // A plain row: it is the height of the taller card and no taller, and the
        // leftover becomes the margin the arrangement puts above and below the
        // group. Children align to the top, so if a translation ever does make
        // one card taller, the two titles still sit on the same line.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            // Start to end, so Xtream is on the right in Arabic and on the left
            // in English without either being written down anywhere. A `Row`
            // resolves its own direction; an index or an offset would not.
            SourceCard(
                title = stringResource(R.string.source_xtream_title),
                detail = stringResource(R.string.source_xtream_detail),
                onClick = onXtream,
                // Equal halves. The heights come out equal too, because both
                // cards hold a title and one line -- see the note on the screen.
                modifier = Modifier
                    .weight(1f)
                    .testTag(ActivationTags.SOURCE_XTREAM),
                padding = TV_CARD_PADDING,
            )
            SourceCard(
                title = stringResource(R.string.source_m3u_title),
                detail = stringResource(R.string.source_m3u_detail),
                onClick = onPlaylist,
                modifier = Modifier
                    .weight(1f)
                    .testTag(ActivationTags.SOURCE_M3U),
                padding = TV_CARD_PADDING,
            )
        }

        BackButton(onBack, Modifier.testTag(ActivationTags.SOURCE_BACK))
    }
}

@Composable
private fun Heading() {
    val colors = CastivioTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = stringResource(R.string.source_choice_title),
            style = CastivioType.headlineMedium,
            color = colors.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.source_choice_subtitle),
            style = CastivioType.bodyLarge,
            color = colors.onBackgroundVariant,
        )
    }
}

/**
 * Deeper than it is wide-padded, on a television.
 *
 * The pane is 420dp across and holds 50dp of type. Padding it equally would
 * leave a letterbox; `Spacing.xxxl` down and `Spacing.xxl` across is what makes
 * it read as a tile.
 *
 * The default stays `Spacing.xl`, which is what the phone has always used. The
 * reason for widening it was that the card had become half the width of the
 * screen -- and on a phone it has not: it is still full width and still
 * stacked. Carrying the television's number over would have been changing a
 * frame nobody asked about, on the strength of an argument that does not apply
 * to it.
 */
private val TV_CARD_PADDING = PaddingValues(horizontal = Spacing.xxl, vertical = Spacing.xxxl)

@Composable
private fun SourceCard(
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(Spacing.xl),
) {
    val colors = CastivioTheme.colors

    InteractiveGlassCard(
        onClick = onClick,
        modifier = modifier,
        // Held at `glassFillStrong` rather than faded to `glassFill` down the
        // card. The default brush is right for a column of tiles; here the whole
        // screen is two large panes, and at this size the fade puts their lower
        // halves at the fill an inactive surface uses -- so a pane the user is
        // being asked to choose looks half switched off. Same token, no fade.
        fill = SolidColor(colors.glassFillStrong),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // `Spacing.xl` on a phone, [TV_CARD_PADDING] on a television:
                // the two frames give the card opposite shapes, and one number
                // cannot be right for both.
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            // `titleLarge` over `bodySmall`, where it was `titleMedium` over
            // `bodyMedium`. One point of size and no difference in weight is not
            // a hierarchy -- only the colour was saying which of the two lines
            // was the name of the thing. The source name is what is being chosen
            // between, so it leads and the sentence under it explains.
            Text(text = title, style = CastivioType.titleLarge, color = colors.onBackground)
            Text(text = detail, style = CastivioType.bodySmall, color = colors.onBackgroundVariant)
        }
    }
}
