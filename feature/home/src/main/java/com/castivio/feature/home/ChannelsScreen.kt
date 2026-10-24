package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.castivio.core.common.format.CastivioDates
import com.castivio.core.design.components.CastivioMiddleEllipsisText
import com.castivio.core.design.components.DelayedSpinner
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.ErrorState
import com.castivio.core.design.components.ProviderArtwork
import com.castivio.core.design.components.CastivioClock
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.components.formatCount
import com.castivio.core.design.components.ltrToken
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.platform.CastivioTrace
import com.castivio.core.platform.PerfSection
import com.castivio.core.platform.PerformanceLog
import com.castivio.domain.CatalogQuery
import com.castivio.domain.Channel
import com.castivio.domain.PressVerdict
import com.castivio.domain.pressVerdictFor
import com.castivio.domain.MediaItem
import com.castivio.domain.Programme
import com.castivio.domain.SectionLoad
import com.castivio.domain.PlayerPulse
import com.castivio.domain.PulsePhase
import com.castivio.domain.StreamQuality
import com.castivio.domain.SortOrder
import com.castivio.domain.isProviderHeading
import java.util.Calendar
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.castivio.core.design.R as DesignR

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
 *
 * ## Two things the design shows, and what this draws instead of inventing them
 *
 *  1. **The stream's resolution.** The design prints `1920 × 1080` beside the channel
 *     number. That is a property of a *decoded* stream, known to the engine after the
 *     player has opened it and to nothing on a board that has opened nothing. So a quality
 *     badge -- SD, HD, FHD or 4K -- is drawn only for a channel that has been measured: its
 *     picture was drawn and the decoder gave a size, which the board keeps. A channel nobody
 *     has opened has no badge, and no name is ever read as one.
 *  2. **The channel's logo.** `artwork_url` is imported, stored and drawn on each row through
 *     `ProviderArtwork`. Where a channel has none the slot is reserved and empty, so the names
 *     start in the same place on every row.
 */
@Composable
fun ChannelsScreen(
    onPlay: (CatalogSelection) -> Unit,
    /**
     * Make the picture the full screen, on the channel already playing.
     *
     * The board cannot do this itself: expanding is the shell swapping one shape of the
     * player for the other, and `:feature:home` does not know the player exists. It is the
     * very action the compact player's own press already performs — passed in so the name
     * under the picture can reach it without a second route to the same place.
     *
     * A press with nothing playing does nothing; the shell holds that condition, because
     * the shell is what holds the request.
     */
    onExpand: () -> Unit,
    /**
     * What the player is doing, or null when there is no player: the stream it was asked for,
     * whether that has a picture yet, and what the picture measures.
     *
     * Told to the board rather than found by it, for the reason [preview] is a slot: this
     * module does not know the player exists. It is the player's own answer -- see
     * `PlayerState.pulse` -- and not the last press, so a channel that was pressed and has not
     * drawn yet, or that failed, is not marked, its preview does not claim to be live, and a
     * mark does not move until the next channel has actually played. It is the only thing that
     * says a row is playing: where the remote is says nothing about it.
     */
    pulse: PlayerPulse? = null,
    /**
     * What goes in the preview plate when something is playing.
     *
     * A slot, not a player, and the module graph is the reason: `:feature:home` does not
     * depend on `:feature:player` and must not start — the board would then carry an
     * engine contract, a subtitle module and a set of playback types it has no business
     * knowing. The shell is the one place that knows both exist, so the shell passes the
     * compact player in and this screen only decides where it goes.
     *
     * Null is the board with nothing playing, which is what it drew before this existed:
     * the focused channel's mark and its programme strip.
     */
    preview: (@Composable () -> Unit)? = null,
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
    /** Which channels are locked, and the password that opens them. See [LockUi]. */
    lockModel: ChannelLockViewModel = hiltViewModel(),
    /** Which channels are favourites, and the picks of the mode that adds or removes several at once. */
    favoritesModel: ChannelFavoritesViewModel = hiltViewModel(),
    /** What the viewer is choosing to hide, and the write that hides it. See [HidingUi]. */
    hidingModel: ChannelHidingViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { model.show(CatalogSection.Live) }

    val state by model.state.collectAsStateWithLifecycle()
    val previewed by previewModel.preview.collectAsStateWithLifecycle()
    // Collected here and not inside the overlay, so the page's state survives the
    // overlay being closed and re-opened on the same channel: a second look costs no
    // query at all, which is what makes the store the cache.
    val fullGuide by previewModel.fullGuide.collectAsStateWithLifecycle()
    val qualities by previewModel.quality.collectAsStateWithLifecycle()
    val searchText by model.searchText.collectAsStateWithLifecycle()
    // Whether the header's field has the remote. The list takes the first focus when it first has
    // rows; it must not take it back from a field somebody is typing into when the results come
    // and go under their keys.
    var searchFocused by remember { mutableStateOf(false) }
    val tv = CastivioTheme.device.isTv

    // Back, in reorder mode, puts down what is held, and when nothing is held leaves the mode as
    // Cancel does -- the viewer who presses it has seen the bar and what it offers. Not a way out
    // of the screen while the mode is on: a half-made order is not something to walk away from.
    BackHandler(enabled = state.arranging != null) {
        if (state.arranging?.held != null) model.drop() else model.cancel()
    }

    // **The Favourite button: one press for the channel in the picture, a held press for several.**
    // Pressed, it marks the channel or takes the mark away at once -- the star it always was. Held, it
    // starts picking, which is not reorder mode: a press on a row picks or puts back, and the same
    // button then adds the picks or -- when they all are favourites -- removes them. Nothing here moves
    // a channel, changes an order, or touches what is playing: the picks are a set of ids, and the one
    // write is `FavoritesRepository.set`. Back leaves the mode with nothing written.
    val favoriteIds by favoritesModel.ids.collectAsStateWithLifecycle()
    val picked by favoritesModel.picked.collectAsStateWithLifecycle()
    BackHandler(enabled = picked != null) { favoritesModel.cancel() }

    // **The button and the stars say the same thing at the same moment.** The well's own flag was a
    // second read of the favourites, which lit the button long before the row's star came in; both are
    // drawn from [ChannelFavoritesViewModel.ids] now, which shows a press at once.
    val shown = remember(previewed, favoriteIds) {
        previewed.copy(favorite = previewed.channel?.id?.let { it in favoriteIds } == true)
    }
    val shownId = shown.channel?.id
    val favorites = remember(favoriteIds, picked, shownId) {
        FavoritesUi(
            ids = favoriteIds,
            picked = picked,
            toggle = favoritesModel::toggle,
            press = { if (picked == null) shownId?.let(favoritesModel::flip) else favoritesModel.apply() },
            hold = { if (picked == null) favoritesModel.begin() },
        )
    }

    // **Hiding, which is neither reorder mode nor picking favourites.** The Hide button starts it; the
    // rail's bouquets and the list's channels then carry the same circles, and a press on either ticks
    // it or puts it back -- a bouquet, or one channel of the bouquet that is open, or both. The bar's Hide
    // selected hides what is ticked, and Cancel -- and Back -- leave with nothing hidden. The three modes
    // are three holders and the strip answers one at a time. Hidden is local: the lists read around it,
    // and Settings brings it back.
    val hidingNow by hidingModel.hiding.collectAsStateWithLifecycle()
    BackHandler(enabled = hidingNow != null) { hidingModel.cancel() }
    val hider = remember(hidingNow) {
        HidingUi(
            hiding = hidingNow,
            toggleBouquet = hidingModel::toggleBouquet,
            toggleChannel = hidingModel::toggleChannel,
            confirm = hidingModel::confirm,
            cancel = hidingModel::cancel,
            press = hidingModel::begin,
            hold = hidingModel::begin,
        )
    }

    // **A channel with a picture on it tells the board what it measures.** Only a stream that is
    // showing -- the player's word, not the press -- and only for the channel that was pressed to
    // start it, so a channel that is opening or failed is never recorded at the last one's size.
    // The badge on a row and in the well is read back from what is recorded here, and from nowhere
    // else: not from the channel's name.
    val playingChannel = shown.channel?.takeIf { pulse?.playingUrl == it.streamUrl }
    val frame = pulse?.frame
    LaunchedEffect(playingChannel?.id, frame) {
        if (playingChannel != null && frame != null) previewModel.measured(playingChannel.id, frame)
    }

    // **The lock: what a press on a channel does about it, and the one dialog that asks.**
    //
    // A locked channel does not start until the password has been typed, so a press on one asks
    // first and starts only when it is given -- from the list, and from a search that narrowed the
    // list, which are the same rows. The strip's Lock button asks as well: to lock the channel in the
    // well, and, when it is already locked, to open it. Nothing is asked of a channel that is not
    // locked. The second press on the one that is already in the picture makes the picture large and
    // starts nothing, and the shell asks for the password before it does when that channel is locked --
    // see `expandPreview` there.
    val lockedIds by lockModel.locked.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf<PinAsk?>(null) }
    val scope = rememberCoroutineScope()
    val latestPlay by rememberUpdatedState(onPlay)
    val latestState by rememberUpdatedState(state)
    val lock = remember(lockedIds) {
        LockUi(
            locked = lockedIds,
            start = { channel, number ->
                val begin = {
                    // Selecting and playing are one act, and both wait for the password: a board that
                    // named the locked channel in its well while the picture was another one is the
                    // contradiction the press was made to avoid.
                    previewModel.select(channel, number)
                    // With the list it was pressed in: the bouquet and the search the rows are those
                    // of. The large player's channel keys step through that list, in its order.
                    val list = CatalogQuery(
                        kind = CatalogSection.Live.kind,
                        groupId = latestState.selectedGroup,
                        sort = latestState.sort,
                        search = latestState.search,
                        favorites = latestState.favorites,
                        // The keys step through the channel's own bouquet from here -- see `rank` for why.
                        recent = latestState.recent,
                    )
                    channel.asSelection()?.copy(listed = list)?.let(latestPlay)
                    Unit
                }
                when (pressVerdictFor(live = true, channelId = channel.id, known = lockModel.locked.value)) {
                    PressVerdict.Start -> begin()
                    PressVerdict.Ask -> { asking = PinAsk(PinPurpose.Play, begin) }
                    PressVerdict.Wait -> {
                        scope.launch {
                            if (lockModel.isLocked(channel.id)) asking = PinAsk(PinPurpose.Play, begin) else begin()
                        }
                    }
                }
            },
            toggle = { channel ->
                asking = if (channel.id in lockModel.locked.value.orEmpty()) {
                    PinAsk(PinPurpose.Unlock) { lockModel.unlock(channel.id) }
                } else {
                    PinAsk(PinPurpose.Lock) { lockModel.lock(channel.id) }
                }
            },
        )
    }

    // **The board does not move for the keyboard.**
    //
    // This was `safeDrawingPadding()`, and `safeDrawing` is the system bars, the cutout *and the IME*:
    // with a search field typed into, the box shrank to the strip above the keys and every metric below
    // was measured again from it -- the whole board squeezed and stretched each time the keyboard came
    // and went. The navigation bar did the same at the side, because Android forces it on for as long as
    // a keyboard is up (`InsetsPolicy.getNavControlTarget`), whatever the window asked for.
    //
    // So the board is padded by what stays put: the cutout, and the status bar should one ever be
    // showing. The keyboard and the navigation bar it brings are drawn over the board, which keeps its
    // size; both search fields are in the band at the top, above the keys. With neither on screen this
    // is the padding it always had -- `MainActivity` keeps the bars hidden, and bars swiped back in for a
    // moment are drawn over the window without changing its insets.
    BoxWithConstraints(
        modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
    ) {
        val m = channelsMetricsFor(tv = tv, width = maxWidth, height = maxHeight)

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = m.edge)
                .padding(top = m.boardTop, bottom = m.boardBottom),
        ) {
            BoardHeader(
                state = state,
                m = m,
                searchText = searchText,
                onSearch = model::search,
                onSearchFocus = { searchFocused = it },
            )

            Spacer(Modifier.height(m.headerGap))

            Board(
                state = state,
                shown = shown,
                fullGuide = fullGuide,
                m = m,
                model = model,
                previewModel = previewModel,
                onExpand = onExpand,
                pulse = pulse,
                qualities = qualities,
                preview = preview,
                lock = lock,
                favorites = favorites,
                hider = hider,
                typing = searchFocused,
                modifier = Modifier.weight(1f),
            )
        }

        asking?.let { ask ->
            PinPrompt(
                purpose = ask.purpose,
                onGranted = {
                    asking = null
                    ask.granted()
                },
                onDismiss = { asking = null },
                model = lockModel,
            )
        }
    }
}

/**
 * The lock, as the board's columns see it: which channels are locked, and the two things a press can
 * ask of it.
 *
 * Gathered in one value so the three places that draw a lock -- the row, the strip's button, and the
 * press -- are handed one thing and not a set and two lambdas each.
 */
@Immutable
internal class LockUi(
    /** The locked channels' ids, or null until they have been read: no lock is drawn before then. */
    val locked: Set<String>?,
    /** A press on a channel, outside reorder mode: start it, asking for the password first if it is locked. */
    val start: (Channel, Int) -> Unit,
    /** The strip's Lock button, for the channel in the well: lock it, or -- locked already -- open it. */
    val toggle: (Channel) -> Unit,
) {
    fun isLocked(channelId: String): Boolean = locked?.contains(channelId) == true
}

/** A password asked for, and what is done when it is given. */
private class PinAsk(val purpose: PinPurpose, val granted: () -> Unit)

/**
 * The favourites, as the board's columns see them: which channels are favourites, which are picked
 * while the board is picking, and the two things a press can do.
 *
 * Gathered in one value, like [LockUi], so the rows, the strip's button and the list's empty state
 * are handed one thing. [picked] is null outside the mode, and a board that is not picking draws
 * exactly what it always drew.
 */
@Immutable
internal class FavoritesUi(
    /** Every favourite's id. */
    val ids: Set<String>,
    /** The channels picked so far, or null when the board is not picking. */
    val picked: Set<String>?,
    /** A press on a row while picking: the channel is picked, or put back. */
    val toggle: (String) -> Unit,
    /**
     * The strip's Favourite button pressed: mark the channel in the picture, or take the mark away --
     * and, while picking, add the picks or remove them and leave the mode.
     */
    val press: () -> Unit,
    /** The same button held: start picking several. Nothing while the board is picking already. */
    val hold: () -> Unit = {},
) {
    val picking: Boolean get() = picked != null

    /** What the button's word and act are while picking: remove, when every pick already is a favourite. */
    val removes: Boolean get() = picked != null && picksRemove(picked, ids)

    /** What a row is told while picking, or null when it is not. */
    fun markOf(channelId: String): RowPick? =
        picked?.let { RowPick(picked = channelId in it, favorite = channelId in ids) }

    companion object {
        /** A board that is not told about favourites: nothing to draw, nothing picked, nothing to press. */
        val None = FavoritesUi(ids = emptySet(), picked = null, toggle = {}, press = {})
    }
}

