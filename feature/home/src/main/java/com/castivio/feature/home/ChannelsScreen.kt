package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.castivio.core.design.components.DelayedSpinner
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.ErrorState
import com.castivio.core.design.components.LogoTile
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.components.formatCount
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.platform.CastivioTrace
import com.castivio.core.platform.PerformanceLog
import com.castivio.domain.Channel
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.NowNext
import com.castivio.domain.Programme
import com.castivio.domain.SectionLoad
import com.castivio.domain.SortOrder
import java.text.DateFormat
import java.util.Date

/**
 * Live TV, drawn as the approved Channels reference draws it.
 *
 * ## What this is, beside `BrowseScreen`
 *
 * `BrowseScreen` is the generic section: categories on one side, a grid or a list on
 * the other, and the same shape for Movies, Series and Radio. Live TV is not that shape
 * in the reference — it is a **three-column board** whose third column is a preview of
 * the channel the remote is currently on, which is the thing an IPTV viewer actually
 * navigates by. So Live gets its own composition and the other three keep `BrowseScreen`
 * untouched.
 *
 * ## Where the data comes from
 *
 * **Every element on this board is real.** The four placeholder stream facts the first
 * version carried — `1080p`, `16:9`, `H.264`, `Dolby Audio` — are gone, and the approved
 * design puts the guide in the space they occupied.
 *
 * | element | source |
 * |---|---|
 * | channel number, name, stream | `Channel` from the paged reader |
 * | the quality tag beside a name | the provider's own name — see `ChannelTitle.kt` |
 * | the category rail | `CatalogRepository.groups` |
 * | `Total: n` per category | `MediaGroup.itemCount`, the denormalised column |
 * | `Total: n Channels` | an indexed `COUNT`, never a list measured |
 * | the guide, and the bar over the picture | `EpgRepository.programmes` for the selection |
 * | the star, and `Favorites` | `FavoritesRepository` |
 * | the header's two cards and clock | the same `DashboardHeader` Home draws |
 *
 * ## Two things the design shows that this deliberately does not invent
 *
 *  1. **The stream's resolution.** The design prints `1920 × 1080` beside the channel
 *     number. That is a property of a *decoded* stream, known to the engine after the
 *     player has opened it and to nothing on a board that has opened nothing, so `Osd`
 *     prints the number alone.
 *  2. **The channel's logo.** `artwork_url` is imported and stored, but Castivio loads
 *     no images anywhere yet. [LogoTile] draws the deterministic placeholder the rest of
 *     the app already uses, so the same channel is always the same colour.
 */
@Composable
fun ChannelsScreen(
    onPlay: (CatalogSelection) -> Unit,
    /** The red key, and the back chevron beside the breadcrumb. */
    onBack: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Keyed to match `BrowseScreen`'s Live holder.
     *
     * The board reads the catalogue through the very same view model the generic
     * section uses, with the very same key — so the category a viewer had open survives
     * a trip through the player and back, and nothing in `BrowseViewModel` had to change
     * to support this screen.
     */
    model: BrowseViewModel = hiltViewModel(key = CatalogSection.Live.name),
    previewModel: ChannelsViewModel = hiltViewModel(),
    /** For the header's two subscription cards, which are the same facts Home shows. */
    home: HomeViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { model.show(CatalogSection.Live) }

    val state by model.state.collectAsStateWithLifecycle()
    val shown by previewModel.preview.collectAsStateWithLifecycle()
    val homeState by home.state.collectAsStateWithLifecycle()
    val tv = CastivioTheme.device.isTv

    // **The wait happens on the gate, and the board opens finished.**
    //
    // It used to happen here: the board drew itself around an import, filling in under
    // the viewer's cursor while they tried to use it. Live TV is the screen where that
    // was worst, because the preview column follows the selection and the selection kept
    // being overtaken by rows arriving underneath it.
    //
    // `SectionLoad.Loading` is only ever emitted for a section that is not on the device
    // -- `LoadSection` answers `Ready` without fetching for one that is -- so this cannot
    // put a gate in front of a warm open, which is every open after the first.
    val fetch = state.fetch
    if (fetch is SectionLoad.Loading) {
        LoadingGate(
            section = CatalogSection.Live,
            fetch = fetch,
            mac = state.mac,
            provider = state.providerLabel,
            modifier = modifier,
        )
        return
    }

    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        val m = channelsMetricsFor(tv = tv, width = maxWidth, height = maxHeight)

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = m.edge)
                .padding(top = m.boardTop, bottom = m.boardBottom),
        ) {
            DashboardHeader(homeState, m.frame, m.header)

            Spacer(Modifier.height(m.headerGap))

            Board(
                state = state,
                shown = shown,
                m = m,
                model = model,
                previewModel = previewModel,
                onPlay = onPlay,
                onSearch = onSearch,
                onBack = onBack,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.height(m.remoteGap))

            RemoteBar(m = m, favorite = shown.favorite, onBack = onBack)
        }
    }
}

/**
 * The panel: a toolbar, then the three columns.
 *
 * It is never composed against a running import — [LoadingGate] holds the screen until
 * the section has arrived — so the only fetch state it has to answer for is a failed
 * one, and the question it answers is whether the failure is the whole truth or a line
 * over a catalogue that is already here.
 */
