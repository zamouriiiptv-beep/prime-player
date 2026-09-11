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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.castivio.core.common.EmptyReason
import com.castivio.core.design.components.CardShape
import com.castivio.core.design.components.CastivioChip
import com.castivio.core.design.components.ChannelCard
import com.castivio.core.design.components.DelayedSpinner
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.ErrorState
import com.castivio.core.design.components.MediaCard
import com.castivio.core.design.components.SectionHeader
import com.castivio.core.design.components.Skeleton
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.components.formatCount
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.castivioStage
import com.castivio.core.platform.CastivioTrace
import com.castivio.domain.Channel
import com.castivio.domain.MediaItem
import com.castivio.domain.SectionLoad
import com.castivio.domain.SeriesSummary
import com.castivio.domain.SortOrder

/**
 * One section of the catalogue, categories first.
 *
 * The structure is the one `UI_ARCHITECTURE.md` §3.4 fixes and the approved mockup
 * draws: categories on the leading side, content on the trailing side, and on a phone
 * the same two things stacked as a chip row above a list. There is no hero and no
 * carousel — an IPTV catalogue is something people navigate, not something they are
 * shown.
 *
 * Every row here is real. The rows come from the paged reader, the counts from an
 * indexed `COUNT`, and a press carries the provider's own stream URL out to whoever
 * composed the player.
 */
@Composable
fun BrowseScreen(
    section: CatalogSection,
    onPlay: (CatalogSelection) -> Unit,
    onOpenShow: (SeriesSummary) -> Unit,
    /**
     * Leave for search, from inside a section.
     *
     * The reference players put a search field in this header, and a field here would
     * be a second search — a second query, a second debounce, a second set of empty
     * states — beside the one `CatalogSearchScreen` already runs across the whole
     * catalogue. So the header carries the *way in* rather than the field: one press,
     * one search, and the results still span every section, which is what someone
     * typing a film name in Live TV actually wanted.
     */
    onSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * Keyed by section, which is what gives each one its own selection.
     *
     * Four sections share this composable; without the key they would share one
     * holder as well, and opening Movies would move the category Live TV was on.
     */
    model: BrowseViewModel = hiltViewModel(key = section.name),
) {
    // Told once, and idempotently: composition runs again for reasons that are not a
    // change of section, and clearing the category on each of them would fight the user.
    LaunchedEffect(section) { model.show(section) }

    val state by model.state.collectAsStateWithLifecycle()
    val tv = CastivioTheme.device.isTv

    // The surface, measured, and every size on this screen derived from it. It used to
    // be `DeviceClass`: two numbers chosen by what kind of box this is -- a screen
    // padding and a column count -- with every other size a fixed token shared by a
    // 360dp handset and a 55-inch television.
    BoxWithConstraints(modifier.fillMaxSize().statusBarsPadding()) {
    val m = catalogMetricsFor(tv = tv, width = maxWidth, height = maxHeight)

    Column(
        Modifier
            .fillMaxSize()
            .castivioStage(m.frame),
        verticalArrangement = Arrangement.spacedBy(m.bandGap),
    ) {
        SectionHeader(
            title = stringResource(section.label),
            count = state.total,
            titleStyle = castivioTitleStyle(m.frame.fsTitle),
            countStyle = castivioBodyStyle(m.frame.fsBody),
            gap = m.rowGap,
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(m.rowGap)) {
                    // Not on Series. That section pages *shows*, aggregated by SQL from
                    // their episodes, and `CatalogPager.series` reads one fixed order —
                    // so a control here would be a control that does nothing. An
                    // affordance that lies is worse than one that is absent.
                    if (section != CatalogSection.Series) {
                        CastivioChip(
                            text = stringResource(R.string.browse_sort, stringResource(state.sort.label)),
                            onClick = { model.sortBy(state.sort.next()) },
                            icon = Icons.Rounded.Sort,
                            labelStyle = castivioChipStyle(m.frame.fsChip),
                            padH = m.entryPadH,
                            padV = m.entryPadV,
                        )
                    }
                    CastivioChip(
                        text = stringResource(R.string.search_label),
                        onClick = onSearch,
                        icon = Icons.Rounded.Search,
                        labelStyle = castivioChipStyle(m.frame.fsChip),
                        padH = m.entryPadH,
                        padV = m.entryPadV,
                    )
                }
            },
        )

        if (m.twoPane) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(m.paneGap),
            ) {
                CategoryColumn(
                    state = state,
                    m = m,
                    onChoose = model::choose,
                    modifier = Modifier.width(m.pane),
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Pane(section, state, m, model, onPlay, onOpenShow)
                }
            }
        } else {
            CategoryChips(state = state, m = m, onChoose = model::choose)
            Box(Modifier.fillMaxSize()) {
                Pane(section, state, m, model, onPlay, onOpenShow)
            }
        }
    }
    }
}

