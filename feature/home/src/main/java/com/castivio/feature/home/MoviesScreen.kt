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
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.formatCount
import com.castivio.core.design.components.ltrToken
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.MediaItem
import com.castivio.domain.Movie
import com.castivio.domain.SectionLoad

/**
 * The films, on the channels board's own frame: the same band over the same panel, the bouquets in the
 * same rail on the leading side and a wall of posters where the channel list is.
 *
 * ## One board, two sections
 *
 * What the viewer learned on the channels is true here. The band carries the mark, the section and the
 * list that is open, the search field and the clock; the rail lists *All*, the favourites, the films
 * played last and then every category the provider listed with its total, with a field of its own to
 * find one among hundreds; and the wall to its right narrows to whatever the rail or the band's field
 * asks. They are the channels' own composables -- `BoardBand` and `CategoryRail` -- given this section's
 * words, so the two boards cannot drift apart.
 *
 * ## What is on the wall, and where it comes from
 *
 * Every poster is a film the provider listed, with the picture and the running time it sent; the counts
 * are the catalogue's indexed counts and the search is the full-text index the channels' field already
 * asks. There is no rating, year or synopsis drawn anywhere, because none is stored -- a fact that was
 * not in the data is not made up to fill a card -- and a film with no poster keeps the card's own
 * placeholder.
 *
 * ## The same holder as before
 *
 * Read through [BrowseViewModel], keyed as the generic section screen keys it, so the category a viewer
 * had open survives a trip to a film's page and back; the favourites, the films played last, the search
 * and the order are the holder's own. A press on a poster opens the film's page -- see [MovieScreen] --
 * and does not start it; the page is where Play is.
 */
