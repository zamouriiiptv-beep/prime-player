package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.material3.Text
import com.castivio.core.common.EmptyReason
import com.castivio.core.design.components.CardShape
import com.castivio.core.design.components.ChannelCard
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.MediaCard
import com.castivio.core.design.components.SectionHeader
import com.castivio.core.design.components.Skeleton
import com.castivio.core.design.components.formatCount
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.DeviceClass
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.Channel
import com.castivio.domain.MediaItem
import com.castivio.domain.SeriesSummary
import androidx.paging.LoadState

/**
 * One section of the catalogue, categories first.
 *
 * The structure is the one `UI_ARCHITECTURE.md` §3.4 fixes and the approved mockup
 * draws: categories on the leading side, content on the trailing side, and on a phone
 * the same two things stacked as a chip row above a list. There is no hero and no
 * carousel — an IPTV catalogue is something people navigate, not something they are
 * shown.
 *
 * Every row here is real. The rows come from the paged reader, the counts from an
 * indexed `COUNT`, and a press carries the provider's own stream URL out to whoever
 * composed the player.
 */
@Composable
fun BrowseScreen(
    section: CatalogSection,
    onPlay: (CatalogSelection) -> Unit,
    onOpenShow: (SeriesSummary) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Keyed by section, which is what gives each one its own selection.
     *
     * Four sections share this composable; without the key they would share one
     * holder as well, and opening Movies would move the category Live TV was on.
     */
    model: BrowseViewModel = hiltViewModel(key = section.name),
) {
    // Told once, and idempotently: composition runs again for reasons that are not a
    // change of section, and clearing the category on each of them would fight the user.
    LaunchedEffect(section) { model.show(section) }

    val state by model.state.collectAsStateWithLifecycle()
    val device = CastivioTheme.device
    val twoPane = device == DeviceClass.Television || device == DeviceClass.Expanded

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = device.screenPadding, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionHeader(title = stringResource(section.label), count = state.total)

        if (twoPane) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                CategoryColumn(
                    state = state,
                    onChoose = model::choose,
                    modifier = Modifier.width(CATEGORY_PANE),
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Content(section, state, model, onPlay, onOpenShow)
                }
            }
        } else {
            CategoryChips(state = state, onChoose = model::choose)
            Box(Modifier.fillMaxSize()) {
                Content(section, state, model, onPlay, onOpenShow)
            }
        }
    }
}

/**
 * The categories, as the tall pane a remote steps down.
 *
 * "All" is first and is a category like any other — a pane whose first entry is a
 * real group leaves no way back to the whole section without a second control.
 */
@Composable
private fun CategoryColumn(
    state: BrowseState,
    onChoose: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        contentPadding = PaddingValues(bottom = Spacing.xxl),
    ) {
        item(key = ALL_CATEGORIES) {
            CategoryEntry(
                label = stringResource(R.string.browse_all_categories),
                count = state.total,
                selected = state.selectedGroup == null,
                onClick = { onChoose(null) },
            )
        }
        items(state.groups, key = { it.id }) { group ->
            CategoryEntry(
                label = group.name,
                count = null,
                selected = group.id == state.selectedGroup,
                onClick = { onChoose(group.id) },
            )
        }
    }
}

/**
 * The same choice on a phone, where a 168dp pane would be a third of the screen.
 *
 * A horizontal row rather than a drawer: a category change is the most frequent move
 * in this screen, and putting it behind a button costs two presses every time.
 */
@Composable
private fun CategoryChips(state: BrowseState, onChoose: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item(key = ALL_CATEGORIES) {
            CategoryEntry(
                label = stringResource(R.string.browse_all_categories),
                count = state.total,
                selected = state.selectedGroup == null,
                pill = true,
                onClick = { onChoose(null) },
            )
        }
        items(state.groups, key = { it.id }) { group ->
            CategoryEntry(
                label = group.name,
                count = null,
                selected = group.id == state.selectedGroup,
                pill = true,
                onClick = { onChoose(group.id) },
            )
        }
    }
}

@Composable
private fun CategoryEntry(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pill: Boolean = false,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (pill) Radius.pill else Radius.md)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier
            .then(if (pill) Modifier else Modifier.fillMaxWidth())
            .clip(shape)
            .background(if (selected) colors.glassFillStrong else colors.glassFill)
            .border(1.dp, if (selected) colors.glassBorder else colors.glassBorderSoft, shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Text(
            label,
            style = CastivioType.labelLarge,
            color = if (selected) colors.onBackground else colors.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (pill) Modifier else Modifier.weight(1f),
        )
        if (count != null) {
            Text(formatCount(count), style = CastivioType.labelSmall, color = colors.onBackgroundMuted)
        }
    }
}

/**
 * The rows, and the two things that are not rows.
 *
 * The pager is collected inside the branch that uses it rather than above the `when`,
 * so a section reads one query and not two: Series pages shows, everything else pages
 * items, and neither opens a cursor the screen will never read.
 *
 * Loading and empty are drawn here rather than left to whichever branch remembers,
 * because "the grid is empty" and "the grid has not answered yet" look identical on a
 * television and mean opposite things.
 */
@Composable
private fun Content(
    section: CatalogSection,
    state: BrowseState,
    model: BrowseViewModel,
    onPlay: (CatalogSelection) -> Unit,
    onOpenShow: (SeriesSummary) -> Unit,
) {
    if (section == CatalogSection.Series) {
        val shows = model.shows.collectAsLazyPagingItems()
        Paged(shows, section, state) { ShowGrid(shows, onOpenShow) }
    } else {
        val rows = model.items.collectAsLazyPagingItems()
        Paged(rows, section, state) {
            if (section == CatalogSection.Live || section == CatalogSection.Radio) {
                ChannelList(rows, state.categoryNames, onPlay)
            } else {
                ItemGrid(rows, onPlay)
            }
        }
    }
}

