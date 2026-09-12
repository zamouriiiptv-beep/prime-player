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
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
 * Every number and every row on this board is real except the four marked
 * [PLACEHOLDER_STREAM_FACTS], which are stated below and nowhere else:
 *
 * | reference element | source |
 * |---|---|
 * | channel number, name, stream | `Channel` from the paged reader |
 * | the category rail | `CatalogRepository.groups` |
 * | `Total: n Channels` | an indexed `COUNT`, never a list measured |
 * | the programme and its timeline | `EpgRepository.nowNext` for the selected channel |
 * | the star, and `Favorites` | `FavoritesRepository` |
 * | the header's two cards and clock | the same `DashboardHeader` Home draws |
 *
 * ## Two things the reference shows that this deliberately does not invent
 *
 *  1. **A per-category count.** `MediaGroup` carries no count and the schema has no
 *     denormalised one, so there is nothing to read. Counting each category would be a
 *     query per row of the rail, re-run on every write during an import — which is the
 *     specific shape `CLAUDE.md` forbids. The rail therefore shows a count only where
 *     one exists, and no digit where one does not.
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
 * The fetch outranks the columns for the same reason it does in `BrowseScreen` — a
 * section being downloaded for the first time has no rows, and a board that drew its
 * empty state over that would tell a user their provider carries no television while
 * their television was arriving.
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
        // Debug builds only, and drawn before anything else so its own first
        // composition is the board's first frame -- which is what TTID means.
        PerformancePanel()

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
            fetch is SectionLoad.Loading && !hasRows -> Fetching(fetch, m, Modifier.weight(1f))

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
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One line about a fetch that is running or has failed, over a board that has rows.
 *
 * Drawn only when there is something to say and something already on screen to say it
 * over. It is deliberately not a dialog and not a full-screen state: the catalogue
 * behind it is usable, and interrupting a working screen to report a background refresh
 * is how a user learns to dismiss messages without reading them.
 */
