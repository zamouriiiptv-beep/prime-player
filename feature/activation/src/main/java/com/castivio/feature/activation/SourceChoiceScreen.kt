package com.castivio.feature.activation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing

/**
 * "What did your provider give you?"
 *
 * The question is asked in the user's terms rather than ours. Nobody arrives here
 * knowing they want "Xtream Codes"; they arrive holding an e-mail, and the two IPTV
 * options are described by what that e-mail looks like — a server and a password, or
 * one long link. The protocol names stay as titles because that is what the e-mail
 * calls them.
 *
 * Four destinations, in a two-by-two grid: the two ways to add a subscription, a video
 * file the device already holds, and the subscriptions already saved on it.
 *
 * ## One layout, four identical cards, on every device
 *
 * Stacked in a single column the content came to 424dp against the 393dp a landscape
 * handset has, and the step was inside `verticalScroll`. So it scrolled: the title
 * clipped at the top or Back clipped at the bottom, depending on where the user had
 * left the page, and a remote pressing *down* moved the page rather than the focus.
 *
 * There is no longer a condition anywhere in this file. Two earlier versions asked one
 * — `isTv` first, then `DeviceClass.Expanded`, which is `screenWidthDp >= 840` — and
 * both shipped a stacked, scrolling screen to a landscape handset. `screenWidthDp`
 * describes the *window*; this screen is drawn inside `safeDrawing`, so a display
 * cutout of 41dp is spent before the layout sees a pixel and 873 arrives as 827. Which
 * side of a bucket boundary that lands on varies by handset for reasons that have
 * nothing to do with whether four cards fit.
 *
 * They fit at any width, because nothing here asks for a size: the cards divide what
 * they are given with `weight(1f)`, so the grid cannot overflow horizontally. The
 * activity is `screenOrientation="sensorLandscape"`, so the narrow portrait frame the
 * old column existed for never reaches a user at all.
 *
 * ## Why the four are the same height, and how
 *
 * Not by a number. [SourceGrid] wraps its two rows in a `Column` at
 * `IntrinsicSize.Min` and gives each row `weight(1f)`. Compose resolves a column's
 * minimum intrinsic height over weighted children as the largest child-height-per-unit
 * of weight times the total weight — so the pair comes out at twice the height of the
 * taller row, each row is handed exactly that, and `fillMaxHeight` passes it to all
 * four cards. A description that wraps in one language therefore makes all four cards
 * taller together instead of leaving one standing 20dp proud of its neighbours, and
 * nothing is clipped or ellipsised to achieve it.
 *
 * The cost is honest and worth naming: an intrinsic pass measures the subtree twice,
 * and it throws at runtime rather than at compile time if something inside it does not
 * support intrinsics. Everything here is `Column`, `Row`, `Text` and `Icon`, all of
 * which do.
 *
 * ## Direction
 *
 * `Row` and `Column` resolve their own start and end, so Xtream leads on the right in
 * Arabic and on the left in English with no index, offset or coordinate written down
 * anywhere. The icon is the first child of the title line, which puts it on the
 * starting side in both directions for the same reason.
 *
 * @param onBack what Back does. It lives inside this screen rather than beside it
 *   because it is part of the composition being fitted: the column that measures the
 *   header and the grid has to measure the footer too, or it is fitting two thirds of
 *   a screen.
 */
@Composable
internal fun SourceChoiceScreen(
    onXtream: () -> Unit,
    onPlaylist: () -> Unit,
    onLocalVideo: () -> Unit,
    onSavedSources: () -> Unit,
    onTerms: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            // The screen owns the viewport, so it owns its edge inset too --
            // `ActivationSurface` applies no padding in its fixed frame, for the same
            // reason the address screen pads itself. `screenPadding` rather than a
            // hard-coded figure: it is `Spacing.tvOverscan` on a television, which has
            // overscan to clear, and `Spacing.screen` everywhere else, which does not.
            .padding(CastivioTheme.device.screenPadding),
        verticalArrangement = Arrangement.spacedBy(GroupGap, Alignment.CenterVertically),
    ) {
        Heading()
        SourceGrid(
            onXtream = onXtream,
            onPlaylist = onPlaylist,
            onLocalVideo = onLocalVideo,
            onSavedSources = onSavedSources,
        )
        Footer(onBack = onBack, onTerms = onTerms)
    }
}

