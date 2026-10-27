package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import com.castivio.core.design.components.CastivioChoiceDialog
import com.castivio.core.design.components.ChoiceOption
import com.castivio.core.design.components.Equaliser
import com.castivio.core.design.components.MediaCard
import com.castivio.core.design.components.MetaChip
import com.castivio.core.design.components.PosterPrefetch
import com.castivio.core.design.components.WatchState
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.ltrToken
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.boundedFraction
import com.castivio.domain.Channel
import com.castivio.domain.MediaItem
import com.castivio.domain.SectionLoad
import kotlinx.coroutines.delay

/**
 * The radio, on the channels board's own frame: the same band over the same panel, the same rail on the leading
 * side, and a wall of station cards where the channel list is -- with the sound kept in view under it, or beside it.
 *
 * ## One board, three sections
 *
 * What the viewer learned on the channels and the films is true here. The band carries the mark, the section and the
 * list that is open, the field and the clock; the rail lists *All*, the favourites, the stations played last and then
 * every category the provider listed with its total. They are the channels' own composables -- `BoardBand` and
 * `CategoryRail` -- given this section's words, and the stations are read through [BrowseViewModel], keyed as the
 * generic section screen keys it, so the category a viewer had open survives a trip away and back.
 *
 * ## The wall
 *
 * Square cards, because a station's logo is square and is the whole of what it has to show: no guide, no preview.
 * The logo is shown whole over the blurred colours of itself, as a film's poster is, and a station with no logo
 * keeps the card's own ground and a radio mark -- nothing is drawn to stand in for the artwork. The number is in
 * a corner, the heart of a favourite in the other, and the station that is sounding has the playing edge and the
 * moving mark. The card's second line is the category, and only on the lists that mix categories.
 *
 * ## The sound
 *
 * A press on a card starts the station at once, in place: there is no page per station. What is playing stands in
 * the plate -- a bar under the wall on a handset or a tablet, a panel at the board's end on a television -- with the
 * station before, play or pause, the station after, the favourite and a sleep timer. The sound is [session]'s and the
 * shell's, and it lasts as long as this screen does: leaving the radio, or the application going to the background,
 * lets it go.
 *
 * ## What is not here
 *
 * No country, genre, bitrate, codec or waveform. A panel sends a station as a name, a logo, a number and a category;
 * a fact that was not in the data is not made up to fill a card.
 */
