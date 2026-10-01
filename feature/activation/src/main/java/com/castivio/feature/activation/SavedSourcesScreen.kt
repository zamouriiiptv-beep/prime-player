package com.castivio.feature.activation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioDialog
import com.castivio.core.design.components.CastivioTextField
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioDescriptionColor
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.boundedFraction
import com.castivio.core.design.theme.castivioStage
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The subscriptions this device holds, and what the user may do to them.
 *
 * ## What it is, and what it deliberately is not
 *
 * It is a list of subscriptions, one row each, however many there are. A row says which
 * subscription it is — the name, the kind of source, the day it was added — marks the
 * one in use, and offers the two things this screen exists for: renaming it, and
 * removing it from the device. Choosing a row makes it the one the app shows.
 *
 * **A row carries no connection details.** No username, no password, no server URL.
 * They are held by the repository and used by the importer, and putting them on a list
 * would turn a screen anybody can glance at into one nobody can. What identifies a
 * subscription to its owner is what they called it, and every subscription has a name:
 * the one they typed, or the first free `Playlist n`, decided once when it was
 * registered and stored with it. See `nextPlaylistName`.
 *
 * It is not a details page and not a form. Editing is renaming, and the dialog says so
 * in as many words, because a user who presses a pencil expecting the server form
 * should find out before they start typing rather than after they save.
 *
 * Adding is the two buttons at the bottom, and they do not open anything new — they
 * call the same `useXtream()` and `usePlaylistUrl()` the source choice calls, and land
 * on the same two forms. There is one way to add a provider in this application and
 * this screen is a second door onto it, not a second implementation of it.
 *
 * ## Deleting the subscription in use
 *
 * Allowed, warned about, and followed by nothing. The user may delete any subscription
 * including the active one; when it is the active one the confirmation says what that
 * will actually cost instead of refusing the press; and afterwards no other
 * subscription is promoted in its place. Which one the app shows is the user's choice,
 * and making it for them at the moment they deleted another is the one thing a screen
 * built for making that choice must not do.
 *
 * ## The frame
 *
 * The stage, the header and the four type steps are
 * [com.castivio.core.design.theme.CastivioMetrics]', computed from the measured size of
 * this surface. What a *row* needs beyond them — the disc, its padding, the gap between
 * rows, the two action buttons, the badges — is this screen's own, and goes through
 * [boundedFraction] like everything else: shares read off the 1280×720 reference, each
 * with a floor that keeps the shortest surface usable and a ceiling that keeps the
 * largest proportionate. They are not tokens and must not become tokens.
 *
 * It is a list, so it is the one step here that can be taller than the screen, and it
 * is given the *fixed* frame rather than the scrolling one. `ActivationSurface`'s
 * scrolling branch wraps its content in `verticalScroll`, and a `LazyColumn` inside an
 * unbounded height does not scroll, it crashes. Inside the fixed frame the list has a
 * bounded height and scrolls itself, which is what a list is supposed to do.
 *
 * ## There is no way to add a subscription from here
 *
 * There were two buttons under the list, Add Xtream and Add M3U, and they are gone. The
 * source choice one step back is where a subscription is added — four cards, of which
 * this screen is the fourth — and a screen reached *from* that chooser offering two of
 * its own four options back again is the same decision asked twice, in two places that
 * can disagree the first time either is edited.
 *
 * What that costs is the empty state: a device with nothing saved lands here on a list
 * with nothing in it and no control to fix that. It is one Back press from the chooser
 * that does, and Back is on the header of this screen, so it is a detour rather than a
 * dead end — but it is a detour, and it is the price of not drawing the same two
 * buttons in two places.
 */