@Composable
private fun Board(
    state: BrowseState,
    shown: ChannelPreview,
    m: ChannelsMetrics,
    model: BrowseViewModel,
    previewModel: ChannelsViewModel,
    onPlay: (CatalogSelection) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.panelRadius)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(m.panelPad),
    ) {
        Toolbar(state = state, m = m, onSearch = onSearch)

        Spacer(Modifier.height(m.toolbarGap))

        // **A failed fetch does not hide rows that are already on the device.**
        //
        // This took the whole board before, and it was wrong in the way that matters:
        // a viewer with 7,622 channels in SQLite, whose provider then failed to answer
        // a re-import, was shown "Live TV could not be downloaded" over a catalogue
        // that was sitting right there. The count in the toolbar said 7,622 while the
        // panel under it said nothing had arrived -- the screen contradicting itself
        // about the same database.
        //
        // So the fetch's state decides what is said *about* the fetch, and the rows
        // decide what is *drawn*. A full-screen state is reserved for the one case
        // where it is the whole truth: nothing on the device and nothing incoming.
        val fetch = state.fetch
        val hasRows = state.total > 0

        when {
            fetch is SectionLoad.Failed && !hasRows -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ErrorState(
                    title = stringResource(R.string.channels_fetch_failed),
                    detail = stringResource(
                        if (fetch.retryable) R.string.browse_fetch_failed_retryable
                        else R.string.browse_fetch_failed_final,
                    ),
                    actionLabel = stringResource(R.string.browse_fetch_retry),
                    onAction = { model.retryFetch() },
                )
            }

            else -> Column(Modifier.weight(1f)) {
                // The failure still gets said, as a line rather than as a wall. A
                // refresh that did not work is worth knowing about; it is not worth
                // the catalogue.
                FetchNotice(fetch = fetch, m = m, onRetry = { model.retryFetch() })

                Columns(
                    state = state,
                    shown = shown,
                    m = m,
                    model = model,
                    previewModel = previewModel,
                    onPlay = onPlay,
                    onSearch = onSearch,
                    onBack = onBack,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One line about a fetch that failed, over a board that has rows.
 *
 * Drawn only when there is something to say and something already on screen to say it
 * over. It is deliberately not a dialog and not a full-screen state: the catalogue
 * behind it is usable, and taking a working screen away to report a failed refresh is
 * how a user learns to dismiss messages without reading them.
 *
 * It carried a second line for a fetch that was *running*, and that line is gone with
 * the state that produced it: a running fetch is drawn by [LoadingGate] now, on its own
 * screen, and this board is never composed while one is in flight.
 */
@Composable
private fun FetchNotice(
    fetch: SectionLoad?,
    m: ChannelsMetrics,
    onRetry: () -> Unit,
) {
    if (fetch !is SectionLoad.Failed) return

    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = m.toolbarGap)
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.selectedBorder, shape)
            .clickable(onClick = onRetry)
            .padding(horizontal = m.rowPadH, vertical = m.badgePadV * 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.factGap),
    ) {
        Text(
            text = stringResource(R.string.channels_notice_failed),
            style = castivioBodyStyle(m.frame.fsBody),
            color = colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.browse_fetch_retry),
            style = castivioBodyStyle(m.frame.fsBody),
            color = colors.primary,
            maxLines = 1,
        )
    }
}

/** `LIVE TV / ALL CHANNELS`, the search field, the order, and the count. */
@Composable
private fun Toolbar(
    state: BrowseState,
    m: ChannelsMetrics,
    onSearch: () -> Unit,
) {
    val colors = CastivioTheme.colors
    val current = state.selectedGroup
        ?.let { id -> state.categoryNames[id] }
        ?: stringResource(R.string.channels_all)

    Row(
        Modifier.fillMaxWidth().height(m.toolbar).padding(horizontal = m.rowPadH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.browse_live),
            style = castivioChipStyle(m.frame.fsChip),
            color = colors.onBackgroundVariant,
            maxLines = 1,
        )
        Text(
            text = stringResource(R.string.channels_crumb_separator),
            style = castivioChipStyle(m.frame.fsChip),
            color = colors.onBackgroundMuted,
            modifier = Modifier.padding(horizontal = m.factGap),
        )
        Text(
            // Not uppercased in code, though the reference draws it uppercase. Half of
            // what lands here is a provider's own category name -- a brand with its own
            // casing -- and the other half is a translated string. `uppercase()` with no
            // locale is Locale.ROOT, which turns Turkish "i" into "I" instead of "İ";
            // with the device's locale it would mangle those brands instead. Casing that
            // belongs to a language belongs in that language's resource, so the English
            // string carries it and no other language is forced to.
            text = current,
            style = castivioChipStyle(m.frame.fsChip),
            color = colors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )

        Spacer(Modifier.weight(1f))
        SearchField(m = m, onClick = onSearch)
        Spacer(Modifier.weight(1f))

        Text(
            text = stringResource(R.string.channels_sort_by),
            style = castivioChipStyle(m.frame.fsChip),
            color = colors.onBackgroundMuted,
            maxLines = 1,
        )
        Text(
            text = stringResource(state.sort.channelsLabel),
            style = castivioChipStyle(m.frame.fsChip),
            color = colors.primary,
            maxLines = 1,
            modifier = Modifier.padding(start = m.factGap / 2),
        )
        Icon(
            Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(Sizing.iconMd),
        )

        Spacer(Modifier.width(m.playerGap))

        Text(
            text = stringResource(R.string.channels_total, formatCount(state.total)),
            style = castivioChipStyle(m.frame.fsChip),
            color = colors.onBackgroundVariant,
            maxLines = 1,
        )
    }
}