@Composable
fun RadioScreen(
    session: RadioSession,
    modifier: Modifier = Modifier,
    /** Keyed as `BrowseScreen` keys its holders, so each section keeps its own selection. */
    model: BrowseViewModel = hiltViewModel(key = CatalogSection.Radio.name),
    favorites: ChannelFavoritesViewModel = hiltViewModel(),
) {
    // Told once, and idempotently: composition runs again for reasons that are not a change of section.
    LaunchedEffect(Unit) { model.show(CatalogSection.Radio) }

    val state by model.state.collectAsStateWithLifecycle()
    val typed by model.searchText.collectAsStateWithLifecycle()
    val favoriteIds by favorites.ids.collectAsStateWithLifecycle()
    val rows = model.items.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()
    // A new list starts at its first card; the same list keeps its place.
    LaunchedEffect(state.selectedGroup, state.favorites, state.recent, state.search, state.sort) {
        gridState.scrollToItem(0)
    }
    val tv = CastivioTheme.device.isTv
    var sleepOpen by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }

    // The sleep timer holds the sound when it ends, as the viewer's own press would.
    val sleepAt = session.sleepAtMs
    LaunchedEffect(sleepAt) {
        if (sleepAt != null) {
            delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0L))
            session.sleepEnded()
        }
    }

    // Padded by the cutout and the status bar alone, as the channels' board is: the keyboard the field brings up
    // is drawn over the board and does not shrink it.
    BoxWithConstraints(
        modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        val m = channelsMetricsFor(tv = tv, width = maxWidth, height = maxHeight)
        val skeleton = catalogMetricsFor(tv = tv, width = maxWidth, height = maxHeight)

        // What the plate shows, worked out here and not in the plate: which station, in which category, what the
        // sound is doing, and whether the open list has a station on either side of it.
        val station = session.station
        val neighbours = remember(station?.id, rows.itemCount, state.selectedGroup, state.favorites, state.recent, state.search, state.sort) {
            station?.let { current ->
                val idAt: (Int) -> String? = { rows.peek(it)?.id }
                (neighbourOf(current.id, by = -1, count = rows.itemCount, idAt = idAt) != null) to
                    (neighbourOf(current.id, by = 1, count = rows.itemCount, idAt = idAt) != null)
            }
        }
        val plate = station?.let { current ->
            RadioPlateState(
                station = current,
                category = current.categoryIn(state.categoryNames),
                phase = radioPhaseOf(current.streamUrl, session.status, session.paused),
                favorite = current.id in favoriteIds,
                sleeping = sleepAt != null,
                canPrevious = neighbours?.first == true,
                canNext = neighbours?.second == true,
            )
        }
        val actions = RadioPlateActions(
            onToggle = session::toggle,
            onPrevious = { step(session, rows, by = -1) },
            onNext = { step(session, rows, by = 1) },
            onFavorite = { session.station?.let { favorites.flip(it.id) } },
            onSleep = { sleepOpen = true },
            onOpen = if (tv) null else ({ opened = true }),
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = m.edge)
                .padding(top = m.boardTop, bottom = m.boardBottom),
        ) {
            BoardBand(
                section = stringResource(R.string.browse_radio),
                list = radioListName(state),
                m = m,
                searchText = typed,
                onSearch = model::search,
                onSearchFocus = {},
                hint = stringResource(R.string.radio_search_hint),
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

            RadioBoard(
                state = state,
                m = m,
                skeleton = skeleton,
                model = model,
                rows = rows,
                gridState = gridState,
                playingUrl = session.status.soundingUrl,
                favoriteIds = favoriteIds,
                plate = plate,
                actions = actions,
                tv = tv,
                onPress = session::play,
                modifier = Modifier.weight(1f),
            )
        }

        // The plate opened, over the board, which goes on playing under it.
        if (opened && plate != null && !tv) {
            RadioNowPlaying(plate = plate, actions = actions, m = m, onClose = { opened = false })
        }

        if (sleepOpen) {
            BackHandler { sleepOpen = false }
            SleepDialog(session = session, onClose = { sleepOpen = false })
        }
    }
}

/** What the band calls the list that is open: the category's name, or one of the viewer's own lists, or all. */
@Composable
private fun radioListName(state: BrowseState): String = when (val list = state.movieList()) {
    MovieList.All -> stringResource(R.string.radio_all)
    MovieList.Favorites -> stringResource(R.string.channels_favorites)
    MovieList.Recent -> stringResource(R.string.radio_recent)
    is MovieList.Category -> state.categoryNames[list.id] ?: stringResource(R.string.radio_all)
}

/** The station after [by] places in the open list, asked for by the plate's controls: nothing if there is none. */
private fun step(session: RadioSession, rows: LazyPagingItems<MediaItem>, by: Int) {
    val current = session.station ?: return
    val target = neighbourOf(current.id, by, rows.itemCount) { rows.peek(it)?.id } ?: return
    (rows.peek(target) as? Channel)?.let(session::play)
}

/**
 * The panel: the rail, the wall beside it, and on a television the plate's panel at the end -- the channels' panel,
 * drawn the same way, with the same frame, padding and rail width.
 *
 * Drawn left to right whatever the language, as the channels' columns are. The line about a refresh that is running
 * or failed sits over all of it, as it does on the channels.
 */