@Composable
internal fun SavedSourcesScreen(
    state: SavedSourcesState,
    onChoose: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = CastivioTheme.device.isTv

    // Which subscription a dialog is open for, if any. Held here rather than in the
    // view model because it is a question about this screen and not about the device:
    // a dialog that survived the screen would be a dialog asking about a row that is
    // no longer on it.
    var renaming by remember { mutableStateOf<ProviderSource?>(null) }
    var deleting by remember { mutableStateOf<ProviderSource?>(null) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val m = sourceMetricsFor(tv = tv, width = maxWidth, height = maxHeight)
        val rows = rowMetricsFor(height = maxHeight, touchTarget = m.frame.touchTarget)

        Column(
            Modifier
                .fillMaxSize()
                .castivioStage(m.frame),
        ) {
            ChooserHeader(
                m = m,
                title = stringResource(R.string.saved_sources_title),
                headingTag = ActivationTags.SAVED_TITLE,
                backTag = ActivationTags.SAVED_BACK,
                onBack = onBack,
                subtitle = stringResource(R.string.saved_sources_subtitle),
            )
            Spacer(Modifier.height(m.bandTop))

            // The band, still weighted though nothing follows it any more: the three
            // states have to occupy the same height whichever one is drawn, or the list
            // arriving would move everything above it.
            SavedBand(
                m = m,
                rows = rows,
                state = state,
                onChoose = onChoose,
                onRename = { renaming = it },
                onDelete = { deleting = it },
            )
        }

        renaming?.let { source ->
            RenameDialog(
                source = source,
                onSave = { name ->
                    onRename(source.id, name)
                    renaming = null
                },
                onDismiss = { renaming = null },
            )
        }

        deleting?.let { source ->
            val active = (state as? SavedSourcesState.Ready)?.activeId == source.id
            // Two questions, not one with a clause bolted on. Deleting a subscription
            // that is merely saved is a tidy-up; deleting the one the app is using
            // changes what the app shows next time it opens, and a user is owed that
            // sentence before they answer rather than after.
            CastivioDialog(
                title = stringResource(
                    if (active) R.string.saved_sources_delete_active_title
                    else R.string.saved_sources_delete_title,
                ),
                message = stringResource(
                    if (active) R.string.saved_sources_delete_active_detail
                    else R.string.saved_sources_delete_detail,
                ),
                confirmLabel = stringResource(R.string.saved_sources_delete),
                dismissLabel = stringResource(R.string.saved_sources_cancel),
                // Filled, and filled in red. Everywhere else in this application a
                // destructive confirmation is a ghost beside an outlined Cancel, and
                // for the azure fill that is right — a blue button is the app saying
                // "do this", which is not what a dialog guarding a deletion means.
                //
                // Red is not approval. It is the colour the bin on the row behind this
                // dialog is already drawn in, so filling the button with it says what
                // the press will cost rather than endorsing it, and the word stops
                // being a label floating next to the only thing on screen that looks
                // like a button. Which way out is the safe one is still said by the
                // focus: it starts on Cancel, and a remote's first press cancels.
                confirmWeight = ButtonWeight.Primary,
                confirmFill = SolidColor(CastivioTheme.colors.danger),
                onConfirm = {
                    onDelete(source.id)
                    deleting = null
                },
                onDismiss = { deleting = null },
                modifier = Modifier.testTag(ActivationTags.SAVED_DELETE_DIALOG),
            )
        }
    }
}

/**
 * What sits between the header and the two buttons, whichever of the three states
 * this screen is in.
 *
 * All three take the same weighted band, so nothing above or below them moves as the
 * repository answers. Loading shows nothing at all rather than telling a returning
 * user they have no subscriptions and correcting it a moment later.
 */
@Composable
private fun ColumnScope.SavedBand(
    m: SourceMetrics,
    rows: RowMetrics,
    state: SavedSourcesState,
    onChoose: (String) -> Unit,
    onRename: (ProviderSource) -> Unit,
    onDelete: (ProviderSource) -> Unit,
) {
    val band = Modifier.weight(1f).fillMaxWidth()

    when (state) {
        SavedSourcesState.Loading -> Spacer(band)

        is SavedSourcesState.Ready -> if (state.isEmpty) {
            Box(band.padding(m.cardPad), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.saved_sources_empty),
                    style = castivioBodyStyle(m.fsDetail),
                    color = castivioDescriptionColor,
                    modifier = Modifier.testTag(ActivationTags.SAVED_EMPTY),
                )
            }
        } else {
            LazyColumn(
                modifier = band.testTag(ActivationTags.SAVED_LIST),
                verticalArrangement = Arrangement.spacedBy(rows.gap),
            ) {
                items(state.saved, key = { it.id }) { source ->
                    SavedSourceRow(
                        m = m,
                        rows = rows,
                        source = source,
                        isActive = source.id == state.activeId,
                        expiresAtMs = state.expiries[source.id],
                        onClick = { onChoose(source.id) },
                        onRename = { onRename(source) },
                        onDelete = { onDelete(source) },
                    )
                }
            }
        }
    }
}