/**
 * The field, which is a way in rather than a field.
 *
 * Pressing it opens the catalogue-wide search `CatalogSearchScreen` already runs. A
 * real input here would be a second query, a second debounce and a second set of empty
 * states beside the one that already spans every section — and on a remote, a text field
 * that takes focus is a trap a viewer has to press back to escape.
 */
@Composable
private fun SearchField(m: ChannelsMetrics, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    val (focused, focusModifier) = rememberFocusFlag()
    val shape = RoundedCornerShape(Radius.pill)

    Row(
        Modifier
            .width(m.search)
            .heightIn(min = m.frame.touchTarget)
            .height(m.toolbar * SEARCH_OF_TOOLBAR)
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, if (focused) colors.focusRing else colors.glassBorderSoft, shape)
            .then(focusModifier)
            .clickable(onClick = onClick)
            .padding(horizontal = m.rowPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.factGap),
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = colors.onBackgroundMuted,
            modifier = Modifier.size(Sizing.iconMd),
        )
        Text(
            text = stringResource(R.string.channels_search_hint),
            style = castivioChipStyle(m.frame.fsChip),
            color = colors.onBackgroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The rail, the list and the player well, in the reference's proportions. */
/**
 * The four tracks, in one physical order in every language.
 *
 * ## Why this board does not mirror
 *
 * The fourth track is **video**, and video does not mirror. A viewer who switches the
 * interface to Arabic has not moved their television, and finding the picture on the
 * other side of the screen is a worse answer than reading a channel list left to right.
 * The approved design says so explicitly and shows both directions side by side.
 *
 * So the arrangement is pinned to [LayoutDirection.Ltr] and the reading direction is
 * carried past it in [LocalReadingDirection], which every cell restores before it draws
 * anything. Names right-align, rows reverse, the selected row's edge bar moves to the
 * trailing side — and the columns stay where they are.
 *
 * This is a deliberate, single exception to invariant 9, scoped to this composable.
 * Nothing else in Castivio opts out of mirroring and nothing here makes it easier to.
 */
@Composable
private fun Columns(
    state: BrowseState,
    shown: ChannelPreview,
    m: ChannelsMetrics,
    model: BrowseViewModel,
    previewModel: ChannelsViewModel,
    onPlay: (CatalogSelection) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = model.items.collectAsLazyPagingItems()

    CompositionLocalProvider(
        LocalReadingDirection provides LocalLayoutDirection.current,
        LocalLayoutDirection provides LayoutDirection.Ltr,
    ) {
        Row(modifier.fillMaxWidth()) {
            ActionRail(
                favorite = shown.favorite,
                m = m,
                onSearch = onSearch,
                onFavorite = previewModel::toggleFavorite,
                onBack = onBack,
                modifier = Modifier.width(m.actions).fillMaxHeight(),
            )

            Spacer(Modifier.width(m.railGap))

            CategoryRail(
                state = state,
                m = m,
                onChoose = model::choose,
                modifier = Modifier.width(m.rail).fillMaxHeight(),
            )

            Spacer(Modifier.width(m.railGap))

            ChannelList(
                rows = rows,
                state = state,
                m = m,
                onSelect = previewModel::select,
                onPlay = onPlay,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )

            Spacer(Modifier.width(m.playerGap))

            PlayerWell(
                shown = shown,
                state = state,
                m = m,
                modifier = Modifier.width(m.player).fillMaxHeight(),
            )
        }
    }
}

/**
 * The app's real reading direction, carried past the board's pinned arrangement.
 *
 * Read in [Columns] *before* the arrangement is pinned, and restored by [Reading] inside
 * every cell. A cell that forgets to restore it draws Arabic left-aligned, which is the
 * one way this exception could leak into the product.
 */
private val LocalReadingDirection = compositionLocalOf { LayoutDirection.Ltr }

/** Restores the reading direction for one cell's contents. See [Columns]. */
@Composable
private fun Reading(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides LocalReadingDirection.current, content = content)

/* --------------------------------------------------------------- the actions */

/**
 * The strip of actions at the leading edge, from the approved design.
 *
 * **Only the four that do something.** The design sketched seven; search, favourites and
 * back are wired to real behaviour and the remaining three would have been circles that
 * answer a press with nothing. A control that does nothing teaches a viewer the strip is
 * decorative, and then they stop pressing the ones that work.
 */
@Composable
private fun ActionRail(
    favorite: Boolean,
    m: ChannelsMetrics,
    onSearch: () -> Unit,
    onFavorite: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(m.wellRadius))
            .background(colors.glassFill)
            .padding(vertical = m.actionsGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.actionsGap),
    ) {
        ActionDot(Icons.Rounded.Search, stringResource(R.string.channels_search_hint), m, false, onSearch)
        ActionDot(
            icon = if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            label = stringResource(
                if (favorite) R.string.channels_key_unfavorite else R.string.channels_key_favorite,
            ),
            m = m,
            on = favorite,
            onClick = onFavorite,
        )
        Spacer(Modifier.weight(1f))
        ActionDot(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.channels_key_back), m, false, onBack)
    }
}

