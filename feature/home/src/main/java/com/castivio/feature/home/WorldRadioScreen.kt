package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.CastivioSpinner
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.ltrToken
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.Channel
import com.castivio.domain.DirectoryFailure
import com.castivio.domain.WorldStation
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The radio of the world: the directory's stations on the board the subscription's radio has, beside it and apart from it.
 *
 * ## The same board, the other source
 *
 * The band over the panel, the rail on the leading side, the stations as the channels' own rows, and at the board's end the picture and the
 * plate -- the subscription radio's composables, given this source's words. What differs is what stands in the rail: the choice between the
 * two sources at its head, then the top of the world, the viewer's favourites and recents *of the world's stations*, a way to swap the
 * countries for the genres, and the countries -- named in the reader's language, each with how many stations it has.
 *
 * ## Apart from the subscription
 *
 * Nothing here reads the subscription and nothing the subscription keeps reads this: its own session, its own store for the favourites and
 * the recents, its own view-model. A station of the directory is drawn through the channels' row by a display-only stand-in
 * ([asRow]) and is never given to [RadioSession], to the shell's recently-watched or to the subscription's favourites.
 *
 * ## What each state says
 *
 * On its way, empty, no connection, the directory not answering, and a search with no result are each their own words -- see [WorldNotice]
 * -- and a failure has the press that asks again. The countries and the genres are kept on the device: the directory being out does not
 * empty the rail, it says the list is the saved one.
 */
@Composable
fun WorldRadioScreen(
    session: WorldRadioSession,
    onSource: (RadioSource) -> Unit,
    modifier: Modifier = Modifier,
    model: WorldRadioViewModel = hiltViewModel(),
) {
    val choice by model.choice.collectAsStateWithLifecycle()
    val typed by model.typed.collectAsStateWithLifecycle()
    val rail by model.rail.collectAsStateWithLifecycle()
    val favoriteIds by model.favoriteIds.collectAsStateWithLifecycle()
    val favorites by model.favorites.collectAsStateWithLifecycle()
    val recents by model.recents.collectAsStateWithLifecycle()
    val rows = model.stations.collectAsLazyPagingItems()
    val searching = typed.isNotBlank()
    val saved = if (searching) null else when (choice) {
        WorldChoice.Favorites -> favorites ?: SavedStations(emptyList(), emptySet())
        WorldChoice.Recents -> recents ?: SavedStations(emptyList(), emptySet())
        else -> null
    }
    val savedLoaded = when (choice) {
        WorldChoice.Favorites -> favorites != null
        WorldChoice.Recents -> recents != null
        else -> true
    }
    val locale = remember { Locale.getDefault() }
    val tv = CastivioTheme.device.isTv
    var sleepOpen by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    // A new list starts at its first row; the same list keeps its place.
    LaunchedEffect(choice, searching, typed.trim()) { listState.scrollToItem(0) }

    // The sleep timer holds the sound when it ends, as the viewer's own press would.
    val sleepAt = session.sleep.atMs
    LaunchedEffect(sleepAt) {
        if (sleepAt != null) {
            delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0L))
            session.sleepEnded()
        }
    }

    WorldListeningRecorder(session = session, model = model)

    BoxWithConstraints(
        modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        val m = channelsMetricsFor(tv = tv, width = maxWidth, height = maxHeight)

        // The open list, as the plate's neighbours and the stations' rows both read it: a saved list held whole, or the page window.
        val open = if (saved != null) {
            OpenStations(saved.stations.size) { saved.stations.getOrNull(it) }
        } else {
            OpenStations(rows.itemCount) { rows.peek(it) }
        }
        val station = session.station
        val neighbours = station?.let { current ->
            (neighbourOf(current.uuid, by = -1, count = open.count) { open.at(it)?.uuid } != null) to
                (neighbourOf(current.uuid, by = 1, count = open.count) { open.at(it)?.uuid } != null)
        }
        val plate = station?.let { current ->
            RadioPlateState(
                title = current.name,
                artworkUrl = current.favicon,
                category = current.countryCode?.let { countryNameOf(it, current.country ?: it, locale) },
                phase = radioPhaseOf(session.url ?: current.streamUrl, session.status, session.paused),
                favorite = current.uuid in favoriteIds,
                sleeping = sleepAt != null,
                canPrevious = neighbours?.first == true,
                canNext = neighbours?.second == true,
                // Only the stream that is playing: a rating belongs to the stream it was made from.
                quality = session.status.takeIf { it.url == session.url }?.quality,
            )
        }
        val actions = RadioPlateActions(
            onToggle = session::toggle,
            onPrevious = { stepIn(session, open, by = -1) },
            onNext = { stepIn(session, open, by = 1) },
            onFavorite = { session.station?.let(model::toggleFavorite) },
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
                list = stringResource(R.string.radio_world_in, worldListName(choice, typed.trim(), locale)),
                m = m,
                searchText = typed,
                onSearch = model::search,
                onSearchFocus = {},
                hint = stringResource(R.string.radio_world_search_hint),
            )

            Spacer(Modifier.height(m.headerGap))

            WorldBoard(
                m = m,
                rail = rail,
                choice = choice,
                searching = searching,
                typed = typed.trim(),
                saved = saved,
                savedLoaded = savedLoaded,
                rows = rows,
                listState = listState,
                locale = locale,
                playingId = session.station?.uuid.takeIf { session.status.soundingUrl != null },
                favoriteIds = favoriteIds,
                plate = plate,
                actions = actions,
                onPress = session::play,
                onChoose = model::choose,
                onFilter = model::filterRail,
                onShowTags = model::showTags,
                onRetryRail = model::retryRail,
                onClearSearch = { model.search("") },
                onSource = onSource,
                modifier = Modifier.weight(1f),
            )
        }

        // The plate opened, over the board, which goes on playing under it.
        if (opened && plate != null && !tv) {
            RadioNowPlaying(plate = plate, actions = actions, m = m, onClose = { opened = false })
        }

        if (sleepOpen) {
            BackHandler { sleepOpen = false }
            SleepDialog(
                minutes = session.sleep.minutes,
                atMs = session.sleep.atMs,
                onSet = { session.sleepIn(it, System.currentTimeMillis()) },
                onClose = { sleepOpen = false },
            )
        }
    }
}