/** What a channel row is told while the board is picking favourites: picked or not, and a favourite or not. */
internal data class RowPick(val picked: Boolean, val favorite: Boolean)

/* ---------------------------------------------------------------- the header */

/**
 * One thin band, in the order the approved drawing sets: mark, breadcrumb, search,
 * dates.
 *
 * ## What it carries, and what came off it
 *
 * The mark, which section and which category, the search, and the two dates that decide
 * whether anything on this screen still works.
 *
 * Off it went a clock, the provider's hostname, an Active/Inactive chip and the version
 * number. The clock went with the system bars — this is a screen for watching television
 * and neither the phone's time nor a second copy of it is one of the facts a viewer
 * opened it for. The hostname is a credential rather than a fact anybody reads, and it
 * was long enough on its own to push the dates off the band.
 *
 * ## The two dates are two different things
 *
 *  - **Subscription** is the provider's. When it lapses the streams stop.
 *  - **Licence** is Castivio's. When it lapses the app stops.
 *
 * A viewer whose picture has gone needs to know which of the two ran out, so both are
 * drawn and both are captioned.
 *
 * ## Each date is one string, and that is load-bearing
 *
 * The caption and the value were two composables side by side, which pins their physical
 * order: in Arabic the value ended up *before* its caption and the line read `2026/09/17
 * ينتهي الاشتراك`. One `Text` holding `"caption: value"` lets the text engine place them,
 * and it places them correctly in both directions from the same source — caption first,
 * value after it, in Arabic and in English alike.
 *
 * The band is laid out left to right in every language, like the columns under it. That
 * is the whole of the difference between languages on this screen: none of these boxes
 * moves, and only the glyphs inside them are shaped by their own script.
 */
