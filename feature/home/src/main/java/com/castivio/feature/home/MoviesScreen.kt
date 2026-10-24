package com.castivio.feature.home

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.components.formatCount
import com.castivio.core.design.components.ltrToken
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.castivioStage
import com.castivio.domain.MediaItem
import com.castivio.domain.Movie
import com.castivio.domain.SectionLoad

/**
 * The films: a sidebar of categories with their counts, a wall of posters, and a way to search, filter and
 * order them -- the reference's screen, drawn from what the subscription actually holds.
 *
 * ## What is on it, and where it comes from
 *
 * Every poster is a film the provider listed, with the picture and the running time it sent; the counts
 * are the catalogue's own indexed counts and the search is the full-text index the channels board's field
 * already asks. There is no rating, year or synopsis drawn anywhere, because none is stored -- a fact that
 * was not in the data is not made up to fill a card -- and a film that has no poster keeps the card's own
 * placeholder.
 *
 * ## The same holder as before
 *
 * It reads the catalogue through [BrowseViewModel], keyed as the generic section screen keys it, so the
 * category a viewer had open survives a trip to a film's page and back -- and nothing in the holder had to
 * change for it: the favourites list, the search and the order are the holder's own, as they are for the
 * channels. The screen only asks for them.
 *
 * ## The board's geometry
 *
 * Drawn left to right whatever the language, as the channels board is: the sidebar on the left and the
 * wall on the right, with each name reading in its own direction inside its box. A press on a poster opens
 * the film's page -- see [MovieScreen] -- and does not start it; the page is where Play is.
 *
 * ## Filtering
 *
 * Under a thumb the sidebar may not fit, and on a television it is already there; the Filter button opens
 * the same list as a page of its own, so a category is one press from anywhere on every surface. A choice
 * made there closes it, and Back leaves it with nothing changed.
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
    var filtering by remember { mutableStateOf(false) }
    // Hoisted, so the wall stays where it was while the filter page is open over it.
    val gridState = rememberLazyGridState()
    // A new list starts at its first poster; the same list keeps its place.
    LaunchedEffect(state.selectedGroup, state.favorites, state.search, state.sort) {
        gridState.scrollToItem(0)
    }

    val pick: (MovieList) -> Unit = { list ->
        when (list) {
            MovieList.All -> model.choose(null)
            MovieList.Favorites -> model.openFavorites()
            is MovieList.Category -> model.choose(list.id)
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().statusBarsPadding()) {
        val m = catalogMetricsFor(tv = CastivioTheme.device.isTv, width = maxWidth, height = maxHeight)

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            if (filtering) {
                BackHandler { filtering = false }
                MovieFilterPage(
                    state = state,
                    m = m,
                    onPick = { list ->
                        pick(list)
                        filtering = false
                    },
                    modifier = Modifier.fillMaxSize().castivioStage(m.frame),
                )
            } else if (m.twoPane) {
                Row(
                    Modifier.fillMaxSize().castivioStage(m.frame),
                    horizontalArrangement = Arrangement.spacedBy(m.paneGap),
                ) {
                    MovieSidebar(
                        state = state,
                        m = m,
                        onPick = pick,
                        modifier = Modifier.width(m.pane),
                    )
                    MoviesColumn(
                        state = state,
                        typed = typed,
                        m = m,
                        model = model,
                        gridState = gridState,
                        onFilter = { filtering = true },
                        onOpenMovie = onOpenMovie,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else {
                MoviesColumn(
                    state = state,
                    typed = typed,
                    m = m,
                    model = model,
                    gridState = gridState,
                    onFilter = { filtering = true },
                    onOpenMovie = onOpenMovie,
                    modifier = Modifier.fillMaxSize().castivioStage(m.frame),
                )
            }
        }
    }
}

/** The controls, the heading and the wall: everything to the right of the sidebar. */
@Composable
private fun MoviesColumn(
    state: BrowseState,
    typed: String,
    m: CatalogMetrics,
    model: BrowseViewModel,
    gridState: LazyGridState,
    onFilter: () -> Unit,
    onOpenMovie: (Movie) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.bandGap)) {
        MovieControls(
            state = state,
            typed = typed,
            m = m,
            onType = model::search,
            onFilter = onFilter,
            onSort = { model.sortBy(state.sort.next()) },
        )
        MovieHeading(state = state, m = m)
        Box(Modifier.fillMaxSize()) {
            MoviesPane(state, m, model, gridState, onOpenMovie)
        }
    }
}

/**
 * The search field, the Filter button and the order: the reference's band above the wall.
 *
 * The field searches the section in place -- by name, as it is typed, after the holder's 120 ms pause --
 * and emptying it is the list that was open before. Sort is the cycling chip the generic screen has always
 * had, saying the order it is in; Filter opens the category page.
 */