/**
 * What listening says, written down once per listen: that a station sounded -- for the recents and for the directory's count of listens --
 * and what the listen proved about the stream, which is what keeps a station that does not play out of the lists for a while.
 *
 * Composed with the screen and gone with it. A listen is recorded against the number the session gave it ([WorldRadioSession.listen]), so a
 * report that arrives twice, or a station held and let go, is not written twice.
 */
@Composable
private fun WorldListeningRecorder(session: WorldRadioSession, model: WorldRadioViewModel) {
    var listenedAt by remember { mutableIntStateOf(NONE) }
    var provedAt by remember { mutableIntStateOf(NONE) }
    val listen = session.listen
    val sounding = session.status.soundingUrl
    val evidence = session.status.evidence
    LaunchedEffect(listen, sounding) {
        val station = session.station
        if (sounding != null && station != null && listenedAt != listen) {
            listenedAt = listen
            model.listened(station)
        }
    }
    LaunchedEffect(listen, evidence) {
        val station = session.station
        if (evidence != null && station != null && provedAt != listen) {
            provedAt = listen
            model.heard(station, evidence)
        }
    }
}

/** The open list's stations as the plate's neighbours read them: how many, and which is at a place. */
private class OpenStations(val count: Int, val at: (Int) -> WorldStation?)

/** The station [by] places from the playing one in the open list, played; nothing if there is none. */
private fun stepIn(session: WorldRadioSession, open: OpenStations, by: Int) {
    val current = session.station ?: return
    val target = neighbourOf(current.uuid, by, open.count) { open.at(it)?.uuid } ?: return
    open.at(target)?.let(session::play)
}