@Composable
private fun BoardHeader(
    state: BrowseState,
    m: ChannelsMetrics,
    searchText: String,
    onSearch: (String) -> Unit,
    onSearchFocus: (Boolean) -> Unit,
) {
    val colors = CastivioTheme.colors

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier.fillMaxWidth().height(m.header),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // **Exactly as wide as the rail column beneath it.**
            //
            // Which is what puts the breadcrumb after it at the channel list's own
            // leading edge -- directly above the slot each row reserves for a channel
            // logo. It was sitting against the wordmark, and `Live TV` read as part of
            // the product's name rather than as which section is open.
            Row(
                Modifier.width(m.rail + m.railGap),
                verticalAlignment = Alignment.CenterVertically,
                // Centred in the cell rather than pushed against its leading edge. The
                // cell is exactly the rail's width, so the mark now sits over the middle
                // of the bouquet column instead of over its corner.
                horizontalArrangement = Arrangement.spacedBy(m.osdGap, Alignment.CenterHorizontally),
            ) {
                Image(
                    painter = painterResource(DesignR.drawable.castivio_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(m.header * MARK_OF_HEADER),
                )
                Text(
                    text = stringResource(R.string.gate_wordmark),
                    style = castivioTitleStyle(m.frame.fsLabel * WORDMARK_OF_LABEL),
                    color = colors.onBackgroundStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Bounded, because the second line is a *provider's* words — see
            // `ChannelsMetrics.crumb`. An unbounded cell let one long category name
            // decide where everything after it on the band sat.
            Column(Modifier.width(m.crumb)) {
                Text(
                    text = stringResource(R.string.browse_live),
                    style = castivioChipStyle(m.frame.fsLabel),
                    color = colors.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.selectedGroup?.let { state.categoryNames[it] }
                        ?: stringResource(
                            when {
                                state.favorites -> R.string.channels_favorites
                                state.recent -> R.string.channels_recent
                                else -> R.string.channels_all_rail
                            },
                        ),
                    style = castivioBodyStyle(m.frame.fsBody * LEGEND_OF_BODY),
                    color = colors.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // **The field is the only child of this band that may shrink,** and that is
            // the fix for the defect the device showed: the dates were being squeezed
            // until they dropped their value.
            //
            // A `Row` measures its unweighted children first, each with what the ones
            // before it left, and only then hands the remainder to the weighted ones. So
            // making this the sole weighted child inverts the old order of sacrifice:
            // the mark, the breadcrumb, the clock and the dates all take their declared
            // widths first, and whatever is left is the field's. It shrinks, and it is
            // the right thing to shrink — a search field shows its icon and less of its
            // hint and still does its whole job, where a narrower date column simply
            // stops saying the date.
            //
            // Centring it in that remainder is also what keeps it in the middle of the
            // band, which two weighted spacers used to do.
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                HeaderSearchField(
                    text = searchText,
                    m = m,
                    onType = onSearch,
                    onFocusChange = onSearchFocus,
                    // Reorder mode is of the lists as they are: nothing to narrow while it is on.
                    enabled = state.arranging == null,
                )
            }

            // **The clock, at the band's trailing end, where the two expiry dates were.**
            //
            // The dates are gone at the owner's instruction. They answered a question a
            // viewer asks twice a year -- when does my subscription run out -- from a
            // corner of a screen they look at every day, and they were the widest thing
            // on the band. What a viewer actually wants from that corner is the time,
            // which the app has to draw itself because it hides the system bars.
            //
            // Where the two dates ran out is still a fact worth knowing, and it is not
            // lost: Home draws both, captioned and side by side, on a screen with room
            // to say which is which.
            BoardClock(m)
        }
    }
}

/**
 * The time, and today's date under it.
 *
 * Both isolated and both in the header's own fixed shape: the date is a `dd-MM-yyyy`
 * token rather than the reader's own convention, because the band pins one physical
 * order in every language. [ltrToken] carries the marks that keep it from reading
 * backwards in Arabic, which an earlier attempt at this did.
 *
 * **It has the band's trailing corner to itself now.** The two expiry dates that shared
 * it are gone, so the cell is theirs plus its own and the time is drawn a step larger:
 * this is the clock a viewer reads instead of the phone's, which the app hides.
 */
@Composable
private fun BoardClock(m: ChannelsMetrics) {
    // The system's clock, which is twenty-four hours in every language now -- the format
    // this board used to hand in as its own. It was a second clock written out in full
    // here before that, which is two tick rates and two answers to what the
    // bidirectional algorithm does to a date.
    //
    // Its own bounded cell, for the reason the breadcrumb has one: nothing whose width
    // is text may decide where the rest of the band sits.
    CastivioClock(
        fsTime = m.frame.fsLabel * CLOCK_OF_LABEL,
        fsDate = m.frame.fsBody * LEGEND_OF_BODY,
        modifier = Modifier.width(m.dates),
    )
}

/**
 * The rail's own field: it filters a list already in memory.
 *
 * Unlike the header's — which asks the database, paused for 120 ms and answered by the
 * index — this one types into a few hundred names the holder is already holding, so there
 * is nothing to debounce and no query to cancel.
 */
@Composable
private fun RailSearch(text: String, m: ChannelsMetrics, onType: (String) -> Unit) {
    val colors = CastivioTheme.colors
    val (focused, focusModifier) = rememberFocusFlag()
    val shape = RoundedCornerShape(Radius.pill)

    Row(
        Modifier
            .fillMaxWidth()
            .height(m.railMin * FIELD_OF_ROW)
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, if (focused) colors.focusRing else colors.glassBorderSoft, shape)
            .padding(horizontal = m.railPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.factGap),
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = colors.onBackgroundMuted,
            modifier = Modifier.size(Sizing.iconSm),
        )
        BasicTextField(
            value = text,
            onValueChange = onType,
            singleLine = true,
            textStyle = castivioBodyStyle(m.frame.fsBody).copy(color = colors.onBackground),
            cursorBrush = SolidColor(colors.secondary),
            modifier = Modifier.weight(1f).then(focusModifier),
            decorationBox = { field ->
                // The placeholder is drawn under the field rather than inside it, so an
                // empty box says what it is for without the hint becoming the value.
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.channels_rail_search),
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

/**
 * The search, in the middle of the band: a field, and the channels narrow as it is typed in.
 *
 * ## What it replaced
 *
 * A button. Pressing it left the board for the catalogue-wide search screen, and leaving the
 * board stopped the preview -- a viewer who wanted to find a channel while another was
 * playing lost the picture to do it, and came back to a list that had not moved. The owner
 * asked for the field that was already here to work where it stands, not for a second one.
 *
 * ## What it is
 *
 * The same band, the same pill and the same hint; what changed is that it takes text. The
 * holder keeps what was typed ([BrowseViewModel.searchText]) and narrows the rows to it after
 * a 120 ms pause -- by name, whatever the case and however many spaces, from the first
 * letters of each word -- and emptying the field is the list that was open before. Nothing
 * here touches what is playing: the player and the preview are not parameters of this.
 *
 * ## The remote
 *
 * A field a remote can focus is a trap if it keeps the key it was given, so it keeps none that
 * a list needs: up and down leave it as they leave any row. The keyboard's search key -- the one
 * that says "I'm done" -- hides the keyboard and leaves the field focused with its text and its
 * results, as the rail's field does; the next press of down is what moves on. [onFocusChange]
 * tells the board whether the field has the remote, so the list does not take it back whenever
 * the results change under a viewer's keys.
 */
@Composable
internal fun HeaderSearchField(
    text: String,
    m: ChannelsMetrics,
    onType: (String) -> Unit,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit = {},
    enabled: Boolean = true,
) {
    val colors = CastivioTheme.colors
    val (focused, focusModifier) = rememberFocusFlag()
    val shape = RoundedCornerShape(Radius.pill)

    Row(
        modifier
            .width(m.search)
            .height(m.header * FIELD_OF_HEADER)
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, if (focused) colors.focusRing else colors.glassBorderSoft, shape)
            .padding(horizontal = m.controlPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.factGap),
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = colors.onBackgroundMuted,
            modifier = Modifier.size(Sizing.iconSm),
        )
        BasicTextField(
            value = text,
            onValueChange = onType,
            enabled = enabled,
            singleLine = true,
            textStyle = castivioBodyStyle(m.frame.fsBody).copy(color = colors.onBackground),
            cursorBrush = SolidColor(colors.secondary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // The search key hides the keyboard and leaves the field where it is, text and results
            // as they are -- what the rail's field does with the keyboard's own default action. The
            // channels narrow as they are typed, so there is nothing left for the key to start.
            keyboardActions = KeyboardActions(
                onSearch = { defaultKeyboardAction(ImeAction.Done) },
            ),
            modifier = Modifier
                .weight(1f)
                .then(focusModifier)
                .onFocusChanged { onFocusChange(it.isFocused) },
            decorationBox = { field ->
                // Under the field rather than inside it, so an empty box says what it is for
                // without the hint becoming the value.
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.channels_search_hint),
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

/**
 * The panel: the four columns.
 *
 * It is never composed before the section has a category on the device -- Home opens it
 * with its first one, and the rest join the rail -- so the fetch states it has to answer
 * for are the first load arriving unannounced, a refresh running behind the rows and a
 * failed one, and the question a failure asks is whether it is the whole truth or a line
 * over a catalogue that is already here.
 */
@Composable
private fun Board(
    state: BrowseState,
    shown: ChannelPreview,
    fullGuide: FullGuide,
    m: ChannelsMetrics,
    model: BrowseViewModel,
    previewModel: ChannelsViewModel,
    onExpand: () -> Unit,
    pulse: PlayerPulse?,
    qualities: Map<String, StreamQuality>,
    preview: (@Composable () -> Unit)?,
    lock: LockUi,
    favorites: FavoritesUi,
    hider: HidingUi,
    /** The header's field has the remote: see [ChannelList]. */
    typing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.panelRadius)
    // Whether this APK may print a failure's figures: debuggable, as for the performance
    // panel, and for the same reason -- a property of the installed package, read once.
    val context = LocalContext.current
    val instrumented = remember(context) { PerformanceLog.isVisibleIn(context) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(m.panelPad),
    ) {
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
                    // In a debuggable build, the failure's figures in place of the sentence:
                    // stage, status, exception, bytes, entries. "Something went wrong"
                    // could not be diagnosed from a television. See `fetchReadout`.
                    detail = state.fetchReadout?.takeIf { instrumented }
                        ?: stringResource(fetch.error.fetchFailureDetail()),
                    actionLabel = stringResource(R.string.browse_fetch_retry),
                    onAction = model::refresh,
                )
            }

            else -> Column(Modifier.weight(1f)) {
                state.arranging?.let { mode ->
                    ReorderBar(mode = mode, m = m, onSave = model::save, onCancel = model::cancel)
                }
                // Choosing what to hide has its own band, of the same shape and apart from the reorder
                // bar: the two modes are never on at once.
                hider.hiding?.let { choosing ->
                    HideBar(hiding = choosing, m = m, onConfirm = hider.confirm, onCancel = hider.cancel)
                }

                // The failure still gets said, as a line rather than as a wall. A
                // refresh that did not work is worth knowing about; it is not worth
                // the catalogue.
                FetchNotice(fetch = fetch, firstLoad = state.firstLoad, m = m, onRetry = model::refresh)

                Columns(
                    state = state,
                    shown = shown,
                    fullGuide = fullGuide,
                    m = m,
                    model = model,
                    previewModel = previewModel,
                    onExpand = onExpand,
                    pulse = pulse,
                    qualities = qualities,
                    preview = preview,
                    lock = lock,
                    favorites = favorites,
                    hider = hider,
                    typing = typing,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One line about this section's load, over a board that has rows: a refresh running behind
 * it, or one that failed.
 *
 * Drawn only when there is something to say and something already on screen to say it
 * over. It is deliberately not a dialog and not a full-screen state: the catalogue
 * behind it is usable, and taking a working screen away to report a failed refresh is
 * how a user learns to dismiss messages without reading them.
 *
 * A refresh still running is said with the state vocabulary's loading mark -- a word, in
 * the working amber (`UI_ARCHITECTURE.md` §5.1) -- because the rail's refresh is pressed
 * here and this is where its answer is looked for. Its progress is on Home's card.
 *
 * **The section's first load is not said at all.** The board opened with its first
 * category and the rest join the rail as they complete; the owner's rule is that the viewer
 * does not feel that, so no word, no bar and no "loaded" after it. Only a failure is said,
 * or the rail would stay short with nobody knowing why.
 */
@Composable
private fun FetchNotice(
    fetch: SectionLoad?,
    firstLoad: Boolean,
    m: ChannelsMetrics,
    onRetry: () -> Unit,
) {
    when (fetch) {
        is SectionLoad.Queued, is SectionLoad.Loading -> if (!firstLoad) RefreshingNotice(m)
        is SectionLoad.Failed -> FailedNotice(fetch, m, onRetry)
        else -> Unit
    }
}

/** The refresh behind the rows: one word in the working amber, and nothing to press. */
@Composable
private fun RefreshingNotice(m: ChannelsMetrics) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)
    Text(
        text = stringResource(R.string.home_load_refreshing),
        style = castivioBodyStyle(m.frame.fsBody),
        color = colors.warning,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = m.headerGap)
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(horizontal = m.controlPadH, vertical = m.badgePadV * 2),
    )
}

/** A refresh that failed over a board that has rows; pressing it asks again. */
@Composable
private fun FailedNotice(
    fetch: SectionLoad.Failed,
    m: ChannelsMetrics,
    onRetry: () -> Unit,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = m.headerGap)
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.selectedBorder, shape)
            .clickable(onClick = onRetry)
            .padding(horizontal = m.controlPadH, vertical = m.badgePadV * 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.factGap),
    ) {
        // The cause, not a blanket "did not answer": a refused playlist and an
        // unreachable one are different things to do next. See `fetchFailureDetail`.
        Text(
            text = stringResource(fetch.error.fetchFailureDetail()),
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

/**
 * The three columns, in one physical order in every language — and so is everything
 * inside them.
 *
 * ## Why this board does not mirror
 *
 * The third column is **video**, and video does not mirror. A viewer who switches the
 * interface to Arabic has not moved their television, and finding the picture on the
 * other side of the screen is a worse answer than reading a channel list left to right.
 * The approved design says so explicitly and shows both directions side by side.
 *
 * ## It is the whole board, not only the arrangement
 *
 * The first attempt pinned the columns and then carried the reader's own direction past
 * the pin, restoring it inside each cell — so the boxes stayed put while the text in them
 * right-aligned in Arabic. That is a third layout: neither the mirrored screen the
 * platform would give nor the screen every other language gets, and on the device the
 * category names started at the far side of the rail from where the drawing puts them.
 *
 * The approved behaviour is simpler and is what this now does: one direction for the
 * board, in all thirty-seven languages. Nothing moves, and what a script shapes is the
 * order of glyphs inside a line — which the text engine does on its own, from the text,
 * without a layout direction to help it.
 *
 * This is a deliberate, single exception to invariant 9, scoped to this composable.
 * Nothing else in Castivio opts out of mirroring and nothing here makes it easier to.
 */
@Composable
private fun Columns(
    state: BrowseState,
    shown: ChannelPreview,
    fullGuide: FullGuide,
    m: ChannelsMetrics,
    model: BrowseViewModel,
    previewModel: ChannelsViewModel,
    onExpand: () -> Unit,
    pulse: PlayerPulse?,
    qualities: Map<String, StreamQuality>,
    preview: (@Composable () -> Unit)?,
    lock: LockUi,
    favorites: FavoritesUi,
    hider: HidingUi,
    typing: Boolean,
    modifier: Modifier = Modifier,
) {
    val rows = model.items.collectAsLazyPagingItems()
    val listState = rememberLazyListState()

    var guideOpen by remember { mutableStateOf(false) }

    val listFocus = remember { FocusRequester() }
    val wellFocus = remember { FocusRequester() }

    // **Into reorder mode, and out of it, the remote goes to the list.** Going in it was on the
    // strip's Reorder button, which stops answering in the mode; coming out it was on the bar's
    // Save or Cancel, which is not drawn any more. A remote left on nothing is one that seems to
    // have stopped answering. Not on the first composition: the list takes the remote itself when
    // it first has rows.
    val arranging = state.arranging != null
    // Choosing what to hide is a mode with a bar of its own, and the remote is taken the same way: the
    // Hide button stops answering in it, and the bar's buttons are not on the way to anything.
    val modal = arranging || hider.active
    var wasModal by remember { mutableStateOf(false) }
    LaunchedEffect(modal) {
        if (modal != wasModal) {
            wasModal = modal
            runCatching { listFocus.requestFocus() }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxSize()) {
                CategoryRail(
                    state = state,
                    m = m,
                    onChoose = model::choose,
                    onFilter = model::filterGroups,
                    onRefresh = model::refresh,
                    onPress = model::press,
                    onMove = model::moveHeld,
                    onMark = model::mark,
                    onFavorites = model::openFavorites,
                    onRecent = model::openRecent,
                    hider = hider,
                    modifier = Modifier.width(m.rail).fillMaxHeight(),
                )

                Spacer(Modifier.width(m.railGap))

                ChannelList(
                    rows = rows,
                    state = state,
                    m = m,
                    listState = listState,
                    // The stream the player has a picture of, so the list can mark it. The
                    // player's answer and nothing derived from the preview or the remote: the
                    // row the well is *about* is the last press, and a press is not a picture.
                    playingUrl = pulse?.playingUrl,
                    // The stream the player was asked for, drawn or not: a press on the channel
                    // that is already in the picture makes the picture large -- see
                    // `isExpandingPress`.
                    askedUrl = pulse?.url,
                    qualities = qualities,
                    // Which row was pressed to start it, so a stream listed twice marks one.
                    pressedId = shown.channel?.id,
                    lock = lock,
                    favorites = favorites,
                    hider = hider,
                    onExpand = onExpand,
                    typing = typing,
                    onClearSearch = { model.search("") },
                    onPress = model::press,
                    onMove = model::moveHeld,
                    onMark = model::mark,
                    onPlace = model::placeHeld,
                    onShowAll = { model.choose(null) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusRequester(listFocus)
                        .focusGroup()
                        // Right out of the list is the well, and nothing else. Stated
                        // rather than left to the geometric search, because the strip's
                        // buttons are all to the list's right and the nearest one is not
                        // the one a viewer means.
                        .focusProperties { right = wellFocus },
                )

                // **Always here.** It was composed only once something was playing, so
                // the board changed shape on the first press of a channel: two columns
                // became three and every row moved sideways under the remote. The owner
                // asked for a board that does not move, and that is also the right
                // answer — a jump on the press that starts a stream reads as a fault.
                //
                // With nothing playing the well draws its idle plate and its buttons are
                // dark, which is a screen saying what it can do rather than a gap.
                Spacer(Modifier.width(m.playerGap))

                ChannelWell(
                    shown = shown,
                    m = m,
                    pulse = pulse,
                    qualities = qualities,
                    preview = preview,
                    onOpenGuide = { guideOpen = true },
                    // One press marks the channel in the picture or unmarks it; held, the button starts
                    // the mode that adds or removes several at once, and a press then ends it -- see
                    // `FavoritesUi`.
                    onFavorite = favorites.press,
                    onFavoriteHold = favorites.hold,
                    favoriting = favorites.picking,
                    favoriteRemoves = favorites.removes,
                    // The Hide button starts choosing bouquets and channels to hide -- see `HidingUi`.
                    onHide = hider.press,
                    onHideHold = hider.hold,
                    hiding = hider.active,
                    locked = shown.channel?.let { lock.isLocked(it.id) } == true,
                    onLock = { shown.channel?.let(lock.toggle) },
                    arranging = arranging,
                    onArrange = model::arrange,
                    onExpand = onExpand,
                    modifier = Modifier
                        .width(m.player)
                        .fillMaxHeight()
                        .focusRequester(wellFocus)
                        .focusGroup()
                        .focusProperties { left = listFocus },
                )
            }

            if (guideOpen) {
                // Asked for here rather than in the view model's own init, because this
                // is the moment a person asked. `openGuide` reads storage first and only
                // reaches the provider when what is stored runs out; see
                // `ChannelsViewModel.loadFullGuide`.
                LaunchedEffect(shown.channel?.id) { previewModel.openGuide() }
                ChannelGuideOverlay(
                    shown = shown,
                    guide = fullGuide,
                    m = m,
                    onClose = { guideOpen = false },
                )
            }
        }
    }
}

/* --------------------------------------------------------------- the actions */

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
internal fun CategoryRail(
    state: BrowseState,
    m: ChannelsMetrics,
    onChoose: (String?) -> Unit,
    onFilter: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    /** OK on a bouquet in reorder mode: pick it up, or put it down. */
    onPress: (Held) -> Unit = {},
    /** The arrow keys, or the two taps, on the bouquet that is held. */
    onMove: (up: Boolean) -> Unit = {},
    /** A long press, or OK held, on a bouquet in reorder mode: tick it to be moved with the others that are. */
    onMark: (Held) -> Unit = {},
    /** The Favourites entry: list the viewer's favourite channels. */
    onFavorites: () -> Unit = {},
    /** The Recently watched entry: list the channels the viewer watched, the last first. */
    onRecent: () -> Unit = {},
    /** Choosing bouquets to hide: each entry is ticked by a press, and opens nothing. */
    hider: HidingUi = HidingUi.None,
) {
    // **In reorder mode the rail is the bouquets and nothing else -- and so it is while bouquets are
    // being chosen to hide.** The three entries above them open lists and the field filters them; none
    // of that is what is being done, and an "all" that could be pressed would leave a mode the viewer
    // is half way through.
    val mode = state.arranging
    val modal = mode != null || hider.bouquets
    Column(modifier) {
        // **The rail's own field.** It filters the categories in this column and nothing
        // else: a provider with four hundred bouquets is a column nobody scrolls to the
        // end of, and the field is how a viewer reaches "SPORT" without passing 300
        // others. Filtered in the holder, not here -- see `BrowseState.groupFilter`.
        if (!modal) {
            RailSearch(text = state.groupFilter, m = m, onType = onFilter)

            Spacer(Modifier.height(m.railEntryGap * 2))
        }

        // The held bouquet is kept on screen by the column, which knows where the move put it --
        // see `keepInView`. In reorder mode the bouquets are the column's only rows.
        val railState = rememberLazyListState()
        val heldBouquet = (mode?.held as? Held.Bouquet)?.id
        LaunchedEffect(railState, heldBouquet, state.shownGroups) {
            if (heldBouquet != null) railState.keepInView(state.shownGroups.indexOfFirst { it.id == heldBouquet })
        }

        LazyColumn(
            state = railState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(m.railEntryGap),
        ) {
            if (!modal) item(key = RAIL_ALL) {
                RailEntry(
                    label = stringResource(R.string.channels_all_rail),
                    // **No total here.** The line under the name said how many channels the
                    // subscription carries, which Home's Live TV card already says -- the
                    // same figure drawn twice, on the one row whose job is to mean "all of
                    // them". The bouquets below keep their own count, which nothing else on
                    // the board gives.
                    count = null,
                    // Not while the favourites are open: they also have no category, and two entries
                    // lit for one list is not an answer to "which list is this".
                    selected = state.selectedGroup == null && !state.favorites && !state.recent,
                    m = m,
                    onClick = { onChoose(null) },
                )
            }
            if (!modal) item(key = RAIL_FAVORITES) {
                RailEntry(
                    label = stringResource(R.string.channels_favorites),
                    // No count: "Favourites: 0" before anything has been marked is a true sentence
                    // nobody needs, and the entry is one line tall like the others that are not
                    // bouquets.
                    count = null,
                    selected = state.favorites,
                    m = m,
                    onClick = onFavorites,
                )
            }
            if (!modal) item(key = RAIL_RECENT) {
                RailEntry(
                    label = stringResource(R.string.channels_recent),
                    // No count, as for the favourites: the entry is one line tall like the others that are
                    // not bouquets.
                    count = null,
                    // It opened nothing: its press was empty, and nothing recorded what was watched. The rows
                    // are now the channels whose picture showed, the last first -- see `RecentChannelsViewModel`.
                    selected = state.recent,
                    m = m,
                    onClick = onRecent,
                )
            }

            if (!modal) item(key = RAIL_DIVIDER) { RailDivider(m) }

        // **A provider with no categories says so.**
        //
        // This was two thirds of the rail's height left blank, under a divider that
        // promised a list. A viewer reading it cannot tell a playlist that genuinely
        // carries no bouquets from a catalogue that failed to record them, and neither
        // could the screen — the catalogue holds the channels and holds no groups, and
        // nothing on either side of the query says which of the two happened.
        //
        // So the rail states the condition and offers the one action that resolves it
        // in either case: a forced re-import. A playlist with no `group-title` comes
        // back the same way and the line stands; a catalogue that lost its groups comes
        // back with them. It is the same call the strip's refresh makes.
            if (!modal && state.noCategories) {
                item(key = RAIL_EMPTY) { RailEmpty(m = m, onRefresh = onRefresh) }
            }

            // The filtered list, so the field actually does something; `groups` stays
            // whole for the rows that look a category's name up by id.
            //
            // **The categories that are complete, and only those** -- see `listedOf`. While
            // the section's first load runs, each one that completes is inserted in its
            // place, keyed by id, so a row the remote is on keeps its focus and the column
            // does not move under it.
            items(state.shownGroups, key = { it.id }) { group ->
                RailEntry(
                    label = group.name,
                    // The denormalised column, read rather than counted.
                    // `RoomCatalogWriter` has filled `item_count` at the end of every
                    // import since the schema existed, and until `MediaGroup` carried it
                    // the rail had nowhere to read it from -- so the value was written
                    // for nobody and the reference's "Total: N" could not be drawn. It
                    // is one field on a list of hundreds, not a query per row.
                    count = group.itemCount,
                    // In reorder mode no bouquet is "selected": the fill is for the one held, and the
                    // bouquet whose channels are on the right is outlined -- see `RailEntry`.
                    selected = !modal && group.id == state.selectedGroup,
                    m = m,
                    onClick = when {
                        // Choosing what to hide: a press ticks the bouquet or puts it back, and opens nothing.
                        hider.bouquets -> ({ hider.toggleBouquet(group.id) })
                        mode == null -> ({ onChoose(group.id) })
                        else -> ({ onPress(Held.Bouquet(group.id)) })
                    },
                    arrange = mode?.let { rowArrangeOf(Held.Bouquet(group.id), it, onMove, onMark) },
                    owner = mode != null && group.id == mode.channelsOf,
                    pick = hider.bouquetMarkOf(group.id),
                )
            }
        }
    }
}

/**
 * What a row is told in reorder mode: whether it is the one picked up, whether it is carried with that
 * one or ticked to be, and what a long press does -- tick it.
 */
private fun rowArrangeOf(
    row: Held,
    mode: Arranging,
    onMove: (up: Boolean) -> Unit,
    onMark: (Held) -> Unit,
) = RowArrange(
    held = mode.held == row,
    onMove = onMove,
    carried = mode.held != row && row in mode.carried,
    marked = mode.held == null && row in mode.marked,
    onMark = { onMark(row) },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailEntry(
    label: String,
    count: Int?,
    selected: Boolean,
    m: ChannelsMetrics,
    onClick: () -> Unit,
    /** Told in reorder mode, null otherwise: whether this bouquet is the one held, and how to move it. */
    arrange: RowArrange? = null,
    /** In reorder mode, this bouquet's channels are the ones listed on the right. */
    owner: Boolean = false,
    /**
     * While bouquets are chosen to hide: whether this one is ticked. Null outside the mode, and an entry
     * that is not told draws what it always drew. A ticked entry takes the board's *chosen* pair of
     * colours, as a picked channel row does, and a tick in the shape of the row's own marks.
     */
    pick: RowPick? = null,
) {
    val colors = CastivioTheme.colors
    val (focused, focusModifier) = rememberFocusFlag()
    val shape = RoundedCornerShape(m.previewRadius)
    val interaction = remember { MutableInteractionSource() }
    val held = arrange?.held == true || arrange?.carried == true
    val ticked = pick?.picked == true || arrange?.marked == true

    // Selection is the surface and focus is the ring around it — the design system's
    // rule, and on this board both are true of a row at once while a viewer arrows past
    // the category that is currently applied.
    //
    // `onBackground`, the channel list's own ink, and not the variant this used to take.
    // The two columns sit side by side and carry the same kind of thing — a name the
    // provider wrote — so a bouquet reading a shade dimmer than a channel made the rail
    // look like furniture around the list rather than half of the same board.
    val ink = if (selected || held) colors.onSecondary else colors.onBackground

    // **A one-line entry is one line tall.** Every entry took `railMin`, which is the height of
    // the two lines a bouquet carries -- its name and its count -- so "Favorites" and
    // "Recently watched", with one line each, stood in rows twice as tall as their text and
    // read as two words a long way apart. A bouquet's rhythm is not theirs; theirs is a line
    // and its breathing room, and it is the same for all of them.
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (count != null) m.railMin else m.railMin * SINGLE_OF_RAIL)
            .clip(shape)
            .background(
                when {
                    selected || held -> colors.secondary
                    ticked -> colors.secondaryContainer
                    else -> Color.Transparent
                },
            )
            .border(
                1.dp,
                when {
                    focused || held -> colors.focusRing
                    selected || owner || ticked -> colors.selectedBorder
                    else -> Color.Transparent
                },
                shape,
            )
            .movedByArrows(arrange)
            .then(if (pick != null) Modifier.semantics { this.selected = ticked } else Modifier)
            .then(focusModifier)
            .pressOrHold(interaction, onClick = onClick, onHold = arrange?.onMark)
            .padding(horizontal = m.railPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.railPadH),
    ) {
        // **A name too long for the rail scrolls on the entry the remote is on, and on the one that was
        // pressed -- the channel list's own rule, asked of the same function.** An entry whose name fits
        // does not move, because a marquee only runs when its text is wider than the room it has; one
        // that is cut stands cut, as it always did, until the remote or a press reaches it. Moving on
        // takes the modifier away, so the name is back at its first letter the next time it is read.
        // The count under it is a number and stays where it is.
        val scrolls = nameScrolls(focused = focused, pressed = selected)
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = castivioChipStyle(m.frame.fsLabel),
                color = ink,
                maxLines = 1,
                overflow = if (scrolls) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = if (scrolls) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,
            )
            // The reference's second line. Absent rather than zero for the entries
            // that are not categories -- "Favourites: 0" before anything has been
            // favourited is a true sentence nobody needs, and the count for those
            // comes from a different store anyway.
            if (count != null) {
                Text(
                    text = stringResource(R.string.channels_rail_total, formatCount(count)),
                    style = castivioBodyStyle(m.frame.fsBody),
                    color = if (selected || held) ink else colors.onBackgroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        arrange?.let { ArrangeTail(it, ink, m.railMin * ARROW_OF_ROW) }
        pick?.let { PickMark(it, size = m.railMin * ARROW_OF_ROW, onFill = selected || held) }
    }
}

/** The rail's own empty state: what is missing, and the press that settles it. */
@Composable
private fun RailEmpty(m: ChannelsMetrics, onRefresh: () -> Unit) {
    val colors = CastivioTheme.colors
    val (focused, focusModifier) = rememberFocusFlag()
    val shape = RoundedCornerShape(m.previewRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, if (focused) colors.focusRing else colors.glassBorderSoft, shape)
            .then(focusModifier)
            .clickable(onClick = onRefresh)
            .padding(horizontal = m.railPadH, vertical = m.badgePadV * 2),
    ) {
        Text(
            text = stringResource(R.string.channels_rail_empty),
            style = castivioBodyStyle(m.frame.fsBody),
            color = colors.onBackgroundMuted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.channels_action_refresh),
            style = castivioChipStyle(m.frame.fsBody),
            color = colors.primary,
            maxLines = 1,
            modifier = Modifier.padding(top = m.badgePadV),
        )
    }
}

@Composable
private fun RailDivider(m: ChannelsMetrics) {
    val colors = CastivioTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = m.railPadH, vertical = m.railDivider / 2)
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
    /** Hoisted so the board can scroll this list to the channel it restores on entry. */
    listState: LazyListState,
    /** The stream the player has a picture of, or null when it has none. */
    playingUrl: String?,
    /** The stream the player was asked for -- drawn yet or not -- or null when there is no player. */
    askedUrl: String?,
    /** What each channel has been measured at; a channel with no entry has no badge. */
    qualities: Map<String, StreamQuality>,
    /** The row pressed last, which is the row that started the stream playing. */
    pressedId: String?,
    /** Which channels are locked, and the press that asks for the password before one of them plays. */
    lock: LockUi,
    /** Which channels are favourites and which are picked, while the board is picking. */
    favorites: FavoritesUi,
    /** Which channels are ticked, while the board is choosing channels to hide. */
    hider: HidingUi,
    /** Make the picture that is playing the large one: the second press on its channel. */
    onExpand: () -> Unit,
    /**
     * The header's field has the remote. Rows come and go under a viewer's keys as the search
     * narrows, and the list's first-focus rule must not answer by taking the remote from the
     * field they are typing in.
     */
    typing: Boolean,
    onClearSearch: () -> Unit,
    /** OK on a row in reorder mode: pick it up, or put it down. */
    onPress: (Held) -> Unit,
    /** The arrow keys, or the two taps, on the row that is held. */
    onMove: (up: Boolean) -> Unit,
    /** A long press, or OK held, on a row in reorder mode: tick it to be moved with the others that are. */
    onMark: (Held) -> Unit,
    /** The channels carried let go by a thumb: put before this channel, or -- null -- at the end. */
    onPlace: (beforeId: String?) -> Unit,
    /** The list of favourites has nothing in it: leave it for the whole section. */
    onShowAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.wellRadius)
    val mode = state.arranging

    // The instant the board first has something a viewer can act on. The same boundary
    // `BrowseScreen` reports, reported from the screen that now draws Live — so the
    // Phase B baseline for CHANNELS keeps measuring the same thing it did before.
    var contentSeen by remember { mutableStateOf(false) }
    val hasContent = rows.itemCount > 0
    LaunchedEffect(hasContent) {
        if (hasContent && !contentSeen) {
            contentSeen = true
            CastivioTrace.instant(CastivioTrace.FIRST_CONTENT)
            PerformanceLog.firstContent(PerfSection.CHANNELS)
        }
    }

    // **No fill.** The column used to paint `backgroundElevated`, a solid, over the
    // app's own gradient -- so the one part of the board a viewer spends their time
    // reading was a flat dark slab cut out of a lit page, and it looked like a hole. It
    // is a *frame* around the list, not a surface under it: the hairline says where the
    // column is and the page shows through. The rail beside it has never had a fill and
    // never needed one.
    Box(
        modifier
            .clip(shape)
            .border(1.dp, colors.glassBorderSoft, shape),
    ) {
        if (rows.itemCount == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ChannelListEmpty(
                    loading = rows.loadState.refresh is LoadState.Loading,
                    search = state.search,
                    onClearSearch = onClearSearch,
                    onRefresh = rows::refresh,
                    favorites = state.favorites,
                    recent = state.recent,
                    onShowAll = onShowAll,
                )
            }
            return@Box
        }

        // Focus lands on the first row when the board opens, so a remote has somewhere
        // to be. Without it the first press of any arrow key goes nowhere, which on a
        // television reads as the screen having hung.
        val first = remember { FocusRequester() }
        FirstRowFocus(hasContent = hasContent, fieldHasIt = typing, first = first)

        // The held channel, kept on screen by the list as the rows come back from a move -- read
        // from the rows the list has, off composition, each time they change.
        val heldChannel = (mode?.held as? Held.Channel)?.id
        LaunchedEffect(listState, rows, heldChannel) {
            if (heldChannel == null) return@LaunchedEffect
            snapshotFlow { rows.itemSnapshotList.indexOfFirst { it?.id == heldChannel } }
                .distinctUntilChanged()
                .collect { index -> listState.keepInView(index) }
        }

        // A thumb drags the channels carried -- the one picked up, or the ticked ones with it -- and lets
        // them go anywhere in the bouquet; the arrows still move them a place at a time, and a remote has
        // only those. Where the finger is while it drags, and null when it is not dragging.
        var dragY by remember { mutableStateOf<Float?>(null) }
        val canDrag = mode?.channelsOf != null && mode.held is Held.Channel
        val carriedIds: Set<String> = remember(mode) { mode?.carried.orEmpty().mapTo(HashSet()) { it.id } }
        val density = LocalDensity.current
        val lineColor = colors.focusRing
        // Held near the top or the bottom, the list scrolls under the finger, so a place off screen --
        // the first of the bouquet included -- can be reached in one drag. Only the start and the end of a
        // drag are read here: the finger's every move would otherwise recompose the list.
        val dragAt by remember { derivedStateOf { dragY != null } }
        LaunchedEffect(listState, dragAt) {
            if (!dragAt) return@LaunchedEffect
            val edge = with(density) { DragEdge.toPx() }
            val fastest = with(density) { DragScrollFastest.toPx() }
            while (true) {
                withFrameNanos { }
                val y = dragY ?: break
                val step = edgeScrollStep(y, listState.layoutInfo.viewportSize.height.toFloat(), edge, fastest)
                if (step != 0f) listState.scrollBy(step)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(m.wellPad / 2)
                .dragToPlace(
                    listState = listState,
                    enabled = canDrag,
                    carried = { index -> rows.peek(index)?.id?.let { it in carriedIds } == true },
                    onMove = { dragY = it },
                    onDrop = { index ->
                        val target = dropTarget(
                            index = index,
                            count = rows.itemCount,
                            idAt = { rows.peek(it)?.id },
                            carried = carriedIds,
                            complete = rows.loadState.append.endOfPaginationReached,
                        )
                        when (target) {
                            is DropTarget.Before -> onPlace(target.id)
                            DropTarget.End -> onPlace(null)
                            null -> Unit
                        }
                    },
                )
                // The line that says where they will go, drawn while the finger drags. Read at draw, so a
                // move of the finger redraws the line and recomposes nothing.
                .drawWithContent {
                    drawContent()
                    val y = dragY ?: return@drawWithContent
                    val shown = listState.shownRows()
                    val at = dropIndexAt(shown, y)?.let { dropLineAt(shown, it) } ?: return@drawWithContent
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, at.toFloat()),
                        end = Offset(size.width, at.toFloat()),
                        strokeWidth = DropLine.toPx(),
                    )
                },
            contentPadding = PaddingValues(bottom = m.wellPad),
        ) {
            items(rows.itemCount, key = rows.itemKey { it.id }) { index ->
                val item = rows[index] ?: return@items
                val channel = item as? Channel ?: return@items
                // In reorder mode a press picks the row up instead of playing it, and only where
                // there is a bouquet whose channels can be moved; with "all" open the rows are as
                // they were, and a press does nothing -- the bar says why.
                val arrange = mode?.takeIf { it.channelsOf != null }
                    ?.let { rowArrangeOf(Held.Channel(channel.id), it, onMove, onMark) }
                ChannelRow(
                    channel = channel,
                    arrange = arrange,
                    // The reference's badge: where this row is in the list as ordered,
                    // one-based. See `ChannelsViewModel.position` for why it is not read
                    // off the channel.
                    number = index + 1,
                    playing = isPlayingRow(channel.id, channel.streamUrl, pressedId, playingUrl),
                    quality = qualities[channel.id],
                    locked = lock.isLocked(channel.id),
                    favorite = channel.id in favorites.ids,
                    // While the board is picking favourites each row says whether it is picked and
                    // whether it already is a favourite; outside the mode, null, and nothing is drawn.
                    pick = favorites.markOf(channel.id) ?: hider.channelMarkOf(channel.id),
                    m = m,
                    pressed = mode == null && channel.id == pressedId,
                    // **Moving is not choosing, and that distinction is the whole of how
                    // this screen behaves now.**
                    //
                    // Focus used to select: arrowing down a list changed what the preview
                    // column said. That was harmless while the column only drew a mark and
                    // a guide. It is not harmless now, because the well also *plays* — and
                    // a well whose picture is one channel while its name and its guide are
                    // the channel the remote happens to be passing over is a well that
                    // contradicts itself. Worse, tuning on every arrow would open a stream
                    // per row on a list of 55,000.
                    //
                    // So focus does nothing at all here, and pressing is what selects: it
                    // sets the picture, the name, the number and the guide together, from
                    // one act.
                    onClick = when {
                        // Picking: a press picks the channel or puts it back. It plays nothing, asks
                        // for no password and makes nothing large -- what is in the picture stays.
                        favorites.picking -> ({ favorites.toggle(channel.id) })
                        // Choosing what to hide: the same press, ticking the channel, and nothing is played.
                        hider.channels -> ({ hider.toggleChannel(channel.id) })
                        mode == null -> ({
                            // The first press on a channel plays it in the preview; the next on
                            // the same channel makes that picture large, as a press on the picture
                            // does. A remote's OK and a thumb are the same press.
                            if (isExpandingPress(channel.id, channel.streamUrl, pressedId, askedUrl)) {
                                onExpand()
                            } else {
                                lock.start(channel, index + 1)
                            }
                        })
                        mode.channelsOf != null -> ({ onPress(Held.Channel(channel.id)) })
                        else -> ({ })
                    },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}

/**
 * What the list says when it has no rows: that they are on their way, that a search matched
 * nothing, or that the category is empty.
 *
 * "The query has not answered yet" and "there is nothing to answer with" look identical on a
 * television and mean opposite things, so they are drawn differently here rather than both as
 * an empty state -- and a search that matched nothing is a third thing: it names what was
 * asked, in the catalogue search screen's own words, and its one action leaves the search.
 */
@Composable
internal fun ChannelListEmpty(
    loading: Boolean,
    search: String,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    /** The list is the viewer's favourites, and none is marked yet. */
    favorites: Boolean = false,
    /** The list is the channels watched last, and none has been watched yet. */
    recent: Boolean = false,
    /** What the favourites' -- and the recent channels' -- empty state offers: the whole section. */
    onShowAll: () -> Unit = {},
) {
    when {
        loading -> DelayedSpinner()
        search.isNotEmpty() -> EmptyState(
            title = stringResource(R.string.search_none_title, search),
            detail = stringResource(R.string.search_none_detail),
            actionLabel = stringResource(R.string.search_clear),
            onAction = onClearSearch,
        )
        // No favourites is not "no channels": nothing is missing from the catalogue, the viewer has
        // not marked any. Said so, and the one action is to go where they can mark them.
        favorites -> EmptyState(
            title = stringResource(R.string.channels_favorites_empty_title),
            detail = stringResource(R.string.channels_favorites_empty_detail),
            actionLabel = stringResource(R.string.channels_all_rail),
            onAction = onShowAll,
        )
        // Nothing watched yet is not "no channels" either: said so, and the one action is the whole section.
        recent -> EmptyState(
            title = stringResource(R.string.channels_recent_empty_title),
            detail = stringResource(R.string.channels_recent_empty_detail),
            actionLabel = stringResource(R.string.channels_all_rail),
            onAction = onShowAll,
        )
        else -> EmptyState(
            title = stringResource(R.string.channels_empty_title),
            detail = stringResource(R.string.channels_empty_detail),
            actionLabel = stringResource(R.string.browse_empty_action),
            onAction = onRefresh,
        )
    }
}

/**
 * Focus lands on the first row when the list first has rows, so a remote has somewhere to be.
 * Without it the first press of any arrow key goes nowhere, which on a television reads as the
 * screen having hung.
 *
 * Not when the header's field has the remote. Rows come and go under a viewer's keys as the
 * search narrows -- a word with no match and then one with, an emptied field -- and each time
 * the list has rows again this would take the remote out of the field in the middle of a word.
 */
@Composable
internal fun FirstRowFocus(hasContent: Boolean, fieldHasIt: Boolean, first: FocusRequester) {
    val held by rememberUpdatedState(fieldHasIt)
    LaunchedEffect(hasContent) { if (hasContent && !held) runCatching { first.requestFocus() } }
}

// `basicMarquee` is stable from Compose Foundation 1.7, which is the line this BOM
// pins. Declared anyway: an opt-in that turns out to be unnecessary costs a warning,
// and one that turns out to be necessary costs a red CI run.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChannelRow(
    channel: Channel,
    number: Int,
    /** True of the one row whose stream the player has a picture of. See the rule below. */
    playing: Boolean,
    m: ChannelsMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * What this channel was last measured at, or null when it never has been -- which draws no
     * badge. Never read from the name: a name is a claim, and the badge is a fact.
     */
    quality: StreamQuality? = null,
    /**
     * In reorder mode: whether this row is the one picked up, and how to move it. Null outside the
     * mode, and a row that is not told draws what it always drew. A held row takes the focused
     * look -- it is the one the remote is carrying -- and shows the two taps in place of the handle.
     */
    arrange: RowArrange? = null,
    /**
     * This is the row that was pressed, outside reorder mode. Its name scrolls to be read whole,
     * as the remote's row does -- see [nameScrolls].
     */
    pressed: Boolean = false,
    /**
     * This channel is locked: a small lock closes the row, and pressing it asks for the password
     * before anything plays. Drawn on the row and not said in its name -- the name is the provider's.
     */
    locked: Boolean = false,
    /**
     * This channel is a favourite: a small star follows the name, so the row itself says that the
     * Favourite button took -- not only the button, lit, while this channel is the one in the picture.
     */
    favorite: Boolean = false,
    /**
     * While the board is picking favourites: whether this row is picked, and whether it already is a
     * favourite. Null outside the mode, and a row that is not told draws what it always drew. A picked
     * row takes the board's *chosen* pair of colours -- the one the strip's lit buttons wear -- and never
     * the violet, which stays the remote's.
     */
    pick: RowPick? = null,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    // The one picked up, and -- when it was picked up with ticked ones -- the others carried with it: all of
    // them take the look of a row that is held. A ticked row, before anything is picked up, takes the
    // *chosen* pair of colours below, as a picked favourite does.
    val held = arrange?.held == true || arrange?.carried == true
    val picked = pick?.picked == true || arrange?.marked == true

    // **Where the remote is, and what is playing, are two facts and the row draws them apart.**
    //
    // The violet is the remote: it is on this row, and it moves with every arrow. The mark is
    // the player: this row's stream has a picture on it, and it stays put while the remote
    // goes on down the list. Before, the violet was the last channel *pressed*, and it stayed
    // lit while the remote moved on -- so the one colour answered "where am I" on one row and
    // "what am I watching" on another, and a viewer could not tell which of the two a violet
    // row was saying. Neither fact depends on the other: a row can be both, either, or
    // neither, and `rowLook` is where that is stated.
    //
    // The ring stays with the violet. A fill alone is not a focus indicator for a viewer who
    // cannot tell the two colours apart, and the ring has been what says "focused" on every
    // surface of this board.
    val look = rowLook(focused = focused || held, playing = playing)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = m.rowMin)
            .clip(shape)
            .background(
                when {
                    look.surface -> colors.secondary
                    picked -> colors.secondaryContainer
                    else -> Color.Transparent
                },
            )
            .border(
                1.dp,
                when {
                    look.ring -> colors.focusRing
                    picked -> colors.selectedBorder
                    else -> Color.Transparent
                },
                shape,
            )
            .movedByArrows(arrange)
            .then(if (pick != null) Modifier.semantics { selected = picked } else Modifier)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .pressOrHold(interaction, onClick = onClick, onHold = arrange?.onMark)
            .padding(horizontal = m.rowPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.rowPadH),
    ) {
        // **A provider's own heading is not a channel.**
        //
        // `##### UHD 3840P #####` is a rule drawn in text, written as a row because an
        // M3U playlist has only one row type -- see `isProviderHeading`. Numbering it
        // says it is the category's first channel, which it is not, so it gets no plate
        // and no logo; the plate's width is held open so that every name on the column
        // still starts at the same place. Muted and spaced, because a heading that looks
        // exactly like a channel is a channel that does not play.
        val heading = remember(channel.title) { isProviderHeading(channel.title) }

        // Keyed to the *fill*: ink has to be legible on whatever is behind it, and only the
        // row under the remote is violet.
        val ink = when {
            look.surface -> colors.onSecondary
            heading -> colors.onBackgroundMuted
            else -> colors.onBackground
        }

        // The number, on a plate, and it opens the row. It is what a remote dials, so
        // it is the first thing on the line a viewer reads down -- a column of numbers
        // is scanned, and a column of numbers behind two other things is not a column.
        if (heading) {
            Spacer(Modifier.width(m.numberWidth))
        } else {
            NumberPlate(label = numberPlate(number), onFill = look.surface, m = m)
        }

        // **The channel's own logo, from the provider.**
        //
        // `artwork_url` has been imported and stored since the schema existed and was
        // drawn by nothing; this is where it lands. A channel whose provider shipped no
        // logo, and one whose logo is still arriving, both draw an empty box of exactly
        // this size -- see `ProviderArtwork`. Reserved rather than collapsed, so that a
        // list settling does not move the names beside it.
        if (heading) {
            Spacer(Modifier.width(m.logoWidth))
        } else {
            ProviderArtwork(
                url = channel.artworkUrl,
                // Null: the name is drawn immediately after it, and a screen reader that
                // announced the logo as well would say the channel twice.
                description = null,
                modifier = Modifier.width(m.logoWidth).height(m.rowMin * LOGO_OF_ROW),
                imageScale = LOGO_SCALE,
            )
        }

        // **The name takes the row.**
        //
        // This read `weight(1f, fill = false)` with a `Spacer(Modifier.weight(1f))`
        // after the quality tag, and the two of them split the row's free space in
        // half -- inside a list column that was itself only nineteen per cent of the
        // board, beside a logo seven per cent wide. What reached the device was a
        // column of rows each showing a coloured tile, a number plate, and the single
        // character `…` where the channel's name should have been.
        //
        // So: `fill = true`, no trailing spacer, and the two things beside it are the
        // two smallest things on the row. Whatever is left after a 60px mark and a
        // 62px plate belongs to the name, which is the only part of the row anybody
        // is reading.
        //
        // Without the tag, because the tag is drawn beside it. `|FR| TF1 HD` reads as
        // `|FR| TF1  ᴴᴰ` rather than repeating itself.
        // The name as the provider wrote it. A tag in it -- `HD`, `UHD`, `4K` -- is the provider's
        // claim and is left where they put it: the badge beside it is measured, and the two are
        // allowed to disagree on screen, which is the point of the badge.
        val name = channel.title
        val nameStyle = castivioChipStyle(m.frame.fsLabel)
            .copy(letterSpacing = if (heading) HEADING_TRACKING else TextUnit.Unspecified)

        // **A name too long for the row loses its middle, and the one under the remote
        // scrolls.**
        //
        // Two different jobs, so two different answers. A viewer running an eye down
        // twelve rows is *identifying* channels, and the identifying part is at the
        // end: the owner's screen showed sixty-one rows reading `US: CINEMANIA
        // HOLLYWOOD …` because 186.8dp of name field holds about twenty-three
        // upper-case characters and the shared prefix is twenty-three characters long.
        // A tail ellipsis had kept every character they have in common and thrown away
        // the only one they do not. `CastivioMiddleEllipsisText` keeps both ends.
        //
        // The row under the remote is being *read*, not identified, so it gets the
        // whole name and scrolls it. Scrolling all twelve at once would make the
        // column unreadable and would animate twelve texts on a stick that has to
        // hold 60fps while paging a catalogue.
        if (nameScrolls(focused = focused, pressed = pressed)) {
            Text(
                text = name,
                style = nameStyle,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .weight(1f)
                    .basicMarquee(iterations = Int.MAX_VALUE),
            )
        } else {
            CastivioMiddleEllipsisText(
                text = name,
                style = nameStyle,
                color = ink,
                modifier = Modifier.weight(1f),
            )
        }

        // **The star, first after the name.** Like the lock, a fact about the channel, so it comes
        // before the player's mark. Not while the row's picking end already draws it -- one star a row.
        if (favorite && pick?.favorite != true) FavoriteMark(size = m.rowMin * MARK_OF_ROW, onFill = look.surface)

        // **The lock, after the star.** It is a fact about the channel and the mark below is a fact
        // about the player, so it comes before it: a locked row that is also playing shows both, lock
        // then mark, and neither moves the other.
        if (locked) LockedMark(size = m.rowMin * MARK_OF_ROW, onFill = look.surface)

        // **The mark that this one is playing.** Between the name and the tag, so that the tag
        // keeps the row's end on every row and only the name of the playing one gives it room.
        if (look.mark) PlayingMark(size = m.rowMin * MARK_OF_ROW, onFill = look.surface)

        // **The quality tag: SD, HD, FHD or 4K, and only when the channel has been measured.**
        // A channel nobody has opened has no entry and draws nothing -- not a default, and not
        // a reading of its name. The name field takes the row's slack and is start-aligned, so
        // every name starts in the same place whether or not a tag follows it.
        quality?.let { measured ->
            Text(
                text = measured.label,
                style = castivioBodyStyle(m.frame.fsBody * QUALITY_OF_BODY),
                color = when {
                    look.surface -> colors.onSecondary
                    measured == StreamQuality.SD -> colors.onBackgroundMuted
                    else -> colors.secondary
                },
                maxLines = 1,
            )
        }

        arrange?.let { ArrangeTail(it, ink, m.rowMin * ARROW_OF_ROW) }
        pick?.let { PickMark(it, size = m.rowMin * MARK_OF_ROW, onFill = look.surface) }
    }
}