@Composable
private fun MovieControls(
    state: BrowseState,
    typed: String,
    m: CatalogMetrics,
    onType: (String) -> Unit,
    onFilter: () -> Unit,
    onSort: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(m.rowGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MovieSearchField(text = typed, m = m, onType = onType, modifier = Modifier.weight(1f))
        CastivioChip(
            text = stringResource(R.string.movies_filter),
            onClick = onFilter,
            icon = Icons.Rounded.FilterList,
            labelStyle = castivioChipStyle(m.frame.fsChip),
            padH = m.entryPadH,
            padV = m.entryPadV,
        )
        CastivioChip(
            text = stringResource(R.string.browse_sort, stringResource(state.sort.label)),
            onClick = onSort,
            icon = Icons.Rounded.Sort,
            labelStyle = castivioChipStyle(m.frame.fsChip),
            padH = m.entryPadH,
            padV = m.entryPadV,
        )
    }
}

/**
 * A field a remote can focus and a thumb can type into: a pill with a magnifier, the hint under the text.
 *
 * It keeps no key a list needs -- up and down leave it as they leave any row -- and the keyboard's search
 * key only hides the keyboard, because the wall narrows as the words are typed and there is no button left
 * for the key to press.
 */
@Composable
private fun MovieSearchField(
    text: String,
    m: CatalogMetrics,
    onType: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val (focused, focusModifier) = rememberFocusFlag()
    val shape = RoundedCornerShape(Radius.pill)

    Row(
        modifier
            .heightIn(min = Sizing.minTarget(CastivioTheme.device.isTv))
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, if (focused) colors.focusRing else colors.glassBorderSoft, shape)
            .padding(horizontal = m.entryPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.rowGap),
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = colors.onBackgroundMuted,
            modifier = Modifier.size(Sizing.iconMd),
        )
        BasicTextField(
            value = text,
            onValueChange = onType,
            singleLine = true,
            textStyle = castivioBodyStyle(m.frame.fsBody).copy(color = colors.onBackground),
            cursorBrush = SolidColor(colors.secondary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { defaultKeyboardAction(ImeAction.Done) }),
            modifier = Modifier.weight(1f).then(focusModifier),
            decorationBox = { field ->
                // Under the field rather than inside it, so an empty box says what it is for without the
                // hint becoming the value.
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.movies_search_hint),
                        style = castivioBodyStyle(m.frame.fsBody),
                        color = colors.onBackgroundMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                field()
            },
        )
    }
}

/** The list's name and, where there is a true one, how many films it holds -- the reference's heading. */
@Composable
private fun MovieHeading(state: BrowseState, m: CatalogMetrics) {
    val colors = CastivioTheme.colors
    val name = when {
        state.search.isNotEmpty() -> stringResource(R.string.search_results)
        else -> when (val list = state.movieList()) {
            MovieList.All -> stringResource(R.string.movies_all)
            MovieList.Favorites -> stringResource(R.string.channels_favorites)
            is MovieList.Category -> state.categoryNames[list.id] ?: stringResource(R.string.movies_all)
        }
    }
    val count = state.headingCount()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(m.rowGap),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = name,
            style = castivioTitleStyle(m.frame.fsTitle),
            color = colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (count != null) {
            Text(
                text = ltrToken("(${formatCount(count)})"),
                style = castivioBodyStyle(m.frame.fsBody),
                color = colors.onBackgroundMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * The categories, as the tall list a remote steps down: all the films, the favourites, then each
 * category the provider listed that has something in it.
 *
 * "All" says the section's count and keeps saying it while a category is open; the favourites carry none,
 * for the reason the channels' rail gives -- "Favourites: 0" before anything is marked is true and no
 * use, and the count would come from another store.
 */
@Composable
private fun MovieSidebar(
    state: BrowseState,
    m: CatalogMetrics,
    onPick: (MovieList) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = state.movieList()
    LazyColumn(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(m.entryGap),
        contentPadding = PaddingValues(bottom = m.listBottom),
    ) {
        item(key = SIDEBAR_ALL) {
            MovieListEntry(
                label = stringResource(R.string.movies_all),
                count = state.sectionTotal,
                selected = list == MovieList.All,
                m = m,
                icon = Icons.Rounded.GridView,
                onClick = { onPick(MovieList.All) },
            )
        }
        item(key = SIDEBAR_FAVORITES) {
            MovieListEntry(
                label = stringResource(R.string.channels_favorites),
                count = null,
                selected = list == MovieList.Favorites,
                m = m,
                icon = Icons.Rounded.Favorite,
                onClick = { onPick(MovieList.Favorites) },
            )
        }
        items(state.shownGroups, key = { it.id }) { group ->
            MovieListEntry(
                label = group.name,
                count = group.itemCount,
                selected = list == MovieList.Category(group.id),
                m = m,
                onClick = { onPick(MovieList.Category(group.id)) },
            )
        }
    }
}

/**
 * The Filter button's page: the same list as the sidebar, on a page of its own and centred.
 *
 * Nothing else is on the screen to be focused while it is open, so the remote can only be in the list --
 * on the entry that is in force. A press chooses and closes; Back, which the screen owns, closes it with
 * nothing changed.
 */
@Composable
private fun MovieFilterPage(
    state: BrowseState,
    m: CatalogMetrics,
    onPick: (MovieList) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val list = state.movieList()
    val inForce = remember { FocusRequester() }

    // The entry in force takes the remote, so the page opens where the viewer already is in the list.
    LaunchedEffect(Unit) { runCatching { inForce.requestFocus() } }

    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(m.bandGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.movies_filter),
            style = castivioTitleStyle(m.frame.fsTitle),
            color = colors.onBackground,
            maxLines = 1,
        )
        LazyColumn(
            modifier = Modifier.widthIn(max = FILTER_PAGE_WIDTH).fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(m.entryGap),
            contentPadding = PaddingValues(bottom = m.listBottom),
        ) {
            item(key = SIDEBAR_ALL) {
                MovieListEntry(
                    label = stringResource(R.string.movies_all),
                    count = state.sectionTotal,
                    selected = list == MovieList.All,
                    m = m,
                    icon = Icons.Rounded.GridView,
                    onClick = { onPick(MovieList.All) },
                    modifier = if (list == MovieList.All) Modifier.focusRequester(inForce) else Modifier,
                )
            }
            item(key = SIDEBAR_FAVORITES) {
                MovieListEntry(
                    label = stringResource(R.string.channels_favorites),
                    count = null,
                    selected = list == MovieList.Favorites,
                    m = m,
                    icon = Icons.Rounded.Favorite,
                    onClick = { onPick(MovieList.Favorites) },
                    modifier = if (list == MovieList.Favorites) Modifier.focusRequester(inForce) else Modifier,
                )
            }
            items(state.shownGroups, key = { it.id }) { group ->
                val mine = list == MovieList.Category(group.id)
                MovieListEntry(
                    label = group.name,
                    count = group.itemCount,
                    selected = mine,
                    m = m,
                    onClick = { onPick(MovieList.Category(group.id)) },
                    modifier = if (mine) Modifier.focusRequester(inForce) else Modifier,
                )
            }
        }
    }
}

/**
 * One entry of the list: a name, its count, and whether it is the list in force.
 *
 * Selection is the surface and focus is the ring around it -- the design system's rule, and the
 * channels' rail's -- so an entry that is both in force and under the remote shows both.
 */
@Composable
private fun MovieListEntry(
    label: String,
    count: Int?,
    selected: Boolean,
    m: CatalogMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val colors = CastivioTheme.colors
    val (focused, focusModifier) = rememberFocusFlag()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Radius.md)
    val ink = if (selected) colors.onSecondary else colors.onBackground

    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizing.minTarget(CastivioTheme.device.isTv))
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
            .padding(horizontal = m.entryPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.rowGap),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(Sizing.iconMd))
        }
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
                color = if (selected) ink else colors.onBackgroundMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * The wall, or the reason it is not here -- the generic screen's rules, unchanged: a failure takes the
 * pane only when there is nothing under it, loading is the shape of what is coming, and an empty list says
 * which kind of empty it is.
 */