/**
 * The title, and nothing under it.
 *
 * There was a subtitle — "both end up in the same place" — written when this screen
 * offered two options. Four named cards each carrying a description made it false and
 * then redundant, so it is deleted rather than reworded: a sentence explaining that
 * the options are alternatives tells the reader what the grid already shows.
 *
 * Deleting it also returned 32dp, which is the difference between a screen with 2.5dp
 * of slack and one that can absorb a description wrapping in a longer language.
 */
@Composable
private fun Heading() {
    Text(
        text = stringResource(R.string.source_choice_title),
        style = CastivioType.headlineMedium,
        color = CastivioTheme.colors.onBackground,
        modifier = Modifier
            .testTag(ActivationTags.SOURCE_HEADING)
            .semantics { heading() },
    )
}

/**
 * Two by two, equal in both directions.
 *
 * The reading order is the grid order — Xtream, M3U, then the device's own video and
 * the subscriptions already saved on it. Nothing is emphasised by being larger or
 * filled differently; an earlier draft gave the lower pair a lighter fill and no
 * description and it read as two designs rather than one set. Xtream leads because it
 * is first and holds the focus on entry, which is the only ranking this screen makes.
 */
@Composable
private fun SourceGrid(
    onXtream: () -> Unit,
    onPlaylist: () -> Unit,
    onLocalVideo: () -> Unit,
    onSavedSources: () -> Unit,
) {
    Column(
        // The whole point. See the note on the screen: this is what makes the two rows
        // -- and therefore all four cards -- one height rather than two.
        Modifier.height(IntrinsicSize.Min),
        verticalArrangement = Arrangement.spacedBy(GridGap),
    ) {
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
        ) {
            SourceCard(
                icon = Icons.Rounded.Dns,
                title = stringResource(R.string.source_xtream_title),
                detail = stringResource(R.string.source_xtream_detail),
                onClick = onXtream,
                tag = ActivationTags.SOURCE_XTREAM,
            )
            SourceCard(
                icon = Icons.Rounded.Link,
                title = stringResource(R.string.source_m3u_title),
                detail = stringResource(R.string.source_m3u_detail),
                onClick = onPlaylist,
                tag = ActivationTags.SOURCE_M3U,
            )
        }
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GridGap),
        ) {
            SourceCard(
                icon = Icons.Rounded.PlayCircle,
                title = stringResource(R.string.source_local_title),
                detail = stringResource(R.string.source_local_detail),
                onClick = onLocalVideo,
                tag = ActivationTags.SOURCE_LOCAL,
            )
            SourceCard(
                icon = Icons.Rounded.SwitchAccount,
                title = stringResource(R.string.source_users_title),
                detail = stringResource(R.string.source_users_detail),
                onClick = onSavedSources,
                tag = ActivationTags.SOURCE_USERS,
            )
        }
    }
}

/**
 * Back at the start, Terms at the end, one row.
 *
 * Terms on a line of its own was the obvious reading and it does not fit: a
 * `bodySmall` line plus the gap separating it is 36dp, and the band has 10.5 spare.
 * A third row would overflow, and this frame does not scroll — it would clip.
 *
 * On Back's row it costs nothing at all: the row is already the height of the button
 * and the full width of the band, and the link is 20dp inside it. `SpaceBetween` puts
 * them on opposite ends, which the layout direction resolves — Back on the right and
 * Terms on the left in Arabic, mirrored in English, with no side named here.
 */
@Composable
private fun Footer(onBack: () -> Unit, onTerms: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onBack, Modifier.testTag(ActivationTags.SOURCE_BACK))
        TermsLink(onTerms)
    }
}

/**
 * The Terms link, built here rather than reached for.
 *
 * `ButtonWeight` has three values — `Primary`, `Secondary`, `Ghost` — and none of them
 * is a text link. Adding a fourth is a change to `:core:design`, which is a shared
 * component every screen inherits and not something to alter for one footer; so this
 * is local, and it is the only element on the screen that is.
 *
 * It is still a control rather than a caption. `clickable` with the module's own
 * indication, a `minTarget` floor so a thumb and a D-pad can both land on it, and the
 * underline that is the whole reason a reader knows it can be pressed. Type and colour
 * are `bodySmall` in `onBackgroundVariant`, which is what every secondary line on this
 * screen already uses, so it introduces no new value of anything.
 */