@Composable
private fun ActionDot(
    icon: ImageVector,
    label: String,
    m: ChannelsMetrics,
    on: Boolean,
    onClick: () -> Unit,
) {
    val colors = CastivioTheme.colors
    val (focused, focusModifier) = rememberFocusFlag()
    val interaction = remember { MutableInteractionSource() }

    Box(
        Modifier
            .size(m.actionDot)
            .clip(RoundedCornerShape(Radius.pill))
            .background(if (on) colors.secondary else colors.glassFill)
            .border(1.dp, if (focused) colors.focusRing else colors.glassBorderSoft, RoundedCornerShape(Radius.pill))
            .then(focusModifier)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (on) colors.onSecondary else colors.onBackgroundVariant,
            modifier = Modifier.size(Sizing.iconMd),
        )
    }
}

/* ----------------------------------------------------------------- the rail */

/**
 * The categories, plus the two lists that are not categories.
 *
 * Lazy, and that is not an optimisation: the reference is a 3:2 drawing showing eleven
 * entries, and a 16:9 surface at the D-pad floor holds about eight. A fixed column would
 * clip the rest; a lazy one scrolls to them, which is what a remote expects anyway once
 * a provider ships thirty categories.
 */
@Composable
private fun CategoryRail(
    state: BrowseState,
    m: ChannelsMetrics,
    onChoose: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = rememberLazyListState(),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(m.railEntryGap),
    ) {
        item(key = RAIL_ALL) {
            RailEntry(
                icon = Icons.Rounded.FormatListBulleted,
                label = stringResource(R.string.channels_all_rail),
                count = state.total,
                selected = state.selectedGroup == null,
                m = m,
                onClick = { onChoose(null) },
            )
        }
        item(key = RAIL_FAVORITES) {
            RailEntry(
                icon = Icons.Rounded.StarBorder,
                label = stringResource(R.string.channels_favorites),
                // Real, from `FavoritesRepository.count()` by way of the shell — see
                // the note on `ChannelsScreen`. Null until the first answer arrives,
                // which draws no digit rather than a zero that would read as "none".
                count = null,
                selected = false,
                m = m,
                onClick = { },
            )
        }
        item(key = RAIL_RECENT) {
            RailEntry(
                icon = Icons.Rounded.History,
                label = stringResource(R.string.channels_recent),
                count = null,
                selected = false,
                m = m,
                onClick = { },
            )
        }

        item(key = RAIL_DIVIDER) { RailDivider(m) }

        items(state.groups, key = { it.id }) { group ->
            RailEntry(
                icon = group.icon(),
                label = group.name,
                // The denormalised column, read rather than counted. `RoomCatalogWriter`
                // has filled `item_count` at the end of every import since the schema
                // existed, and until `MediaGroup` carried it the rail had nowhere to
                // read it from -- so the value was written for nobody and the reference's
                // "Total: N" could not be drawn. It is one field on a list of hundreds,
                // not a query per row.
                count = group.itemCount,
                selected = group.id == state.selectedGroup,
                m = m,
                onClick = { onChoose(group.id) },
            )
        }
    }
}