/** Skeletons, an explained emptiness, or the caller's rows — in that order. */
@Composable
private fun <T : Any> Paged(
    paged: LazyPagingItems<T>,
    section: CatalogSection,
    state: BrowseState,
    rows: @Composable () -> Unit,
) {
    when {
        paged.itemCount > 0 -> rows()

        paged.loadState.refresh is LoadState.Loading -> LoadingRows()

        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            SectionEmpty(section = section, state = state, onRetry = paged::refresh)
        }
    }
}

/**
 * The shape of what is coming, not a spinner.
 *
 * Rows rather than cards because both grids and the channel list arrive top to bottom:
 * a skeleton that does not sit where the content will lands as a second layout change
 * the moment the query answers.
 */
@Composable
private fun LoadingRows() {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        repeat(SKELETON_ROWS) { Skeleton(height = SKELETON_HEIGHT, modifier = Modifier.fillMaxWidth()) }
    }
}

/**
 * Channels as a list, not a grid.
 *
 * A channel is chosen by its name and what is on it, and both are text. A poster wall
 * of identical logos is the shape every mediocre IPTV player uses and the shape that
 * makes 900 channels unreadable.
 */
@Composable
private fun ChannelList(
    rows: LazyPagingItems<MediaItem>,
    /**
     * Group id to name, for the row's second line.
     *
     * The guide is not wired to this screen yet, so the honest second line is the
     * category the provider filed the channel under — a fact the app already holds,
     * rather than a blank line where "now playing" will go.
     */
    categories: Map<String, String>,
    onPlay: (CatalogSelection) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        contentPadding = PaddingValues(bottom = Spacing.xxl),
    ) {
        items(rows.itemCount, key = rows.itemKey { it.id }) { index ->
            val item = rows[index] ?: return@items
            val selection = item.asSelection() ?: return@items
            ChannelCard(
                name = item.title,
                nowPlaying = (item as? Channel)?.groupId?.let { categories[it] }.orEmpty(),
                number = selection.channelNumber,
                seed = index,
                onClick = { onPlay(selection) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ItemGrid(rows: LazyPagingItems<MediaItem>, onPlay: (CatalogSelection) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(CastivioTheme.device.gridColumns),
        horizontalArrangement = Arrangement.spacedBy(Spacing.gridGutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.gridGutter),
        contentPadding = PaddingValues(bottom = Spacing.xxl),
    ) {
        items(rows.itemCount, key = rows.itemKey { it.id }) { index ->
            val item = rows[index] ?: return@items
            val selection = item.asSelection() ?: return@items
            MediaCard(
                title = item.title,
                subtitle = selection.subtitle,
                shape = CardShape.Poster,
                artworkSeed = index,
                onClick = { onPlay(selection) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ShowGrid(shows: LazyPagingItems<SeriesSummary>, onOpenShow: (SeriesSummary) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(CastivioTheme.device.gridColumns),
        horizontalArrangement = Arrangement.spacedBy(Spacing.gridGutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.gridGutter),
        contentPadding = PaddingValues(bottom = Spacing.xxl),
    ) {
        items(shows.itemCount, key = shows.itemKey { it.seriesId }) { index ->
            val show = shows[index] ?: return@items
            MediaCard(
                title = show.title,
                subtitle = stringResource(R.string.browse_episode_count, show.episodeCount),
                shape = CardShape.Poster,
                artworkSeed = index,
                onClick = { onOpenShow(show) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Why this is empty, and what to do about it.
 *
 * Two different sentences, because the two situations are different: a category the
 * provider left empty is not the same as a provider that carries no radio at all, and
 * a user who is told the second one stops looking for the first.
 */
@Composable
private fun SectionEmpty(section: CatalogSection, state: BrowseState, onRetry: () -> Unit) {
    val reason = if (state.selectedGroup != null) {
        EmptyReason.CATEGORY_EMPTY
    } else {
        EmptyReason.PROVIDER_HAS_NO_CONTENT
    }
    val provider = state.providerLabel ?: stringResource(R.string.browse_your_provider)
    EmptyState(
        title = when (reason) {
            EmptyReason.CATEGORY_EMPTY -> stringResource(R.string.browse_empty_category_title)
            else -> stringResource(R.string.browse_empty_section_title, provider, stringResource(section.label))
        },
        detail = when (reason) {
            EmptyReason.CATEGORY_EMPTY -> stringResource(R.string.browse_empty_category_detail)
            else -> stringResource(R.string.browse_empty_section_detail)
        },
        actionLabel = stringResource(R.string.browse_empty_action),
        onAction = onRetry,
    )
}

/** Stable across a re-import, unlike any group id, so the "all" row never re-animates. */
private const val ALL_CATEGORIES = "castivio.all"

/**
 * The category pane, sized to the approved layout.
 *
 * Wide enough for a real provider's category names — "Sports | Premium HD" rather than
 * "Sports" — and narrow enough to leave the content pane the majority of a 1920 screen.
 */
private val CATEGORY_PANE = 240.dp

/** Enough placeholder rows to fill a television without pretending to know the count. */
private const val SKELETON_ROWS = 8

/** A channel row's height, so the skeleton and the content occupy the same space. */
private val SKELETON_HEIGHT = 46.dp

/** Its own name, as the user reads it. */
internal val CatalogSection.label: Int
    get() = when (this) {
        CatalogSection.Live -> R.string.browse_live
        CatalogSection.Movies -> R.string.browse_movies
        CatalogSection.Series -> R.string.browse_series
        CatalogSection.Radio -> R.string.browse_radio
    }
