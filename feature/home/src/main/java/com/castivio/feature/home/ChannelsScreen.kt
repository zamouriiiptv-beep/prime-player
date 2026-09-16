package com.castivio.feature.home

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
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
import com.castivio.core.design.components.ltrIsolate
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
 * | the star, and `Favorites` | `FavoritesRepository` |
 * | the header's clock, provider and status | `HomeViewModel`, as Home reads them |
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
            BoardHeader(home = homeState, state = state, m = m)

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

/* ---------------------------------------------------------------- the header */

/**
 * One band across the top, and everything the reference puts in it.
 *
 * ## Why this is not `DashboardHeader`
 *
 * Because that one is Home's, and Home is a screen whose whole job is the subscription:
 * two stacked cards, a brand lockup and a clock, 76dp of height and worth every pixel
 * *there*. On this board it was the first of two full-width bands above a catalogue —
 * with the toolbar under it, the two together took **295 of 1080 pixels**, twenty-seven
 * per cent of the screen, before a single channel was drawn. The reference spends 88.
 *
 * So the facts are the same facts, laid along one line instead of stacked: the clock,
 * the mark and its version, which section and which category, the provider and when it
 * expires, and whether it is active. Nothing was dropped to make it fit; it was the
 * stacking that cost the height, not the content.
 *
 * Left to right in every language, like the columns under it and for the same reason —
 * this band is a row of instruments, and each value inside it still reads in its own
 * direction.
 */