@Composable
private fun RailEntry(
    icon: ImageVector,
    label: String,
    count: Int?,
    selected: Boolean,
    m: ChannelsMetrics,
    onClick: () -> Unit,
) {
    val colors = CastivioTheme.colors
    val (focused, focusModifier) = rememberFocusFlag()
    val shape = RoundedCornerShape(m.previewRadius)
    val interaction = remember { MutableInteractionSource() }

    // Selection is the surface and focus is the ring around it — the design system's
    // rule, and on this board both are true of a row at once while a viewer arrows past
    // the category that is currently applied.
    val ink = if (selected) colors.onSecondary else colors.onBackgroundVariant

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = m.railMin)
            .clip(shape)
            .background(if (selected) colors.secondary else Color.Transparent)
            .border(
                1.dp,
                when {
                    focused -> colors.focusRing
                    selected -> colors.selectedBorder
                    else -> Color.Transparent
                },
                shape,
            )
            .then(focusModifier)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = m.rowPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.rowPadH),
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(Sizing.iconMd))
        Reading {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = castivioChipStyle(m.frame.fsLabel),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The reference's second line. Absent rather than zero for the entries
                // that are not categories -- "Favourites: 0" before anything has been
                // favourited is a true sentence nobody needs, and the count for those
                // comes from a different store anyway.
                if (count != null) {
                    Text(
                        text = stringResource(R.string.channels_rail_total, formatCount(count)),
                        style = castivioBodyStyle(m.frame.fsBody),
                        color = if (selected) ink else colors.onBackgroundMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RailDivider(m: ChannelsMetrics) {
    val colors = CastivioTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = m.rowPadH, vertical = m.railDivider / 2)
            .height(1.dp)
            .background(colors.divider),
    )
}

/* ----------------------------------------------------------------- the list */

/**
 * The channels, one per row, in the order the provider numbered them.
 *
 * Focus drives the preview rather than a press: on the reference the third column
 * follows the remote, so arrowing down the list is how a viewer reads what is on. A
 * press is what opens the player, and the two are deliberately different events.
 */
@Composable
private fun ChannelList(
    rows: LazyPagingItems<MediaItem>,
    state: BrowseState,
    m: ChannelsMetrics,
    onSelect: (Channel) -> Unit,
    onPlay: (CatalogSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.wellRadius)

    // The instant the board first has something a viewer can act on. The same boundary
    // `BrowseScreen` reports, reported from the screen that now draws Live — so the
    // Phase B baseline for CHANNELS keeps measuring the same thing it did before.
    var contentSeen by remember { mutableStateOf(false) }
    val hasContent = rows.itemCount > 0
    LaunchedEffect(hasContent) {
        if (hasContent && !contentSeen) {
            contentSeen = true
            CastivioTrace.instant(CastivioTrace.FIRST_CONTENT)
            PerformanceLog.firstContent()
        }
    }

    Box(
        modifier
            .clip(shape)
            .background(colors.backgroundElevated)
            .border(1.dp, colors.glassBorderSoft, shape),
    ) {
        if (rows.itemCount == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // "The query has not answered yet" and "there is nothing to answer
                // with" look identical on a television and mean opposite things, so
                // they are drawn differently here rather than both as an empty state.
                if (rows.loadState.refresh is LoadState.Loading) {
                    DelayedSpinner()
                } else {
                    EmptyState(
                        title = stringResource(R.string.channels_empty_title),
                        detail = stringResource(R.string.channels_empty_detail),
                        actionLabel = stringResource(R.string.browse_empty_action),
                        onAction = rows::refresh,
                    )
                }
            }
            return@Box
        }

        // Focus lands on the first row when the board opens, so a remote has somewhere
        // to be. Without it the first press of any arrow key goes nowhere, which on a
        // television reads as the screen having hung.
        val first = remember { FocusRequester() }
        LaunchedEffect(hasContent) { if (hasContent) runCatching { first.requestFocus() } }

        LazyColumn(
            Modifier.fillMaxSize().padding(m.wellPad / 2),
            contentPadding = PaddingValues(bottom = m.wellPad),
        ) {
            items(rows.itemCount, key = rows.itemKey { it.id }) { index ->
                val item = rows[index] ?: return@items
                val channel = item as? Channel ?: return@items
                ChannelRow(
                    channel = channel,
                    seed = index,
                    m = m,
                    onFocused = { onSelect(channel) },
                    onClick = { channel.asSelection()?.let(onPlay) },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    seed: Int,
    m: ChannelsMetrics,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = m.rowMin)
            .clip(shape)
            .background(if (focused) colors.secondary else Color.Transparent)
            .border(1.dp, if (focused) colors.focusRing else Color.Transparent, shape)
            .onFocusChanged {
                val now = it.isFocused || it.hasFocus
                focused = now
                if (now) onFocused()
            }
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = m.rowPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.rowPadH),
    ) {
        val ink = if (focused) colors.onSecondary else colors.onBackground

        Reading {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.rowPadH),
            ) {
                LogoTile(
                    initials = initialsOf(channel.title),
                    seed = seed,
                    modifier = Modifier.width(m.logoWidth).height(m.rowMin * LOGO_OF_ROW),
                )

                Text(
                    // Without the tag, because the tag is drawn beside it. `|FR| TF1 HD`
                    // reads as `|FR| TF1  ᴴᴰ` rather than repeating itself.
                    text = titleWithoutQuality(channel.title),
                    style = castivioChipStyle(m.frame.fsLabel),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                // The reference's quality tag, read out of the name the provider wrote.
                // Absent when the provider wrote none -- never inferred, and never a
                // default. See `ChannelTitle.kt`.
                qualityOf(channel.title)?.let { quality ->
                    Text(
                        text = quality.label,
                        style = castivioBodyStyle(m.frame.fsBody * QUALITY_OF_BODY),
                        color = when {
                            focused -> colors.onSecondary
                            quality == StreamQuality.SD -> colors.onBackgroundMuted
                            else -> colors.secondary
                        },
                        maxLines = 1,
                    )
                }

                Spacer(Modifier.weight(1f))
            }
        }

        // The number, on a plate. It is what a remote dials, so it is a token rather
        // than a column of text -- and it keeps the trailing edge in every language,
        // because the row is what mirrors and this is the row's end.
        NumberPlate(label = channel.numberLabel(), focused = focused, m = m)
    }
}

/**
 * The channel number, as the reference draws it: a pill, not a column.
 *
 * `----` for a provider that numbered nothing, which is what [Channel.numberLabel]
 * already said and is kept — a blank plate would read as a number that failed to load.
 */
@Composable
private fun NumberPlate(label: String, focused: Boolean, m: ChannelsMetrics) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius / 2)
    Box(
        Modifier
            .width(m.numberWidth)
            .height(m.numberHeight)
            .clip(shape)
            .background(if (focused) colors.secondary else colors.glassFill)
            .border(1.dp, if (focused) Color.Transparent else colors.glassBorderSoft, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = castivioBodyStyle(m.frame.fsBody),
            color = if (focused) colors.onSecondary else colors.secondary,
            maxLines = 1,
        )
    }
}