/**
 * The end of a row while the board is picking favourites: a star when the channel already is one, and
 * a ring that fills with a tick when it is picked.
 *
 * Plain glyphs in the row's own inks, like the lock: a disc is the player's, and a second filled shape
 * at the end of a row would be two answers to one question. The tick is the *chosen* colour and the
 * empty ring is the quiet one, so a column of rows reads at a glance and not only by the fill behind
 * them -- the pick is said by shape as well as by colour. The picked state is also told to a screen
 * reader by the row itself.
 */
@Composable
private fun PickMark(pick: RowPick, size: Dp, onFill: Boolean) {
    val colors = CastivioTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(size / 4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pick.favorite) FavoriteMark(size = size, onFill = onFill)
        Icon(
            imageVector = if (pick.picked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = when {
                onFill -> colors.onSecondary
                pick.picked -> colors.secondary
                else -> colors.onBackgroundMuted
            },
            modifier = Modifier.size(size),
        )
    }
}

/**
 * The star on a channel that is a favourite: the glyph on its own, in the *chosen* colour the lit
 * Favourite button wears, and the row's own ink on the violet. A plain glyph like the lock, and said in
 * words for a screen reader.
 */
@Composable
private fun FavoriteMark(size: Dp, onFill: Boolean) {
    val colors = CastivioTheme.colors
    Icon(
        imageVector = Icons.Rounded.Star,
        contentDescription = stringResource(R.string.channels_favorites),
        tint = if (onFill) colors.onSecondary else colors.secondary,
        modifier = Modifier.size(size),
    )
}

