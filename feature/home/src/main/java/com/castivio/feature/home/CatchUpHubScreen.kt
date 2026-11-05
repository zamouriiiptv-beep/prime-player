package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
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
import com.castivio.core.design.components.CastivioIconButton
import com.castivio.core.design.components.CastivioSpinner
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.components.ProviderArtwork
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.ArchiveGroup
import com.castivio.domain.CatchUpPolicy
import com.castivio.domain.Channel

/**
 * **Catch-up, a place of its own.** The categories that keep an archive, a category's channels, and -- over them -- a
 * channel's programmes, which is the screen [CatchUpOverlay] draws and which plays through the same hand-off a film does.
 *
 * Home's rail opens it; it replaces the button on every channel's guide, and the channels that have nothing to
 * replay are not in it, so nobody opens a screen of programmes that cannot be played to find that out.
 *
 * ## Back, one place at a time
 *
 * Programmes, then the channels, then the categories, and from the categories out of Catch-up -- [CatchUpHubViewModel.back]
 * says which, and a false from it is the caller's [onBack]. A search that holds something is closed first.
 *
 * ## One search, one box
 *
 * At the end of the panel's header, the same box at every place a list is. On the first page it finds categories and
 * channels together, in two sections; inside a category, that category's channels. The header follows the language --
 * Back at the right in Arabic, pointing right -- while the lists under it keep the board's left-to-right order, because
 * what they hold are the provider's names.
 *
 * ## What it asks of the network
 *
 * Nothing. The pages read the catalogue; a provider is asked for a channel's programmes only when that channel is opened,
 * by [CatchUpViewModel], exactly as before.
 */
@Composable
fun CatchUpHubScreen(
    onPlay: (CatalogSelection) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The player is open over this screen: a programme was started from it, and Back from the player returns here. */
    playing: Boolean = false,
    hub: CatchUpHubViewModel = hiltViewModel(),
    programmes: CatchUpViewModel = hiltViewModel(),
) {
    val tv = CastivioTheme.device.isTv
    // The language's own direction, read before the board is made left to right: the panel's header follows it.
    val direction = LocalLayoutDirection.current
    val stage by hub.stage.collectAsStateWithLifecycle()
    val groups by hub.groups.collectAsStateWithLifecycle()
    val matches by hub.matches.collectAsStateWithLifecycle()
    val supported by hub.supported.collectAsStateWithLifecycle()
    val searchText by hub.searchText.collectAsStateWithLifecycle()
    val channels = hub.channels.collectAsLazyPagingItems()
    // Whether the cursor is in the search box: a list that arrives while the viewer types must not take it away.
    var searchFocused by remember { mutableStateOf(false) }

    // Leaving Catch-up for Home ends this visit: the next one begins at the categories.
    DisposableEffect(Unit) { onDispose { hub.reset() } }
    BackHandler { if (!hub.back()) onBack() }

    // Written left to right in every language, as the channels' board is: the names in it are the provider's.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
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
                BoardBand(
                    section = stringResource(R.string.catchup_title),
                    // Short, because the cell it stands in is a fixed 90dp or so, and the same at every place: what this
                    // page is and which category is open are the panel's header's to say, and said twice they were repeated
                    // here (and the sentence cut off).
                    list = stringResource(R.string.catchup_band),
                    m = m,
                    searchText = "",
                    onSearch = {},
                    onSearchFocus = {},
                    withSearch = false,
                )

                Spacer(Modifier.height(m.headerGap))

                HubPanel(
                    m = m,
                    direction = direction,
                    searchText = searchText,
                    onSearch = hub::search,
                    onSearchFocus = { searchFocused = it },
                    // A category's channels read "choose a channel", then the category -- the action first, as a title, and what
                    // it is chosen from after it, in the brighter of the two lines because it is the data.
                    title = when (stage) {
                        HubStage.Groups -> stringResource(R.string.catchup_title)
                        else -> stringResource(R.string.catchup_pick_channel)
                    },
                    hint = when (val at = stage) {
                        HubStage.Groups -> stringResource(R.string.catchup_hub_subtitle)
                        is HubStage.Channels -> at.group?.name ?: stringResource(R.string.catchup_all)
                        is HubStage.Programmes -> at.group?.name ?: stringResource(R.string.catchup_all)
                    },
                    hintIsName = stage != HubStage.Groups,
                    onBack = { if (!hub.back()) onBack() },
                ) {
                    val found = matches
                    val onFirstPage = stage == HubStage.Groups || (stage as? HubStage.Programmes)?.fromGroups == true
                    when {
                        // The categories, or -- while something is searched -- what matched among them and the channels.
                        onFirstPage && found == null -> GroupsPage(
                            groups = groups,
                            m = m,
                            autoFocus = !searchFocused,
                            supported = supported,
                            onOpen = hub::openGroup,
                        )
                        onFirstPage && found != null -> SearchResults(
                            found = found,
                            channels = channels,
                            m = m,
                            direction = direction,
                            onOpenGroup = hub::openGroup,
                            onOpenChannel = hub::openChannel,
                        )
                        else -> ChannelsPage(
                            channels = channels,
                            m = m,
                            searching = searchText.isNotBlank(),
                            autoFocus = !searchFocused,
                            onOpen = hub::openChannel,
                        )
                    }
                }
            }

            (stage as? HubStage.Programmes)?.let { at ->
                CatchUpOverlay(
                    channel = at.channel,
                    m = m,
                    model = programmes,
                    onPlay = onPlay,
                    onClose = { hub.back() },
                    playing = playing,
                    reserveEnd = 0.dp,
                    direction = direction,
                )
            }
        }
    }
}