@Composable
fun MoviesScreen(
    onOpenMovie: (Movie) -> Unit,
    modifier: Modifier = Modifier,
    /** Keyed as `BrowseScreen` keys its holders, so each section keeps its own selection. */
    model: BrowseViewModel = hiltViewModel(key = CatalogSection.Movies.name),
) {
    // Told once, and idempotently: composition runs again for reasons that are not a change of section.
    LaunchedEffect(Unit) { model.show(CatalogSection.Movies) }

    val state by model.state.collectAsStateWithLifecycle()
    val typed by model.searchText.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    // A new list starts at its first poster; the same list keeps its place.
    LaunchedEffect(state.selectedGroup, state.favorites, state.recent, state.search, state.sort) {
        gridState.scrollToItem(0)
    }
    val tv = CastivioTheme.device.isTv

    // Padded by the cutout and the status bar alone, as the channels' board is: the keyboard the field
    // brings up is drawn over the board and does not shrink it.
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
                section = stringResource(R.string.browse_movies),
                list = movieListName(state),
                m = m,
                searchText = typed,
                onSearch = model::search,
                onSearchFocus = {},
                hint = stringResource(R.string.movies_search_hint),
                // The count and the order sit in the band, where the channels' clock and field are, so the
                // panel under it starts at the same height as the channel list does.
                trailing = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(m.factGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MovieCount(state, m)
                        CastivioChip(
                            text = stringResource(R.string.browse_sort, stringResource(state.sort.label)),
                            onClick = { model.sortBy(state.sort.next()) },
                            icon = Icons.Rounded.Sort,
                            labelStyle = castivioChipStyle(m.frame.fsChip),
                            padH = m.controlPadH,
                            padV = m.badgePadV,
                        )
                    }
                },
            )

            Spacer(Modifier.height(m.headerGap))

            MoviesBoard(
                state = state,
                m = m,
                skeleton = skeleton,
                model = model,
                gridState = gridState,
                onOpenMovie = onOpenMovie,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** What the band calls the list that is open: the category's name, or one of the viewer's own lists, or all. */
@Composable
private fun movieListName(state: BrowseState): String = when (val list = state.movieList()) {
    MovieList.All -> stringResource(R.string.movies_all)
    MovieList.Favorites -> stringResource(R.string.channels_favorites)
    MovieList.Recent -> stringResource(R.string.channels_recent)
    is MovieList.Category -> state.categoryNames[list.id] ?: stringResource(R.string.movies_all)
}

/** How many films the list holds, in brackets, where the figure is true. Nothing otherwise. */
@Composable
private fun MovieCount(state: BrowseState, m: ChannelsMetrics) {
    val count = state.headingCount() ?: return
    Text(
        text = ltrToken("(${formatCount(count)})"),
        style = castivioBodyStyle(m.frame.fsBody),
        color = CastivioTheme.colors.onBackgroundMuted,
        maxLines = 1,
    )
}

/**
 * The panel: the rail, and the wall beside it -- the channels' panel, drawn the same way: the same frame,
 * the same padding, the same rail at the same width, and the wall in the space the list and the well have.
 *
 * Drawn left to right whatever the language, as the channels' columns are. The line about a refresh that is
 * running or failed sits over both, as it does on the channels.
 */
@Composable
private fun MoviesBoard(
    state: BrowseState,
    m: ChannelsMetrics,
    skeleton: CatalogMetrics,
    model: BrowseViewModel,
    gridState: LazyGridState,
    onOpenMovie: (Movie) -> Unit,
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
                    onFavorites = model::openFavorites,
                    onRecent = model::openRecent,
                    allLabel = stringResource(R.string.movies_all),
                    modifier = Modifier.width(m.rail).fillMaxHeight(),
                )

                Spacer(Modifier.width(m.railGap))

                MoviesPane(
                    state = state,
                    m = m,
                    skeleton = skeleton,
                    model = model,
                    gridState = gridState,
                    onOpenMovie = onOpenMovie,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * The wall: the channel list's frame and padding, with the posters in it.
 *
 * Five to a row while the width allows it, and the cells share the whole of what is left, so the wall fills
 * the space the list and the well take on the channels. The height of a poster is fitted so two whole rows
 * and the top of the third show: the sliver is what says there is more below.
 */
@Composable
private fun MoviesPane(
    state: BrowseState,
    m: ChannelsMetrics,
    skeleton: CatalogMetrics,
    model: BrowseViewModel,
    gridState: LazyGridState,
    onOpenMovie: (Movie) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fetch = state.fetch
    if (fetch is SectionLoad.Failed && state.sectionTotal == 0) {
        Box(modifier) { FetchFailed(CatalogSection.Movies, fetch, onRetry = model::refresh) }
        return
    }
    val rows = model.items.collectAsLazyPagingItems()
    val shape = RoundedCornerShape(m.wellRadius)

    Box(modifier.clip(shape).border(1.dp, CastivioTheme.colors.glassBorderSoft, shape)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(m.wellPad / 2)) {
            val columns = posterColumns(maxWidth, m.railGap, MIN_POSTER_WIDTH).coerceAtMost(POSTER_COLUMNS)
            val fitted = posterHeightFor(
                available = maxHeight,
                rowExtra = m.frame.fsLabel * LABEL_LINE * TITLE_LINES + m.nameGap * 2,
                rowGap = m.nameGap,
                peek = PEEK_OF_POSTER,
                floor = MIN_POSTER_HEIGHT,
            )
            // The card is as wide as a 2:3 poster of that height, or narrower where the column is: the
            // frame is then the poster's shape, and nothing shows beside a poster fitted into it.
            val cardWidth = posterCardWidth(maxWidth, m.railGap, columns, fitted)
            val poster = cardWidth / POSTER_RATIO
            Paged(
                paged = rows,
                section = CatalogSection.Movies,
                state = state,
                m = skeleton,
                onShowAll = { model.choose(null) },
                onClearSearch = { model.search("") },
            ) {
                MovieWall(rows, m, columns, cardWidth, poster, gridState, onOpenMovie)
            }
        }
    }
}

/**
 * The posters: the provider's picture on each card, the title under it, and the running time on its lower
 * edge when the provider gave one. A press opens the film's page.
 */
@Composable
private fun MovieWall(
    rows: LazyPagingItems<MediaItem>,
    m: ChannelsMetrics,
    columns: Int,
    cardWidth: Dp,
    poster: Dp,
    gridState: LazyGridState,
    onOpenMovie: (Movie) -> Unit,
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(m.railGap),
        verticalArrangement = Arrangement.spacedBy(m.nameGap),
        contentPadding = PaddingValues(bottom = m.wellPad),
    ) {
        items(rows.itemCount, key = rows.itemKey { it.id }) { index ->
            val movie = rows[index] as? Movie ?: return@items
            // Centred in its column, so what a card narrower than its column leaves is shared out evenly
            // between the cards rather than left beside a poster inside one.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                MediaCard(
                    title = movie.title,
                    shape = CardShape.Poster,
                    artworkSeed = index,
                    onClick = { onOpenMovie(movie) },
                    width = cardWidth,
                    gap = m.nameGap,
                    titleStyle = castivioChipStyle(m.frame.fsLabel),
                    captionStyle = castivioBodyStyle(m.frame.fsBody),
                    artworkUrl = movie.artworkUrl,
                    tag = movieDuration(movie.durationMinutes)?.let(::ltrToken),
                    posterHeight = poster,
                    titleLines = TITLE_LINES,
                    titleHeight = m.frame.fsLabel * LABEL_LINE * TITLE_LINES,
                    focusBrush = focusBrush(),
                    focusScale = 1f,
                    artworkScale = ContentScale.Fit,
                    cardFill = CastivioTheme.colors.glassFill,
                )
            }
        }
    }
}

/** Six posters to a row, the most the wall draws, however wide the surface is. */
private const val POSTER_COLUMNS = 6

/**
 * The narrowest column a poster may be given before a row of them stops reading as posters. Low enough that
 * six 2:3 posters fit across a phone held sideways, where 100dp allowed five.
 */
private val MIN_POSTER_WIDTH = 72.dp

/** The shortest a poster may be drawn, so a very short surface still shows posters. */
private val MIN_POSTER_HEIGHT = 80.dp

/** How much of the third row shows: the top of a poster, about one part in thirteen. */
private const val PEEK_OF_POSTER = 0.05f

/** The title's line, as a share of its type: what the card's name takes beneath the artwork. */
private const val LABEL_LINE = 1.4f

/** A film's name may take two lines; a third is cut with an ellipsis, so every card's name area is the same. */
private const val TITLE_LINES = 2

/** The focus ring: the azure of the focus colour going into the violet of the selection, Castivio's own pair. */
@Composable
private fun focusBrush(): Brush {
    val colors = CastivioTheme.colors
    return Brush.linearGradient(listOf(colors.focusRing, colors.selectedBorder))
}