/**
 * The lock on a channel that is locked: the glyph on its own, in the row's quiet ink, and the row's
 * own ink on the violet.
 *
 * Not a disc and not a colour of its own. The disc is the player's -- it says *this one is on* -- and a
 * second filled shape at the end of a row would be two answers to one question. A lock is a plain
 * glyph, and it says what it is in words for a screen reader, because a shape is the only other way
 * to know.
 */
@Composable
private fun LockedMark(size: Dp, onFill: Boolean) {
    val colors = CastivioTheme.colors
    Icon(
        imageVector = Icons.Rounded.Lock,
        contentDescription = stringResource(R.string.channels_locked),
        tint = if (onFill) colors.onSecondary else colors.onBackgroundVariant,
        modifier = Modifier.size(size),
    )
}

/**
 * The mark on the channel the player is showing: a small disc in the "now" colour with a play
 * triangle in it.
 *
 * `live` is the colour the design system keeps for exactly this -- a playing stream -- and it is
 * the calm green of the board's identity rather than a status colour: it never means
 * "succeeded". The triangle is the page's own ground so that it reads on the disc in both
 * themes. On the violet -- the remote is on the playing row, which is the usual case just after a
 * press -- the aqua alone is a little over 1.5:1 against it, so the disc takes the row's own ink
 * as a ring there and stays a disc a viewer can find from across the room.
 *
 * It says what it is in words, for a screen reader, because a colour and a shape are the only
 * other way to know.
 */
@Composable
private fun PlayingMark(size: Dp, onFill: Boolean) {
    val colors = CastivioTheme.colors
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.live)
            .then(if (onFill) Modifier.border(MARK_RING, colors.onSecondary, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = stringResource(R.string.channels_now_playing),
            tint = colors.background,
            modifier = Modifier.size(size * MARK_GLYPH),
        )
    }
}

/**
 * The channel number, as the reference draws it: a pill, not a column.
 *
 * `----` only where there is no position to draw — before any row has been focused. A
 * blank plate would read as a number that failed to load.
 */