/**
 * One saved subscription: what it is called, what kind it is, when it arrived.
 *
 * The name leads because it is what the user chose and what they will recognise. Beside
 * it, the mark if it is the one in use, the kind as a badge and the date as plain
 * text — enough to tell two subscriptions with similar names apart, and the most a list
 * has any business knowing.
 *
 * The name is the one string here that can be unbounded, so it is the one that
 * ellipsises: a row that grew to fit a 300-character name is a row that pushes every
 * other subscription off the screen.
 *
 * ## One group in the middle, not four columns
 *
 * The facts had a column each, every column the same share of every row. That carried
 * the width honestly and read, on a 65-inch television, as four facts pushed apart by
 * air: each column was wider than the thing in it, and the widest gap of all sat behind
 * a short name like `Playlist 1`.
 *
 * So the facts are one group now. The name keeps a share of its own, the two actions
 * keep the trailing end, and the group between them takes the rest and centres itself
 * in it. The slack that used to pool in one place is divided in two and sits on either
 * side of a group that reads as a single phrase.
 *
 * ## Why the name is still a share and not its own width
 *
 * It was its own width for one revision, which is the obvious reading of "tight": a
 * short name takes a short column and the group starts right after it. What that costs
 * is the thing this row is for — two subscriptions named `Home` and `Cabin` differ by
 * 4dp, the group is centred in what is left after the name, and every fact in it sits
 * 2dp off the one above. The drift is the name's length, so it is small between two
 * English words and the width of a sentence between `Playlist 1` and a name somebody
 * typed in full.
 *
 * A share is the same width on every row, so the group's region is too, and a column of
 * facts is a column again. It is also what keeps the row inside the narrowest frame
 * this ships to: shares shrink together, and a name with a width of its own would push
 * the two actions off an 800dp handset before it ellipsised.
 *
 * ## The mark's place is held on every row
 *
 * The group is centred, so a row that drops an item re-centres what is left and every
 * fact after it moves. Exactly one item comes and goes between one subscription and the
 * next — the "in use" mark — and it is the first of them, so losing it would shift the
 * whole group on every row but one.
 *
 * It is therefore composed on every row and drawn on one: hidden by [placeHeld], which
 * takes the badge out of the drawing and out of the semantics tree while leaving the
 * space it measured. Reserved that way rather than by a width, because the width of
 * "Active now" belongs to the translation and the frame, and a number here would be
 * right in English on a television and wrong in Arabic on a handset.
 *
 * The expiry is the other item that can be absent, and it is *not* held open: a
 * provider that states no date, and a playlist that has no subscription behind it at
 * all, are the common case rather than the exception, and a reserved slot for them
 * would be a permanent gap drawn in the name of alignment. It is last in the group for
 * that reason — the one thing after it is nothing.
 *
 * ## Why the group's items still carry shares
 *
 * Every one of them takes `weight(share, fill = false)`, which on a wide frame is not a
 * width at all: each item is as wide as its content and the shares never bind. They
 * bind on the shortest handset, where the row has 160dp for all four, and what they buy
 * there is that everything shrinks in proportion and ellipsises inside the row instead
 * of the last fact being laid out past its edge.
 */
