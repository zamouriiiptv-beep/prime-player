package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.castivio.core.design.components.CardShape
import com.castivio.core.design.components.CastivioChip
import com.castivio.core.design.components.MediaCard
import com.castivio.core.design.components.PosterPrefetch
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.SectionLoad
import com.castivio.domain.SeriesSummary

/**
 * The shows, on the films' board: the same band over the same panel, the bouquets in the same rail on the leading
 * side, and a wall of posters where the channel list is -- one board for the channels, the films, the radio and
 * this, so what a viewer learned on one is true on the others.
 *
 * ## What is the films' and what is not
 *
 * The band, the rail, the wall's frame, the posters and their blurred colours, the prefetch and the order chip are
 * the films' own composables and constants -- see [MoviesScreen] -- given this section's words, so the two walls
 * cannot drift apart. A press on a poster opens the show's page, [ShowScreen], and starts nothing: the page is where
 * the seasons and Play are.
 *
 * What a show does not have is a viewer's own list. The rail lists *All* and the provider's categories, and not the
 * favourites or the ones watched last: a favourite and a watch are of a film or a channel -- the player records an
 * episode, and a show is not one -- so an entry that opened an empty list would be a control that does nothing, and
 * is not drawn.
 *
 * ## Only what the provider sent
 *
 * A show's card is its poster and its name. A provider sends a show as a name and a cover; its seasons and episodes
 * are fetched when the show is opened, so a card that said how many seasons it has would say nothing until then and
 * a number afterwards. None is drawn.
 *
 * ## The search and the order
 *
 * The field narrows the shows by name, found through the index the episodes are in too, and the order chip cycles the
 * provider's order, the names either way, and recently added -- all answered by the shows' own query, one window at a
 * time.
 */