/**
 * The header every page of Catch-up has, so that the three look like one: Back, what this page is, and the one search box.
 *
 * It follows the language, as a page of this kind does: Back at the start -- the right in Arabic, mirrored to point right
 * there -- then [title], then at the end the box. The room that is left once those are placed is shared equally between the
 * three gaps -- Back and the title, the title and the box, the box and the panel's edge -- so none is tighter than another.
 * The pages under it are the board's, drawn left to right in every language, so the box keeps its own mark at its left.
 *
 * What gives, on a narrow screen, is the last thing in [title] that can: it ends in an ellipsis, and the box stays whole.
 *
 * @param title the words of the header's middle, in a row: what the page is and what it is about.
 */
@Composable
internal fun CatchUpHeader(
    m: ChannelsMetrics,
    direction: LayoutDirection,
    onBack: () -> Unit,
    searchText: String,
    onSearch: (String) -> Unit,
    onSearchFocus: (Boolean) -> Unit,
    searchHint: String,
    title: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CastivioIconButton(
                icon = CastivioIcons.ArrowBack,
                contentDescription = stringResource(R.string.catchup_back),
                onClick = onBack,
                modifier = if (direction == LayoutDirection.Rtl) Modifier.graphicsLayer(scaleX = -1f) else Modifier,
            )
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(m.controlPadH),
                    verticalAlignment = Alignment.CenterVertically,
                    content = title,
                )
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    HeaderSearchField(
                        text = searchText,
                        m = m,
                        onType = onSearch,
                        onFocusChange = onSearchFocus,
                        hint = searchHint,
                        markAsButton = true,
                        scale = CATCHUP_SEARCH_SCALE,
                    )
                }
            }
        }
    }
}

/** The frame of a page: the back control, the title, what to do, and the page under them. */
@Composable
private fun ColumnScope.HubPanel(
    m: ChannelsMetrics,
    direction: LayoutDirection,
    title: String,
    hint: String,
    /** The line under the title is a name from the catalogue and not a sentence: it is drawn as one, in the brighter ink. */
    hintIsName: Boolean,
    searchText: String,
    onSearch: (String) -> Unit,
    onSearchFocus: (Boolean) -> Unit,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.panelRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(shape)
            .background(colors.backgroundElevated)
            .border(1.dp, colors.glassBorder, shape)
            .padding(m.wellPad),
    ) {
        CatchUpHeader(
            m = m,
            direction = direction,
            onBack = onBack,
            searchText = searchText,
            onSearch = onSearch,
            onSearchFocus = onSearchFocus,
            searchHint = stringResource(R.string.catchup_search_hint),
        ) {
            Text(
                text = title,
                style = castivioTitleStyle(m.frame.fsLabel),
                color = colors.onBackgroundStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = hint,
                style = if (hintIsName) castivioChipStyle(m.frame.fsBody * HINT_SCALE)
                else castivioBodyStyle(m.frame.fsBody * HINT_SCALE),
                color = if (hintIsName) colors.onBackground else colors.onBackgroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.height(m.guideGap))
        content()
    }
}

