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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
 * ## Four columns, not two lines and a void
 *
 * The facts were stacked in a column that took `weight(1f)`, so they gathered at one
 * end of the row, the two actions sat at the other, and everything between them was
 * air. Measured on the television frame: 490dp of an 868dp row, which is more than half
 * the row, and it is worst exactly where the row is widest.
 *
 * So each fact has a column of its own and every column is the same share of every
 * row. Three things follow from that, and the third is the one worth having:
 *
 *  - the slack has one place to go — the name, the only one of them that can use it;
 *  - the kinds sit under the kinds and the dates under the dates, so six subscriptions
 *    are scanned rather than read;
 *  - **the mark keeps its column on the rows that do not have one.** A slot that
 *    collapsed when it was empty would put the kind in a different place on every line,
 *    which is the whole of what a column is.
 *
 * Shares rather than a table of widths, for the reason everything else here is a share:
 * they are the same proportion on a television and on the shortest handset, and no
 * number has to be re-derived per frame. [NAME_SHARE] and the three beside it are read
 * off the approved drawing at 960×540.
 */
@Composable
private fun SavedSourceRow(
    m: SourceMetrics,
    rows: RowMetrics,
    source: ProviderSource,
    isActive: Boolean,
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

            // Empty on every row but one, and it still takes its share. See the note
            // above: this is the column that keeps the two after it from moving.
            Box(Modifier.weight(ACTIVE_SHARE)) {
                if (isActive) {
                    ActiveBadge(rows, inUse, m.fsBadge)
                }
            }

            Box(Modifier.weight(KIND_SHARE).testTag(ActivationTags.SAVED_KIND)) {
                KindBadge(rows, source.kind, hue, m.fsBadge)
            }

            Text(
                text = stringResource(R.string.saved_sources_added, addedOn(source.createdAtMs)),
                style = castivioBodyStyle(m.fsDetail),
                color = castivioDescriptionColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(DATE_SHARE),
            )

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

/** "Active now", the one mark that has to be findable across a room. */
@Composable
private fun ActiveBadge(rows: RowMetrics, text: String, fontSize: Dp) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(rows.badge / 2)
    Row(
        Modifier
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
private fun KindBadge(rows: RowMetrics, kind: SourceKind, hue: Color, fontSize: Dp) {
    val colors = CastivioTheme.colors
    Text(
        text = if (kind == SourceKind.XTREAM) XTREAM else M3U,
        style = castivioChipStyle(fontSize),
        color = colors.onBackground,
        maxLines = 1,
        modifier = Modifier
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
 */
@Composable
private fun addedOn(atMs: Long): String {
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

/* ------------------------------------------------------------ the row's columns
 *
 * Read off the approved drawing at 960×540, where the name took 309dp of the row's
 * flexible width, the mark 88, the kind 78 and the date 152. Shares rather than those
 * four numbers, so a television and the shortest handset lay the row out in the same
 * proportions and nothing has to be re-derived per frame.
 *
 * The name's share is the large one because it is the only column whose content is the
 * user's: the other three hold a mark of fixed words, a product name of two, and a date.
 * Those three are as wide as they need and no wider, and what is left over is the name's
 * — which is the whole point of the row having columns at all.
 */
private const val NAME_SHARE = 4f
private const val ACTIVE_SHARE = 1.2f
private const val KIND_SHARE = 1f
private const val DATE_SHARE = 2f

/** Day, month as a word, year — order and separators are the locale's business. */
private const val ADDED_SKELETON = "dMMMy"

/** The two kinds, as their providers spell them. Not translated: they are product names. */
private const val XTREAM = "Xtream"
private const val M3U = "M3U"