@Composable
fun SeriesScreen(
    onOpenShow: (SeriesSummary) -> Unit,
    modifier: Modifier = Modifier,
    /** Keyed as `BrowseScreen` keys its holders, so each section keeps its own selection. */
    model: BrowseViewModel = hiltViewModel(key = CatalogSection.Series.name),
) {
    // Told once, and idempotently: composition runs again for reasons that are not a change of section.
    LaunchedEffect(Unit) { model.show(CatalogSection.Series) }

    val state by model.state.collectAsStateWithLifecycle()
    val typed by model.searchText.collectAsStateWithLifecycle()
    val shows = model.shows.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()
    // A new list starts at its first poster; the same list keeps its place.
    LaunchedEffect(state.selectedGroup, state.search, state.sort) { gridState.scrollToItem(0) }
    val tv = CastivioTheme.device.isTv

    // Padded by the cutout and the status bar alone, as the channels' board is: the keyboard the field brings up is
    // drawn over the board and does not shrink it.
    BoxWithConstraints(
        modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        val m = channelsMetricsFor(tv = tv, width = maxWidth, height = maxHeight)
        val skeleton = catalogMetricsFor(tv = tv, width = maxWidth, height = maxHeight)

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = m.edge)
                .padding(top = m.boardTop, bottom = m.boardBottom),
        ) {
            BoardBand(
                section = stringResource(R.string.browse_series),
                list = seriesListName(state),
                m = m,
                searchText = typed,
                onSearch = model::search,
                onSearchFocus = {},
                hint = stringResource(R.string.series_search_hint),
                // The films' band, and the order is the one thing a section adds to it: in the middle of the space
                // between the field and the clock, with no count beside it.
                trailing = {
                    CastivioChip(
                        text = stringResource(R.string.browse_sort, stringResource(state.sort.label)),
                        onClick = { model.sortBy(state.sort.next()) },
                        icon = Icons.Rounded.Tune,
                        labelStyle = castivioChipStyle(m.frame.fsChip),
                        padH = m.controlPadH,
                        padV = m.badgePadV,
                        trailingIcon = Icons.Rounded.KeyboardArrowDown,
                    )
                },
            )

            Spacer(Modifier.height(m.headerGap))

            SeriesBoard(
                state = state,
                m = m,
                skeleton = skeleton,
                model = model,
                shows = shows,
                gridState = gridState,
                onOpenShow = onOpenShow,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** What the band calls the list that is open: the category's name, or all of them. */
@Composable
private fun seriesListName(state: BrowseState): String {
    val group = state.selectedGroup
    return (if (group == null) null else state.categoryNames[group]) ?: stringResource(R.string.series_all)
}

/**
 * The panel: the rail, and the wall beside it -- the films' panel, drawn the same way, left to right whatever the
 * language, with the line about a refresh that is running or failed over both.
 */
@Composable
private fun SeriesBoard(
    state: BrowseState,
    m: ChannelsMetrics,
    skeleton: CatalogMetrics,
    model: BrowseViewModel,
    shows: LazyPagingItems<SeriesSummary>,
    gridState: LazyGridState,
    onOpenShow: (SeriesSummary) -> Unit,
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
        FetchNotice(fetch = state.fetch, firstLoad = state.firstLoad, m = m, onRetry = model::refresh)

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                CategoryRail(
                    state = state,
                    m = m,
                    onChoose = model::choose,
                    onFilter = model::filterGroups,
                    onRefresh = model::refresh,
                    allLabel = stringResource(R.string.series_all),
                    ownLists = false,
                    modifier = Modifier.width(m.rail).fillMaxHeight(),
                )

                Spacer(Modifier.width(m.railGap))

                SeriesPane(
                    state = state,
                    m = m,
                    skeleton = skeleton,
                    model = model,
                    shows = shows,
                    gridState = gridState,
                    onOpenShow = onOpenShow,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * The wall's frame: the films' frame and padding, and as many posters to a row as the width allows -- the films' own
 * arithmetic, so the two walls draw the same grid.
 */
@Composable
private fun SeriesPane(
    state: BrowseState,
    m: ChannelsMetrics,
    skeleton: CatalogMetrics,
    model: BrowseViewModel,
    shows: LazyPagingItems<SeriesSummary>,
    gridState: LazyGridState,
    onOpenShow: (SeriesSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fetch = state.fetch
    if (fetch is SectionLoad.Failed && state.sectionTotal == 0) {
        Box(modifier) { FetchFailed(CatalogSection.Series, fetch, onRetry = model::refresh) }
        return
    }
    val shape = RoundedCornerShape(m.wellRadius)

    Box(modifier.clip(shape).border(1.dp, CastivioTheme.colors.glassBorderSoft, shape)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(m.wellPad / 2)) {
            val gutter = m.nameGap
            val columns = posterColumns(maxWidth, gutter, MIN_POSTER_WIDTH).coerceAtMost(POSTER_COLUMNS)
            val titleStep = filmTitleStep(m)
            val poster = posterHeightFor(
                available = maxHeight,
                rowExtra = titleStep * LABEL_LINE * TITLE_LINES + m.nameGap * 2,
                rowGap = m.nameGap,
                peek = PEEK_OF_POSTER,
                floor = MIN_POSTER_HEIGHT,
            )
            val cardWidth = posterCardWidth(maxWidth, gutter, columns)
            Paged(
                paged = shows,
                section = CatalogSection.Series,
                state = state,
                m = skeleton,
                onShowAll = { model.choose(null) },
                onClearSearch = { model.search("") },
            ) {
                ShowWall(shows, m, columns, cardWidth, poster, gridState, onOpenShow)
            }
        }
    }
}

/** The posters: the provider's cover on each card and the show's name under it. A press opens the show's page. */
@Composable
private fun ShowWall(
    shows: LazyPagingItems<SeriesSummary>,
    m: ChannelsMetrics,
    columns: Int,
    cardWidth: Dp,
    poster: Dp,
    gridState: LazyGridState,
    onOpenShow: (SeriesSummary) -> Unit,
) {
    val titleStep = filmTitleStep(m)
    // The covers of the next rows are asked for while the wall is still, so they are on the device when it scrolls.
    PosterPrefetch(
        state = gridState,
        ahead = columns * PREFETCH_ROWS,
        itemCount = { shows.itemCount },
        urlAt = { index -> shows.peek(index)?.artworkUrl },
    )
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(m.nameGap),
        verticalArrangement = Arrangement.spacedBy(m.nameGap),
        contentPadding = PaddingValues(bottom = m.wellPad),
    ) {
        items(shows.itemCount, key = shows.itemKey { it.seriesId }) { index ->
            val show = shows[index] ?: return@items
            // Centred in its column, so what a card narrower than its column leaves is shared out evenly.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                MediaCard(
                    title = show.title,
                    shape = CardShape.Poster,
                    artworkSeed = index,
                    onClick = { onOpenShow(show) },
                    width = cardWidth,
                    gap = m.nameGap,
                    titleStyle = castivioChipStyle(titleStep),
                    captionStyle = castivioBodyStyle(m.frame.fsBody),
                    artworkUrl = show.artworkUrl,
                    posterHeight = poster,
                    titleLines = TITLE_LINES,
                    titleHeight = titleStep * LABEL_LINE * TITLE_LINES,
                    focusBrush = focusBrush(),
                    focusScale = 1f,
                    artworkScale = ContentScale.Fit,
                    cardFill = CastivioTheme.colors.glassFill,
                    cinematicBackdrop = true,
                )
            }
        }
    }
}