/* ----------------------------------------------------------- the player well */

/**
 * The third column: what is on the channel the remote is currently on.
 *
 * Everything in it is either real or absent. There is no invented programme, no
 * invented artwork and no invented duration — an absent guide draws the reference's own
 * "No Information" and an absent channel draws nothing at all.
 */
@Composable
private fun PlayerWell(
    shown: ChannelPreview,
    state: BrowseState,
    m: ChannelsMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.wellRadius)
    val channel = shown.channel

    Column(
        modifier
            .clip(shape)
            .background(colors.backgroundElevated)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(m.wellPad),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(CHANNELS_PREVIEW_ASPECT)
                .clip(RoundedCornerShape(m.previewRadius)),
        ) {
            if (channel != null) {
                LogoTile(
                    initials = initialsOf(channel.title),
                    seed = channel.id.hashCode(),
                    modifier = Modifier.fillMaxSize(),
                )
                Osd(
                    channel = channel,
                    guide = shown.guide,
                    m = m,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(m.osdPad),
                )
            } else {
                Box(
                    Modifier.fillMaxSize().background(colors.glassFill),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.channels_preview_none),
                        style = castivioBodyStyle(m.frame.fsBody),
                        color = colors.onBackgroundMuted,
                    )
                }
            }
        }

        Spacer(Modifier.height(m.guideGap))

        // The guide, where the reference puts it. Three rows at most -- what is on, and
        // what follows it. More would be the guide screen, which this is not.
        if (shown.schedule.isEmpty()) {
            Text(
                // Three different absences reach this line -- a channel with no guide
                // id, a guide never imported, a schedule that has run out -- and all
                // three are honestly "no information". None of them is an empty panel.
                text = stringResource(R.string.channels_no_information),
                style = castivioBodyStyle(m.frame.fsBody),
                color = colors.onBackgroundMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = m.nameGap),
            )
        } else {
            val nowId = shown.guide?.now?.let { it.startMs }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(m.guideGap),
            ) {
                shown.schedule.forEach { programme ->
                    GuideEntry(
                        programme = programme,
                        now = programme.startMs == nowId,
                        m = m,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(m.guideGap))

        // The provider. It is the one fact this column has room for that the board does
        // not state anywhere else, and it is real.
        state.providerLabel?.let { provider ->
            Text(
                text = provider,
                style = castivioBodyStyle(m.frame.fsBody),
                color = colors.onBackgroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The bar over the picture: which channel, what is on, and how far through it is.
 *
 * ## What it does not say
 *
 * The reference prints a resolution here — `1920 × 1080`. Castivio does not know it. A
 * stream's real geometry comes from the decoder after the player has opened it, and this
 * board has opened nothing; printing the tag from the channel's *name* in a slot that
 * looks like a measurement would be the one dishonest pixel on the screen. The number
 * and the tag are enough, and both are things the provider actually said.
 */
@Composable
private fun Osd(
    channel: Channel,
    guide: NowNext?,
    m: ChannelsMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)
    val programme = guide?.now

    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.scrim)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(horizontal = m.osdPad, vertical = m.osdPad / 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.osdGap),
    ) {
        Reading {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.osdGap / 2),
                ) {
                    Text(
                        text = titleWithoutQuality(channel.title),
                        style = castivioTitleStyle(m.fsChannelName),
                        color = colors.onBackgroundStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    qualityOf(channel.title)?.let {
                        Text(
                            text = it.label,
                            style = castivioBodyStyle(m.frame.fsBody * QUALITY_OF_BODY),
                            color = colors.secondary,
                            maxLines = 1,
                        )
                    }
                }

                Spacer(Modifier.height(m.nameGap / 2))
                Track(fraction = guide?.progressAt(System.currentTimeMillis()), m = m)
                Spacer(Modifier.height(m.nameGap / 2))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = programme?.startMs?.let(::clockLabel).orEmpty(),
                        style = castivioBodyStyle(m.frame.fsBody),
                        color = colors.onBackgroundVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = programme?.title ?: stringResource(R.string.channels_no_information),
                        style = castivioBodyStyle(m.frame.fsBody),
                        color = colors.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(horizontal = m.osdGap),
                    )
                    Text(
                        text = programme?.stopMs?.let(::clockLabel).orEmpty(),
                        style = castivioBodyStyle(m.frame.fsBody),
                        color = colors.onBackgroundVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        Text(
            text = channel.numberLabel(),
            style = castivioTitleStyle(m.fsChannelName),
            color = colors.secondary,
            maxLines = 1,
        )
    }
}

/**
 * One programme in the guide: what it is, what it is about, and when it runs.
 *
 * The current one carries the fill and the marker; the rest are quiet. That difference
 * is the whole grammar of this panel — a viewer arriving mid-scroll should be able to
 * tell what is on *now* without reading a clock.
 */
@Composable
private fun GuideEntry(
    programme: Programme,
    now: Boolean,
    m: ChannelsMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)

    Reading {
        Column(
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (now) colors.secondaryContainer else colors.glassFill)
                .border(1.dp, if (now) colors.selectedBorder else colors.glassBorderSoft, shape)
                .padding(horizontal = m.guidePad, vertical = m.guidePad / 2),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.osdGap / 2),
            ) {
                Icon(
                    if (now) Icons.Rounded.PlayArrow else Icons.Rounded.History,
                    contentDescription = null,
                    tint = if (now) colors.live else colors.onBackgroundMuted,
                    modifier = Modifier.size(Sizing.iconSm),
                )
                Text(
                    text = programme.title,
                    style = castivioChipStyle(m.frame.fsLabel),
                    color = if (now) colors.onBackgroundStrong else colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            programme.description?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = castivioBodyStyle(m.frame.fsBody),
                    color = colors.onBackgroundMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = m.nameGap / 2),
                )
            }

            Spacer(Modifier.height(m.nameGap / 2))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.osdGap),
            ) {
                Text(
                    text = clockLabel(programme.startMs),
                    style = castivioBodyStyle(m.frame.fsBody),
                    color = colors.onBackgroundVariant,
                    maxLines = 1,
                )
                Track(
                    // Only the programme that is on has a share to show. A bar under a
                    // future programme would be a progress claim about something that
                    // has not begun.
                    fraction = if (now) programme.progressAt(System.currentTimeMillis()) else null,
                    m = m,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = clockLabel(programme.stopMs),
                    style = castivioBodyStyle(m.frame.fsBody),
                    color = colors.onBackgroundVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * How far through a programme we are, as a bar.
 *
 * One declaration for the two places the board shows elapsed time — the bar over the
 * picture and the bar under the programme that is on — because two copies of "red means
 * what has already gone" is two chances for them to stop agreeing.
 *
 * The share is read once, from the guide's own timestamps. It does not tick: a bar that
 * animated would be a second clock disagreeing with the header's. Null draws the empty
 * track, which is the honest shape for a programme that has not started.
 */
@Composable
private fun Track(fraction: Float?, m: ChannelsMetrics, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(m.trackHeight)
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.divider),
    ) {
        if (fraction != null) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(colors.live),
            )
        }
    }
}