/**
 * The categories, as the tall pane a remote steps down.
 *
 * "All" is first and is a category like any other — a pane whose first entry is a
 * real group leaves no way back to the whole section without a second control.
 */
@Composable
private fun CategoryColumn(
    state: BrowseState,
    m: CatalogMetrics,
    onChoose: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(m.entryGap),
        contentPadding = PaddingValues(bottom = m.listBottom),
    ) {
        item(key = ALL_CATEGORIES) {
            CategoryEntry(
                label = stringResource(R.string.browse_all_categories),
                count = state.total,
                selected = state.selectedGroup == null,
                m = m,
                onClick = { onChoose(null) },
            )
        }
        items(state.groups, key = { it.id }) { group ->
            CategoryEntry(
                label = group.name,
                count = null,
                selected = group.id == state.selectedGroup,
                m = m,
                onClick = { onChoose(group.id) },
            )
        }
    }
}

/**
 * The same choice on a phone, where a 168dp pane would be a third of the screen.
 *
 * A horizontal row rather than a drawer: a category change is the most frequent move
 * in this screen, and putting it behind a button costs two presses every time.
 */
@Composable
private fun CategoryChips(state: BrowseState, m: CatalogMetrics, onChoose: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(m.rowGap)) {
        item(key = ALL_CATEGORIES) {
            CategoryEntry(
                label = stringResource(R.string.browse_all_categories),
                count = state.total,
                selected = state.selectedGroup == null,
                m = m,
                pill = true,
                onClick = { onChoose(null) },
            )
        }
        items(state.groups, key = { it.id }) { group ->
            CategoryEntry(
                label = group.name,
                count = null,
                selected = group.id == state.selectedGroup,
                m = m,
                pill = true,
                onClick = { onChoose(group.id) },
            )
        }
    }
}

@Composable
private fun CategoryEntry(
    label: String,
    count: Int?,
    selected: Boolean,
    m: CatalogMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pill: Boolean = false,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (pill) Radius.pill else Radius.md)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.rowGap),
        modifier = modifier
            .then(if (pill) Modifier else Modifier.fillMaxWidth())
            // A category is the most frequently pressed thing on this screen and it
            // was sized by its own padding alone -- which on a short surface put it
            // under the floor a thumb needs, and on every surface under the one a
            // remote needs. The padding still decides the width.
            .heightIn(min = m.rowMin)
            .clip(shape)
            .background(if (selected) colors.glassFillStrong else colors.glassFill)
            .border(1.dp, if (selected) colors.glassBorder else colors.glassBorderSoft, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = m.entryPadH, vertical = m.entryPadV),
    ) {
        Text(
            label,
            style = castivioChipStyle(m.frame.fsLabel),
            color = if (selected) colors.onBackground else colors.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (pill) Modifier else Modifier.weight(1f),
        )
        if (count != null) {
            Text(
                formatCount(count),
                style = castivioBodyStyle(m.frame.fsBody),
                color = colors.onBackgroundMuted,
            )
        }
    }
}

