package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
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
 * says which, and a false from it is the caller's [onBack].
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
    hub: CatchUpHubViewModel = hiltViewModel(),
    programmes: CatchUpViewModel = hiltViewModel(),
) {
    val tv = CastivioTheme.device.isTv
    val stage by hub.stage.collectAsStateWithLifecycle()
    val groups by hub.groups.collectAsStateWithLifecycle()
    val channels = hub.channels.collectAsLazyPagingItems()

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
                    list = when (val at = stage) {
                        HubStage.Groups -> stringResource(R.string.catchup_hub_subtitle)
                        is HubStage.Channels -> at.group?.name ?: stringResource(R.string.catchup_all)
                        is HubStage.Programmes -> at.channel.title
                    },
                    m = m,
                    searchText = "",
                    onSearch = {},
                    onSearchFocus = {},
                    withSearch = false,
                )

                Spacer(Modifier.height(m.headerGap))

                HubPanel(
                    m = m,
                    title = when (val at = stage) {
                        HubStage.Groups -> stringResource(R.string.catchup_title)
                        is HubStage.Channels -> at.group?.name ?: stringResource(R.string.catchup_all)
                        is HubStage.Programmes -> at.group?.name ?: stringResource(R.string.catchup_all)
                    },
                    hint = when (stage) {
                        HubStage.Groups -> stringResource(R.string.catchup_hub_subtitle)
                        else -> stringResource(R.string.catchup_pick_channel)
                    },
                    onBack = { if (!hub.back()) onBack() },
                ) {
                    when (stage) {
                        HubStage.Groups -> GroupsPage(groups = groups, m = m, onOpen = hub::openGroup)
                        else -> ChannelsPage(channels = channels, m = m, onOpen = hub::openChannel)
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
                    reserveEnd = 0.dp,
                )
            }
        }
    }
}

/** The frame of a page: the back control, the title, what to do, and the page under them. */
@Composable
private fun ColumnScope.HubPanel(
    m: ChannelsMetrics,
    title: String,
    hint: String,
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.controlPadH)) {
            CastivioIconButton(
                icon = CastivioIcons.ArrowBack,
                contentDescription = stringResource(R.string.catchup_back),
                onClick = onBack,
            )
            Text(
                text = title,
                style = castivioTitleStyle(m.frame.fsLabel),
                color = colors.onBackgroundStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = hint,
                style = castivioBodyStyle(m.frame.fsBody),
                color = colors.onBackgroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(m.guideGap))
        content()
    }
}

/** The categories that keep an archive, and "all" first, in the azure of the one thing a first press does. */
@Composable
private fun GroupsPage(groups: HubGroups?, m: ChannelsMetrics, onOpen: (ArchiveGroup?) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(groups?.groups?.isNotEmpty()) {
        if (groups?.groups?.isNotEmpty() == true) runCatching { first.requestFocus() }
    }
    when {
        groups == null -> Waiting(m)
        groups.groups.isEmpty() -> Empty(m)
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
        modifier = modifier.fillMaxWidth().heightIn(min = Sizing.minTarget(tv)),
        shape = RoundedCornerShape(m.panelRadius),
        fill = if (lead) colors.primaryBrush else SolidColor(colors.glassFill),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = m.controlPadH, vertical = Spacing.sm),
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
            Text(
                text = count.toString(),
                style = castivioChipStyle(m.frame.fsChip),
                color = if (lead) colors.onBackgroundStrong else colors.secondary,
                maxLines = 1,
                modifier = Modifier
                    .clip(pill)
                    .background(if (lead) colors.onBackgroundStrong.copy(alpha = BADGE_ON_LEAD) else colors.secondary.copy(alpha = BADGE_ALPHA))
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
private fun ChannelsPage(channels: LazyPagingItems<Channel>, m: ChannelsMetrics, onOpen: (Channel) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(channels.itemCount > 0) {
        if (channels.itemCount > 0) runCatching { first.requestFocus() }
    }
    when {
        channels.itemCount == 0 && channels.loadState.refresh is LoadState.Loading -> Waiting(m)
        channels.itemCount == 0 -> Empty(m)
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
        modifier = modifier.fillMaxWidth().heightIn(min = Sizing.minTarget(tv)),
        shape = RoundedCornerShape(m.panelRadius),
        fill = SolidColor(colors.glassFill),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = m.controlPadH, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.controlPadH),
        ) {
            Text(
                text = channel.number?.toString().orEmpty(),
                style = castivioChipStyle(m.frame.fsChip),
                color = colors.onBackgroundMuted,
                maxLines = 1,
            )
            ProviderArtwork(
                url = channel.artworkUrl,
                description = null,
                modifier = Modifier.size(m.frame.fsLabel * LOGO_IN_ENTRY),
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
private fun Empty(m: ChannelsMetrics) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.catchup_hub_empty),
            style = castivioBodyStyle(m.frame.fsBody),
            color = CastivioTheme.colors.onBackgroundMuted,
        )
    }
}

/** A category's card is at least this wide: three in a row on a television's board, fewer on a phone. */
private val GROUP_MIN_WIDTH = 150.dp

/** A channel's entry is at least this wide: two in a row on the board. */
private val CHANNEL_MIN_WIDTH = 220.dp

/** The channel's logo in an entry, about two lines of the label's own size. */
private const val LOGO_IN_ENTRY = 1.8f

/** How much of its colour a count's or a day's pill is filled with, and how much of white on the azure card. */
private const val BADGE_ALPHA = 0.16f
private const val BADGE_ON_LEAD = 0.22f

/** The first card's key: "all", which is no category's id. */
private const val ALL_KEY = "catchup.all"