/* The four stream facts -- `1080p`, `16:9`, `H.264`, `Dolby Audio` -- are gone.
 *
 * They were the only placeholders on this board, and the approved design puts the
 * guide in the space they occupied. Every one of them is a property of a *decoded*
 * stream, known to the engine's `Format` after the player has opened it and to nothing
 * on a screen that has opened nothing. Where the reference prints a resolution, `Osd`
 * now prints the channel number instead -- a fact the provider actually gave us. */

@Composable
private fun Badge(
    text: String,
    fill: Color,
    ink: Color,
    m: ChannelsMetrics,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(m.previewRadius / 2))
            .background(fill)
            .padding(horizontal = m.badgePadH, vertical = m.badgePadV),
    ) {
        Text(text = text, style = castivioBodyStyle(m.frame.fsBody), color = ink, maxLines = 1)
    }
}

/* --------------------------------------------------------------- the remote */

/**
 * What the coloured keys do, as the reference states them.
 *
 * A legend rather than a control strip: these are what the **remote's** keys do, and a
 * legend that also accepted focus would put four more stops between a viewer and the
 * list. The one exception is back, which a thumb has no key for — so it is pressable
 * and nothing else here is.
 */
@Composable
private fun RemoteBar(m: ChannelsMetrics, favorite: Boolean, onBack: () -> Unit) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.wellRadius)

    Row(
        Modifier
            .fillMaxWidth()
            .height(m.remote)
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(horizontal = m.remoteGapInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.remoteGapInner),
    ) {
        RemoteKey(
            dot = colors.danger,
            label = stringResource(R.string.channels_key_back),
            m = m,
            onClick = onBack,
        )
        RemoteKey(dot = colors.success, label = stringResource(R.string.channels_key_options), m = m)
        RemoteKey(dot = colors.warning, label = stringResource(R.string.channels_key_sort), m = m)
        RemoteKey(
            dot = colors.primary,
            label = stringResource(
                if (favorite) R.string.channels_key_unfavorite else R.string.channels_key_favorite,
            ),
            m = m,
        )

        NamedKey(name = stringResource(R.string.channels_key_info_name),
            label = stringResource(R.string.channels_key_info), m = m)
        NamedKey(name = stringResource(R.string.channels_key_menu_name),
            label = stringResource(R.string.channels_key_menu), m = m)

        Spacer(Modifier.weight(1f))

        Icon(
            Icons.Rounded.Menu,
            contentDescription = null,
            tint = colors.onBackgroundMuted,
            modifier = Modifier.size(Sizing.iconMd),
        )
    }
}

@Composable
private fun RemoteKey(
    dot: Color,
    label: String,
    m: ChannelsMetrics,
    onClick: (() -> Unit)? = null,
) {
    val colors = CastivioTheme.colors
    Row(
        Modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = m.badgePadV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.factGap),
    ) {
        Box(Modifier.size(m.remoteDot).clip(RoundedCornerShape(Radius.pill)).background(dot))
        Text(
            text = label,
            style = castivioChipStyle(m.frame.fsChip),
            color = colors.onBackground,
            maxLines = 1,
        )
    }
}

