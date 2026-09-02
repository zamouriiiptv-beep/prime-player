package com.castivio.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.CardShape
import com.castivio.core.design.components.ChannelCard
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.MediaCard
import com.castivio.core.design.components.MediaRow
import com.castivio.core.design.components.MetaChip
import com.castivio.core.design.components.SkeletonRow
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.MediaItem

/**
 * Home, over the provider's own catalogue.
 *
 * Three rows and a header, and every number on it answered by SQL. There is no hero
 * and nothing is featured: an IPTV catalogue has no editorial, so a large picture at
 * the top would be a picture of whatever happened to import first. The rows are a
 * sample; the sections are where browsing actually happens.
 */
@Composable
fun HomeScreen(
    onPlay: (CatalogSelection) -> Unit,
    onSeeSection: (CatalogSection) -> Unit,
    modifier: Modifier = Modifier,
    model: HomeViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val colors = CastivioTheme.colors

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CastivioTheme.device.screenPadding, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.home_brand),
                style = CastivioType.titleLarge,
                color = colors.secondary,
            )
            Box(Modifier.weight(1f))
            state.provider?.let { MetaChip(it) }
        }

        when {
            state.loading -> SkeletonRow(cards = 4, label = null)

            state.isEmpty -> Box(
                Modifier.fillMaxSize().padding(CastivioTheme.device.screenPadding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    title = stringResource(R.string.home_empty_title),
                    detail = stringResource(R.string.home_empty_detail),
                    actionLabel = stringResource(R.string.browse_live),
                    onAction = { onSeeSection(CatalogSection.Live) },
                )
            }

            else -> {
                ChannelRow(
                    title = stringResource(R.string.browse_live),
                    count = state.liveCount,
                    items = state.live,
                    onPlay = onPlay,
                )
                PosterRow(
                    title = stringResource(R.string.browse_movies),
                    count = state.movieCount,
                    items = state.movies,
                    onPlay = onPlay,
                )
                PosterRow(
                    title = stringResource(R.string.browse_series),
                    count = state.seriesCount,
                    items = state.episodes,
                    onPlay = onPlay,
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    title: String,
    count: Int,
    items: List<MediaItem>,
    onPlay: (CatalogSelection) -> Unit,
) {
    if (items.isEmpty()) return
    MediaRow(title = title, items = items, key = { it.id }, count = count) { item ->
        val selection = item.asSelection() ?: return@MediaRow
        ChannelCard(
            name = item.title,
            nowPlaying = selection.channelNumber.orEmpty(),
            seed = item.id.hashCode(),
            onClick = { onPlay(selection) },
        )
    }
}

@Composable
private fun PosterRow(
    title: String,
    count: Int,
    items: List<MediaItem>,
    onPlay: (CatalogSelection) -> Unit,
) {
    if (items.isEmpty()) return
    MediaRow(title = title, items = items, key = { it.id }, count = count) { item ->
        val selection = item.asSelection() ?: return@MediaRow
        MediaCard(
            title = item.title,
            subtitle = selection.subtitle,
            shape = CardShape.Poster,
            width = 120.dp,
            artworkSeed = item.id.hashCode(),
            onClick = { onPlay(selection) },
        )
    }
}