@Composable
private fun FetchNotice(
    fetch: SectionLoad?,
    m: ChannelsMetrics,
    onRetry: () -> Unit,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)

    val text = when (fetch) {
        is SectionLoad.Loading -> stringResource(
            R.string.channels_notice_refreshing,
            formatCount(fetch.items),
        )
        is SectionLoad.Failed -> stringResource(R.string.channels_notice_failed)
        else -> return
    }
    val failed = fetch is SectionLoad.Failed

    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = m.toolbarGap)
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, if (failed) colors.selectedBorder else colors.glassBorderSoft, shape)
            .then(if (failed) Modifier.clickable(onClick = onRetry) else Modifier)
            .padding(horizontal = m.rowPadH, vertical = m.badgePadV * 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.factGap),
    ) {
        Text(
            text = text,
            style = castivioBodyStyle(m.frame.fsBody),
            color = if (failed) colors.onBackground else colors.onBackgroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (failed) {
            Text(
                text = stringResource(R.string.browse_fetch_retry),
                style = castivioBodyStyle(m.frame.fsBody),
                color = colors.primary,
                maxLines = 1,
            )
        }
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
@Composable
private fun Columns(
    state: BrowseState,
    shown: ChannelPreview,
    m: ChannelsMetrics,
    model: BrowseViewModel,
    previewModel: ChannelsViewModel,
    onPlay: (CatalogSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = model.items.collectAsLazyPagingItems()

    Row(modifier.fillMaxWidth()) {
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
                // No count: nothing in the schema holds one, and a query per rail row
                // re-run on every import write is exactly what the data rules forbid.
                count = null,
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
        Text(
            text = label,
            style = castivioChipStyle(m.frame.fsLabel),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(
                text = formatCount(count),
                style = castivioBodyStyle(m.frame.fsBody),
                color = ink,
                maxLines = 1,
            )
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
                    category = channel.groupId?.let { state.categoryNames[it] },
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
    category: String?,
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

        Text(
            text = channel.numberLabel(),
            style = castivioChipStyle(m.frame.fsLabel),
            color = if (focused) colors.onSecondary else colors.onBackgroundVariant,
            maxLines = 1,
            modifier = Modifier.width(m.numberWidth),
        )

        LogoTile(
            initials = initialsOf(channel.title),
            seed = seed,
            modifier = Modifier.width(m.logoWidth).height(m.rowMin * LOGO_OF_ROW),
        )

        Text(
            text = channel.title,
            style = castivioChipStyle(m.frame.fsLabel),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // The reference's quality tag. `Channel` carries no such field and the schema
        // has no column for one -- a stream's resolution is something the decoder learns
        // when it opens the stream, which has not happened for a row nobody pressed. So
        // the category the provider filed the channel under goes here instead: a fact
        // the app already holds, in the place the reference puts a fact.
        if (category != null) {
            Text(
                text = category,
                style = castivioBodyStyle(m.frame.fsBody),
                color = if (focused) colors.onSecondary else colors.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(m.rail * CATEGORY_OF_RAIL),
            )
        }

        Icon(
            Icons.Rounded.StarBorder,
            contentDescription = null,
            tint = if (focused) colors.onSecondary else colors.onBackgroundMuted,
            modifier = Modifier.size(Sizing.iconMd),
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
                Badge(
                    text = stringResource(R.string.channels_live),
                    fill = colors.live,
                    ink = colors.onPrimary,
                    m = m,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(m.wellPad),
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

        Text(
            text = channel?.title ?: stringResource(R.string.channels_preview_none),
            style = castivioTitleStyle(m.fsChannelName),
            color = colors.onBackgroundStrong,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = m.nameGap * 2),
        )

        Text(
            // The guide's own title where there is one, and the reference's own
            // sentence where there is not. Three different absences reach this line --
            // a channel with no guide id, a guide never imported, a programme that has
            // ended -- and all three are honestly "no information".
            text = shown.guide?.now?.title ?: stringResource(R.string.channels_no_information),
            style = castivioBodyStyle(m.frame.fsBody),
            color = colors.onBackgroundMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = m.nameGap),
        )

        Spacer(Modifier.height(m.timelineGap))

        Timeline(guide = shown.guide, m = m)

        Spacer(Modifier.height(m.nameGap * 2))

        StreamFacts(m)

        Spacer(Modifier.weight(1f))

        // The provider, where the reference leaves the well's lower area empty. It is
        // the one fact this column has room for that the board does not state anywhere
        // else, and it is real.
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
 * The programme's start, its end, and how far through it is.
 *
 * Drawn only when there is a programme. A rail with no times under it would be a
 * control implying a seek on a stream nobody has opened.
 */
@Composable
private fun Timeline(guide: NowNext?, m: ChannelsMetrics) {
    val colors = CastivioTheme.colors
    val programme = guide?.now

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = programme?.startMs?.let(::clockLabel).orEmpty(),
                style = castivioBodyStyle(m.frame.fsBody),
                color = colors.onBackgroundVariant,
                maxLines = 1,
            )
            Text(
                text = programme?.stopMs?.let(::clockLabel).orEmpty(),
                style = castivioBodyStyle(m.frame.fsBody),
                color = colors.onBackgroundVariant,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(m.nameGap))

        // The elapsed share is read once, from the guide's own timestamps. It does not
        // tick: a bar that animated would be a second clock disagreeing with the header's.
        val fraction = guide?.progressAt(System.currentTimeMillis())

        Box(
            Modifier
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
                        .background(colors.secondary),
                )
            }
        }
    }
}

/**
 * The reference's four stream facts — **placeholders, and the only ones on this board**.
 *
 * `1080p`, `16:9`, `H.264` and `Dolby Audio` are properties of a decoded stream. Nothing
 * in Castivio knows any of them for a channel nobody has pressed: they arrive from the
 * engine's `Format` after the player has opened the stream, and this board has not
 * opened one. They are drawn in the muted ink rather than the live one and are named by
 * [PLACEHOLDER_STREAM_FACTS] so there is exactly one place to delete when the player
 * starts reporting them.
 */
@Composable
private fun StreamFacts(m: ChannelsMetrics) {
    Row(horizontalArrangement = Arrangement.spacedBy(m.factGap)) {
        PLACEHOLDER_STREAM_FACTS.forEach { fact -> FactChip(text = fact, m = m) }
    }
}

@Composable
private fun FactChip(text: String, m: ChannelsMetrics) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius / 2)
    Box(
        Modifier
            .height(m.factChip)
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(horizontal = m.badgePadH * 2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = castivioBodyStyle(m.frame.fsBody),
            color = colors.onBackgroundMuted,
            maxLines = 1,
        )
    }
}

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

/* --------------------------------------------------------------- the states */

/** A section arriving, with the counts it is arriving at. Same wording `BrowseScreen` uses. */
@Composable
private fun Fetching(fetch: SectionLoad.Loading, m: ChannelsMetrics, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DelayedSpinner()
        Text(
            text = stringResource(
                R.string.browse_fetch_title,
                stringResource(R.string.browse_live),
            ),
            style = castivioChipStyle(m.frame.fsLabel),
            color = colors.onBackgroundStrong,
            modifier = Modifier.padding(top = m.headerGap),
        )
        Text(
            text = stringResource(
                R.string.browse_fetch_progress,
                formatCount(fetch.items),
                formatCount(fetch.groups),
            ),
            style = castivioBodyStyle(m.frame.fsBody),
            color = colors.onBackgroundVariant,
            modifier = Modifier.padding(top = m.nameGap),
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
private val PLACEHOLDER_STREAM_FACTS = listOf("1080p", "16:9", "H.264", "Dolby Audio")

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
private const val CATEGORY_OF_RAIL = 120f / 302f