/**
 * The section, or the reason it is not here yet.
 *
 * The fetch outranks the rows, and it has to: a section being downloaded for the
 * first time has no rows, and the pager cannot tell that apart from a provider that
 * carries none. Before this existed the two rendered the same sentence — "your
 * provider carries no Movies" — over a section that was thirty seconds from arriving.
 */
@Composable
private fun Pane(
    section: CatalogSection,
    state: BrowseState,
    m: CatalogMetrics,
    model: BrowseViewModel,
    onPlay: (CatalogSelection) -> Unit,
    onOpenShow: (SeriesSummary) -> Unit,
) {
    when (val fetch = state.fetch) {
        is SectionLoad.Loading -> Fetching(section, fetch, m)
        is SectionLoad.Failed -> FetchFailed(section, fetch, onRetry = { model.retryFetch() })
        // Ready, Done, NoSource and "not asked yet" all mean: draw what is stored.
        // NoSource included -- a section with no provider behind it is empty for a
        // reason the section's own empty state already explains.
        else -> Content(section, state, m, model, onPlay, onOpenShow)
    }
}

/**
 * A section arriving, with the numbers it is arriving at.
 *
 * Counts rather than a percentage, because there is no denominator: the provider does
 * not say how many films it has until it has sent them. A rising count is honest and
 * a fake progress bar is not.
 */
@Composable
private fun Fetching(section: CatalogSection, fetch: SectionLoad.Loading, m: CatalogMetrics) {
    val colors = CastivioTheme.colors
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        DelayedSpinner()
        Text(
            text = stringResource(R.string.browse_fetch_title, stringResource(section.label)),
            style = castivioChipStyle(m.frame.fsLabel),
            color = colors.onBackgroundStrong,
            modifier = Modifier.padding(top = m.bandGap),
        )
        Text(
            text = stringResource(
                R.string.browse_fetch_progress,
                formatCount(fetch.items),
                formatCount(fetch.groups),
            ),
            style = castivioBodyStyle(m.frame.fsBody),
            color = colors.onBackgroundVariant,
            modifier = Modifier.padding(top = m.entryGap),
        )
        Text(
            text = stringResource(R.string.browse_fetch_once),
            style = castivioBodyStyle(m.frame.fsBody),
            color = colors.onBackgroundMuted,
            modifier = Modifier.padding(top = m.rowGap),
        )
    }
}

/** A section that did not arrive, and whether asking again is worth anything. */
@Composable
private fun FetchFailed(section: CatalogSection, fetch: SectionLoad.Failed, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ErrorState(
            title = stringResource(R.string.browse_fetch_failed, stringResource(section.label)),
            detail = stringResource(
                if (fetch.retryable) R.string.browse_fetch_failed_retryable
                else R.string.browse_fetch_failed_final,
            ),
            actionLabel = stringResource(R.string.browse_fetch_retry),
            onAction = onRetry,
        )
    }
}

/**
 * The rows, and the two things that are not rows.
 *
 * The pager is collected inside the branch that uses it rather than above the `when`,
 * so a section reads one query and not two: Series pages shows, everything else pages
 * items, and neither opens a cursor the screen will never read.
 *
 * Loading and empty are drawn here rather than left to whichever branch remembers,
 * because "the grid is empty" and "the grid has not answered yet" look identical on a
 * television and mean opposite things.
 */
@Composable
private fun Content(
    section: CatalogSection,
    state: BrowseState,
    m: CatalogMetrics,
    model: BrowseViewModel,
    onPlay: (CatalogSelection) -> Unit,
    onOpenShow: (SeriesSummary) -> Unit,
) {
    if (section == CatalogSection.Series) {
        val shows = model.shows.collectAsLazyPagingItems()
        Paged(shows, section, state, m) { ShowGrid(shows, m, onOpenShow) }
    } else {
        val rows = model.items.collectAsLazyPagingItems()
        Paged(rows, section, state, m) {
            if (section == CatalogSection.Live || section == CatalogSection.Radio) {
                ChannelList(rows, state.categoryNames, m, onPlay)
            } else {
                ItemGrid(rows, m, onPlay)
            }
        }
    }
}