/** What the band calls the list that is open: the country's or the genre's name, one of the viewer's own lists, or what was searched. */
@Composable
private fun worldListName(choice: WorldChoice, query: String, locale: Locale): String = when {
    query.isNotEmpty() -> stringResource(R.string.radio_world_results, query)
    else -> when (choice) {
        WorldChoice.Top -> stringResource(R.string.radio_world_top)
        WorldChoice.Favorites -> stringResource(R.string.channels_favorites)
        WorldChoice.Recents -> stringResource(R.string.radio_recent)
        is WorldChoice.Country -> countryNameOf(choice.code, choice.code, locale)
        is WorldChoice.Tag -> choice.name.replaceFirstChar { it.titlecase(locale) }
    }
}

/**
 * The panel: the rail, the stations beside it as rows, and at the end the picture with the plate under it -- the subscription radio's panel,
 * with the same frame, padding, rail width and column at the end. Drawn left to right whatever the language, as the channels' columns are.
 */
@Composable
private fun WorldBoard(
    m: ChannelsMetrics,
    rail: WorldRailState,
    choice: WorldChoice,
    searching: Boolean,
    typed: String,
    saved: SavedStations?,
    savedLoaded: Boolean,
    rows: LazyPagingItems<WorldStation>,
    listState: LazyListState,
    locale: Locale,
    playingId: String?,
    favoriteIds: Set<String>,
    plate: RadioPlateState?,
    actions: RadioPlateActions,
    onPress: (WorldStation) -> Unit,
    onChoose: (WorldChoice) -> Unit,
    onFilter: (String) -> Unit,
    onShowTags: (Boolean) -> Unit,
    onRetryRail: () -> Unit,
    onClearSearch: () -> Unit,
    onSource: (RadioSource) -> Unit,
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
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                WorldRail(
                    rail = rail,
                    choice = choice,
                    searching = searching,
                    m = m,
                    onSource = onSource,
                    onChoose = onChoose,
                    onFilter = onFilter,
                    onShowTags = onShowTags,
                    onRetry = onRetryRail,
                    modifier = Modifier.width(m.rail).fillMaxHeight(),
                )

                Spacer(Modifier.width(m.railGap))

                val paneShape = RoundedCornerShape(m.wellRadius)
                Box(Modifier.weight(1f).fillMaxHeight().clip(paneShape).border(1.dp, colors.glassBorderSoft, paneShape)) {
                    if (saved != null) {
                        SavedList(
                            saved = saved,
                            loaded = savedLoaded,
                            favorites = choice == WorldChoice.Favorites,
                            m = m,
                            listState = listState,
                            locale = locale,
                            playingId = playingId,
                            favoriteIds = favoriteIds,
                            onPress = onPress,
                        )
                    } else {
                        PagedList(
                            rows = rows,
                            searching = searching,
                            typed = typed,
                            mixes = searching || choice !is WorldChoice.Country,
                            m = m,
                            listState = listState,
                            locale = locale,
                            playingId = playingId,
                            favoriteIds = favoriteIds,
                            onPress = onPress,
                            onClearSearch = onClearSearch,
                        )
                    }
                }

                Spacer(Modifier.width(m.playerGap))

                RadioSide(plate = plate, actions = actions, m = m, modifier = Modifier.width(m.player).fillMaxHeight())
            }
        }
    }
}

/**
 * The rail: the choice of source, the field that narrows the countries, the lists that are not a country, and then the countries -- or the
 * genres, once the viewer has asked for them. The directory's name stands under it, as the data's source.
 */
