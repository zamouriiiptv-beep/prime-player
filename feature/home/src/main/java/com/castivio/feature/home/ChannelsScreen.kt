package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
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
import com.castivio.domain.NowNext
import com.castivio.domain.Programme
import com.castivio.domain.SectionLoad
import com.castivio.domain.SortOrder
import com.castivio.domain.entitlement.EntitlementState
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
 * | the header's two expiry dates | `HomeViewModel`, as Home reads them |
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
    /** For the header's two subscription cards, which are the same facts Home shows. */
    home: HomeViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { model.show(CatalogSection.Live) }

    val state by model.state.collectAsStateWithLifecycle()
    val shown by previewModel.preview.collectAsStateWithLifecycle()
    val homeState by home.state.collectAsStateWithLifecycle()
    val tv = CastivioTheme.device.isTv

    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        val m = channelsMetricsFor(tv = tv, width = maxWidth, height = maxHeight)

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = m.edge)
                .padding(top = m.boardTop, bottom = m.boardBottom),
        ) {
            BoardHeader(home = homeState, state = state, m = m, onSearch = onSearch)

            Spacer(Modifier.height(m.headerGap))

            Board(
                state = state,
                shown = shown,
                m = m,
                model = model,
                previewModel = previewModel,
                onPlay = onPlay,
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
private fun BoardHeader(home: HomeState, state: BrowseState, m: ChannelsMetrics, onSearch: () -> Unit) {
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

            // **The clock belongs beside the expiry dates, not beside the mark.**
            //
            // All three answer *when*, and reading them as one cluster is easier than
            // hunting for the time at the other end of the band. It exists again at all
            // because the app hides the system bars: nothing else on the screen can tell
            // a viewer the time now.
            BoardClock(m)

            // The clock was against the dates and read as a third expiry. A gap, a
            // hairline and a gap say what the spacing alone could not: these are two
            // different kinds of fact that happen to share a corner.
            Spacer(Modifier.width(m.clockGap))
            Box(
                Modifier
                    .width(1.dp)
                    .height(m.header * RULE_OF_HEADER)
                    .background(colors.glassBorderSoft),
            )
            Spacer(Modifier.width(m.clockGap))

            Column(
                // **A reserved width, not the remainder.** See `ChannelsMetrics.dates`
                // for the defect this ends: in Arabic the pair was measured with what
                // the rest of the band had left, came up short, and dropped the date.
                Modifier.width(m.dates),
                // **Against the band's trailing edge.** `Start` left the two lines at
                // the leading edge of their reserved column with the reserve showing as
                // a gap after them, so the cluster read as floating in the middle of
                // nowhere rather than as the corner of the screen it belongs to. `End`
                // also lines the two dates up under each other, which is the comparison
                // a viewer is making when they look here at all.
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(m.badgePadV / 2),
            ) {
                ExpiryLine(
                    text = stringResource(
                        R.string.channels_server_expires,
                        shortDate(home.subscription?.expiresAtMs),
                    ),
                    color = colors.secondary,
                    m = m,
                )
                ExpiryLine(
                    text = stringResource(R.string.channels_app_expires, licenceExpiry(home.entitlement)),
                    color = colors.live,
                    m = m,
                )
            }
        }
    }
}

/**
 * One expiry: a caption and its date, in one order in every language.
 *
 * [TextDirection.Ltr] rather than the default, which resolves the paragraph from its
 * first strong character and would therefore lay an Arabic caption out right to left
 * inside a band that is pinned left to right. That produced a third layout — not the
 * mirrored screen the platform would give and not the screen every other language gets —
 * and it is the reason the two lines did not sit under each other at the same place.
 * Pinned here, the caption starts at the column's leading edge and the date follows it,
 * in Arabic exactly as in English; the glyphs of the caption are still shaped by their
 * own script, which is the text engine's business and not the layout's.
 *
 * `Ellipsis` rather than the default clip, so that a translation this column cannot hold
 * degrades into something a reader can see is cut rather than into a caption whose value
 * silently disappeared.
 */