@Composable
private fun BoardHeader(home: HomeState, state: BrowseState, m: ChannelsMetrics) {
    val colors = CastivioTheme.colors
    val context = LocalContext.current
    val version = remember(context) { context.versionName() }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier.fillMaxWidth().height(m.header),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.railGap),
        ) {
            BoardClock(m)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.osdGap),
            ) {
                Image(
                    painter = painterResource(DesignR.drawable.castivio_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(m.header * MARK_OF_HEADER),
                )
                Column {
                    Text(
                        text = stringResource(R.string.gate_wordmark),
                        style = castivioTitleStyle(m.frame.fsLabel),
                        color = colors.onBackgroundStrong,
                        maxLines = 1,
                    )
                    if (version != null) {
                        Text(
                            text = stringResource(R.string.gate_version, ltrIsolate(version)),
                            style = castivioBodyStyle(m.frame.fsBody * LEGEND_OF_BODY),
                            color = colors.onBackgroundMuted,
                            maxLines = 1,
                        )
                    }
                }
            }

            // Which section, and which category inside it. The reference's own
            // breadcrumb, and the reason this board has no toolbar: it was the only
            // thing in that band the header could not already say.
            //
            // Weighted, so it is this that gives way when the band runs out of width
            // rather than the three facts at the end -- a category name ellipsised is
            // still a category name, and it is the one string here that can be long.
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.browse_live),
                    style = castivioChipStyle(m.frame.fsLabel),
                    color = colors.secondary,
                    maxLines = 1,
                )
                Text(
                    text = state.selectedGroup?.let { state.categoryNames[it] }
                        ?: stringResource(R.string.channels_all_rail),
                    style = castivioBodyStyle(m.frame.fsBody * LEGEND_OF_BODY),
                    color = colors.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            state.providerLabel?.let { provider ->
                HeaderCard(
                    icon = Icons.Rounded.FormatListBulleted,
                    caption = stringResource(R.string.channels_playlist),
                    value = provider,
                    tint = colors.secondary,
                    m = m,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

            HeaderCard(
                icon = Icons.Rounded.CalendarMonth,
                caption = stringResource(R.string.home_status_expires),
                // Home's own, not a second opinion about the same date. It is already
                // isolated by `rememberDate`, which is where the `162026/09/` this
                // header used to draw was actually coming from.
                value = expiryLabel(home.subscription),
                tint = colors.hueViolet,
                m = m,
                modifier = Modifier.weight(1f, fill = false),
            )

            HeaderCard(
                icon = Icons.Rounded.CheckCircle,
                caption = stringResource(R.string.home_status_account),
                value = subscriptionLabel(home.subscription),
                tint = when (home.subscription?.usable) {
                    null -> colors.onBackgroundMuted
                    true -> colors.success
                    false -> colors.danger
                },
                m = m,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** The time, and the day under it — both isolated. See [ltrIsolate]. */
@Composable
private fun BoardClock(m: ChannelsMetrics) {
    val colors = CastivioTheme.colors
    val now = rememberMinute()
    val locale = LocalConfiguration.current
    val time = remember(now, locale) {
        ltrIsolate(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)))
    }
    val day = remember(now, locale) {
        ltrIsolate(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(now)))
    }
    Column {
        Text(time, style = castivioTitleStyle(m.frame.fsLabel), color = colors.onBackgroundStrong, maxLines = 1)
        Text(
            day,
            style = castivioBodyStyle(m.frame.fsBody * LEGEND_OF_BODY),
            color = colors.onBackgroundMuted,
            maxLines = 1,
        )
    }
}

/** One of the header's two facts: a caption over a value, behind glass. */
@Composable
private fun HeaderCard(
    icon: ImageVector,
    caption: String,
    value: String,
    tint: Color,
    m: ChannelsMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)
    Row(
        modifier
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(horizontal = m.rowPadH, vertical = m.badgePadV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.osdGap),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizing.iconSm))
        Column {
            Text(
                text = caption,
                style = castivioBodyStyle(m.frame.fsBody * LEGEND_OF_BODY),
                color = colors.onBackgroundMuted,
                maxLines = 1,
            )
            Text(
                text = value,
                style = castivioChipStyle(m.frame.fsBody),
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The panel: the four columns.
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
            .padding(bottom = m.headerGap)
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
                canPlay = shown.channel != null,
                m = m,
                onHome = onBack,
                // The same conversion a press on the row uses, not a second one. A
                // channel the catalogue cannot turn into a stream opens nothing here for
                // exactly the reason it opens nothing there.
                onFullscreen = { shown.channel?.asSelection()?.let(onPlay) },
                onSearch = onSearch,
                onFavorite = previewModel::toggleFavorite,
                onSort = { model.sortBy(state.sort.next()) },
                onRefresh = { model.retryFetch(force = true) },
                modifier = Modifier.width(m.actions).fillMaxHeight(),
            )

            Spacer(Modifier.width(m.railGap))

            CategoryRail(
                state = state,
                m = m,
                onChoose = model::choose,
                onRefresh = { model.retryFetch(force = true) },
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
 * The strip of actions at the leading edge, in the reference's shape.
 *
 * ## Six circles, where the reference draws ten
 *
 * The reference's strip is home, fullscreen, favourites, lock, search, settings, list,
 * language, information and power. Six of those have behaviour in Castivio today and are
 * here. The other four — a parental lock, a settings route out of this board, an
 * in-screen language switch and an exit — have none, and drawing them would put four
 * circles on the most prominent strip of the screen that answer a press with nothing.
 *
 * That is not a smaller design. It is the same strip with the same geometry and the same
 * spacing; the cells that would be dishonest are simply not drawn, and the day any of the
 * four gets an implementation it takes its place without the strip moving. A control that
 * does nothing teaches a viewer the strip is decorative, and then they stop pressing the
 * ones that work.
 *
 * ## What each one does, so none of them is a guess
 *
 * | circle | behaviour |
 * |---|---|
 * | home | leaves the board, the same event the red key sends |
 * | fullscreen | opens the focused channel in the player |
 * | search | the catalogue-wide search screen |
 * | favourites | toggles the focused channel, from `FavoritesRepository` |
 * | sort | cycles `SortOrder`, which re-reads the pager |
 * | refresh | a forced re-import of this section |
 */
@Composable
private fun ActionRail(
    favorite: Boolean,
    canPlay: Boolean,
    m: ChannelsMetrics,
    onHome: () -> Unit,
    onFullscreen: () -> Unit,
    onSearch: () -> Unit,
    onFavorite: () -> Unit,
    onSort: () -> Unit,
    onRefresh: () -> Unit,
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
        ActionDot(Icons.Rounded.Menu, stringResource(R.string.channels_key_back), m, false, onHome)
        // Absent rather than inert while nothing is focused: there is no channel to
        // open, and a circle that is drawn but ignores a press is the thing the note
        // above is about.
        if (canPlay) {
            ActionDot(
                Icons.Rounded.Fullscreen,
                stringResource(R.string.channels_action_fullscreen),
                m,
                false,
                onFullscreen,
            )
        }
        ActionDot(
            icon = if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            label = stringResource(
                if (favorite) R.string.channels_key_unfavorite else R.string.channels_key_favorite,
            ),
            m = m,
            on = favorite,
            onClick = onFavorite,
        )
        ActionDot(Icons.Rounded.Search, stringResource(R.string.channels_search_hint), m, false, onSearch)
        ActionDot(Icons.Rounded.Sort, stringResource(R.string.channels_action_sort), m, false, onSort)
        Spacer(Modifier.weight(1f))
        ActionDot(Icons.Rounded.Refresh, stringResource(R.string.channels_action_refresh), m, false, onRefresh)
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
    onRefresh: () -> Unit,
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
        if (state.groups.isEmpty() && state.fetch !is SectionLoad.Failed) {
            item(key = RAIL_EMPTY) { RailEmpty(m = m, onRefresh = onRefresh) }
        }

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
            .padding(horizontal = m.rowPadH, vertical = m.badgePadV * 2),
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

        LogoTile(
            initials = initialsOf(channel.title),
            seed = seed,
            modifier = Modifier.width(m.logoWidth).height(m.rowMin * LOGO_OF_ROW),
        )

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
        Text(
            // Without the tag, because the tag is drawn beside it. `|FR| TF1 HD`
            // reads as `|FR| TF1  ᴴᴰ` rather than repeating itself.
            text = titleWithoutQuality(channel.title),
            style = castivioChipStyle(m.frame.fsLabel),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
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

        // The number, on a plate. It is what a remote dials, so it is a token rather
        // than a column of text, and it sits at the row's trailing edge in every
        // language -- the board does not mirror, and this is the board's own edge.
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

        // **The guide's area is the same size whether or not there is a guide.**
        //
        // It used to be a `weight(1f)` column when a schedule existed and a bare line of
        // text when it did not, so the whole well changed shape the moment a channel
        // without EPG took focus -- the picture grew, the provider's name jumped, and
        // arrowing down a list alternated between two layouts. The region is claimed
        // either way now, and what varies is only what is drawn inside it.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.schedule.isEmpty()) {
                Text(
                    // Three different absences reach this line -- a channel with no
                    // guide id, a guide never imported, a schedule that has run out --
                    // and all three are honestly "no information".
                    text = stringResource(R.string.channels_no_information),
                    style = castivioBodyStyle(m.frame.fsBody),
                    color = colors.onBackgroundMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val nowId = shown.guide?.now?.let { it.startMs }
                Column(
                    Modifier.fillMaxSize(),
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
                    // **LIVE, and it means it.** Live television is the one thing this
                    // board shows, so the badge is a statement about the section rather
                    // than a per-channel claim nothing could back -- which is exactly
                    // why it is drawn here and not, say, from a guide that may be absent.
                    LiveBadge(m)
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
private const val RAIL_EMPTY = "castivio.rail.empty"


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

/** The mark's height inside the header band. */
private const val MARK_OF_HEADER = 0.68f

/** The header's and the rail's second line, against the frame's body step. */
private const val LEGEND_OF_BODY = 0.88f