/** The categories that keep an archive, and "all" first, in the azure of the one thing a first press does. */
@Composable
private fun GroupsPage(
    groups: HubGroups?,
    m: ChannelsMetrics,
    autoFocus: Boolean,
    supported: Boolean,
    onOpen: (ArchiveGroup?) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(groups?.groups?.isNotEmpty(), autoFocus) {
        if (autoFocus && groups?.groups?.isNotEmpty() == true) runCatching { first.requestFocus() }
    }
    when {
        groups == null -> Waiting(m)
        // A subscription of a kind with no archive says so, which is a statement about the subscription; one that has the
        // kind and no channel with an archive says that, which is a statement about the provider.
        groups.groups.isEmpty() -> Empty(m, if (supported) R.string.catchup_hub_empty else R.string.catchup_unsupported)
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(GROUP_MIN_WIDTH),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(m.guideGap),
            verticalArrangement = Arrangement.spacedBy(m.guideGap),
        ) {
            item(key = ALL_KEY) {
                GroupCard(
                    name = stringResource(R.string.catchup_all),
                    count = groups.total,
                    lead = true,
                    m = m,
                    modifier = Modifier.focusRequester(first),
                    onClick = { onOpen(null) },
                )
            }
            items(groups.groups, key = { it.id }) { group ->
                GroupCard(name = group.name, count = group.channels, lead = false, m = m, onClick = { onOpen(group) })
            }
        }
    }
}

@Composable
private fun GroupCard(
    name: String,
    count: Int,
    lead: Boolean,
    m: ChannelsMetrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val tv = CastivioTheme.device.isTv
    val pill = RoundedCornerShape(m.panelRadius)
    InteractiveGlassCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(m.panelRadius),
        fill = if (lead) colors.primaryBrush else SolidColor(colors.glassFill),
    ) {
        // The floor under the card's height is the row's, not the card's: a card holds its content at the top, so a floor
        // set on the card left the content above its middle and a bare strip under it.
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizing.minTarget(tv))
                .padding(horizontal = m.controlPadH, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = name,
                style = castivioChipStyle(m.frame.fsLabel),
                color = colors.onBackgroundStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // A sky blue beside the white of the name, on a plate of the azure: it was violet, which was not liked, and white,
            // which was not either -- white figures next to a white name read as one grey run. On the azure card itself the
            // figure stays white, as the card is the colour.
            Text(
                text = count.toString(),
                style = castivioChipStyle(m.frame.fsChip),
                color = if (lead) colors.onBackgroundStrong else lerp(colors.hueAzure, colors.onBackgroundStrong, COUNT_INK_LIFT),
                maxLines = 1,
                modifier = Modifier
                    .clip(pill)
                    .background(
                        if (lead) colors.onBackgroundStrong.copy(alpha = BADGE_ON_LEAD)
                        else colors.hueAzure.copy(alpha = COUNT_PLATE_ALPHA),
                    )
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
            Icon(
                CastivioIcons.Chevron,
                contentDescription = null,
                tint = colors.onBackgroundMuted,
                modifier = Modifier.size(Sizing.iconSm),
            )
        }
    }
}