@Composable
private fun WorldRail(
    rail: WorldRailState,
    choice: WorldChoice,
    searching: Boolean,
    m: ChannelsMetrics,
    onSource: (RadioSource) -> Unit,
    onChoose: (WorldChoice) -> Unit,
    onFilter: (String) -> Unit,
    onShowTags: (Boolean) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Column(modifier) {
        RadioSourceSwitch(RadioSource.World, onSource, m)

        Spacer(Modifier.height(m.railEntryGap * 2))

        RailSearch(
            text = rail.filter,
            m = m,
            onType = onFilter,
            hint = stringResource(if (rail.tags) R.string.radio_world_search_genres else R.string.radio_world_search_countries),
        )

        Spacer(Modifier.height(m.railEntryGap * 2))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.railEntryGap)) {
            item(key = KEY_TOP) {
                RailEntry(
                    label = stringResource(R.string.radio_world_top),
                    count = null,
                    selected = !searching && choice == WorldChoice.Top,
                    m = m,
                    onClick = { onChoose(WorldChoice.Top) },
                )
            }
            item(key = KEY_FAVORITES) {
                RailEntry(
                    label = stringResource(R.string.channels_favorites),
                    count = null,
                    selected = !searching && choice == WorldChoice.Favorites,
                    m = m,
                    onClick = { onChoose(WorldChoice.Favorites) },
                )
            }
            item(key = KEY_RECENT) {
                RailEntry(
                    label = stringResource(R.string.radio_recent),
                    count = null,
                    selected = !searching && choice == WorldChoice.Recents,
                    m = m,
                    onClick = { onChoose(WorldChoice.Recents) },
                )
            }
            // The other of the rail's two lists: the genres while the countries are shown, and the countries again from them.
            item(key = KEY_KIND) {
                RailEntry(
                    label = stringResource(if (rail.tags) R.string.radio_world_countries else R.string.radio_world_genres),
                    count = null,
                    selected = false,
                    m = m,
                    onClick = { onShowTags(!rail.tags) },
                )
            }
            item(key = KEY_DIVIDER) { RailDivider(m) }

            when (val load = rail.load) {
                RailLoad.Loading -> item(key = KEY_NOTE) { RailNote(stringResource(R.string.radio_world_loading), null, m, {}) }
                is RailLoad.Unavailable -> item(key = KEY_NOTE) {
                    RailNote(
                        text = stringResource(
                            if (load.reason == DirectoryFailure.OFFLINE) R.string.radio_world_offline_title else R.string.radio_world_down_title,
                        ),
                        action = stringResource(R.string.radio_retry),
                        m = m,
                        onAction = onRetry,
                    )
                }
                is RailLoad.Ready -> {
                    if (load.fromSavedList) item(key = KEY_NOTE) { RailNote(stringResource(R.string.radio_world_saved_list), null, m, {}) }
                    items(load.entries, key = { it.key }) { entry ->
                        RailEntry(
                            label = entry.label,
                            count = entry.count,
                            selected = !searching && choice == entry.choice,
                            m = m,
                            onClick = { onChoose(entry.choice) },
                        )
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.radio_world_credit, ltrToken(DIRECTORY_NAME)),
            style = castivioBodyStyle(m.frame.fsBody),
            color = colors.onBackgroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = m.railPadH, vertical = m.railEntryGap),
        )
    }
}

/** A line the rail says in place of a list that is not there, with the press that asks again when asking again can help. */
@Composable
private fun RailNote(text: String, action: String?, m: ChannelsMetrics, onAction: () -> Unit) {
    val colors = CastivioTheme.colors
    val (focused, focusModifier) = rememberFocusFlag()
    val shape = RoundedCornerShape(m.previewRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, if (focused) colors.focusRing else colors.glassBorderSoft, shape)
            .then(if (action != null) focusModifier.clickable(onClick = onAction) else Modifier)
            .padding(horizontal = m.railPadH, vertical = m.badgePadV * 2),
    ) {
        Text(
            text = text,
            style = castivioBodyStyle(m.frame.fsBody),
            color = colors.onBackgroundMuted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null) {
            Text(
                text = action,
                style = castivioChipStyle(m.frame.fsBody),
                color = colors.primary,
                maxLines = 1,
                modifier = Modifier.padding(top = m.badgePadV),
            )
        }
    }
}