@Composable
private fun NumberPlate(
    label: String,
    /** True when the row behind the plate carries the violet of the remote's focus. */
    onFill: Boolean,
    m: ChannelsMetrics,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius / 2)
    Box(
        Modifier
            .width(m.numberWidth)
            .height(m.numberHeight)
            .clip(shape)
            // On the violet the plate is the one thing that must not also be violet, so
            // it inverts: the row's ink becomes the plate's ground and the plate keeps
            // its edge. Everywhere else it is the glass tile the reference draws.
            .background(if (onFill) colors.onSecondary else colors.glassFill)
            .border(1.dp, if (onFill) Color.Transparent else colors.glassBorderSoft, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = castivioBodyStyle(m.frame.fsBody),
            // White on the glass plate of an ordinary row, and a deeper violet on the white
            // plate of the row the remote is on -- the row's own violet there is 3.5:1.
            color = if (onFill) colors.onSelectedPlate else colors.onBackground,
            maxLines = 1,
        )
    }
}

/* ----------------------------------------------------------------- the well */

/**
 * The third column: a picture, what it is, and what you can do to it.
 *
 * ## Why it is a column again
 *
 * It was a card floating over the channel list, and the owner rejected that for the
 * reason the arithmetic also gives: it covered the names. No rectangle drawn over a list
 * of names avoids covering some of them, and a rule that moves the rectangle only
 * chooses *which* names are covered. So the width comes back, and nothing is ever drawn
 * over the list again.
 *
 * ## The three layers, and why each is where it is
 *
 * 1. **The picture**, and only the picture. It carries the live badge and the number and
 *    nothing else: the channel's *name* used to be printed over the frame, which is a
 *    caption written across moving video.
 * 2. **The facts** under it — the name, what is on, what is next, and what the stream
 *    actually is. This is where "No Information" belongs, and where it now lives: a
 *    guide's absence is a fact about the channel, not a thing to write inside a player.
 * 3. **The strip** — the things you can do to the channel in the picture. Actions with a
 *    subject drawn above them, rather than a menu that has to name what it acts on.
 *
 * The whole column costs nothing until a channel is chosen, and it is never taken away:
 * the board does not change shape once, from the first press to the last.
 *
 * ## Everything in it is real or absent
 *
 * No invented programme, no invented artwork, no invented duration. A channel whose
 * provider sent no schedule says so in one line; an absent channel draws the idle plate,
 * because the column is always here and always has to be saying something true.
 */
@Composable
internal fun ChannelWell(
    shown: ChannelPreview,
    m: ChannelsMetrics,
    pulse: PlayerPulse?,
    qualities: Map<String, StreamQuality>,
    preview: (@Composable () -> Unit)?,
    onOpenGuide: () -> Unit,
    onFavorite: () -> Unit,
    /** The Favourite button held: start picking several channels at once. */
    onFavoriteHold: () -> Unit = {},
    /** The board is picking favourites: the Favourite button is lit, and a press applies the picks. */
    favoriting: Boolean = false,
    /** While picking: every pick already is a favourite, so the button removes them. */
    favoriteRemoves: Boolean = false,
    /** The Hide button pressed: choose bouquets to hide. */
    onHide: () -> Unit = {},
    /** The Hide button held: choose channels to hide. */
    onHideHold: () -> Unit = {},
    /** The board is choosing what to hide: the Hide button is lit and the other two modes are dark. */
    hiding: Boolean = false,
    /** The channel in the well is locked. */
    locked: Boolean = false,
    /** Lock it, or -- locked already -- open it: both ask for the password first. */
    onLock: () -> Unit = {},
    /** The board is in reorder mode. */
    arranging: Boolean,
    onArrange: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        // **Measured against the column it is in, not the one the board was promised.**
        //
        // `m.column` is the surface less the bands that are always there. A band that comes and
        // goes -- the reorder bar, a failed refresh's notice -- takes its height from the three
        // columns, and a picture still sized for the full column kept its height while the facts
        // under it lost theirs: in reorder mode on a handset the channel's name ran into what was
        // on and what was next fell off the panel. The well's own height is the column, so the
        // numbers are rederived from it, and the picture yields as it does on a short surface.
        val fitted = remember(m, maxHeight) { m.copy(column = minOf(m.column, maxHeight)) }

        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(fitted.guideGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Sized from the metrics rather than from the column, because on a short surface
            // the picture is *narrower* than the column it sits in -- it gives height back to
            // the facts under it and keeps its shape. See `ChannelsMetrics.wellPicture`.
            WellPicture(
                shown = shown,
                m = fitted,
                pulse = pulse,
                preview = preview,
                modifier = Modifier.width(fitted.wellPictureWidth).height(fitted.wellPicture),
            )

            // **It takes the room, and the room decides what goes in it.**
            //
            // Four shapes were tried here. `weight(1f)` stretched the block to whatever the
            // column had spare -- 333dp of bordered panel around 138dp of content at the
            // reference. Hugging moved the same emptiness outside the border, where it became
            // ground between the last programme and the strip. A bounded floor filled part of
            // the box and set the two coming programmes a hand's width apart. A third
            // programme helped without finishing the job: 170dp of ground became 163dp of
            // content in a 333dp room.
            //
            // What finishes it is letting the *column* decide. The block takes the room in
            // full and distributes what it does not need between its three ruled sections;
            // and where the column is deep enough to afford it, the current programme's
            // synopsis fills part of that room with something worth reading. At 1280x720 that
            // is 83.7dp of synopsis and 45.9dp of air between sections, against 87.7 with the
            // space merely spread. On a handset the synopsis is absent -- it would not fit --
            // and the 11.7dp of ground is simply absorbed. No surface keeps dead ground.
            //
            // **On every channel, and no longer only on the ones with three sections.** This
            // took the room when the guide had a NEXT and hugged its content when it did not,
            // so a channel at the end of its window drew a short panel with 80dp of ground
            // between it and the strip while its neighbour drew a full one. Two shapes for one
            // panel, chosen by what a provider's EPG happened to contain.
            //
            // The room is the column's to give and the block's to take; what varies is how
            // much of it the content uses. Since the gaps inside are now one figure
            // (`ChannelsMetrics.guideSection`) rather than a division of the slack, the
            // leftover collects at the bottom of the block instead of stretching it, and the
            // frame ends up in the same place on every channel.
            ChannelFacts(
                shown = shown,
                m = fitted,
                quality = shown.channel?.let { qualities[it.id] },
                onExpand = onExpand,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            ActionStrip(
                shown = shown,
                m = fitted,
                onOpenGuide = onOpenGuide,
                onFavorite = onFavorite,
                onFavoriteHold = onFavoriteHold,
                favoriting = favoriting,
                favoriteRemoves = favoriteRemoves,
                onHide = onHide,
                onHideHold = onHideHold,
                hiding = hiding,
                locked = locked,
                onLock = onLock,
                arranging = arranging,
                onArrange = onArrange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The picture, with the one mark that belongs on a frame of video and no others.
 *
 * The live badge is over it because it is true of the *picture*; the name and the programme
 * are under it because they are true of the channel, and a caption written across moving
 * video is the thing this board was asked to stop doing. The channel number was over it as
 * well, and the owner asked for it to go: the row in the list already carries it.
 *
 * **This mark is the only one.** The compact player used to draw its own band over
 * the same corner — the channel's name, a second LIVE pill, a second number — so the
 * picture carried each of them twice, one set over the other. That band is gone; see
 * `PlayerScreen`. The picture is now video and this mark, and nothing that is also
 * printed underneath it.
 *
 * There is no close control. The column is a fixture and the owner wants it to stay one:
 * a button whose whole job is to remove a fixed part of the board is a button that can
 * only make the screen wrong.
 */
@Composable
internal fun WellPicture(
    shown: ChannelPreview,
    m: ChannelsMetrics,
    pulse: PlayerPulse?,
    preview: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.wellRadius)
    val channel = shown.channel

    // **Whether this plate has a picture, as the player says it.** The channel the plate is about
    // is the last one pressed; the picture is its own only once the player has drawn a frame of
    // *that* stream. Until then the plate has nothing of the channel to show, and says what it is
    // instead of looking like a player that has gone black.
    val showing = channel != null && pulse?.playingUrl == channel.streamUrl
    val opening = channel != null && pulse?.url == channel.streamUrl && pulse.phase == PulsePhase.OPENING

    Box(
        modifier
            .clip(shape)
            .background(colors.backgroundElevated)
            .border(1.dp, colors.glassBorder, shape),
    ) {
        // **The plate is the player's when there is a player.**
        //
        // Once one is actually playing, the same plate is the compact player. It keeps its size
        // and its place either way, so pressing a channel changes what is in the box and never
        // where the box is.
        if (preview != null) preview()

        when {
            // **Nothing to show yet: the product's own ground and the channel's own mark.**
            //
            // Over the player's plate while it opens -- its cover is the page's near-black, and a
            // black box with a name in a corner reads as a player that has failed. The ground
            // goes the instant the first frame is drawn, because `showing` is the player's word
            // for it, and nothing waits on this layer: the engine is drawing underneath it.
            // Not over a card: when the stream has failed the player's own card is the thing to
            // read, and it is left alone.
            channel != null && (preview == null || opening) -> PlateGround(
                channel = channel,
                waiting = opening && pulse?.waiting == true,
            )

            // Nothing chosen yet.
            channel == null && preview == null -> {
                // **The idle plate, which is why the well can stay.**
                //
                // The column is a fixture now, so it has to have something honest to draw
                // before anything has been pressed and after the close button. A mark and one
                // sentence saying what the board is waiting for: a screen explaining itself,
                // rather than an empty frame that looks like a player which has failed.
                Column(
                    Modifier.fillMaxSize().background(colors.glassFill),
                    verticalArrangement = Arrangement.spacedBy(m.nameGap, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.PlayCircleOutline,
                        contentDescription = null,
                        tint = colors.onBackgroundMuted,
                        modifier = Modifier.size(Sizing.iconXl),
                    )
                    Text(
                        text = stringResource(R.string.channels_preview_none),
                        style = castivioBodyStyle(m.frame.fsBody),
                        color = colors.onBackgroundMuted,
                        maxLines = 1,
                    )
                }
            }
        }

        // **The live badge is a claim, so it is made only when it is true.** It said LIVE over a
        // black plate while nothing had drawn, which told the viewer the channel was working
        // before it was. It is over the picture because it is true of the picture, and it is
        // there when the player has drawn one of *this* channel and not before.
        if (showing) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(m.osdPad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.osdGap),
            ) {
                LiveBadge(m)
            }
        }
    }
}

/**
 * What the plate shows while the channel has no picture: Castivio's own ground, and the
 * channel's logo on it -- or Castivio's mark where the provider shipped none.
 *
 * It says nothing is playing by showing nothing playing: no badge, no frame, no title in a
 * corner. When opening has been silent long enough for the player to say so, a small spinner
 * says it is still working, in the middle of the plate -- the player's own call, at the
 * player's own delay, because the layer is over the player's spinner and would otherwise hide it.
 */
@Composable
private fun PlateGround(channel: Channel, waiting: Boolean) {
    val colors = CastivioTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .testTag(PLATE_GROUND_TAG)
            // The page's own two grounds, top to bottom: quiet enough that a logo -- which is
            // usually drawn for a dark page -- reads on it, and Castivio's rather than black.
            .background(Brush.verticalGradient(listOf(colors.secondaryContainer, colors.backgroundElevated))),
        contentAlignment = Alignment.Center,
    ) {
        if (channel.artworkUrl.isNullOrBlank()) {
            Image(
                painter = painterResource(DesignR.drawable.castivio_mark),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxHeight(PLATE_MARK).aspectRatio(1f),
            )
        } else {
            ProviderArtwork(
                url = channel.artworkUrl,
                description = null,
                modifier = Modifier.fillMaxWidth(PLATE_MARK).fillMaxHeight(PLATE_MARK),
            )
        }
        // **In the middle of the picture, by alignment and not by an offset.** It sat at the bottom
        // edge, under the logo, where it read as belonging to the plate's border rather than to the
        // picture that is being waited for. `Alignment.Center` is the plate's own centre on every
        // surface, whatever size the well takes, with nothing to keep in step with it.
        if (waiting) {
            DelayedSpinner(modifier = Modifier.align(Alignment.Center), delayMs = 0L)
        }
    }
}

/**
 * What the picture is: whose channel it is, what is on, and what follows.
 *
 * ## Five things, and no sixth
 *
 * The identity, the current programme, the bar under it, the hours it runs, and what is
 * next. The block carried a synopsis, the category and the channel's position in it; the
 * owner removed all three. What is left is what a viewer reads *while watching*, and the
 * rest of the schedule is a press away on a page that has room for it.
 *
 * ## Why this is not inside the player
 *
 * It was. "No Information" was drawn in a panel *within* the card, which made a guide's
 * absence look like a fault in the player rather than a fact about the channel. Out here
 * it reads correctly — and a channel with no schedule now simply draws its identity and
 * stops, because a sentence saying nothing is worse than the space it occupies.
 *
 * ## What it may claim
 *
 * The logo, the name and the quality tag come from the catalogue and are always true; the
 * logo is the very artwork the channel row above it already fetched, through the same
 * [ProviderArtwork] and the same cache, so drawing it here costs no request. The
 * programme lines come from the guide and are absent when it is. There is deliberately
 * **no resolution, codec or bitrate**: those are properties of a decoded stream, the board
 * does not hold them, and printing them from a channel's name would be the one dishonest
 * pixel on the screen.
 */
