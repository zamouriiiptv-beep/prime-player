package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.castivio.core.design.components.CardShape
import com.castivio.core.design.components.ChannelCard
import com.castivio.core.design.components.MediaCard
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaItem
import com.castivio.domain.SeriesSummary

/**
 * What is inside one category.
 *
 * The deepest a browse goes before something plays, and the second and last request it
 * costs. Channels are a list because a channel is chosen by its name; films and shows
 * are a grid because a poster is what identifies them.
 *
 * The rows are paged, so a category with nine thousand films materialises a window and
 * never the category. Artwork is fetched by the cards themselves as they come on
 * screen — scrolling past a poster costs nothing.
 */
@Composable
fun CategoryScreen(
    section: CatalogSection,
    group: MediaGroup,
    onPlay: (CatalogSelection) -> Unit,
    onOpenShow: (SeriesSummary) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Keyed by category, so opening two in turn does not share one fetch. */
    model: CategoryViewModel = hiltViewModel(key = group.id),
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(group.id) { model.open(section, group.id) }

    val load by model.load.collectAsStateWithLifecycle()
    val device = CastivioTheme.device

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = device.screenPadding, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (section == CatalogSection.Series) {
            val shows = model.shows.collectAsLazyPagingItems()
            SectionTitle(
                title = group.name,
                subtitle = stringResource(R.string.browse_shows_count, shows.itemCount),
            )
            SectionBody(
                load = load,
                empty = shows.itemCount == 0,
                emptyTitle = stringResource(R.string.browse_empty_category_title),
                emptyDetail = stringResource(R.string.browse_empty_category_detail),
                onRetry = model::retry,
                onBack = onBack,
            ) {
                ShowGrid(shows, onOpenShow)
            }
        } else {
            val rows = model.items.collectAsLazyPagingItems()
            SectionTitle(
                title = group.name,
                subtitle = stringResource(R.string.browse_items_count, rows.itemCount),
            )
            SectionBody(
                load = load,
                empty = rows.itemCount == 0,
                emptyTitle = stringResource(R.string.browse_empty_category_title),
                emptyDetail = stringResource(R.string.browse_empty_category_detail),
                onRetry = model::retry,
                onBack = onBack,
            ) {
                if (section == CatalogSection.Channels) {
                    ChannelList(rows, onPlay)
                } else {
                    PosterGrid(rows, onPlay)
                }
            }
        }
    }
}

/**
 * Channels as a list, not a grid.
 *
 * A channel is chosen by its name and its logo, both of which read at a glance in a
 * row. A wall of identical logos is the shape that makes nine hundred channels
 * unreadable.
 */
@Composable
private fun ChannelList(rows: LazyPagingItems<MediaItem>, onPlay: (CatalogSelection) -> Unit) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        contentPadding = PaddingValues(bottom = Spacing.xxl),
    ) {
        items(rows.itemCount, key = rows.itemKey { it.id }) { index ->
            val item = rows[index] ?: return@items
            val selection = item.asSelection() ?: return@items
            ChannelCard(
                name = item.title,
                nowPlaying = selection.channelNumber.orEmpty(),
                number = selection.channelNumber,
                seed = index,
                logoUrl = item.artworkUrl,
                onClick = { onPlay(selection) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PosterGrid(rows: LazyPagingItems<MediaItem>, onPlay: (CatalogSelection) -> Unit) {
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
                artworkUrl = item.artworkUrl,
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
                // Episodes are not fetched until the show is opened, so a count here
                // would be zero for every show on the screen. The absence is honest.
                shape = CardShape.Poster,
                artworkSeed = index,
                artworkUrl = show.artworkUrl,
                onClick = { onOpenShow(show) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