@Composable
private fun SavedSourceRow(
    m: SourceMetrics,
    rows: RowMetrics,
    source: ProviderSource,
    isActive: Boolean,
    expiresAtMs: Long?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = CastivioTheme.colors
    val inUse = stringResource(R.string.saved_sources_active)
    val xtream = source.kind == SourceKind.XTREAM
    val hue = if (xtream) colors.hueViolet else colors.hueAzure
    val title = source.label.ifBlank { stringResource(R.string.saved_sources_unnamed) }

    InteractiveGlassCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = if (isActive) "$title. $inUse" else title
            },
        shape = RoundedCornerShape(m.radius),
        fill = SolidColor(if (isActive) colors.glassFillStrong else colors.glassFill),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = m.frame.touchTarget)
                .padding(rows.pad),
            horizontalArrangement = Arrangement.spacedBy(rows.pad),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(rows.disc)
                    .background(hue.copy(alpha = DISC_FILL), RoundedCornerShape(rows.discRadius)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (xtream) Icons.Rounded.Dns else Icons.Rounded.Link,
                    contentDescription = null,
                    tint = colors.onBackground,
                    modifier = Modifier.size(Sizing.iconMd),
                )
            }

            Text(
                text = title,
                style = castivioChipStyle(m.fsCard),
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(NAME_SHARE),
            )

            Row(
                modifier = Modifier.weight(GROUP_SHARE),
                horizontalArrangement = Arrangement.spacedBy(
                    rows.badgePad,
                    Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Drawn on the one row in use, measured on all of them. See the note
                // above: this is the item whose absence would move everything after it.
                ActiveBadge(
                    rows = rows,
                    text = inUse,
                    fontSize = m.fsBadge,
                    modifier = Modifier
                        .weight(ACTIVE_SHARE, fill = false)
                        .placeHeld(isActive),
                )
                Separator(m.fsDetail, Modifier.placeHeld(isActive))

                KindBadge(
                    rows = rows,
                    kind = source.kind,
                    hue = hue,
                    fontSize = m.fsBadge,
                    modifier = Modifier
                        .weight(KIND_SHARE, fill = false)
                        .testTag(ActivationTags.SAVED_KIND),
                )
                Separator(m.fsDetail)

                Fact(
                    text = stringResource(
                        R.string.saved_sources_added,
                        dateOn(source.createdAtMs),
                    ),
                    fontSize = m.fsDetail,
                    modifier = Modifier.weight(DATE_SHARE, fill = false),
                )

                // Absent far more often than present — every M3U playlist, and every
                // panel that states no end date — so it is drawn only where the
                // provider actually gave one, and nothing holds its place.
                if (expiresAtMs != null) {
                    Separator(m.fsDetail)
                    Fact(
                        text = stringResource(R.string.saved_sources_expires, dateOn(expiresAtMs)),
                        fontSize = m.fsDetail,
                        modifier = Modifier
                            .weight(DATE_SHARE, fill = false)
                            .testTag(ActivationTags.SAVED_EXPIRES),
                    )
                }
            }

            RowAction(
                rows = rows,
                icon = Icons.Rounded.Edit,
                label = stringResource(R.string.saved_sources_edit),
                fontSize = m.fsBadge,
                tint = colors.onBackground,
                edge = colors.edgeQuiet,
                onClick = onRename,
                tag = ActivationTags.SAVED_EDIT,
            )
            RowAction(
                rows = rows,
                icon = Icons.Rounded.Delete,
                label = stringResource(R.string.saved_sources_delete),
                fontSize = m.fsBadge,
                tint = colors.danger,
                edge = colors.discBorder(colors.danger),
                onClick = onDelete,
                tag = ActivationTags.SAVED_DELETE,
            )
        }
    }
}

/**
 * Composed where a hidden item has to keep the space it measured.
 *
 * Two things at once, and both are needed or the other is a defect. The item is not
 * drawn, and it is not in the semantics tree either — a transparent "Active now" that a
 * screen reader still announced would tell a user every subscription was in use, and a
 * finder looking for that text would match every row.
 *
 * It keeps its size, which is the whole purpose: the width of the thing itself, in this
 * translation, at this frame's type step, which no constant could be.
 */
private fun Modifier.placeHeld(shown: Boolean): Modifier =
    if (shown) this else this.alpha(0f).clearAndSetSemantics { }

/** A dot between two facts, so the group reads as one phrase rather than a queue. */
@Composable
private fun Separator(fontSize: Dp, modifier: Modifier = Modifier) {
    Text(
        text = SEPARATOR,
        style = castivioBodyStyle(fontSize),
        color = castivioDescriptionColor.copy(alpha = SEPARATOR_FILL),
        maxLines = 1,
        modifier = modifier,
    )
}