@Composable
private fun MoviesPane(
    state: BrowseState,
    m: CatalogMetrics,
    model: BrowseViewModel,
    gridState: LazyGridState,
    onOpenMovie: (Movie) -> Unit,
) {
    val fetch = state.fetch
    if (fetch is SectionLoad.Failed && state.sectionTotal == 0) {
        FetchFailed(CatalogSection.Movies, fetch, onRetry = model::refresh)
        return
    }
    val rows = model.items.collectAsLazyPagingItems()
    Paged(
        paged = rows,
        section = CatalogSection.Movies,
        state = state,
        m = m,
        onShowAll = { model.choose(null) },
        onClearSearch = { model.search("") },
    ) {
        MovieWall(rows, m, gridState, onOpenMovie)
    }
}

/**
 * The posters: the provider's picture on each card, the title under it, and the running time on its lower
 * edge when the provider gave one. A press opens the film's page.
 */
@Composable
private fun MovieWall(
    rows: LazyPagingItems<MediaItem>,
    m: CatalogMetrics,
    gridState: LazyGridState,
    onOpenMovie: (Movie) -> Unit,
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(m.columns),
        horizontalArrangement = Arrangement.spacedBy(m.gutter),
        verticalArrangement = Arrangement.spacedBy(m.gutter),
        contentPadding = PaddingValues(bottom = m.listBottom),
    ) {
        items(rows.itemCount, key = rows.itemKey { it.id }) { index ->
            val movie = rows[index] as? Movie ?: return@items
            MediaCard(
                title = movie.title,
                subtitle = movie.year?.takeIf { it > 0 }?.toString(),
                shape = CardShape.Poster,
                artworkSeed = index,
                onClick = { onOpenMovie(movie) },
                modifier = Modifier.fillMaxWidth(),
                gap = m.entryGap,
                titleStyle = castivioChipStyle(m.frame.fsLabel),
                captionStyle = castivioBodyStyle(m.frame.fsBody),
                artworkUrl = movie.artworkUrl,
                tag = movieDuration(movie.durationMinutes)?.let(::ltrToken),
            )
        }
    }
}

private const val SIDEBAR_ALL = "castivio.movies.all"
private const val SIDEBAR_FAVORITES = "castivio.movies.favorites"

/** The Filter page's list is a column a thumb and a remote both reach, not a table across the screen. */
private val FILTER_PAGE_WIDTH = 520.dp