@Composable
private fun ChannelFacts(
    shown: ChannelPreview,
    m: ChannelsMetrics,
    /** What the channel was last measured at, or null: no badge. */
    quality: StreamQuality?,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.wellRadius)
    val channel = shown.channel
    val guide = shown.guide
    val now = guide?.now
    val next = guide?.next

    Column(
        modifier
            .clip(shape)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(horizontal = m.guidePad, vertical = m.guidePad / 2),
        // **One gap, and not a division of the slack.**
        //
        // This was `SpaceBetween`, which spreads the spare height between a column's
        // *children* — and the children here are the channel, what is on, what is next and
        // what follows it, so their number is the number of programmes the provider sent.
        // Three screenshots from one device measured the gap under the channel's name at
        // 13.5dp, 23.1dp and 36.3dp on three channels, and the one with the *fewest*
        // programmes had the widest gaps: the same slack cut into two pieces instead of
        // three. A panel whose rhythm is set by an EPG feed is a panel that reads
        // differently every time a viewer changes channel.
        //
        // `guideSection` is one figure derived from the block's own room, so the four
        // sections sit the same distance apart whatever the provider returned, and
        // whatever is left over collects at the bottom rather than being smeared between
        // the rows. The groups' own top paddings went with `SpaceBetween`: they existed
        // because it could not state a minimum, and a stated gap needs no floor under it.
        verticalArrangement = Arrangement.spacedBy(m.guideSection),
    ) {
        // ---------------------------------------------------------- the identity
        //
        // **Two presses on the name open the picture large.** The same act as a press on
        // the picture itself, reaching the same shell action — not a second route to a
        // second implementation. Double and not single: a single press here has to stay
        // free, because this row sits inside the well's focus group and a remote arriving
        // on it must not tune anything.
        //
        // Keyed on `Unit` over a held reference, and not on the callback: the shell builds
        // that lambda fresh on every recomposition, so keying on it would tear down and
        // rebuild the gesture detector each time — including in the middle of the very
        // double press it exists to catch.
        val expand by rememberUpdatedState(onExpand)
        Row(
            Modifier
                .fillMaxWidth()
                .height(m.identity)
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { expand() }) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.factGap),
        ) {
            // The mark, at the row's own height rather than the list's smaller one: this
            // is the channel being watched, not one of eleven being scanned. Absent
            // artwork draws the "no logo" mark the rows draw, so the name starts at the
            // same place either way.
            ProviderArtwork(
                url = channel?.artworkUrl,
                description = null,
                modifier = Modifier.height(m.identity).aspectRatio(LOGO_BOX),
            )
            Text(
                text = channel?.title ?: stringResource(R.string.channels_preview_none),
                style = castivioTitleStyle(m.fsChannelName),
                color = if (channel != null) colors.onBackgroundStrong else colors.onBackgroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            quality?.let {
                Text(
                    text = it.label,
                    style = castivioChipStyle(m.frame.fsBody * QUALITY_OF_BODY),
                    color = colors.secondary,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(colors.secondaryContainer)
                        .padding(horizontal = m.badgePadH, vertical = m.badgePadV / 2),
                )
            }
        }

        // **A channel with no schedule says so.**
        //
        // It used to stop after the identity row, which left a bordered panel with one
        // line in it and no way to tell "this channel has no guide" from "the guide has
        // not arrived yet" — and, once the block was given a floor, a large area of
        // nothing under a name. One sentence, from the state already in hand: nothing is
        // fetched to say it, and nothing is invented. An empty channel slot keeps its own
        // idle wording and does not claim a missing guide.
        //
        // **And "no schedule" means no schedule, not "nothing on right now".**
        //
        // This read `now == null`, which conflated two different channels: one whose
        // provider ships no guide at all, and one whose guide has a hole over this
        // instant. The second is ordinary -- a provider that sends what starts after now
        // leaves the programme already running out of its answer -- and it was being told
        // it had no guide while `next`, the row after it, and the whole guide page were
        // sitting in state, drawn from the same rows. The sentence was true of the minute
        // and false of the channel.
        //
        // The question is whether there is anything to say, so it is asked of both. With
        // a `next` the panel skips what is on -- there is nothing on -- and opens at what
        // is coming, which is the honest picture of a gap and is what the guide page
        // already showed.
        if (now == null && next == null) {
            if (channel != null) {
                // The whole of what is left under the identity, so the sentence is centred in it
                // on both axes rather than hung from its top edge.
                NoGuideNotice(m, Modifier.fillMaxWidth().weight(1f))
            }
            return@Column
        }

        // ------------------------------------------- what is on, and what is next
        //
        // **What is on, then what is next, as the owner set them out.** What is on is its
        // title, and under it the word that says so, its hours and its bar; what is next is
        // one line, the word, its hours and its title. The words and the hours are cells that
        // line up from one line to the next, so the hours of one sit over the hours of the
        // other whatever the two words measure in the reader's language.
        //
        // **The third programme and the synopsis went with the bigger picture.** They were the
        // block filling a column that had room to spare; the picture now takes that room, and
        // what is on and what is next are what the drawing keeps. Both are still on the guide
        // page, a press away, read from the same rows.
        //
        // Each cell is its own text and so its own paragraph: a word in one script, a pair of
        // clock figures and a title in another never share a line the bidirectional algorithm
        // has to order -- the mixture that once printed `03:50 – 00:05` for `00:05 – 03:50`.
        Column(verticalArrangement = Arrangement.spacedBy(m.badgePadV)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.glassBorder))
            ProgrammeTable(
                now = now,
                next = next,
                progress = if (guide != null && now != null) guide.progressAt(System.currentTimeMillis()) else null,
                m = m,
            )
        }
    }
}

/**
 * What the facts block says when the channel has no schedule: a small mark and one sentence,
 * centred in the room the block has under the channel's name.
 *
 * The mark and the words scale with the body step, so a television gets a larger notice that
 * holds its ground and a handset a small one. Where the room allows, the mark stands over the
 * sentence; where it does not -- a handset's block is as tall as its content -- the two sit side
 * by side, and the sentence wraps to two lines at most. The mark is the quality chip's own pair
 * of colours, so it is the board's ink and not a new one.
 */
@Composable
private fun NoGuideNotice(m: ChannelsMetrics, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val body = m.frame.fsBody
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val stacked = maxHeight >= body * NO_GUIDE_STACKED_OF_BODY
        val mark = @Composable {
            Box(
                Modifier.size(body * NO_GUIDE_MARK_OF_BODY).clip(CircleShape).background(colors.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.EventBusy,
                    contentDescription = null,
                    tint = colors.secondary,
                    modifier = Modifier.size(body * NO_GUIDE_ICON_OF_BODY),
                )
            }
        }
        val words = @Composable { textModifier: Modifier ->
            Text(
                text = stringResource(R.string.channels_guide_none),
                style = castivioBodyStyle(body * NO_GUIDE_OF_BODY),
                color = colors.onBackgroundMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = textModifier,
            )
        }
        if (stacked) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(m.nameGap),
            ) {
                mark()
                words(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.factGap),
            ) {
                mark()
                words(Modifier.weight(1f, fill = false))
            }
        }
    }
}

/**
 * What is on and what is next: what is on's title, the line that says it is on -- the word, its
 * hours, its bar -- and the line of what is next -- the word, its hours, its title.
 *
 * The two lines are laid out here rather than as two rows because their cells have to agree:
 * the hours of what is next sit under the hours of what is on, whatever the two words measure in
 * the reader's language. `MAINTENANT` is three times `NOW`. The word column is as wide as the
 * wider word, the hours column as the wider pair of hours, and the last cell -- the bar, or the
 * title -- takes what is left.
 *
 * **The air between the title, the line of what is on and the line of what is next** is the base
 * gap the block's floor was solved with, plus a share of the body step that is only ever taken
 * from the room the block really has left -- so it is generous where there is room and never
 * costs the picture its size or cuts the last line off where there is not.
 *
 * Either programme may be absent -- a guide with a hole over this instant has nothing on, and
 * one ending with this programme has nothing next -- and the other then stands alone. Nothing
 * is drawn for a programme the guide does not have, and nothing beyond these two: the synopsis
 * and the programmes after them are on the guide page, a press away.
 */