/** One of the quiet facts in the middle group: a date, and the word in front of it. */
@Composable
private fun Fact(text: String, fontSize: Dp, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = castivioBodyStyle(fontSize),
        color = castivioDescriptionColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** "Active now", the one mark that has to be findable across a room. */
@Composable
private fun ActiveBadge(
    rows: RowMetrics,
    text: String,
    fontSize: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(rows.badge / 2)
    Row(
        modifier
            .heightIn(min = rows.badge)
            .background(colors.success.copy(alpha = BADGE_FILL), shape)
            .border(BorderStroke(1.dp, colors.discBorder(colors.success)), shape)
            .padding(horizontal = rows.badgePad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(rows.badgePad / 2),
    ) {
        Box(Modifier.size(rows.dot).background(colors.success, CircleShape))
        Text(
            text = text,
            style = castivioChipStyle(fontSize),
            color = colors.success,
            maxLines = 1,
        )
    }
}

/** Xtream or M3U, in the hue the row's disc already uses. */
@Composable
private fun KindBadge(
    rows: RowMetrics,
    kind: SourceKind,
    hue: Color,
    fontSize: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Text(
        text = if (kind == SourceKind.XTREAM) XTREAM else M3U,
        style = castivioChipStyle(fontSize),
        color = colors.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(hue.copy(alpha = BADGE_STRONG), RoundedCornerShape(rows.badge / 2))
            .padding(horizontal = rows.badgePad, vertical = rows.badgePad / 3),
    )
}

/**
 * One of the two controls on the trailing end of a row.
 *
 * A glyph and the word, not a glyph alone: a pencil and a bin next to each other are
 * two small shapes that differ by a few pixels, and the one that is hard to undo is
 * not a shape to leave a user guessing at across a room.
 */
@Composable
private fun RowAction(
    rows: RowMetrics,
    icon: ImageVector,
    label: String,
    fontSize: Dp,
    tint: Color,
    edge: Color,
    onClick: () -> Unit,
    tag: String,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(rows.actionRadius)
    InteractiveGlassCard(
        onClick = onClick,
        modifier = Modifier
            .size(rows.action)
            .testTag(tag)
            .semantics(mergeDescendants = true) { contentDescription = label },
        shape = shape,
        fill = SolidColor(colors.glassFill),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .border(BorderStroke(1.dp, edge), shape),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizing.iconSm))
            Text(label, style = castivioChipStyle(fontSize), color = tint, maxLines = 1)
        }
    }
}

/**
 * The one field "edit" opens.
 *
 * `CastivioDialog` with a field in it, not a second dialog. It was briefly the latter —
 * the same scrim, the same panel and the same focus rule written out again because the
 * shared one took a message and two buttons and nothing between them — and two modals
 * that agree only while nobody edits either is the thing `:core:design` exists to
 * prevent. So the shared one grew a slot, every existing caller passes nothing and is
 * drawn exactly as it was, and this passes a field.
 */
@Composable
private fun RenameDialog(
    source: ProviderSource,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(source.id) { mutableStateOf(source.label) }

    CastivioDialog(
        title = stringResource(R.string.saved_sources_rename_title),
        message = stringResource(R.string.saved_sources_rename_detail),
        confirmLabel = stringResource(R.string.saved_sources_rename_save),
        dismissLabel = stringResource(R.string.saved_sources_cancel),
        onConfirm = { onSave(name) },
        onDismiss = onDismiss,
        modifier = Modifier.testTag(ActivationTags.SAVED_RENAME_DIALOG),
        // Filled: this one asks for a value, and nothing it does needs guarding.
        confirmWeight = ButtonWeight.Primary,
        field = {
            CastivioTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.saved_sources_rename_field),
                modifier = Modifier.fillMaxWidth().testTag(ActivationTags.SAVED_RENAME_FIELD),
            )
        },
    )
}

/**
 * A timestamp as the interface's own date, memoised on both of its inputs.
 *
 * The skeleton rather than a pattern, for the reason `formatExpiry` gives on the
 * licence screen: the platform knows what order and separators a language puts a day, a
 * month and a year in, and this screen does not.
 *
 * Both dates in a row go through it — when the subscription arrived and when it runs
 * out — because two dates side by side written in two formats read as two different
 * kinds of fact.
 */
@Composable
private fun dateOn(atMs: Long): String {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current).get(0) ?: Locale.getDefault()
    return remember(atMs, locale) {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, ADDED_SKELETON)
        SimpleDateFormat(pattern, locale).format(Date(atMs))
    }
}

/**
 * What a row needs beyond the shared frame.
 *
 * A row is not a grid card: `SourceMetrics.disc` is the source choice's, a 96/720 disc
 * in a square tile in a two-by-two grid, and borrowing it here produced a row tall
 * enough to push both add buttons off a television. These are read off the same
 * 1280×720 reference and bounded by the same rule.
 */
