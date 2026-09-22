package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import com.castivio.core.design.components.DelayedSpinner
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.ErrorState
import com.castivio.core.design.components.LogoTile
import com.castivio.core.design.components.ProviderArtwork
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.components.formatCount
import com.castivio.core.design.components.ltrToken
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.platform.CastivioTrace
import com.castivio.core.platform.PerformanceLog
import com.castivio.domain.Channel
import com.castivio.domain.MediaItem
import com.castivio.domain.Programme
import com.castivio.domain.SectionLoad
import com.castivio.domain.SortOrder
import com.castivio.domain.isProviderHeading
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * ## Two things the design shows that this deliberately does not invent
 *
 *  1. **The stream's resolution.** The design prints `1920 × 1080` beside the channel
 *     number. That is a property of a *decoded* stream, known to the engine after the
 *     player has opened it and to nothing on a board that has opened nothing, so `Osd`
 *     prints the number alone.
 *  2. **The channel's logo.** `artwork_url` is imported and stored, but Castivio loads
 *     no images anywhere yet. So each row *reserves* the slot and draws nothing in it:
 *     the geometry is already the geometry the pictures will land in, and a generated
 *     coloured square is not the channel's logo — see `ChannelRow`. [LogoTile] still
 *     stands in for the preview's own picture, where the alternative is an empty third
 *     of the screen.
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
    onSearch: () -> Unit,
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
) {
    LaunchedEffect(Unit) { model.show(CatalogSection.Live) }

    val state by model.state.collectAsStateWithLifecycle()
    val shown by previewModel.preview.collectAsStateWithLifecycle()
    val tv = CastivioTheme.device.isTv

    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        val m = channelsMetricsFor(tv = tv, width = maxWidth, height = maxHeight)

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = m.edge)
                .padding(top = m.boardTop, bottom = m.boardBottom),
        ) {
            BoardHeader(state = state, m = m, onSearch = onSearch)

            Spacer(Modifier.height(m.headerGap))

            Board(
                state = state,
                shown = shown,
                m = m,
                model = model,
                previewModel = previewModel,
                onPlay = onPlay,
                onExpand = onExpand,
                preview = preview,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/* ---------------------------------------------------------------- the header */

/**
 * One thin band, in the order the approved drawing sets: mark, breadcrumb, search,
 * dates.
 *
 * ## What it carries, and what came off it
 *
 * The mark, which section and which category, a way into search, and the two dates that
 * decide whether anything on this screen still works.
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
private fun BoardHeader(state: BrowseState, m: ChannelsMetrics, onSearch: () -> Unit) {
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
                        ?: stringResource(R.string.channels_all_rail),
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
            // the right thing to shrink — a way in to the search screen shows its icon
            // and less of its hint and still does its whole job, where a narrower date
            // column simply stops saying the date.
            //
            // Centring it in that remainder is also what keeps it in the middle of the
            // band, which two weighted spacers used to do.
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                SearchField(m = m, onClick = onSearch)
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
    val colors = CastivioTheme.colors
    val now = rememberMinute()
    val time = remember(now) {
        ltrToken(SimpleDateFormat(CLOCK_TIME, Locale.ROOT).format(Date(now)))
    }
    val day = remember(now) {
        ltrToken(SimpleDateFormat(SHORT_DATE, Locale.ROOT).format(Date(now)))
    }
    // Its own bounded cell, for the reason the breadcrumb has one: nothing whose width
    // is text may decide where the rest of the band sits.
    Column(Modifier.width(m.dates), horizontalAlignment = Alignment.End) {
        Text(
            text = time,
            style = castivioTitleStyle(m.frame.fsLabel * CLOCK_OF_LABEL),
            color = colors.onBackgroundStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = day,
            style = castivioBodyStyle(m.frame.fsBody * LEGEND_OF_BODY),
            color = colors.onBackgroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The rail's own field: a real one, because it filters a list already in memory.
 *
 * Unlike the header's — which is a way in to the search screen because a text field a
 * remote can focus is a trap, and because searching 400,000 rows belongs on a screen of
 * its own — this one types into a few hundred names the holder is already holding. There
 * is nothing to navigate to and nothing to debounce against a database, so it is the
 * field itself.
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
 * The way into search, from the middle of the band.
 *
 * A way in rather than a field. Pressing it opens the catalogue-wide search screen that
 * already exists; a real input here would be a second query, a second debounce and a
 * second set of empty states beside the one that already spans every section — and on a
 * remote, a text field that takes focus is a trap a viewer has to press back to escape.
 */
@Composable
private fun SearchField(m: ChannelsMetrics, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    val (focused, focusModifier) = rememberFocusFlag()
    val shape = RoundedCornerShape(Radius.pill)

    Row(
        Modifier
            .width(m.search)
            .height(m.header * FIELD_OF_HEADER)
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, if (focused) colors.focusRing else colors.glassBorderSoft, shape)
            .then(focusModifier)
            .clickable(onClick = onClick)
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
        Text(
            text = stringResource(R.string.channels_search_hint),
            style = castivioBodyStyle(m.frame.fsBody),
            color = colors.onBackgroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    onExpand: () -> Unit,
    preview: (@Composable () -> Unit)?,
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
                    onExpand = onExpand,
                    preview = preview,
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
            .padding(horizontal = m.controlPadH, vertical = m.badgePadV * 2),
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
    m: ChannelsMetrics,
    model: BrowseViewModel,
    previewModel: ChannelsViewModel,
    onPlay: (CatalogSelection) -> Unit,
    onExpand: () -> Unit,
    preview: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val rows = model.items.collectAsLazyPagingItems()
    val listState = rememberLazyListState()

    var guideOpen by remember { mutableStateOf(false) }

    val listFocus = remember { FocusRequester() }
    val wellFocus = remember { FocusRequester() }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxSize()) {
                CategoryRail(
                    state = state,
                    m = m,
                    onChoose = model::choose,
                    onFilter = model::filterGroups,
                    onRefresh = { model.retryFetch(force = true) },
                    modifier = Modifier.width(m.rail).fillMaxHeight(),
                )

                Spacer(Modifier.width(m.railGap))

                ChannelList(
                    rows = rows,
                    state = state,
                    m = m,
                    listState = listState,
                    // The row the well is showing, so the list can mark it. Read off the
                    // preview rather than held again here: one answer to "which channel is
                    // this board about", and the picture and the row cannot disagree.
                    selectedId = shown.channel?.id,
                    onSelect = previewModel::select,
                    onPlay = onPlay,
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
                    preview = preview,
                    onOpenGuide = { guideOpen = true },
                    onFavorite = previewModel::toggleFavorite,
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
                ChannelGuideOverlay(shown = shown, m = m, onClose = { guideOpen = false })
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
private fun CategoryRail(
    state: BrowseState,
    m: ChannelsMetrics,
    onChoose: (String?) -> Unit,
    onFilter: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        // **The rail's own field.** It filters the categories in this column and nothing
        // else: a provider with four hundred bouquets is a column nobody scrolls to the
        // end of, and the field is how a viewer reaches "SPORT" without passing 300
        // others. Filtered in the holder, not here -- see `BrowseState.groupFilter`.
        RailSearch(text = state.groupFilter, m = m, onType = onFilter)

        Spacer(Modifier.height(m.railEntryGap * 2))

        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(m.railEntryGap),
        ) {
            item(key = RAIL_ALL) {
                RailEntry(
                    label = stringResource(R.string.channels_all_rail),
                    // **The section's own count, not the open query's.**
                    //
                    // This read the same `total` the list under it reads, which is the
                    // count of the *selected* category -- so choosing SPORT made the
                    // All row report SPORT's size, and the one figure on the board that
                    // is supposed to say how many channels the subscription carries
                    // agreed with whichever bouquet happened to be open. It reads
                    // `sectionTotal` now: a second indexed COUNT, over the kind with no
                    // group filter, so the row means the same thing whatever is chosen.
                    count = state.sectionTotal,
                    selected = state.selectedGroup == null,
                    m = m,
                    onClick = { onChoose(null) },
                )
            }
            item(key = RAIL_FAVORITES) {
                RailEntry(
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

            // The filtered list, so the field actually does something; `groups` stays
            // whole for the rows that look a category's name up by id.
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
                    selected = group.id == state.selectedGroup,
                    m = m,
                    onClick = { onChoose(group.id) },
                )
            }
        }
    }
}

@Composable
private fun RailEntry(
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
    //
    // `onBackground`, the channel list's own ink, and not the variant this used to take.
    // The two columns sit side by side and carry the same kind of thing — a name the
    // provider wrote — so a bouquet reading a shade dimmer than a channel made the rail
    // look like furniture around the list rather than half of the same board.
    val ink = if (selected) colors.onSecondary else colors.onBackground

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
            .padding(horizontal = m.railPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.railPadH),
    ) {
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
    /** The channel the well is showing, or null before a row has been pressed. */
    selectedId: String?,
    onSelect: (Channel, Int) -> Unit,
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
            state = listState,
            modifier = Modifier.fillMaxSize().padding(m.wellPad / 2),
            contentPadding = PaddingValues(bottom = m.wellPad),
        ) {
            items(rows.itemCount, key = rows.itemKey { it.id }) { index ->
                val item = rows[index] ?: return@items
                val channel = item as? Channel ?: return@items
                ChannelRow(
                    channel = channel,
                    // The reference's badge: where this row is in the list as ordered,
                    // one-based. See `ChannelsViewModel.position` for why it is not read
                    // off the channel.
                    number = index + 1,
                    selected = channel.id == selectedId,
                    m = m,
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
                    onClick = {
                        onSelect(channel, index + 1)
                        channel.asSelection()?.let(onPlay)
                    },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}

// `basicMarquee` is stable from Compose Foundation 1.7, which is the line this BOM
// pins. Declared anyway: an opt-in that turns out to be unnecessary costs a warning,
// and one that turns out to be necessary costs a red CI run.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: Channel,
    number: Int,
    /** True of the one row the well is showing. See the surface rule below. */
    selected: Boolean,
    m: ChannelsMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }

    // **Selection is the surface, focus is the ring** — the same sentence `RailEntry`
    // already lives by, and now the same picture. The row used to paint the violet on
    // *focus*, so the board said two different things in two columns a few pixels apart:
    // a bouquet stayed lit while the remote moved on, and the channel actually playing
    // went dark the moment the remote left it. A viewer scrolling for the next thing to
    // watch could not see which channel they were watching.
    //
    // Both states are true of one row at once and they no longer compete: the fill says
    // "this is the one in the picture", the azure ring says "this is where you are".
    //
    // A focused row that is *not* the selected one still takes a surface — the glass the
    // number plate and the idle plate already use — because a hairline alone is too thin
    // a thing to find from three metres with a remote. It is a quieter surface than the
    // violet, which is what keeps the two states apart at a glance.
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = m.rowMin)
            .clip(shape)
            .background(
                when {
                    selected -> colors.secondary
                    focused -> colors.glassFill
                    else -> Color.Transparent
                },
            )
            .border(
                1.dp,
                when {
                    focused -> colors.focusRing
                    selected -> colors.selectedBorder
                    else -> Color.Transparent
                },
                shape,
            )
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(interaction, indication = null, onClick = onClick)
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

        // Keyed to the *fill*, not to focus: ink has to be legible on whatever is behind
        // it, and only the selected row is violet.
        val ink = when {
            selected -> colors.onSecondary
            heading -> colors.onBackgroundMuted
            else -> colors.onBackground
        }

        // The number, on a plate, and it opens the row. It is what a remote dials, so
        // it is the first thing on the line a viewer reads down -- a column of numbers
        // is scanned, and a column of numbers behind two other things is not a column.
        if (heading) {
            Spacer(Modifier.width(m.numberWidth))
        } else {
            NumberPlate(label = numberPlate(number), onFill = selected, m = m)
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
        Text(
            // Without the tag, because the tag is drawn beside it. `|FR| TF1 HD`
            // reads as `|FR| TF1  ᴴᴰ` rather than repeating itself.
            text = if (heading) channel.title else titleWithoutQuality(channel.title),
            style = castivioChipStyle(m.frame.fsLabel)
                .copy(letterSpacing = if (heading) HEADING_TRACKING else TextUnit.Unspecified),
            color = ink,
            maxLines = 1,
            // **A name too long for the row scrolls, but only the one under the remote.**
            //
            // Providers ship names like `4K| SKY SPORTS ULTRA HD MAIN EVENT 1`, and a
            // list column this wide ends most of them in an ellipsis -- which is the one
            // complaint a viewer cannot work around, because the part that is cut is the
            // part that tells two similar channels apart.
            //
            // Scrolling every row at once would make the screen unreadable and would
            // animate twelve texts on a stick that has to hold 60fps while paging a
            // catalogue, so it is the focused row and no other. An unfocused row keeps
            // its ellipsis, which is also the honest cue that there is more to see:
            // arrow onto it and the rest arrives.
            overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .then(if (focused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
        )

        // The reference's quality tag, read out of the name the provider wrote.
        // Absent when the provider wrote none -- never inferred, and never a
        // default. See `ChannelTitle.kt`. A heading is decoration rather than a
        // stream, so nothing is read out of it.
        qualityOf(channel.title).takeIf { !heading }?.let { quality ->
            Text(
                text = quality.label,
                style = castivioBodyStyle(m.frame.fsBody * QUALITY_OF_BODY),
                color = when {
                    selected -> colors.onSecondary
                    quality == StreamQuality.SD -> colors.onBackgroundMuted
                    else -> colors.secondary
                },
                maxLines = 1,
            )
        }
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
    /** True when the row behind the plate carries the selection's violet. */
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
            color = colors.secondary,
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
private fun ChannelWell(
    shown: ChannelPreview,
    m: ChannelsMetrics,
    preview: (@Composable () -> Unit)?,
    onOpenGuide: () -> Unit,
    onFavorite: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(m.guideGap),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Sized from the metrics rather than from the column, because on a short surface
        // the picture is *narrower* than the column it sits in -- it gives height back to
        // the facts under it and keeps its shape. See `ChannelsMetrics.wellPicture`.
        WellPicture(
            shown = shown,
            m = m,
            preview = preview,
            modifier = Modifier.width(m.wellPictureWidth).height(m.wellPicture),
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
        // **Only where there are three sections to set apart.** A channel with no schedule
        // holds a name and one sentence, and one at the end of its guide window holds a
        // name and one programme; stretching *those* over the room would put the hole back
        // inside the border. They keep hugging, and their slack stays plain ground outside
        // the frame, where empty space belongs.
        val sections = shown.guide?.next != null
        ChannelFacts(
            shown = shown,
            m = m,
            onExpand = onExpand,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (sections) Modifier.weight(1f) else Modifier),
        )

        // The slack, named -- and needed only where the block did not take it. It is what
        // keeps the strip on the floor of the board while the facts stay under the picture.
        if (!sections) Spacer(Modifier.weight(1f))

        ActionStrip(
            shown = shown,
            m = m,
            onOpenGuide = onOpenGuide,
            onFavorite = onFavorite,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The picture, with the two marks that belong on a frame of video and no others.
 *
 * The live badge and the number are over it because they are true of the *picture*; the
 * name and the programme are under it because they are true of the channel, and a caption
 * written across moving video is the thing this board was asked to stop doing.
 *
 * **These two marks are the only ones.** The compact player used to draw its own band over
 * the same corner — the channel's name, a second LIVE pill, a second number — so the
 * picture carried each of them twice, one set over the other. That band is gone; see
 * `PlayerScreen`. The picture is now video and these two marks, and nothing that is also
 * printed underneath it.
 *
 * There is no close control. The column is a fixture and the owner wants it to stay one:
 * a button whose whole job is to remove a fixed part of the board is a button that can
 * only make the screen wrong.
 */
@Composable
private fun WellPicture(
    shown: ChannelPreview,
    m: ChannelsMetrics,
    preview: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.wellRadius)
    val channel = shown.channel

    Box(
        modifier
            .clip(shape)
            .background(colors.backgroundElevated)
            .border(1.dp, colors.glassBorder, shape),
    ) {
        // **The plate is the player's when there is a player.**
        //
        // The mark is what this plate shows while nothing has been pressed — a preview of
        // the channel that was chosen. Once one is actually playing, the same plate is the
        // compact player. It keeps its size and its place either way, so pressing a
        // channel changes what is in the box and never where the box is.
        if (preview != null) {
            preview()
        } else if (channel != null) {
            LogoTile(
                initials = initialsOf(channel.title),
                seed = channel.id.hashCode(),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
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

        if (channel != null) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(m.osdPad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.osdGap),
            ) {
                LiveBadge(m)
                Text(
                    text = numberPlate(shown.number),
                    style = castivioChipStyle(m.frame.fsBody * QUALITY_OF_BODY),
                    color = colors.onBackgroundStrong,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(colors.scrim)
                        .padding(horizontal = m.badgePadH, vertical = m.badgePadV),
                )
            }
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
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.wellRadius)
    val channel = shown.channel
    val guide = shown.guide
    val now = guide?.now
    val next = guide?.next

    // **And the one after that, from the guide's own order rather than from the clock.**
    //
    // `shown.schedule` is the window `ChannelsViewModel` already stored -- the same rows
    // the guide page lists, in `start_ms` order, capped at `SCHEDULE_ROWS` = 3. With a
    // programme on air those three are exactly `now`, `next` and this one. **Nothing is
    // requested to draw it**: the third row comes out of the bytes that arrived with the
    // first, and a channel whose window ends sooner simply has no third row.
    //
    // Anchored on `next` and stepped by one, not filtered by "starts after the current
    // time". The two would usually agree; only this one cannot put the panel at odds with
    // itself, because it asks the list where `next` is and takes whatever the provider put
    // after it.
    val after = next?.let { coming ->
        val at = shown.schedule.indexOfFirst { it.startMs == coming.startMs }
        if (at >= 0) shown.schedule.getOrNull(at + 1) else null
    }

    Column(
        modifier
            .clip(shape)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(horizontal = m.guidePad, vertical = m.guidePad / 2),
        // **Spread, because the block is now given the room rather than its content.**
        //
        // `SpaceBetween` distributes whatever the column had spare between the *children*
        // of this column, which is why they are three and not eight: grouped, the air
        // falls between who / what is on / what is coming, and never between a programme's
        // name and the bar measuring it. Each group carries a top padding as well, because
        // `SpaceBetween` cannot state a minimum — where the content already fills the room
        // there is nothing to distribute and the groups would otherwise meet edge to edge.
        //
        // Where the block hugs instead — a channel with no schedule, or one at the end of
        // its window — there is no free space and this arranges nothing, reading exactly
        // as `spacedBy(badgePadV)` did.
        verticalArrangement = Arrangement.SpaceBetween,
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
                text = channel?.let { titleWithoutQuality(it.title) }
                    ?: stringResource(R.string.channels_preview_none),
                style = castivioTitleStyle(m.fsChannelName),
                color = if (channel != null) colors.onBackgroundStrong else colors.onBackgroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            channel?.title?.let(::qualityOf)?.let {
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
        if (now == null) {
            if (channel != null) {
                Text(
                    text = stringResource(R.string.channels_guide_none),
                    style = castivioBodyStyle(m.frame.fsBody),
                    color = colors.onBackgroundMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = m.badgePadV),
                )
            }
            return@Column
        }

        // -------------------------------------------------------- what is on now
        //
        // The title, its bar, its hours and its synopsis are one child of this column,
        // not four. `SpaceBetween` distributes the column's spare height between its
        // *children*, and four loose children would put that air between a programme's
        // name and the bar measuring it. Grouped, the air falls where it belongs: between
        // the three things a reader treats as separate — who, what is on, what is coming.
        Column(
            Modifier.padding(top = m.badgePadV),
            verticalArrangement = Arrangement.spacedBy(m.badgePadV),
        ) {
            // **The seam the block was missing.**
            //
            // The channel's name and the programme on it were separated by `badgePadV` —
            // 2dp on a handset, the same gap that holds the NOW line off its own bar. So
            // nothing said the thing above it was a different kind of thing from the one
            // below, and the only drawn rule sat before what is coming: the seam the eye
            // trusted was in the wrong place, and the block read as "channel and now",
            // then "the rest".
            //
            // This is the same rule, drawn twice. Three sections, one mark between each.
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.glassBorder))
            GuideLine(
                label = stringResource(R.string.channels_guide_now),
                title = now.title,
                now = true,
                m = m,
            )
            Track(fraction = guide.progressAt(System.currentTimeMillis()), m = m)

            // The hours it runs, at the two ends of the bar they describe. Read from the
            // programme's own timestamps -- the same two the bar is drawn from, so the
            // figures and the fill can never disagree.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = clockLabel(now.startMs),
                    style = castivioBodyStyle(m.frame.fsBody * LEGEND_OF_BODY),
                    color = colors.onBackgroundVariant,
                    maxLines = 1,
                )
                Text(
                    text = clockLabel(now.stopMs),
                    style = castivioBodyStyle(m.frame.fsBody * LEGEND_OF_BODY),
                    color = colors.onBackgroundVariant,
                    maxLines = 1,
                )
            }

            // **What the programme actually is, where the column can afford to say it.**
            //
            // It belongs to NOW and sits inside NOW's group, under the bar that measures
            // it. Already on the device and already drawn by the guide page -- this reads
            // the same `Programme.description` and issues nothing.
            //
            // Two conditions, and both are refusals to invent. `wellSynopsis` is the
            // column's answer about room, not a device class; see `ChannelsMetrics`. And a
            // provider that sent no synopsis gets no line at all rather than an empty one,
            // which is why this is a `takeIf` and not a placeholder.
            if (m.wellSynopsis) {
                now.description?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(
                        text = detail,
                        style = castivioBodyStyle(m.frame.fsBody),
                        color = colors.onBackgroundMuted,
                        maxLines = CHANNELS_SYNOPSIS_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // ----------------------------------------------- and the two that follow it
        //
        // **Two children, not one, and that is the whole of a defect this had on a
        // device.** They were one group, so `SpaceBetween` gave the column's spare height
        // to the three gaps *between* groups and none of it to the gap inside this one:
        // what is on sat a finger's width from what is next, and the two coming
        // programmes were glued together 2dp apart. Three sections breathing and two rows
        // suffocating inside the last of them is not a distribution, it is a distribution
        // with an exception.
        //
        // Separated, the column's children are the four things a reader actually scans —
        // who, what is on, what is next, what follows that — and `SpaceBetween` sets the
        // same air between each of them. The rule stays with the row it opens, because a
        // hairline loose in a column reads as floating rather than as a boundary.
        //
        // Both rows come out of `shown.schedule` -- the same ordered window the guide page
        // reads, already on the device. Nothing is fetched to draw the second one.
        next?.let { programme ->
            Column(
                Modifier.padding(top = m.badgePadV),
                verticalArrangement = Arrangement.spacedBy(m.badgePadV),
            ) {
                // One rule for the pair, not one each: they are the same category of thing
                // -- the future -- and ruling between them would say they are not.
                //
                // `glassBorder` rather than the `glassBorderSoft` this drew before. Soft is
                // the pale blue-grey at 10%, which over this ground is about seven steps of
                // lightness -- enough for a container's edge, where the shape does the work,
                // and not enough for a 1dp line that has to be *found* on a handset held at
                // arm's length. At 20% it is about fifteen, and it reads. Both rules take
                // it, because a seam that is stronger than its twin stops being the same
                // mark said twice.
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.glassBorder))
                ComingLine(
                    label = stringResource(R.string.channels_guide_next),
                    programme = programme,
                    m = m,
                )
            }
        }

        // Absent rather than reserved: a channel whose guide window ends after the next
        // programme draws one coming row and the panel is simply shorter. An empty row
        // held open for something that does not exist is the defect this block's whole
        // shape exists to avoid.
        after?.let {
            ComingLine(
                label = stringResource(R.string.channels_guide_then),
                programme = it,
                m = m,
                modifier = Modifier.padding(top = m.badgePadV),
            )
        }
    }
}

/**
 * A programme that has not started: what it is, and the hours it will run.
 *
 * One declaration for both of the rows under the rule, because they are the same row. It
 * was written once and the second would have been a copy — and a copy is where the two
 * stop agreeing about type, colour or the shape of a time.
 *
 * **The range, not the start.** The start alone is the right number and reads as the wrong
 * one: the instant a programme begins is the instant the one before it ends, so a column
 * of single times prints each boundary twice and looks like a clock that has failed to
 * move. Both ends is also what the guide page shows, in the same grammar.
 */
@Composable
private fun ComingLine(
    label: String,
    programme: Programme,
    m: ChannelsMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.factGap),
    ) {
        GuideLine(
            label = label,
            title = programme.title,
            now = false,
            m = m,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                R.string.channels_time_range,
                clockLabel(programme.startMs),
                clockLabel(programme.stopMs),
            ),
            style = castivioBodyStyle(m.frame.fsBody * LEGEND_OF_BODY),
            color = colors.onBackgroundVariant,
            maxLines = 1,
        )
    }
}

/** One line of the facts block: what it is, and what is on. */
@Composable
private fun GuideLine(
    label: String,
    title: String,
    now: Boolean,
    m: ChannelsMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.factGap),
    ) {
        Text(
            text = label,
            style = castivioBodyStyle(m.frame.fsBody * LEGEND_OF_BODY),
            color = if (now) colors.secondary else colors.onBackgroundMuted,
            maxLines = 1,
        )
        Text(
            text = title,
            style = castivioBodyStyle(m.frame.fsBody),
            color = if (now) colors.onBackgroundStrong else colors.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
 * ## Two of these do not work yet, and they are drawn anyway
 *
 * **Lock** and **Hide** have no table behind them: there is no `channel_lock` and no
 * `channel_hidden`, so pressing them changes nothing. They are on screen at the owner's
 * explicit instruction, to see the strip and walk it on a device before the storage is
 * built. This comment is the record of that decision — the buttons are not forgotten
 * work, they are staged work, and the next commit on this file should be the two tables.
 */
@Composable
private fun ActionStrip(
    shown: ChannelPreview,
    m: ChannelsMetrics,
    onOpenGuide: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasChannel = shown.channel != null

    Row(
        modifier.horizontalScroll(rememberScrollState()).focusGroup(),
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
        )
        ActionButton(
            icon = if (shown.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            label = stringResource(
                if (shown.favorite) R.string.channels_action_unfavorite
                else R.string.channels_action_favorite,
            ),
            on = shown.favorite,
            enabled = hasChannel,
            onClick = onFavorite,
            m = m,
        )
        ActionButton(
            icon = Icons.Rounded.Lock,
            label = stringResource(R.string.channels_action_lock),
            enabled = hasChannel,
            onClick = {},
            m = m,
        )
        ActionButton(
            icon = Icons.Rounded.VisibilityOff,
            label = stringResource(R.string.channels_action_hide),
            enabled = hasChannel,
            onClick = {},
            m = m,
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
 */
@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    m: ChannelsMetrics,
    on: Boolean = false,
    enabled: Boolean = true,
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
        Modifier
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
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
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
private fun ChannelGuideOverlay(shown: ChannelPreview, m: ChannelsMetrics, onClose: () -> Unit) {
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
                    text = shown.channel?.let { titleWithoutQuality(it.title) }
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
                items(shown.schedule, key = { it.startMs }) { programme ->
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


/** The logo slot's height as a share of the row — 28 of 68 in the reference. */
private const val LOGO_OF_ROW = 28f / 68f

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

/**
 * `16-09-2026`: two digits a field, hyphens, and the year in full.
 *
 * It was `yy`. Two digits are ambiguous in exactly the place this token is read -- a
 * subscription that ends in `26` is a date a viewer has to do arithmetic on -- and the
 * four characters it costs are four the reserved column was widened to hold.
 */
private const val SHORT_DATE = "dd-MM-yyyy"

/**
 * The clock: twenty-four hours, in every language.
 *
 * The same argument as [SHORT_DATE]. A twelve-hour clock is a locale's convention and
 * carries an `AM`/`PM` whose width is a different number of glyphs in each of the
 * thirty-seven languages, on a band that has no room to change size — and this is a
 * television screen, where the twenty-four-hour clock is what a viewer expects anyway.
 */
private const val CLOCK_TIME = "HH:mm"