/** Skeletons, an explained emptiness, or the caller's rows — in that order. */
@Composable
private fun <T : Any> Paged(
    paged: LazyPagingItems<T>,
    section: CatalogSection,
    state: BrowseState,
    m: CatalogMetrics,
    rows: @Composable () -> Unit,
) {
    // The instant this screen first has something a viewer can act on -- the metric
    // Phase A called *time to first useful content*, and the one it could not derive
    // from any other. Emitted once per section: `remember` is keyed by the composable's
    // position, so leaving Movies and coming back is a new screen and a new first row.
    //
    // A `LaunchedEffect` draws nothing and returns no value. The composition below is
    // the composition that was there before it.
    var contentSeen by remember { mutableStateOf(false) }
    val hasContent = paged.itemCount > 0
    LaunchedEffect(hasContent) {
        if (hasContent && !contentSeen) {
            contentSeen = true
            CastivioTrace.instant(CastivioTrace.FIRST_CONTENT)
        }
    }

    when {
        paged.itemCount > 0 -> rows()

        paged.loadState.refresh is LoadState.Loading -> LoadingRows(m)

        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SectionEmpty(section = section, state = state, onRetry = paged::refresh)
        }
    }
}

/**
 * The shape of what is coming, not a spinner.
 *
 * Rows rather than cards because both grids and the channel list arrive top to bottom:
 * a skeleton that does not sit where the content will lands as a second layout change
 * the moment the query answers.
 */
@Composable
private fun LoadingRows(m: CatalogMetrics) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(m.rowGap),
    ) {
        repeat(SKELETON_ROWS) { Skeleton(height = m.skeleton, modifier = Modifier.fillMaxWidth()) }
    }
}

/**
 * Channels as a list, not a grid.
 *
 * A channel is chosen by its name and what is on it, and both are text. A poster wall
 * of identical logos is the shape every mediocre IPTV player uses and the shape that
 * makes 900 channels unreadable.
 */
@Composable
private fun ChannelList(
    rows: LazyPagingItems<MediaItem>,
    /**
     * Group id to name, for the row's second line.
     *
     * The guide is not wired to this screen yet, so the honest second line is the
     * category the provider filed the channel under — a fact the app already holds,
     * rather than a blank line where "now playing" will go.
     */
    categories: Map<String, String>,
    m: CatalogMetrics,
    onPlay: (CatalogSelection) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(m.rowGap),
        contentPadding = PaddingValues(bottom = m.listBottom),
    ) {
        items(rows.itemCount, key = rows.itemKey { it.id }) { index ->
            val item = rows[index] ?: return@items
            val selection = item.asSelection() ?: return@items
            ChannelCard(
                name = item.title,
                nowPlaying = (item as? Channel)?.groupId?.let { categories[it] }.orEmpty(),
                number = selection.channelNumber,
                seed = index,
                onClick = { onPlay(selection) },
                modifier = Modifier.fillMaxWidth(),
                logo = m.logo,
                pad = m.cardPad,
                minHeight = m.rowMin,
                nameStyle = castivioChipStyle(m.frame.fsLabel),
                captionStyle = castivioBodyStyle(m.frame.fsBody),
            )
        }
    }
}

@Composable
private fun ItemGrid(
    rows: LazyPagingItems<MediaItem>,
    m: CatalogMetrics,
    onPlay: (CatalogSelection) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(m.columns),
        horizontalArrangement = Arrangement.spacedBy(m.gutter),
        verticalArrangement = Arrangement.spacedBy(m.gutter),
        contentPadding = PaddingValues(bottom = m.listBottom),
    ) {
        items(rows.itemCount, key = rows.itemKey { it.id }) { index ->
            val item = rows[index] ?: return@items
            val selection = item.asSelection() ?: return@items
            MediaCard(
                title = item.title,
                subtitle = selection.subtitle,
                shape = CardShape.Poster,
                artworkSeed = index,
                onClick = { onPlay(selection) },
                modifier = Modifier.fillMaxWidth(),
                gap = m.entryGap,
                titleStyle = castivioChipStyle(m.frame.fsLabel),
                captionStyle = castivioBodyStyle(m.frame.fsBody),
            )
        }
    }
}