@Composable
internal fun ProgrammeTable(
    now: Programme?,
    next: Programme?,
    /** How far into what is on we are, from its own hours; null draws the bar empty. */
    progress: Float?,
    m: ChannelsMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val legend = castivioBodyStyle(m.frame.fsBody * LEGEND_OF_BODY)
    val body = castivioBodyStyle(m.frame.fsBody)
    val lines = listOfNotNull(now?.let { it to true }, next?.let { it to false })

    // **One layout for the title and the two lines, so the air between them can be decided
    // where the room is known.** The title used to be a sibling in a `Column` and the gaps were
    // `badgePadV` and twice it -- two or four dp on a handset, where the block is exactly as
    // tall as its content and the title, the word and the next line read as one lump. A fixed
    // larger figure would have been taken from the picture (the block's floor is solved from
    // these gaps) or have cut the last line off; so the base gaps stay what the floor was
    // solved with, and what is added to them is only ever what the block turns out to have
    // spare -- see the measure block.
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            // The title of what is on, over the word that says it is on: the one thing on the
            // panel a viewer reads first, in the block's strongest ink and the whole width to
            // itself.
            if (now != null) {
                Text(
                    text = now.title,
                    style = body,
                    color = colors.onBackgroundStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            for ((programme, isNow) in lines) {
                // **The word, in the colour of what it says.** Aqua is live, and only what
                // is on is live; what is next is told in the block's own quiet ink.
                Text(
                    text = stringResource(if (isNow) R.string.channels_guide_now else R.string.channels_guide_next),
                    style = legend,
                    color = if (isNow) colors.live else colors.onBackgroundVariant,
                    maxLines = 1,
                )
                // Read from the programme's own timestamps -- the same two the bar is drawn
                // from, so the figures and the fill can never disagree.
                Text(
                    text = stringResource(
                        R.string.channels_time_range,
                        clockLabel(programme.startMs),
                        clockLabel(programme.stopMs),
                    ),
                    style = legend,
                    color = colors.onBackgroundVariant,
                    maxLines = 1,
                )
                if (isNow) {
                    Track(fraction = progress, m = m)
                } else {
                    Text(
                        text = programme.title,
                        style = body,
                        color = colors.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val gap = m.factGap.roundToPx()
        val titleGap = m.badgePadV.roundToPx()
        val lineGap = (m.badgePadV * 2).roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val title = if (now != null) measurables.first().measure(loose) else null
        val cells = measurables.drop(if (now != null) 1 else 0).chunked(PROGRAMME_CELLS)
        val words = cells.map { it[0].measure(loose) }
        val hours = cells.map { it[1].measure(loose) }
        val wordWidth = words.maxOfOrNull { it.width } ?: 0
        val hourWidth = hours.maxOfOrNull { it.width } ?: 0
        val lastWidth = (constraints.maxWidth - wordWidth - hourWidth - gap * 2).coerceAtLeast(0)
        val lasts = cells.map { it[2].measure(Constraints.fixedWidth(lastWidth)) }
        val heights = cells.indices.map { maxOf(words[it].height, hours[it].height, lasts[it].height) }
        val content = (title?.let { it.height + titleGap } ?: 0) +
            heights.sum() + lineGap * (heights.size - 1).coerceAtLeast(0)

        // **Air, from the room that is really there.** The room the table was offered, less
        // what it takes: nothing when the height is open (a harness) or already spent. The
        // title's gap and the gap between the two lines are each given a share of the body step
        // and are cut back in proportion to what is spare, so the title never touches the word
        // and what is next is never pushed past the block. The bar shares the line of the word
        // and the hours -- centred on it -- so there is no vertical gap between them to widen.
        val spare = if (constraints.hasBoundedHeight) (constraints.maxHeight - content).coerceAtLeast(0) else 0
        val wantTitle = if (title != null) (m.frame.fsBody * TITLE_AIR_OF_BODY).roundToPx() else 0
        val wantLine = if (heights.size > 1) (m.frame.fsBody * LINE_AIR_OF_BODY).roundToPx() else 0
        val wanted = wantTitle + wantLine
        val given = minOf(spare, wanted)
        val airTitle = if (wanted == 0) 0 else given * wantTitle / wanted
        val airLine = given - airTitle

        layout(constraints.maxWidth, content + given) {
            var y = 0
            if (title != null) {
                title.placeRelative(0, 0)
                y += title.height + titleGap + airTitle
            }
            for (line in cells.indices) {
                val height = heights[line]
                words[line].placeRelative(0, y + (height - words[line].height) / 2)
                hours[line].placeRelative(wordWidth + gap, y + (height - hours[line].height) / 2)
                lasts[line].placeRelative(wordWidth + gap + hourWidth + gap, y + (height - lasts[line].height) / 2)
                y += height + lineGap + airLine
            }
        }
    }
}

/**
 * The things you can do to the channel in the picture above.
 *
 * ## Why it scrolls
 *
 * Four buttons fit across this column on a television and three on a handset, and the
 * owner intends to add more. A fixed row would make every future addition a redesign of
 * the board's arithmetic; a scrolling one makes it an entry in a list. A remote walks it
 * with `right`, a thumb drags it, and the strip is one focus group so neither has to
 * think about which is which.
 *
 * ## One of these does not work yet, and it is drawn anyway
 *
 * **Hide** has no table behind it: there is no `channel_hidden`, so pressing it changes
 * nothing. It is on screen at the owner's explicit instruction, to see the strip and walk it
 * on a device before the storage is built. This comment is the record of that decision — the
 * button is not forgotten work, it is staged work.
 *
 * **Lock** works: it asks for the password and then locks the channel in the picture, or, when
 * the channel is locked already, opens it. Its storage is `ChannelLocks` and not a table — a
 * handful of ids in a preferences file, which a refresh of the catalogue does not touch.
 */
@Composable
internal fun ActionStrip(
    shown: ChannelPreview,
    m: ChannelsMetrics,
    onOpenGuide: () -> Unit,
    onFavorite: () -> Unit,
    arranging: Boolean,
    onArrange: () -> Unit,
    modifier: Modifier = Modifier,
    /** The Favourite button held: start picking several. Nothing while the board is picking already. */
    onFavoriteHold: () -> Unit = {},
    /** The board is picking favourites: the button is lit, and a press applies the picks. */
    favoriting: Boolean = false,
    /** While picking: every pick already is a favourite, so a press removes them and the word says so. */
    favoriteRemoves: Boolean = false,
    /** The Hide button pressed: choose bouquets to hide. */
    onHide: () -> Unit = {},
    /** The Hide button held: choose channels to hide. */
    onHideHold: () -> Unit = {},
    /** The board is choosing what to hide: the button is lit, and the favourite and reorder buttons are dark. */
    hiding: Boolean = false,
    /** The channel in the picture is locked: the button is lit and its word is Unlock. */
    locked: Boolean = false,
    /** Lock the channel, or open it. Asked for the password by whoever is told. */
    onLock: () -> Unit = {},
) {
    val hasChannel = shown.channel != null

    // **Five buttons sharing the width, as the drawing sets them.** The strip used to scroll and
    // each button took its label's width, so four left a gap at the end of the well; five equal
    // shares end flush with the picture and the facts above them. They always fit: the well is
    // never narrower than five control targets and their gaps -- see `ChannelsMetricsTest`.
    Row(
        modifier.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(m.factGap),
    ) {
        ActionButton(
            icon = Icons.Rounded.CalendarMonth,
            label = stringResource(R.string.channels_action_guide),
            // A guide button on a channel whose provider sent no schedule would open an
            // empty page. It is drawn and it is dark, which is a truthful "nothing to
            // show" rather than a press that leads nowhere.
            enabled = shown.schedule.isNotEmpty(),
            onClick = onOpenGuide,
            m = m,
            modifier = Modifier.weight(1f),
        )
        // **One press marks the channel in the picture, or takes the mark away. Held, it is the way into
        // picking several.** Held, the button lights and the rows can be picked; pressed, it then adds
        // the picks -- or removes them, when every one already is a favourite, and its word says which.
        // It needs no channel to be held, because what it then manages is the board's lists. Dark while
        // the board is in reorder mode, which is a different job with its own bar: the two never share a
        // state.
        ActionButton(
            icon = if (favoriting || shown.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            label = stringResource(
                if (if (favoriting) favoriteRemoves else shown.favorite) R.string.channels_action_unfavorite
                else R.string.channels_action_favorite,
            ),
            on = favoriting || shown.favorite,
            enabled = !arranging && !hiding,
            onClick = onFavorite,
            m = m,
            modifier = Modifier.weight(1f),
            onLongClick = onFavoriteHold,
        )
        // **Lit when the channel is locked, and then it opens it.** The lit state is the one the star
        // takes for a favourite -- *chosen*, never *focused* -- and its word says what a press does, as
        // the star's does. Both directions ask for the password: a lock anybody could take off would
        // be a label.
        ActionButton(
            icon = Icons.Rounded.Lock,
            label = stringResource(
                if (locked) R.string.channels_action_unlock else R.string.channels_action_lock,
            ),
            on = locked,
            enabled = hasChannel,
            onClick = onLock,
            m = m,
            modifier = Modifier.weight(1f),
        )
        // **The way into hiding several at once.** Pressed, the rail's bouquets can be ticked; held, the
        // channels in the list can -- a mode of its own either way, with a bar that says which and has
        // Hide selected and Cancel. It needs no channel: what it hides is the board's lists, not the one in
        // the picture. Lit while the mode is on, dark while reorder mode or picking favourites is: the three
        // never share a state. Hiding is local and reversible from Settings.
        ActionButton(
            icon = Icons.Rounded.VisibilityOff,
            label = stringResource(R.string.channels_action_hide),
            on = hiding,
            enabled = !arranging && !favoriting && !hiding,
            onClick = onHide,
            m = m,
            modifier = Modifier.weight(1f),
            onLongClick = onHideHold,
        )
        // **The way into reorder mode, at the end of the strip, as the owner's drawing has it.**
        // It needs no channel: what it arranges is the board's lists, not the one in the picture.
        // Lit while the mode is on and not pressable again -- the bar over the board is where
        // the mode is left, with Save or Cancel.
        ActionButton(
            icon = Icons.Rounded.SwapVert,
            label = stringResource(R.string.reorder_entry),
            on = arranging,
            enabled = !arranging && !favoriting && !hiding,
            onClick = onArrange,
            m = m,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One button in the strip: a mark and a word.
 *
 * A word under the mark rather than a mark alone. Five icons in a row with no labels is a
 * puzzle on a television, where there is no hover to explain one and no convention that
 * covers "hide a channel".
 *
 * [on] is the state of a thing that toggles — a favourited channel — and takes the
 * selection colour, which on this board means *chosen* and never *focused*. The two are
 * different colours for that reason and this button is where they meet.
 *
 * [onLongClick], when a button has one, is a press held -- see [pressOrHold]. A button without one is a
 * plain `clickable`, as it always was.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    m: ChannelsMetrics,
    on: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = CastivioTheme.colors
    val (focused, focusModifier) = rememberFocusFlag()
    val shape = RoundedCornerShape(m.previewRadius)
    val interaction = remember { MutableInteractionSource() }

    val ink = when {
        !enabled -> colors.onBackgroundMuted
        focused -> colors.onSecondary
        on -> colors.secondary
        else -> colors.onBackgroundVariant
    }

    Column(
        modifier
            .height(m.strip)
            .widthIn(min = m.strip)
            .clip(shape)
            .background(
                when {
                    focused -> colors.secondary
                    on -> colors.secondaryContainer
                    else -> colors.glassFill
                },
            )
            .border(
                1.dp,
                when {
                    focused -> colors.focusRing
                    on -> colors.selectedBorder
                    else -> colors.glassBorderSoft
                },
                shape,
            )
            .then(focusModifier)
            .pressOrHold(interaction, onClick = onClick, onHold = onLongClick, enabled = enabled)
            .padding(horizontal = m.controlPadH, vertical = m.badgePadV),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(Sizing.iconMd))
        Spacer(Modifier.height(m.badgePadV))
        Text(
            text = label,
            style = castivioBodyStyle(m.frame.fsBody * LEGEND_OF_BODY),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The whole day, for the channel that is playing.
 *
 * ## Why an overlay and not a fourth region
 *
 * A schedule is twenty rows and the board has no twenty rows to spare — that is the
 * arithmetic that took the guide out of the well in the first place. What it needs is the
 * screen, briefly, and then to give it back.
 *
 * ## Why it stops at the well rather than covering it
 *
 * The obvious full-screen version puts the picture back inside the overlay, which means
 * moving the player's slot from the well into here — and moving a `SurfaceView` between
 * two places in one composition detaches and re-attaches it. The stream survives, because
 * the engine belongs to the shell, but the frame goes black for as long as the surface
 * takes to come back.
 *
 * So the panel takes the rail's width and the list's, and stops. The well is left exactly
 * where it is: the picture never moves, never blinks, and the viewer reads the schedule
 * with the channel still playing beside it — which is what the approved drawing shows and
 * what a full-screen overlay would have had to fake.
 *
 * ## Why it is not a new EPG
 *
 * There is no new query, no new repository call and no new state. `ChannelPreview.schedule`
 * is the list the well already reads two lines of, drawn here in full. The overlay is a
 * second *view* of one fact, not a second fact — which is also why closing it cannot lose
 * the channel: it never owned it.
 *
 * ## The header says whose schedule this is
 *
 * The one thing an overlay of times and titles must never leave ambiguous. The channel's
 * number and name sit above the list, in the same shapes the well draws them in.
 */
@Composable
private fun ChannelGuideOverlay(
    shown: ChannelPreview,
    guide: FullGuide,
    m: ChannelsMetrics,
    onClose: () -> Unit,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.panelRadius)
    val first = remember { FocusRequester() }

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    // Covers the rail and the list, and stops before the well. `CenterStart` with an end
    // padding of the well's own width is how a `Box` says "everything except that
    // column": the scrim dims what the schedule replaces and leaves the picture lit.
    Box(
        Modifier
            .fillMaxSize()
            .padding(end = m.player + m.playerGap)
            .background(colors.scrim)
            // Swallows the press so a tap on the dimmed board behind does not reach a
            // channel row; it also closes, which is what a scrim is for.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(m.panelPad)
                .clip(shape)
                .background(colors.backgroundElevated)
                .border(1.dp, colors.glassBorder, shape)
                .padding(m.wellPad),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = numberPlate(shown.number),
                    style = castivioChipStyle(m.frame.fsLabel),
                    color = colors.secondary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(m.controlPadH))
                Text(
                    text = shown.channel?.title
                        ?: stringResource(R.string.channels_preview_none),
                    style = castivioTitleStyle(m.frame.fsLabel),
                    color = colors.onBackgroundStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.channels_guide_close),
                    style = castivioBodyStyle(m.frame.fsBody),
                    color = colors.onBackgroundMuted,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.height(m.guideGap))

            val nowId = shown.guide?.now?.startMs
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).focusRequester(first),
                verticalArrangement = Arrangement.spacedBy(m.guideGap),
            ) {
                // **Only the days the provider actually sent.**
                //
                // `FullGuide.days` is grouped in the state holder and holds nothing for a
                // day with no programmes in it, so a provider with one day draws one
                // heading and a provider with seven draws seven. Nothing is padded out to
                // a week: an empty day on screen would be a claim about the provider's
                // schedule rather than about our request.
                for (day in guide.days) {
                    item(key = day.startOfDayMs) {
                        DayHeading(startOfDayMs = day.startOfDayMs, m = m)
                    }
                    items(day.programmes, key = { it.startMs }) { programme ->
                        GuideEntry(
                            programme = programme,
                            now = programme.startMs == nowId,
                            m = m,
                            modifier = Modifier.heightIn(min = m.rowMin),
                        )
                    }
                }
            }
        }
    }
}

/** The reference's red `LIVE` chip, at the head of the on-screen display. */
@Composable
private fun LiveBadge(m: ChannelsMetrics) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.pill)
    Text(
        text = stringResource(R.string.channels_live_badge),
        style = castivioChipStyle(m.frame.fsBody * QUALITY_OF_BODY),
        color = colors.onSecondary,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(colors.live)
            .padding(horizontal = m.badgePadH, vertical = m.badgePadV),
    )
}

/**
 * The date a run of programmes belongs to.
 *
 * Drawn only where a day exists, which is what keeps the page honest about how much guide
 * a provider actually holds. Today and tomorrow are named rather than dated — a viewer
 * reading "today" places it instantly and reads a date twice as slowly — and every day
 * after them carries its own date in the device's own format.
 */
@Composable
private fun DayHeading(startOfDayMs: Long, m: ChannelsMetrics) {
    val colors = CastivioTheme.colors
    Text(
        text = dayLabel(startOfDayMs),
        style = castivioChipStyle(m.frame.fsBody),
        color = colors.onBackgroundMuted,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().padding(top = m.guideGap, bottom = m.badgePadV),
    )
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
 * Plain digits, because that is what the reference draws: its badges read 1, 2, 3 down
 * the visible order, not a zero-padded field. [ltrToken] so the figure keeps its shape
 * in an Arabic composition, like every other number on this board.
 *
 * [NO_NUMBER] survives for the one case that is still honest: a plate asked to draw a
 * position nothing has given it, which is the display before any row has been focused.
 */
private fun numberPlate(number: Int?): String =
    number?.let { ltrToken(it.toString()) } ?: NO_NUMBER

/** `13:30`: twenty-four hours and Western digits in every language, as the board's clock. */
private fun clockLabel(epochMs: Long): String = CastivioDates.time(epochMs)

/**
 * A guide day, named where naming helps and dated where it does not.
 *
 * The comparison is against *this* device's midnight rather than a 24-hour arithmetic on
 * the difference: "tomorrow" is a calendar fact, and a programme at 00:30 is tomorrow
 * even though it is forty minutes away.
 */
@Composable
@ReadOnlyComposable
private fun dayLabel(startOfDayMs: Long): String {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when (startOfDayMs) {
        today -> stringResource(R.string.channels_guide_today)
        today + DAY_MS -> stringResource(R.string.channels_guide_tomorrow)
        else -> CastivioDates.date(startOfDayMs)
    }
}

/** One day, for naming tomorrow. Days are not all 24 hours long, and this is not used
 *  to measure one -- only to step from one already-computed midnight to the next. */
private const val DAY_MS = 24 * 60 * 60 * 1000L

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
private const val RAIL_EMPTY = "castivio.rail.empty"


/** How much of the plate the channel's mark takes, in each direction. */
private const val PLATE_MARK = 0.46f

/** What the plate's own ground is found by: present while the channel has no picture, gone with it. */
internal const val PLATE_GROUND_TAG = "castivio.channels.plate.ground"

/**
 * A rail entry with no second line, as a share of one with two.
 *
 * The line and the breathing room a name needs on its own: about 23dp on a phone, where a
 * bouquet's two lines take 32. The three entries that are not bouquets -- all, favourites and
 * recent -- are one line each, and all take this.
 */
private const val SINGLE_OF_RAIL = 0.72f

/**
 * The playing mark's side as a share of the row's minimum height, and its triangle's as a share
 * of the mark. Small beside the name -- it says "this one" and must not compete with the number
 * plate -- and inside the row's own height, so that it never makes a row taller.
 */
private const val MARK_OF_ROW = 0.5f
private const val MARK_GLYPH = 0.64f
private val MARK_RING = 1.5.dp

/** The logo slot's height as a share of the row — 28 of 68 in the reference. */
private const val LOGO_OF_ROW = 28f / 68f

/**
 * How much larger than its fitted size a row's logo is drawn: 5 per cent, in both directions alike.
 * Drawn only -- the slot ([LOGO_OF_ROW], `logoWidth`) is not touched, so the column, the row's height and
 * the gaps stay where they were.
 */
private const val LOGO_SCALE = 1.05f

/**
 * The quality tag, as a share of the body step.
 *
 * Smaller than the name it sits beside, which is what makes it read as a tag rather than
 * as part of the channel's title. The reference sets it as a superscript; a smaller step
 * on the same baseline is the same claim without a typographic trick that Compose would
 * have to fake.
 */
private const val QUALITY_OF_BODY = 0.84f

/**
 * The mark's height inside the band. Nearly the whole of it: it is the mark.
 *
 * Raised with the wordmark beside it — see [WORDMARK_OF_LABEL]. The pair has the rail's
 * full width to itself and was the smallest confident thing on the screen.
 */
private const val MARK_OF_HEADER = 0.98f

/** The header's and the rail's second line, against the frame's body step. */
/**
 * The clock, against the header's label step.
 *
 * Larger than a label because it is the only thing in the band's trailing corner now, and
 * because it is the *only* clock on the screen — the app hides the system bars, so a
 * viewer who wants the time has nowhere else to look.
 */
private const val CLOCK_OF_LABEL = 1.32f

/**
 * The channel logo's box in the identity row, as a ratio.
 *
 * The same 54:40 the channel rows give a provider's mark -- wider than tall, because a
 * broadcaster's logo is a wordmark far more often than it is a square. Restated here
 * rather than shared with the row's `logoWidth`, which is a *width* for a column whose
 * height is a row; this is a *height* for a row whose width follows it.
 */
private const val LOGO_BOX = 54f / 40f

private const val LEGEND_OF_BODY = 0.88f

/** A line of [ProgrammeTable]: the word, the hours, and the bar or the title. */
private const val PROGRAMME_CELLS = 3

/**
 * What [ProgrammeTable] adds to the gap under the title of what is on, and to the gap between
 * the line of what is on and the line of what is next, as shares of the body step -- the most
 * it asks for, when the block has the room. What the block does not have it does not take.
 */
private const val TITLE_AIR_OF_BODY = 0.35f
private const val LINE_AIR_OF_BODY = 0.45f

/**
 * [NoGuideNotice], in body steps: its sentence a little larger than the body; the disc under the
 * mark and the mark inside it; and the room the notice needs before the mark stands over the
 * sentence instead of beside it -- the disc, the gap, and two lines of the sentence.
 */
private const val NO_GUIDE_OF_BODY = 1.15f
private const val NO_GUIDE_MARK_OF_BODY = 2.4f
private const val NO_GUIDE_ICON_OF_BODY = 1.3f
private const val NO_GUIDE_STACKED_OF_BODY = 7f

/** The hairline between the clock and the dates, as a share of the band. */
private const val RULE_OF_HEADER = 0.56f

/**
 * The wordmark, against the frame's label step.
 *
 * The mark and the name sit over the category rail with the whole of its width to
 * themselves and were drawn at the step a chip uses, which made the product's own name
 * the smallest confident thing on the screen. This is the one place on the board that
 * can afford to be larger than the type scale's own answer.
 */
private const val WORDMARK_OF_LABEL = 1.18f

/**
 * A heading's letter spacing.
 *
 * The one typographic difference between a provider's heading and a channel, beyond the
 * colour: tracking is what a reader's eye reads as *a label* rather than *a name*, and it
 * costs no height on a row whose height is already spent.
 */
private val HEADING_TRACKING = 0.06.em

/** The field's height inside the band. */
private const val FIELD_OF_HEADER = 0.62f

/**
 * The rail's field, against a rail entry's floor.
 *
 * A shade under it, so the field reads as the column's control rather than as its first
 * category — the same trick the header's field plays against the band.
 */
private const val FIELD_OF_ROW = 0.86f