@Composable
private fun RadioBoard(
    state: BrowseState,
    m: ChannelsMetrics,
    skeleton: CatalogMetrics,
    model: BrowseViewModel,
    rows: LazyPagingItems<MediaItem>,
    gridState: LazyGridState,
    playingUrl: String?,
    favoriteIds: Set<String>,
    plate: RadioPlateState?,
    actions: RadioPlateActions,
    tv: Boolean,
    onPress: (Channel) -> Unit,
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
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val panelWidth = maxWidth.boundedFraction(PANEL_SHARE, PANEL_MIN, PANEL_MAX)

                Row(Modifier.fillMaxSize()) {
                    CategoryRail(
                        state = state,
                        m = m,
                        onChoose = model::choose,
                        onFilter = model::filterGroups,
                        onRefresh = model::refresh,
                        onFavorites = model::openFavorites,
                        onRecent = model::openRecent,
                        allLabel = stringResource(R.string.radio_all),
                        recentLabel = stringResource(R.string.radio_recent),
                        modifier = Modifier.width(m.rail).fillMaxHeight(),
                    )

                    Spacer(Modifier.width(m.railGap))

                    // On a handset the bar stands inside the wall's space, under the cards, and the cards stop above it.
                    val bar = plate != null && !tv
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        RadioPane(
                            state = state,
                            m = m,
                            skeleton = skeleton,
                            model = model,
                            rows = rows,
                            gridState = gridState,
                            playingUrl = playingUrl,
                            favoriteIds = favoriteIds,
                            maxColumns = if (tv) TV_COLUMNS else HANDSET_COLUMNS,
                            bottomReserve = if (bar) radioBarHeight(m, tv = false) + m.nameGap * 2 else 0.dp,
                            onPress = onPress,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (bar && plate != null) {
                            RadioBar(
                                plate = plate,
                                actions = actions,
                                m = m,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(m.nameGap),
                            )
                        }
                    }

                    if (tv) {
                        Spacer(Modifier.width(m.railGap))
                        RadioPanel(plate = plate, actions = actions, m = m, modifier = Modifier.width(panelWidth).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/**
 * The wall's frame: the channel list's own frame and padding, with the cards in it.
 *
 * As many cards to a row as the width allows, none narrower than [MIN_STATION_WIDTH] and no more than [maxColumns];
 * the cells share the whole of what is left, so the wall fills the space the list takes on the channels.
 */
@Composable
private fun RadioPane(
    state: BrowseState,
    m: ChannelsMetrics,
    skeleton: CatalogMetrics,
    model: BrowseViewModel,
    rows: LazyPagingItems<MediaItem>,
    gridState: LazyGridState,
    playingUrl: String?,
    favoriteIds: Set<String>,
    maxColumns: Int,
    bottomReserve: Dp,
    onPress: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fetch = state.fetch
    if (fetch is SectionLoad.Failed && state.sectionTotal == 0) {
        Box(modifier) { FetchFailed(CatalogSection.Radio, fetch, onRetry = model::refresh) }
        return
    }
    val shape = RoundedCornerShape(m.wellRadius)

    Box(modifier.clip(shape).border(1.dp, CastivioTheme.colors.glassBorderSoft, shape)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(m.wellPad / 2)) {
            // The space between cards is the one the rows have between them, so the wall reads as an even grid and
            // gives its width to the cards rather than to the gutters.
            val gutter = m.nameGap
            val columns = posterColumns(maxWidth, gutter, MIN_STATION_WIDTH).coerceAtMost(maxColumns)
            val cardWidth = posterCardWidth(maxWidth, gutter, columns)
            Paged(
                paged = rows,
                section = CatalogSection.Radio,
                state = state,
                m = skeleton,
                onShowAll = { model.choose(null) },
                onClearSearch = { model.search("") },
            ) {
                StationWall(
                    rows = rows,
                    // The category is said on the lists that mix them, where it is what tells two stations apart.
                    categories = state.categoryNames.takeIf { state.mixesCategories() },
                    m = m,
                    columns = columns,
                    cardWidth = cardWidth,
                    bottomReserve = bottomReserve,
                    gridState = gridState,
                    playingUrl = playingUrl,
                    favoriteIds = favoriteIds,
                    onPress = onPress,
                )
            }
        }
    }
}

/** The stations: the provider's logo on each card, the name under it, and a second line where it says something. */
@Composable
private fun StationWall(
    rows: LazyPagingItems<MediaItem>,
    categories: Map<String, String>?,
    m: ChannelsMetrics,
    columns: Int,
    cardWidth: Dp,
    bottomReserve: Dp,
    gridState: LazyGridState,
    playingUrl: String?,
    favoriteIds: Set<String>,
    onPress: (Channel) -> Unit,
) {
    // The logos of the next rows are asked for while the wall is still, so they are on the device when it scrolls.
    PosterPrefetch(
        state = gridState,
        ahead = columns * PREFETCH_ROWS,
        itemCount = { rows.itemCount },
        urlAt = { index -> (rows.peek(index) as? Channel)?.artworkUrl },
    )
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(m.nameGap),
        verticalArrangement = Arrangement.spacedBy(m.nameGap),
        contentPadding = PaddingValues(bottom = maxOf(m.wellPad, bottomReserve)),
    ) {
        items(rows.itemCount, key = rows.itemKey { it.id }) { index ->
            val station = rows[index] as? Channel ?: return@items
            // Centred in its column, so what a card narrower than its column leaves is shared out evenly.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                StationCard(
                    station = station,
                    index = index,
                    categories = categories,
                    width = cardWidth,
                    m = m,
                    playing = playingUrl != null && station.streamUrl == playingUrl,
                    favorite = station.id in favoriteIds,
                    onClick = { onPress(station) },
                )
            }
        }
    }
}