@Composable
private fun TermsLink(onClick: () -> Unit) {
    val isTv = CastivioTheme.device.isTv
    Text(
        text = stringResource(R.string.source_terms),
        style = CastivioType.bodySmall,
        color = CastivioTheme.colors.onBackgroundVariant,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .testTag(ActivationTags.SOURCE_TERMS)
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = Sizing.minTarget(isTv))
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = Spacing.sm),
    )
}

/**
 * The gap between the four cards, across and down alike.
 *
 * One figure in both directions, because a grid whose columns and rows are spaced
 * differently reads as two rows that happen to be near each other. A television has
 * the room for the next step up and a handset does not: `Spacing.lg` there leaves
 * 10.5dp of margin, `Spacing.xl` would leave -6.
 */
private val GridGap: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.xl else Spacing.lg

/**
 * Title to grid, and grid to footer.
 *
 * Deliberately larger than [GridGap]: the three parts of the screen have to read as
 * separate from each other while the four cards read as one set.
 *
 * `Spacing.xl` and not `Spacing.xxl` off a television, and the reason is the shortest
 * frame rather than taste. A 360dp-tall landscape window is an ordinary 360dp-wide
 * phone turned sideways, and at `Spacing.xxl` this screen comes to 368 against that
 * 360 — an 8dp overrun in a frame that does not scroll, which clips. At `Spacing.xl`
 * it is 352 and fits, and the reference handset keeps 41dp. `SourceChoiceBudgetTest`
 * computes both from these tokens.
 */
private val GroupGap: Dp
    @Composable @ReadOnlyComposable get() =
        if (CastivioTheme.device.isTv) Spacing.xxl else Spacing.xl

/**
 * Deeper than it is wide-padded, and one step up on a television.
 *
 * The card is between a third and a half of the screen across and holds 50dp of type,
 * so padding it equally would leave a letterbox. `Spacing.xxxl` was the figure when
 * two cards had the whole band to themselves; with four it makes the screen 8dp taller
 * than the shortest frame Castivio ships to, and a frame that no longer scrolls does
 * not scroll on an overrun — it clips. `SourceChoiceBudgetTest` computes both.
 */
private val CardPadding: PaddingValues
    @Composable @ReadOnlyComposable get() = if (CastivioTheme.device.isTv) {
        PaddingValues(horizontal = Spacing.xxl, vertical = Spacing.xl)
    } else {
        PaddingValues(horizontal = Spacing.lg, vertical = Spacing.lg)
    }

/**
 * One card. There is no second kind.
 *
 * @param tag the handle the layout gates measure this card by. Passed rather than
 *   applied by the caller so that every card is built the same way and a future one
 *   cannot arrive with a different modifier chain — the four being structurally
 *   identical is the property `SourceChoiceLayoutTest` asserts.
 */
@Composable
private fun RowScope.SourceCard(
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
            // One node, one label, one target: the card is the control, and a screen
            // reader announces the title and the description as a single item. Without
            // this the icon, the title and the detail are three focusable-looking
            // fragments of one choice.
            .semantics(mergeDescendants = true) { contentDescription = "$title. $detail" },
        fill = SolidColor(colors.glassFillStrong),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    // Null rather than a description: the card above already carries
                    // the whole label, and an icon that names itself makes a screen
                    // reader say the thing twice.
                    contentDescription = null,
                    tint = colors.onBackground,
                    modifier = Modifier.size(Sizing.iconMd),
                )
                // `titleLarge` over `bodySmall`. One point of size and no difference in
                // weight is not a hierarchy -- only the colour was saying which line
                // was the name of the thing.
                Text(text = title, style = CastivioType.titleLarge, color = colors.onBackground)
            }
            Text(
                text = detail,
                style = CastivioType.bodySmall,
                color = colors.onBackgroundVariant,
            )
        }
    }
}