@Composable
private fun ExpiryLine(text: String, color: Color, m: ChannelsMetrics) {
    Text(
        text = text,
        style = castivioBodyStyle(m.frame.fsBody * EXPIRY_OF_BODY)
            .copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.End),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The time, and today's date under it.
 *
 * Both isolated and both in the header's own fixed shape — see [shortDate] for why a
 * token on this band does not follow the reader's date convention, and [ltrToken] for
 * the directional marks that made an earlier attempt at this read backwards.
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
    Column(Modifier.width(m.clock), horizontalAlignment = Alignment.End) {
        Text(
            text = time,
            style = castivioTitleStyle(m.frame.fsLabel),
            color = colors.onBackgroundStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = day,
            style = castivioBodyStyle(m.frame.fsBody * EXPIRY_OF_BODY),
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
            .padding(horizontal = m.rowPadH),
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
            .padding(horizontal = m.rowPadH),
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
 * `16-09-25`, or an em dash where there is no date.
 *
 * Digits and hyphens in one fixed order, in every language: this is the header's most
 * compressed fact and a month spelled out in Arabic is three times as wide as the band
 * can spare. [ltrToken] keeps it one left-to-right object — see the helper for the
 * directional marks that made the last attempt at this read backwards.
 *
 * `Locale.ROOT` deliberately, and it is the one place in the product where a date does
 * not follow the reader's own convention. What is drawn is a *token*, not a sentence,
 * and the alternative is a band whose width changes with the interface language.
 */
@Composable
private fun shortDate(atMs: Long?): String {
    val none = stringResource(R.string.home_expires_none)
    if (atMs == null) return none
    return remember(atMs) {
        ltrToken(SimpleDateFormat(SHORT_DATE, Locale.ROOT).format(Date(atMs)))
    }
}

/**
 * When Castivio's own licence runs out, from the state the policy decided.
 *
 * Every case is answered from a real field. A lifetime purchase never expires and says
 * so; an expired annual restored without its date says that it expired and not when,
 * because [EntitlementState.AnnualExpired] genuinely may not carry one. Nothing here
 * invents a date, and nothing prints a zero where a question was never asked.
 */
@Composable
private fun licenceExpiry(state: EntitlementState?): String = when (state) {
    is EntitlementState.TrialActive -> shortDate(state.expiresAtMs)
    is EntitlementState.AnnualActive -> shortDate(state.expiresAtMs)
    is EntitlementState.AnnualExpired ->
        state.expiredAtMs?.let { shortDate(it) } ?: stringResource(R.string.channels_licence_expired)
    EntitlementState.TrialExpired -> stringResource(R.string.channels_licence_expired)
    EntitlementState.Lifetime -> stringResource(R.string.channels_licence_lifetime)
    else -> stringResource(R.string.home_expires_none)
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
    preview: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val rows = model.items.collectAsLazyPagingItems()
    val listState = rememberLazyListState()

    // Which row the remote is on. Not the same as which channel is *selected* — see
    // `ChannelList` — and it is tracked separately because only this one decides where
    // the card may sit.
    var focusedRow by remember { mutableIntStateOf(0) }
    var anchor by remember { mutableStateOf(FloatAnchor.Bottom) }
    var guideOpen by remember { mutableStateOf(false) }

    val listFocus = remember { FocusRequester() }
    val floatFocus = remember { FocusRequester() }

    // The card exists only once something is playing or chosen. Everything that points
    // *at* it has to ask first: a `FocusRequester` that was never attached to a node
    // throws when focus is moved to it, so "right" out of the list has to mean the
    // default search on a board that has no card yet.
    val cardShown = preview != null || shown.channel != null

    val floatPx = with(LocalDensity.current) { m.floatHeight.roundToPx() }

    // **Where the focused row is, relative to the two places the card may sit.**
    //
    // Read from the list's own layout rather than computed from row heights: the answer
    // has to be the geometry that was actually laid out, and a row is `heightIn(min=)` —
    // it can be taller than the floor. `derivedStateOf` so the whole board does not
    // recompose on every pixel of a scroll; only a change of *zone* is worth a frame.
    val zone by remember(floatPx) {
        derivedStateOf {
            val info = listState.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.index == focusedRow }
            when {
                row == null -> FloatZone.Clear
                row.offset + row.size > info.viewportEndOffset - floatPx -> FloatZone.Bottom
                row.offset < info.viewportStartOffset + floatPx -> FloatZone.Top
                else -> FloatZone.Clear
            }
        }
    }

    // **Two places, one rule, and it cannot oscillate.**
    //
    // The card moves only when the place it is in would cover the row the remote is on,
    // and it moves to the other one — which by construction does not cover that row,
    // because the two zones do not overlap on any list taller than two cards. So a
    // traversal from top to bottom moves the card once, not repeatedly, and a viewer
    // arrowing through the middle of a list never sees it move at all.
    LaunchedEffect(zone) {
        anchor = when {
            zone == FloatZone.Bottom && anchor == FloatAnchor.Bottom -> FloatAnchor.Top
            zone == FloatZone.Top && anchor == FloatAnchor.Top -> FloatAnchor.Bottom
            else -> anchor
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
                    onRefresh = { model.retryFetch(force = true) },
                    modifier = Modifier.width(m.rail).fillMaxHeight(),
                )

                Spacer(Modifier.width(m.railGap))

                // **The list takes everything the rail does not.** The third column is
                // gone and its width went here, which is why a channel's name is no
                // longer the first casualty of the board's arithmetic.
                ChannelList(
                    rows = rows,
                    state = state,
                    m = m,
                    listState = listState,
                    onRowFocused = { focusedRow = it },
                    onSelect = previewModel::select,
                    onPlay = onPlay,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusRequester(listFocus)
                        .focusGroup()
                        // Right out of the list is the card, and nothing else. Stated
                        // rather than left to the geometric search, because the card
                        // *overlaps* the list and a search that finds the nearest
                        // focusable in a direction can pick a row instead.
                        .focusProperties {
                            right = if (cardShown) floatFocus else FocusRequester.Default
                        },
                )
            }

            // Drawn only when there is something for it to be about. Before the first
            // press the board is a board; the card is not an empty frame waiting.
            if (cardShown) {
                FloatingPlayer(
                    shown = shown,
                    m = m,
                    preview = preview,
                    onOpenGuide = { guideOpen = true },
                    modifier = Modifier
                        .align(
                            if (anchor == FloatAnchor.Bottom) Alignment.BottomEnd
                            else Alignment.TopEnd,
                        )
                        .padding(m.playerGap)
                        .width(m.player)
                        .height(m.floatHeight)
                        .focusRequester(floatFocus)
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

/** The two places the floating card is allowed to be. There is no third. */
private enum class FloatAnchor { Bottom, Top }

/** Where the focused row is, against the card's two possible places. */
private enum class FloatZone { Top, Clear, Bottom }

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
            .padding(horizontal = m.rowPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.rowPadH),
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
    /** Hoisted: the floating card's position is decided from this list's own geometry. */
    listState: LazyListState,
    /** Where the remote is. Distinct from [onSelect] — moving is not choosing. */
    onRowFocused: (Int) -> Unit,
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
                    m = m,
                    // **Moving is not choosing, and that distinction is the whole of how
                    // this screen behaves now.**
                    //
                    // Focus used to select: arrowing down a list changed what the preview
                    // column said. That was harmless while the column only drew a mark and
                    // a guide. It is not harmless now, because the card also *plays* — and
                    // a card whose picture is one channel while its name and its guide are
                    // the channel the remote happens to be passing over is a card that
                    // contradicts itself. Worse, tuning on every arrow would open a stream
                    // per row on a list of 55,000.
                    //
                    // So focus reports where the remote is, and nothing more; pressing is
                    // what selects, and it sets the picture, the name, the number and the
                    // guide together, from one act.
                    onFocused = { onRowFocused(index) },
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
        // **A provider's own heading is not a channel.**
        //
        // `##### UHD 3840P #####` is a rule drawn in text, written as a row because an
        // M3U playlist has only one row type -- see `isProviderHeading`. Numbering it
        // says it is the category's first channel, which it is not, so it gets no plate
        // and no logo; the plate's width is held open so that every name on the column
        // still starts at the same place. Muted and spaced, because a heading that looks
        // exactly like a channel is a channel that does not play.
        val heading = remember(channel.title) { isProviderHeading(channel.title) }

        val ink = when {
            focused -> colors.onSecondary
            heading -> colors.onBackgroundMuted
            else -> colors.onBackground
        }

        // The number, on a plate, and it opens the row. It is what a remote dials, so
        // it is the first thing on the line a viewer reads down -- a column of numbers
        // is scanned, and a column of numbers behind two other things is not a column.
        if (heading) {
            Spacer(Modifier.width(m.numberWidth))
        } else {
            NumberPlate(label = numberPlate(number), focused = focused, m = m)
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
                    focused -> colors.onSecondary
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

/* --------------------------------------------------------- the floating card */

/**
 * The channel that is playing, over the list rather than beside it.
 *
 * ## Why it stopped being a column
 *
 * It was the board's third track: a picture, a guide and a channel's whole detail, taking
 * 42% of the width whether or not anything was playing. What that cost was the *names* —
 * the channel list had a third of the board and ended most of its rows in an ellipsis.
 *
 * As an overlay it costs nothing until a channel is chosen, and the list has the width
 * back. What it costs instead is the rows underneath it, which is why it has two places
 * to sit and a rule that keeps it off the one the remote is on. See `Columns`.
 *
 * ## Everything in it is real or absent
 *
 * No invented programme, no invented artwork, no invented duration. An absent guide draws
 * "No Information"; an absent channel draws nothing, because the card is not composed at
 * all until there is one.
 */
@Composable
private fun FloatingPlayer(
    shown: ChannelPreview,
    m: ChannelsMetrics,
    preview: (@Composable () -> Unit)?,
    onOpenGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.wellRadius)
    val channel = shown.channel
    var focused by remember { mutableStateOf(false) }

    // **A filled card, unlike anything else on this board.**
    //
    // The list and the rail are frames with the page showing through, because they are
    // regions of the screen. This one floats *over* them, and a translucent thing over a
    // list of names is a thing you read the list through. It takes the panel's own solid
    // and a shadow's worth of border so the eye reads one plane above another.
    //
    // The ring is the focus ring, on the card and not on what is inside it: everything in
    // here belongs to one channel, so one outline is the honest answer to "where am I".
    //
    // **Landscape, not portrait**, and that shape was decided by the rule that moves the
    // card rather than by taste -- a card taller than half the channel column has no two
    // places to sit that do not overlap. See `ChannelsMetrics.PLAYER`.
    Row(
        modifier
            .onFocusChanged { focused = it.hasFocus }
            .clip(shape)
            .background(colors.backgroundElevated)
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) colors.focusRing else colors.glassBorder,
                shape,
            )
            .padding(m.wellPad),
    ) {
        Box(
            Modifier
                .width(m.cardPicture)
                .aspectRatio(CHANNELS_PREVIEW_ASPECT)
                .clip(RoundedCornerShape(m.previewRadius)),
        ) {
            // **The plate is the player's when there is a player.**
            //
            // The mark and the bar under it are what this plate shows while nothing has
            // been pressed — a *preview* of the channel the remote is on. Once a channel
            // is actually playing, the same plate is the compact player, which draws its
            // own title over its own picture; drawing both would be two titles over one
            // frame.
            //
            // The plate keeps its size and its place either way, so pressing a channel
            // changes what is in the box and never where the box is.
            if (preview != null) {
                preview()
            } else if (channel != null) {
                LogoTile(
                    initials = initialsOf(channel.title),
                    seed = channel.id.hashCode(),
                    modifier = Modifier.fillMaxSize(),
                )
                Osd(
                    channel = channel,
                    number = shown.number,
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

        Spacer(Modifier.width(m.guideGap))

        // **Two lines, and a door to the rest.**
        //
        // The card carried the whole schedule when it was a column; an overlay cannot,
        // and should not — what a viewer wants from a picture in a corner is what is on
        // and what is next. Everything further out is a press away, in an overlay that
        // has the room for it. See [ChannelGuideOverlay].
        //
        // The region is claimed whether or not the guide answered, which is why the card
        // has a fixed height: a channel with no EPG must not make the card change shape
        // while a viewer is arrowing past it.
        GuideSummary(
            shown = shown,
            m = m,
            onOpen = onOpenGuide,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

/**
 * What is on and what is next, and the press that opens the whole day.
 *
 * Pressable as one object rather than carrying a button, for the reason the rest of this
 * board has no buttons on its rows: a remote lands on things, and a panel that *is* the
 * control is one landing place instead of two.
 *
 * Two lines and a bar, which is all a card four rows tall can hold honestly. It does not
 * reuse [GuideEntry] — that draws a programme at a size the full guide has room for, and
 * shrinking it here would have produced a third guide typography. What sits beside a
 * picture this small is a summary, and a summary has its own shape.
 */
@Composable
private fun GuideSummary(
    shown: ChannelPreview,
    m: ChannelsMetrics,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val (focused, focusModifier) = rememberFocusFlag()
    val shape = RoundedCornerShape(m.previewRadius)
    val interaction = remember { MutableInteractionSource() }
    val guide = shown.guide
    val hasGuide = shown.schedule.isNotEmpty()

    Row(
        modifier
            .clip(shape)
            .background(if (focused) colors.glassFillStrong else Color.Transparent)
            .border(1.dp, if (focused) colors.focusRing else Color.Transparent, shape)
            .then(focusModifier)
            .clickable(interaction, indication = null, enabled = hasGuide, onClick = onOpen)
            .padding(horizontal = m.rowPadH),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.factGap),
    ) {
        if (!hasGuide) {
            Text(
                // Three different absences reach this line -- a channel with no guide
                // id, a guide never imported, a schedule that has run out -- and all
                // three are honestly "no information".
                text = stringResource(R.string.channels_no_information),
                style = castivioBodyStyle(m.frame.fsBody),
                color = colors.onBackgroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            return@Row
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.badgePadV)) {
            GuideLine(
                label = stringResource(R.string.channels_guide_now),
                title = guide?.now?.title ?: stringResource(R.string.channels_no_information),
                now = true,
                m = m,
            )
            // The same bar the display over the picture draws, and from the same
            // arithmetic: how far through the programme the clock is.
            Track(fraction = guide?.progressAt(System.currentTimeMillis()), m = m)
            GuideLine(
                label = stringResource(R.string.channels_guide_next),
                title = guide?.next?.title ?: shown.schedule.getOrNull(1)?.title.orEmpty(),
                now = false,
                m = m,
            )
        }

        // The affordance: one mark that says the panel leads somewhere. A word would be
        // a third line on a panel that has room for two.
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = stringResource(R.string.channels_guide_open),
            tint = if (focused) colors.onBackground else colors.secondary,
            modifier = Modifier.size(Sizing.iconMd),
        )
    }
}

/** One line of the summary: what it is, and what is on. */
@Composable
private fun GuideLine(label: String, title: String, now: Boolean, m: ChannelsMetrics) {
    val colors = CastivioTheme.colors
    Row(
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
 * The whole day, for the channel that is playing.
 *
 * ## Why an overlay and not a fourth region
 *
 * A schedule is twenty rows and the board has no twenty rows to spare — that is the
 * arithmetic that took the guide out of the card in the first place. What it needs is the
 * screen, briefly, and then to give it back.
 *
 * ## Why it is not a new EPG
 *
 * There is no new query, no new repository call and no new state. `ChannelPreview.schedule`
 * is the list the card already reads two lines of, drawn here in full with the same
 * [GuideEntry] the card uses. The overlay is a second *view* of one fact, not a second
 * fact — which is also why closing it cannot lose the channel: it never owned it.
 *
 * ## The header says whose schedule this is
 *
 * The one thing an overlay of times and titles must never leave ambiguous. The channel's
 * number and name sit above the list, in the same shapes the card draws them in.
 */
@Composable
private fun ChannelGuideOverlay(shown: ChannelPreview, m: ChannelsMetrics, onClose: () -> Unit) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.panelRadius)
    val first = remember { FocusRequester() }

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            // Swallows the press so a tap on the dimmed board behind does not reach a
            // channel row; it also closes, which is what a scrim is for.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(m.rail + m.railGap + m.player)
                .padding(vertical = m.panelPad)
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
                Spacer(Modifier.width(m.rowPadH))
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

/**
 * The bar over the picture: which channel, what is on, and how far through it is.
 *
 * ## What it does not say
 *
 * The reference prints a resolution here — `1920 × 1080`. Castivio does not know it. A
 * stream's real geometry comes from the decoder after the player has opened it, and this
 * board has opened nothing; printing the tag from the channel's *name* in a slot that
 * looks like a measurement would be the one dishonest pixel on the screen. The quality
 * tag says what the provider wrote, and the number says where the row is — both true.
 */
@Composable
private fun Osd(
    channel: Channel,
    number: Int?,
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

        Text(
            text = numberPlate(number),
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
private const val LEGEND_OF_BODY = 0.88f

/**
 * The expiry pair, smaller again.
 *
 * Two lines of caption-and-date is the densest thing on the band, and at the legend's
 * own step it read as loud as the section name. This is the step that makes it what it
 * is: a fact available when looked for, not an announcement.
 */
private const val EXPIRY_OF_BODY = 0.74f

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