/**
 * One station: the shared card at the logo's own square, with the number, the heart and the mark of what sounds
 * placed over its artwork, and the category on its second line where the list mixes them.
 */
@Composable
private fun StationCard(
    station: Channel,
    index: Int,
    categories: Map<String, String>?,
    width: Dp,
    m: ChannelsMetrics,
    playing: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
) {
    val colors = CastivioTheme.colors
    val titleStep = m.frame.fsLabel * TITLE_OF_LABEL
    val number: (@Composable BoxScope.() -> Unit)? = station.number?.let { value ->
        { MetaChip(text = ltrToken(value.toString())) }
    }
    MediaCard(
        title = station.title,
        shape = CardShape.Square,
        artworkSeed = index,
        onClick = onClick,
        width = width,
        gap = m.nameGap,
        titleStyle = castivioChipStyle(titleStep),
        captionStyle = castivioBodyStyle(m.frame.fsBody * CAPTION_OF_BODY),
        // A line is kept on every card of a list that mixes categories, so a station filed under none does not
        // stand a line shorter than the cards beside it.
        caption = categories?.let { station.categoryIn(it) ?: EMPTY_LINE },
        artworkUrl = station.artworkUrl,
        posterHeight = width,
        titleLines = TITLE_LINES,
        titleHeight = titleStep * LABEL_LINE * TITLE_LINES,
        focusBrush = focusBrush(),
        focusScale = 1f,
        artworkScale = ContentScale.Fit,
        cardFill = colors.glassFill,
        cinematicBackdrop = true,
        ground = { StationGround() },
        watchState = if (playing) WatchState.Playing else WatchState.None,
        badge = number,
        overlay = {
            if (favorite) HeartMark(m, Modifier.align(Alignment.TopEnd).padding(m.nameGap))
            // Drawn larger than the pill's own: on a card it is the one mark that says this station sounds.
            if (playing) Box(Modifier.align(Alignment.BottomEnd).padding(m.nameGap * 3)) { Equaliser(Modifier.scale(EQUALISER_SCALE)) }
        },
    )
}

/** What a station's card stands on where it has no logo, or before the logo arrives: the ground and a radio mark. */
@Composable
private fun StationGround() {
    val colors = CastivioTheme.colors
    Box(Modifier.fillMaxSize().background(colors.backgroundElevated), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Rounded.Radio,
            contentDescription = null,
            tint = colors.onBackgroundMuted.copy(alpha = GROUND_MARK),
            modifier = Modifier.fillMaxSize(GROUND_MARK_SIZE),
        )
    }
}