/** A category's channels that keep an archive, a window at a time. */
@Composable
private fun ChannelsPage(
    channels: LazyPagingItems<Channel>,
    m: ChannelsMetrics,
    searching: Boolean,
    autoFocus: Boolean,
    onOpen: (Channel) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(channels.itemCount > 0, autoFocus) {
        if (autoFocus && channels.itemCount > 0) runCatching { first.requestFocus() }
    }
    when {
        // A search is read again at every pause in the typing: a spinner at each would be a flicker, so there is none.
        channels.itemCount == 0 && channels.loadState.refresh is LoadState.Loading ->
            if (searching) Spacer(Modifier.fillMaxSize()) else Waiting(m)
        channels.itemCount == 0 ->
            Empty(m, if (searching) R.string.catchup_search_none else R.string.catchup_hub_empty)
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(CHANNEL_MIN_WIDTH),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(m.guideGap),
            verticalArrangement = Arrangement.spacedBy(m.guideGap),
        ) {
            items(channels.itemCount, key = channels.itemKey { it.id }) { index ->
                channels[index]?.let { channel ->
                    ChannelEntry(
                        channel = channel,
                        m = m,
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                        onClick = { onOpen(channel) },
                    )
                }
            }
        }
    }
}

/** One channel: its number and logo and name, and how many days the provider says it keeps. */
@Composable
private fun ChannelEntry(channel: Channel, m: ChannelsMetrics, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val tv = CastivioTheme.device.isTv
    val days = CatchUpPolicy.daysOf(channel.catchUpHours)
    InteractiveGlassCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(m.panelRadius),
        fill = SolidColor(colors.glassFill),
    ) {
        // The floor is the row's, so what is in it is centred: see [GroupCard].
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizing.minTarget(tv))
                .padding(horizontal = m.controlPadH, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.controlPadH),
        ) {
            // A plate only for a channel that has a number: a provider that numbers none would otherwise show an empty one.
            channel.number?.let { number ->
                Text(
                    text = number.toString(),
                    style = castivioChipStyle(m.frame.fsChip),
                    color = colors.onBackground,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(m.panelRadius))
                        .background(colors.onBackground.copy(alpha = NUMBER_PLATE_ALPHA))
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
            ProviderArtwork(
                url = channel.artworkUrl,
                description = null,
                modifier = Modifier.size(m.frame.fsLabel * LOGO_IN_ENTRY),
                seed = channel.title,
            )
            Text(
                text = channel.title,
                style = castivioChipStyle(m.frame.fsLabel),
                color = colors.onBackgroundStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (days != null) {
                Text(
                    text = stringResource(R.string.catchup_days, days),
                    style = castivioChipStyle(m.frame.fsChip),
                    color = colors.success,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(m.panelRadius))
                        .background(colors.success.copy(alpha = BADGE_ALPHA))
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
    }
}

/**
 * What a search on the first page found: the categories whose names match, then the channels that match -- in two
 * sections of one grid, so a long list of channels scrolls under its heading and the categories above it go with it.
 *
 * A section with nothing in it is not drawn, and nothing at all says so in words. The channels' count is told only when
 * every one has been read: a window of them is not their number.
 */
@Composable
private fun SearchResults(
    found: List<ArchiveGroup>,
    channels: LazyPagingItems<Channel>,
    m: ChannelsMetrics,
    direction: LayoutDirection,
    onOpenGroup: (ArchiveGroup) -> Unit,
    onOpenChannel: (Channel) -> Unit,
) {
    val reading = channels.itemCount == 0 && channels.loadState.refresh is LoadState.Loading
    if (found.isEmpty() && channels.itemCount == 0) {
        // Reading is not finding nothing: the sentence waits for the channels' answer, and nothing stands in for it.
        if (reading) Spacer(Modifier.fillMaxSize()) else Empty(m, R.string.catchup_search_none)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = searchColumns(maxWidth.value, m.guideGap.value, GROUP_MIN_WIDTH.value, CHANNEL_MIN_WIDTH.value)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.total),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(m.guideGap),
            verticalArrangement = Arrangement.spacedBy(m.guideGap),
        ) {
            if (found.isNotEmpty()) {
                item(key = SECTION_GROUPS, span = { GridItemSpan(maxLineSpan) }) {
                    SearchHeading(stringResource(R.string.catchup_section_groups), found.size, direction, m)
                }
                items(found, key = { "group.${it.id}" }, span = { GridItemSpan(columns.groupSpan) }) { group ->
                    GroupCard(name = group.name, count = group.channels, lead = false, m = m, onClick = { onOpenGroup(group) })
                }
            }
            if (channels.itemCount > 0) {
                item(key = SECTION_CHANNELS, span = { GridItemSpan(maxLineSpan) }) {
                    val total = channels.itemCount.takeIf { channels.loadState.append.endOfPaginationReached }
                    SearchHeading(stringResource(R.string.catchup_section_channels), total, direction, m)
                }
                items(
                    count = channels.itemCount,
                    key = channels.itemKey { "channel.${it.id}" },
                    span = { GridItemSpan(columns.channelSpan) },
                ) { index ->
                    channels[index]?.let { channel -> ChannelEntry(channel = channel, m = m, onClick = { onOpenChannel(channel) }) }
                }
            }
        }
    }
}