@Composable
private fun NamedKey(name: String, label: String, m: ChannelsMetrics) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius / 2)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.factGap),
    ) {
        Box(
            Modifier
                .clip(shape)
                .border(1.dp, colors.edgeQuiet, shape)
                .padding(horizontal = m.remoteKeyPadH, vertical = m.remoteKeyPadV),
        ) {
            Text(
                text = name,
                style = castivioBodyStyle(m.frame.fsBody),
                color = colors.onBackground,
                maxLines = 1,
            )
        }
        Text(
            text = label,
            style = castivioChipStyle(m.frame.fsChip),
            color = colors.onBackground,
            maxLines = 1,
        )
    }
}

/* --------------------------------------------------------------- the pieces */

/**
 * Focus as a value and a modifier, without a second `remember` per call site.
 *
 * `rememberIsFocused` in the design system returns a `State<Boolean>`; this board reads
 * the flag inside layout modifiers where a plain `Boolean` composes more cleanly, so the
 * pair is unwrapped once here rather than at seven call sites.
 */
@Composable
private fun rememberFocusFlag(): Pair<Boolean, Modifier> {
    var focused by remember { mutableStateOf(false) }
    return focused to Modifier.onFocusChanged { focused = it.isFocused || it.hasFocus }
}

/**
 * `0001`, from the provider's own numbering, or the row's id where it numbered nothing.
 *
 * Four digits because that is what the reference shows and what a `Goto Number` entry
 * expects; a provider that numbers past 9999 keeps its own digits rather than being
 * truncated into somebody else's channel.
 */
private fun Channel.numberLabel(): String =
    number?.let { "%04d".format(it) } ?: NO_NUMBER

/**
 * Which icon a category gets.
 *
 * Matched on the provider's own words, because that is the only thing a category has:
 * `MediaGroup` is an id, a name and a kind. A name nothing matches gets the neutral
 * globe rather than a wrong guess — an icon that is confidently wrong is worse than one
 * that says nothing.
 */
private fun MediaGroup.icon(): ImageVector {
    val lower = name.lowercase()
    return when {
        lower.containsAny("sport", "رياض", "futbol", "calcio") -> Icons.Rounded.SportsSoccer
        lower.containsAny("news", "أخبار", "noticias", "info") -> Icons.Rounded.Newspaper
        lower.containsAny("movie", "cinema", "film", "أفلام") -> Icons.Rounded.Movie
        lower.containsAny("kid", "child", "cartoon", "أطفال") -> Icons.Rounded.ChildCare
        lower.containsAny("music", "موسيق", "musica") -> Icons.Rounded.MusicNote
        lower.containsAny("doc", "وثائق", "nature") -> Icons.Rounded.MenuBook
        lower.containsAny("entertain", "ترفيه", "general", "variety") -> Icons.Rounded.TheaterComedy
        else -> Icons.Rounded.Public
    }
}

private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it) }

/**
 * Up to two initials for a channel with no artwork.
 *
 * Words rather than characters, so `Sky Sports Football` is `SS` and not `Sk`. A title
 * of one word gives one letter; a title of none gives the placeholder a shape rather
 * than an empty tile.
 */
internal fun initialsOf(title: String): String =
    title.split(' ', '-', '_')
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
        .ifEmpty { NO_NUMBER.take(1) }

/** `13:30`, in the device's own format. */
private fun clockLabel(epochMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))

/** How the order reads in this board's toolbar, which states a value rather than a verb. */
private val SortOrder.channelsLabel: Int
    get() = when (this) {
        SortOrder.PROVIDER -> R.string.channels_sort_number
        SortOrder.NAME_ASC -> R.string.browse_sort_name_asc
        SortOrder.NAME_DESC -> R.string.browse_sort_name_desc
        SortOrder.RECENTLY_ADDED -> R.string.browse_sort_recent
    }

/**
 * The four stream facts the reference shows, which nothing in Castivio can yet answer.
 *
 * Here as one list so that "what on this board is not real" is a question grep answers
 * in one hit, and so the day the engine reports a `Format` there is one place to delete.
 */

/** Shown where a provider numbered nothing. */
private const val NO_NUMBER = "----"

/** Stable across a re-import, unlike any group id, so these rows never re-animate. */
private const val RAIL_ALL = "castivio.rail.all"
private const val RAIL_FAVORITES = "castivio.rail.favorites"
private const val RAIL_RECENT = "castivio.rail.recent"
private const val RAIL_DIVIDER = "castivio.rail.divider"

/** The search field's height as a share of the toolbar it sits in — 40 of 54 in the reference. */
private const val SEARCH_OF_TOOLBAR = 40f / 54f

/** The logo plate's height as a share of the row — 28 of 68 in the reference. */
private const val LOGO_OF_ROW = 28f / 68f

/** How much of the rail's width the row's category line is allowed — 120 of 302. */
/**
 * The quality tag, as a share of the body step.
 *
 * Smaller than the name it sits beside, which is what makes it read as a tag rather than
 * as part of the channel's title. The reference sets it as a superscript; a smaller step
 * on the same baseline is the same claim without a typographic trick that Compose would
 * have to fake.
 */
private const val QUALITY_OF_BODY = 0.84f
