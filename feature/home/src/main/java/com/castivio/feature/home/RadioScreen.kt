package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.castivio.core.design.components.CastivioChip
import com.castivio.core.design.components.CastivioChoiceDialog
import com.castivio.core.design.components.ChoiceOption
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.ltrToken
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.platform.LoadTrace
import com.castivio.core.platform.PerfSection
import com.castivio.domain.Channel
import com.castivio.domain.MediaItem
import com.castivio.domain.SectionLoad
import kotlinx.coroutines.delay

/**
 * The radio, laid out as the channels' board is: the same band over the same panel, the same rail on the leading side, the stations as
 * rows in a frame of their own, and at the board's end a picture that says radio with the plate of what is playing under it.
 *
 * It was a wall of square cards with a bar of controls laid over its foot (a panel at the end on a television). The wall hid its rows
 * behind the bar, showed a logo big and a name small, and did not look like the board it sits beside. Drawn first in
 * `design/mockups/radio-list-ar.html`.
 *
 * ## One board, three sections
 *
 * What the viewer learned on the channels and the films is true here. The band carries the mark, the section and the
 * list that is open, the field and the clock; the rail lists *All*, the favourites, the stations played last and then
 * every category the provider listed with its total. They are the channels' own composables -- `BoardBand` and
 * `CategoryRail` -- given this section's words, and the stations are read through [BrowseViewModel], keyed as the
 * generic section screen keys it, so the category a viewer had open survives a trip away and back.
 *
 * ## The rows
 *
 * The channels' own row ([ChannelRow]): the number on its plate, the logo -- or the tile of a station with none -- the name, the green
 * disc on the one that sounds, a heart on a favourite. The category stands under the name, and only on the lists that mix categories.
 * A press starts the station at once, in place: there is no page per station.
 *
 * ## The end of the board
 *
 * A station has no picture to preview, so where the channels have one there is [RadioPicture], the section's own; and where the guide's
 * card stands there is the plate -- the station before, play or pause, the station after, the favourite and a sleep timer -- on every
 * device. The sound is [session]'s and the shell's, and it lasts as long as this screen does: leaving the radio, or the application
 * going to the background, lets it go.
 *
 * ## What is not here
 *
 * No country, genre, bitrate, codec or waveform. A panel sends a station as a name, a logo, a number and a category;
 * a fact that was not in the data is not made up to fill a row.
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
    // Temporary, for development: the screen is on, counted from the press on Home -- see `LoadTrace`.
    LaunchedEffect(Unit) { LoadTrace.screenShown(PerfSection.RADIO) }

    val state by model.state.collectAsStateWithLifecycle()
    val typed by model.searchText.collectAsStateWithLifecycle()
    val favoriteIds by favorites.ids.collectAsStateWithLifecycle()
    val rows = model.items.collectAsLazyPagingItems()
    // The first station, from the device or from the load: told once, to the session of the press that opened this.
    val hasRows = rows.itemCount > 0
    LaunchedEffect(hasRows) { if (hasRows) LoadTrace.contentShown(PerfSection.RADIO) }
    val listState = rememberLazyListState()
    // A new list starts at its first row; the same list keeps its place.
    LaunchedEffect(state.selectedGroup, state.favorites, state.recent, state.search, state.sort) {
        listState.scrollToItem(0)
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
                // Only the station that is the one playing: a rating belongs to the stream it was made from.
                quality = session.status.takeIf { it.url == current.streamUrl }?.quality,
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
                // The films' band, and the order is the one thing a section adds to it: between the field and the
                // clock, the same space either side as the band leaves between all of its pieces, with no count
                // beside it.
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
                listState = listState,
                playingUrl = session.status.soundingUrl,
                favoriteIds = favoriteIds,
                plate = plate,
                actions = actions,
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
 * The panel: the rail, the stations beside it as rows, and at the end the picture that says radio with the plate of what is
 * playing under it -- the channels' panel, drawn the same way, with the same frame, padding, rail width and column at the end.
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
    listState: LazyListState,
    playingUrl: String?,
    favoriteIds: Set<String>,
    plate: RadioPlateState?,
    actions: RadioPlateActions,
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
        LoadStrip(section = PerfSection.RADIO, runningOnly = true, fontSize = m.frame.fsLabel)
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
                    allLabel = stringResource(R.string.radio_all),
                    recentLabel = stringResource(R.string.radio_recent),
                    modifier = Modifier.width(m.rail).fillMaxHeight(),
                )

                Spacer(Modifier.width(m.railGap))

                RadioPane(
                    state = state,
                    m = m,
                    skeleton = skeleton,
                    model = model,
                    rows = rows,
                    listState = listState,
                    playingUrl = playingUrl,
                    favoriteIds = favoriteIds,
                    onPress = onPress,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )

                Spacer(Modifier.width(m.playerGap))

                RadioSide(plate = plate, actions = actions, m = m, modifier = Modifier.width(m.player).fillMaxHeight())
            }
        }
    }
}

/**
 * The stations' frame: the channel list's own frame and padding, with the rows in it.
 */
@Composable
private fun RadioPane(
    state: BrowseState,
    m: ChannelsMetrics,
    skeleton: CatalogMetrics,
    model: BrowseViewModel,
    rows: LazyPagingItems<MediaItem>,
    listState: LazyListState,
    playingUrl: String?,
    favoriteIds: Set<String>,
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
        Paged(
            paged = rows,
            section = CatalogSection.Radio,
            state = state,
            m = skeleton,
            onShowAll = { model.choose(null) },
            onClearSearch = { model.search("") },
        ) {
            StationList(
                rows = rows,
                // The category is said on the lists that mix them, where it is what tells two stations apart.
                categories = state.categoryNames.takeIf { state.mixesCategories() },
                m = m,
                listState = listState,
                playingUrl = playingUrl,
                favoriteIds = favoriteIds,
                onPress = onPress,
            )
        }
    }
}

/**
 * The stations, one per row, in the order the list has them -- the channels' own row: the number on its plate, the logo, the name, and
 * the green disc on the one that sounds, with the category under the name on the lists that mix them and a heart after it on a favourite.
 */
@Composable
private fun StationList(
    rows: LazyPagingItems<MediaItem>,
    categories: Map<String, String>?,
    m: ChannelsMetrics,
    listState: LazyListState,
    playingUrl: String?,
    favoriteIds: Set<String>,
    onPress: (Channel) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(m.wellPad / 2),
        contentPadding = PaddingValues(bottom = m.wellPad),
    ) {
        items(rows.itemCount, key = rows.itemKey { it.id }) { index ->
            val station = rows[index] as? Channel ?: return@items
            val playing = playingUrl != null && station.streamUrl == playingUrl
            ChannelRow(
                channel = station,
                number = station.number ?: (index + 1),
                playing = playing,
                m = m,
                onClick = { onPress(station) },
                favorite = station.id in favoriteIds,
                favoriteIcon = Icons.Rounded.Favorite,
                // The name of the station that sounds scrolls to be read whole, as the row the remote is on does.
                pressed = playing,
                // A line is kept only where the list says something with it: the category the station is filed under.
                caption = categories?.let { station.categoryIn(it) },
            )
        }
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

/** How often the sleep dialog redraws what is left, in milliseconds: often enough that a second never skips. */
private const val TICK_MS = 500L