@Composable
private fun ShowGrid(
    shows: LazyPagingItems<SeriesSummary>,
    m: CatalogMetrics,
    onOpenShow: (SeriesSummary) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(m.columns),
        horizontalArrangement = Arrangement.spacedBy(m.gutter),
        verticalArrangement = Arrangement.spacedBy(m.gutter),
        contentPadding = PaddingValues(bottom = m.listBottom),
    ) {
        items(shows.itemCount, key = shows.itemKey { it.seriesId }) { index ->
            val show = shows[index] ?: return@items
            MediaCard(
                title = show.title,
                subtitle = stringResource(R.string.browse_episode_count, show.episodeCount),
                shape = CardShape.Poster,
                artworkSeed = index,
                onClick = { onOpenShow(show) },
                modifier = Modifier.fillMaxWidth(),
                gap = m.entryGap,
                titleStyle = castivioChipStyle(m.frame.fsLabel),
                captionStyle = castivioBodyStyle(m.frame.fsBody),
            )
        }
    }
}

/**
 * Why this is empty, and what to do about it.
 *
 * Two different sentences, because the two situations are different: a category the
 * provider left empty is not the same as a provider that carries no radio at all, and
 * a user who is told the second one stops looking for the first.
 */
@Composable
private fun SectionEmpty(section: CatalogSection, state: BrowseState, onRetry: () -> Unit) {
    val reason = if (state.selectedGroup != null) {
        EmptyReason.CATEGORY_EMPTY
    } else {
        EmptyReason.PROVIDER_HAS_NO_CONTENT
    }
    val provider = state.providerLabel ?: stringResource(R.string.browse_your_provider)
    EmptyState(
        title = when (reason) {
            EmptyReason.CATEGORY_EMPTY -> stringResource(R.string.browse_empty_category_title)
            else -> stringResource(R.string.browse_empty_section_title, provider, stringResource(section.label))
        },
        detail = when (reason) {
            EmptyReason.CATEGORY_EMPTY -> stringResource(R.string.browse_empty_category_detail)
            else -> stringResource(R.string.browse_empty_section_detail)
        },
        actionLabel = stringResource(R.string.browse_empty_action),
        onAction = onRetry,
    )
}

/** Stable across a re-import, unlike any group id, so the "all" row never re-animates. */
private const val ALL_CATEGORIES = "castivio.all"

/** Enough placeholder rows to fill a television without pretending to know the count. */
private const val SKELETON_ROWS = 8

/**
 * The next order in the cycle.
 *
 * A cycling chip rather than a menu, because this is a television first: a menu is
 * open, move, choose, close — four presses for a choice between four values, three of
 * which a viewer will try in order anyway. The chip states the order it is in, so no
 * press is a guess.
 */
internal fun SortOrder.next(): SortOrder = when (this) {
    SortOrder.PROVIDER -> SortOrder.NAME_ASC
    SortOrder.NAME_ASC -> SortOrder.NAME_DESC
    SortOrder.NAME_DESC -> SortOrder.RECENTLY_ADDED
    SortOrder.RECENTLY_ADDED -> SortOrder.PROVIDER
}

/** How an order reads on the chip. */
private val SortOrder.label: Int
    get() = when (this) {
        SortOrder.PROVIDER -> R.string.browse_sort_provider
        SortOrder.NAME_ASC -> R.string.browse_sort_name_asc
        SortOrder.NAME_DESC -> R.string.browse_sort_name_desc
        SortOrder.RECENTLY_ADDED -> R.string.browse_sort_recent
    }

/** Its own name, as the user reads it. */
internal val CatalogSection.label: Int
    get() = when (this) {
        CatalogSection.Live -> R.string.browse_live
        CatalogSection.Movies -> R.string.browse_movies
        CatalogSection.Series -> R.string.browse_series
        CatalogSection.Radio -> R.string.browse_radio
    }