/** A section's name, how many it holds when that is known, and a hairline to the far edge -- in the language's direction. */
@Composable
private fun SearchHeading(label: String, count: Int?, direction: LayoutDirection, m: ChannelsMetrics) {
    val colors = CastivioTheme.colors
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = label,
                style = castivioChipStyle(m.frame.fsChip),
                color = colors.onBackgroundMuted,
                maxLines = 1,
            )
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = castivioChipStyle(m.frame.fsChip),
                    color = colors.onBackground,
                    maxLines = 1,
                )
            }
            Box(Modifier.weight(1f).height(1.dp).background(colors.glassBorderSoft))
        }
    }
}

@Composable
private fun Waiting(m: ChannelsMetrics) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.guideGap, Alignment.CenterVertically),
    ) {
        CastivioSpinner()
        Text(
            text = stringResource(R.string.catchup_loading),
            style = castivioBodyStyle(m.frame.fsBody),
            color = CastivioTheme.colors.onBackgroundMuted,
        )
    }
}

@Composable
private fun Empty(m: ChannelsMetrics, @StringRes message: Int = R.string.catchup_hub_empty) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(message),
            style = castivioBodyStyle(m.frame.fsBody),
            color = CastivioTheme.colors.onBackgroundMuted,
        )
    }
}

/** The line under the title a little larger than a body line: it is what the page says it is for. */
private const val HINT_SCALE = 1.2f

/**
 * A category's card is at least this wide: four in a row on a handset's board (770dp wide, 4dp between the cards: 188dp a card), and still
 * four on a television's, which is the grid's own rule -- as many as fit.
 *
 * It was 150, which put five in a row, and a name has the room a card leaves it after the figure and the arrow: 71dp, about ten
 * letters, so `FR| FRANCE` ended in an ellipsis. Four give each card 38dp more and a name of fifteen letters its whole width; a long name
 * still ends in one, and that is the next thing to find an answer for. If four are not enough, the number is the one to raise again.
 */
internal val GROUP_MIN_WIDTH = 184.dp

/** A channel's entry is at least this wide: two in a row on the board. */
internal val CHANNEL_MIN_WIDTH = 220.dp

/** The channel's logo in an entry, about two lines of the label's own size. */
private const val LOGO_IN_ENTRY = 1.8f

/** How much of its colour a day's pill is filled with, and how much of white on the azure card. */
private const val BADGE_ALPHA = 0.16f
private const val BADGE_ON_LEAD = 0.22f

/**
 * A category's count, and a channel's number.
 *
 * The count is the azure lifted [COUNT_INK_LIFT] of the way to white -- the sky blue that sits beside a white name -- on a
 * plate of the azure at [COUNT_PLATE_ALPHA]: five to one and over against the plate it stands on, in the drawing the owner
 * chose from (`design/mockups/catchup-count-colours-ar.html`, option A). A channel's number stays white, on a plate of
 * white at [NUMBER_PLATE_ALPHA], as it is on the channels' board.
 */
private const val COUNT_INK_LIFT = 0.35f
private const val COUNT_PLATE_ALPHA = 0.22f
private const val NUMBER_PLATE_ALPHA = 0.10f

/** The first card's key: "all", which is no category's id. */
private const val ALL_KEY = "catchup.all"

/** The keys of a search's two headings: neither is a category's or a channel's id. */
private const val SECTION_GROUPS = "catchup.section.groups"
private const val SECTION_CHANNELS = "catchup.section.channels"