/** The favourites or the recents, held whole: the station that rests among them is dimmed and never left out. */
@Composable
private fun SavedList(
    saved: SavedStations,
    loaded: Boolean,
    favorites: Boolean,
    m: ChannelsMetrics,
    listState: LazyListState,
    locale: Locale,
    playingId: String?,
    favoriteIds: Set<String>,
    onPress: (WorldStation) -> Unit,
) {
    if (!loaded) {
        WorldNotice(title = stringResource(R.string.radio_world_loading), detail = null, action = null, onAction = {}, m = m, busy = true)
        return
    }
    if (saved.stations.isEmpty()) {
        WorldNotice(
            title = stringResource(if (favorites) R.string.radio_favorites_empty_title else R.string.radio_recent_empty_title),
            detail = stringResource(if (favorites) R.string.radio_favorites_empty_detail else R.string.radio_recent_empty_detail),
            action = null,
            onAction = {},
            m = m,
        )
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(m.wellPad / 2),
        contentPadding = PaddingValues(bottom = m.wellPad),
    ) {
        itemsIndexed(saved.stations, key = { _, station -> station.uuid }) { index, station ->
            WorldStationRow(
                station = station,
                number = index + 1,
                playing = station.uuid == playingId,
                favorite = station.uuid in favoriteIds,
                dimmed = station.uuid in saved.dimmed,
                withCountry = true,
                m = m,
                locale = locale,
                onPress = onPress,
            )
        }
    }
}