internal data class RowMetrics(
    val disc: Dp,
    val discRadius: Dp,
    val pad: Dp,
    val gap: Dp,
    val action: Dp,
    val actionRadius: Dp,
    val badge: Dp,
    val badgePad: Dp,
    val dot: Dp,
)

internal fun rowMetricsFor(height: Dp, touchTarget: Dp): RowMetrics {
    val disc = height.boundedFraction(DISC, 40.dp, 64.dp)
    return RowMetrics(
        disc = disc,
        discRadius = height.boundedFraction(DISC_RADIUS, 11.dp, 18.dp),
        pad = height.boundedFraction(PAD, 8.dp, 18.dp),
        gap = height.boundedFraction(GAP, 10.dp, 20.dp),
        // Never under the floor a remote and a thumb both need, whatever the share says.
        action = maxOf(disc, touchTarget),
        actionRadius = height.boundedFraction(ACTION_RADIUS, 9.dp, 16.dp),
        badge = height.boundedFraction(BADGE, 18.dp, 28.dp),
        badgePad = height.boundedFraction(BADGE_PAD, 6.dp, 12.dp),
        dot = height.boundedFraction(DOT, 6.dp, 10.dp),
    )
}

/* The shares, read off the 1280×720 reference the rest of the system is read off. */
private const val DISC = 78f / 720f
private const val DISC_RADIUS = 22f / 720f
private const val PAD = 16f / 720f
private const val GAP = 22f / 720f
private const val ACTION_RADIUS = 18f / 720f
private const val BADGE = 28f / 720f
private const val BADGE_PAD = 12f / 720f
private const val DOT = 10f / 720f

/** How much of its hue a disc and a badge take. Opacity, not a colour. */
private const val DISC_FILL = 0.92f
private const val BADGE_STRONG = 0.85f
private const val BADGE_FILL = 0.12f

/* ------------------------------------------------------------ the row's two regions
 *
 * What is left of the row once the disc and the two actions have taken their fixed
 * sizes, split between the name and the group of facts. Shares rather than widths, so a
 * television and the shortest handset lay the row out in the same proportions and
 * nothing has to be re-derived per frame — and so that both regions are the same width
 * on every row, which is what lets the group above be a column of facts rather than
 * four facts that drift with the length of the name beside them.
 *
 * The split is lopsided because the content is. Measured on the drawing at 1280x720,
 * the four facts and the three dots between them come to 743dp of the 877 the row has
 * to give — a mark, a product name and two dates each carrying the words in front of
 * it. A name column of any generosity would cut the last date off, so the name takes
 * what is left: 132dp there and 94 on the television, which is `Playlist 1` and a
 * little, and an ellipsis beyond it.
 *
 * That is the trade and it is the right way round. A name ellipsised from its end is
 * still recognisable — it is the user's own string and they are looking for the one
 * they named. Half a date is not a date.
 */
private const val NAME_SHARE = 1.5f
private const val GROUP_SHARE = 8.5f

/* ----------------------------------------------------- the middle group's ceilings
 *
 * Not widths. Every item in the group takes its share with `fill = false`, so on any
 * frame with room the share is simply never reached and each item is as wide as its own
 * content — which is what makes the group tight.
 *
 * What they decide is the narrowest frame, where the four facts want more than the
 * group has. There the shares bind, and they say what a crowded row gives up first: the
 * dates ellipsise before the mark does, and the mark before the kind, because a
 * half-read date is still a date and half a badge is a shape.
 */
private const val ACTIVE_SHARE = 1.2f
private const val KIND_SHARE = 1f
private const val DATE_SHARE = 2f

/** Day, month as a word, year — order and separators are the locale's business. */
private const val ADDED_SKELETON = "dMMMy"

/** Between two facts. Punctuation, not a word: it is the same mark in every language. */
private const val SEPARATOR = "•"

/** How much of the description's colour a separator keeps. Quieter than what it parts. */
private const val SEPARATOR_FILL = 0.55f

/** The two kinds, as their providers spell them. Not translated: they are product names. */
private const val XTREAM = "Xtream"
private const val M3U = "M3U"