/** The heart of a favourite, on a dim disc so it reads over a logo of any colour. */
@Composable
private fun HeartMark(m: ChannelsMetrics, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val disc = m.frame.fsLabel * HEART_DISC
    Box(
        modifier.size(disc).clip(CircleShape).background(colors.scrim.copy(alpha = HEART_GROUND)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Favorite,
            contentDescription = null,
            tint = colors.secondary,
            modifier = Modifier.size(disc * HEART_OF_DISC),
        )
    }
}

/**
 * The sleep timer's times, and the one that is running with what it has left. A press chooses and closes: the
 * answer in force is filled, and focus opens on it.
 */
@Composable
private fun SleepDialog(session: RadioSession, onClose: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val running = session.sleepMinutes
    val left = sleepRemainingMs(session.sleepAtMs, now)
    val options = buildList {
        add(ChoiceOption(label = stringResource(R.string.radio_sleep_off), selected = running == null))
        SLEEP_MINUTES.forEach { minutes ->
            add(
                ChoiceOption(
                    label = stringResource(R.string.radio_sleep_after, minutes),
                    detail = left?.takeIf { running == minutes }?.let { stringResource(R.string.radio_sleep_left, ltrToken(remainingText(it))) },
                    selected = running == minutes,
                ),
            )
        }
    }
    CastivioChoiceDialog(
        title = stringResource(R.string.radio_sleep),
        options = options,
        onChoose = { index ->
            session.sleepIn(minutes = SLEEP_MINUTES.getOrNull(index - 1), nowMs = System.currentTimeMillis())
            onClose()
        },
        closeLabel = stringResource(R.string.radio_close),
        onClose = onClose,
    )
}

/** The focus ring: the azure of the focus colour going into the violet of the selection, Castivio's own pair. */
@Composable
private fun focusBrush(): Brush {
    val colors = CastivioTheme.colors
    return Brush.linearGradient(listOf(colors.focusRing, colors.selectedBorder))
}

/** Five cards to a row on a handset or a tablet, the most the wall draws, as the films' wall has it. */
private const val HANDSET_COLUMNS = 5

/** Four beside the panel: the wall is narrower by what the panel takes. */
private const val TV_COLUMNS = 4

/** How many rows below the last one drawn have their logos asked for while the wall is still. */
private const val PREFETCH_ROWS = 2

/**
 * The narrowest column a card may be given before its name stops being readable in two lines: a narrower surface
 * gets fewer columns, not narrower cards.
 */
private val MIN_STATION_WIDTH = 96.dp

/** A station's name is set a little under the label step, as a film's is, so a long one reaches its second line whole. */
private const val TITLE_OF_LABEL = 0.94f

/** The title's line, as a share of its type: what the card's name takes beneath the artwork. */
private const val LABEL_LINE = 1.45f

/** A station's name may take two lines; a third is cut with an ellipsis, so every card's name area is the same. */
private const val TITLE_LINES = 2

/**
 * The second line of a card whose station is filed under no category: a no-break space, which keeps the line's
 * height without drawing anything, so the cards of a row are one height.
 */
private const val EMPTY_LINE = " "

/** A card's second line is set a little under the body step, so a category of ordinary length reaches its end whole. */
private const val CAPTION_OF_BODY = 0.9f

/** How much larger the equaliser is drawn on a card than in the plate. */
private const val EQUALISER_SCALE = 1.6f

/** The radio mark behind a logo, and how dim and how large it is: a ground, not a picture of the station. */
private const val GROUND_MARK = 0.5f
private const val GROUND_MARK_SIZE = 0.4f

/** The heart's disc as a share of the label step, the disc's own darkness, and the heart's share of the disc. */
private const val HEART_DISC = 1.6f
private const val HEART_GROUND = 0.55f
private const val HEART_OF_DISC = 0.62f

/** The television's panel: a share of the board, bounded so it is neither a sliver nor most of the wall. */
private const val PANEL_SHARE = 0.28f
private val PANEL_MIN = 220.dp
private val PANEL_MAX = 340.dp

/** How often the sleep dialog redraws what is left, in milliseconds: often enough that a second never skips. */
private const val TICK_MS = 500L