/** A list of the directory, a page at a time, with a state of its own for each way it may not have rows to show. */
@Composable
private fun PagedList(
    rows: LazyPagingItems<WorldStation>,
    searching: Boolean,
    typed: String,
    mixes: Boolean,
    m: ChannelsMetrics,
    listState: LazyListState,
    locale: Locale,
    playingId: String?,
    favoriteIds: Set<String>,
    onPress: (WorldStation) -> Unit,
    onClearSearch: () -> Unit,
) {
    val refresh = rows.loadState.refresh
    if (rows.itemCount == 0) {
        when {
            refresh is LoadState.Error -> {
                val offline = (refresh.error as? WorldDirectoryException)?.reason == DirectoryFailure.OFFLINE
                WorldNotice(
                    title = stringResource(if (offline) R.string.radio_world_offline_title else R.string.radio_world_down_title),
                    detail = stringResource(if (offline) R.string.radio_world_offline_detail else R.string.radio_world_down_detail),
                    action = stringResource(R.string.radio_retry),
                    onAction = { rows.retry() },
                    m = m,
                )
            }
            refresh is LoadState.NotLoading && refresh.endOfPaginationReached && searching -> WorldNotice(
                title = stringResource(R.string.radio_world_no_results, typed),
                detail = stringResource(R.string.radio_world_no_results_detail),
                action = stringResource(R.string.search_clear),
                onAction = onClearSearch,
                m = m,
            )
            refresh is LoadState.NotLoading && refresh.endOfPaginationReached -> WorldNotice(
                title = stringResource(R.string.radio_world_empty),
                detail = null,
                action = null,
                onAction = {},
                m = m,
            )
            else -> WorldNotice(title = stringResource(R.string.radio_world_loading), detail = null, action = null, onAction = {}, m = m, busy = true)
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(m.wellPad / 2),
        contentPadding = PaddingValues(bottom = m.wellPad),
    ) {
        items(rows.itemCount, key = rows.itemKey { it.uuid }) { index ->
            val station = rows[index] ?: return@items
            WorldStationRow(
                station = station,
                number = index + 1,
                playing = station.uuid == playingId,
                favorite = station.uuid in favoriteIds,
                dimmed = false,
                withCountry = mixes,
                m = m,
                locale = locale,
                onPress = onPress,
            )
        }
        // The end of what was read: more on its way, or the press that asks for it again.
        val append = rows.loadState.append
        if (append is LoadState.Error) {
            item(key = KEY_MORE) {
                Text(
                    text = stringResource(R.string.radio_world_more_failed),
                    style = castivioChipStyle(m.frame.fsBody),
                    color = CastivioTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable { rows.retry() }.padding(vertical = m.wellPad),
                )
            }
        } else if (append is LoadState.Loading) {
            item(key = KEY_MORE) {
                Box(Modifier.fillMaxWidth().padding(vertical = m.wellPad), contentAlignment = Alignment.Center) { CastivioSpinner() }
            }
        }
    }
}

/**
 * One station, in the channels' own row: the number on its plate, the logo, the name, and under it what the directory said of it -- its country
 * where the list mixes countries, and what it plays at. A station that rests is dimmed and says so.
 */
@Composable
private fun WorldStationRow(
    station: WorldStation,
    number: Int,
    playing: Boolean,
    favorite: Boolean,
    dimmed: Boolean,
    withCountry: Boolean,
    m: ChannelsMetrics,
    locale: Locale,
    onPress: (WorldStation) -> Unit,
) {
    val rate = station.bitrate.takeIf { it > 0 }?.let { stringResource(R.string.radio_world_kbps, it) }
    val country = if (withCountry) station.countryCode?.let { countryNameOf(it, station.country ?: it, locale) } else null
    val caption = if (dimmed) {
        stringResource(R.string.radio_world_resting)
    } else {
        worldStationCaption(station, country, rate)
    }
    ChannelRow(
        channel = remember(station.uuid, station.name, station.favicon) { station.asRow() },
        number = number,
        playing = playing,
        m = m,
        onClick = { onPress(station) },
        modifier = if (dimmed) Modifier.alpha(DIMMED) else Modifier,
        favorite = favorite,
        favoriteIcon = Icons.Rounded.Favorite,
        // The name of the station that sounds scrolls to be read whole, as the row the remote is on does.
        pressed = playing,
        caption = caption,
    )
}

/**
 * A station as the channels' row draws it: its name, its logo and its address, and nothing the row does not read.
 *
 * **For drawing only.** It is made in the row, for the row, and is not kept, not given to a session, not written anywhere and not played from:
 * what is pressed is the [WorldStation], and the player is asked for its address by the world's own session. A [Channel] that left this
 * function would be a station of the directory taken for one of the subscription's.
 */
private fun WorldStation.asRow() = Channel(
    id = uuid,
    title = name,
    artworkUrl = favicon,
    number = null,
    groupId = null,
    streamUrl = streamUrl,
    epgChannelId = null,
)

/** What the panel says where there are no rows: on its way, nothing here, or the directory out of reach -- with the press that asks again. */
@Composable
private fun WorldNotice(
    title: String,
    detail: String?,
    action: String?,
    onAction: () -> Unit,
    m: ChannelsMetrics,
    busy: Boolean = false,
) {
    val colors = CastivioTheme.colors
    Column(
        Modifier.fillMaxSize().padding(m.wellPad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.nameGap, Alignment.CenterVertically),
    ) {
        if (busy) CastivioSpinner()
        Text(
            text = title,
            style = castivioChipStyle(m.frame.fsLabel),
            color = colors.onBackground,
            textAlign = TextAlign.Center,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = castivioBodyStyle(m.frame.fsBody),
                color = colors.onBackgroundVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            CastivioButton(
                text = action,
                onClick = onAction,
                weight = ButtonWeight.Secondary,
                labelStyle = castivioChipStyle(m.frame.fsChip),
            )
        }
    }
}

/** What stands in the rail for the directory, in the credit line: its name, as it asks to be named. */
internal const val DIRECTORY_NAME = "radio-browser.info"

/** How dim a resting station's row is drawn: still readable, plainly not the ones that play. */
private const val DIMMED = 0.5f

private const val NONE = -1
private const val KEY_TOP = "w:top"
private const val KEY_FAVORITES = "w:favorites"
private const val KEY_RECENT = "w:recent"
private const val KEY_KIND = "w:kind"
private const val KEY_DIVIDER = "w:divider"
private const val KEY_NOTE = "w:note"
private const val KEY_MORE = "w:more"
