package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.ChannelCard
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.SectionHeader
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.Episode
import com.castivio.domain.SeriesSummary

/**
 * One show's episodes, grouped by season.
 *
 * This exists because a poster that does nothing is worse than no poster. A series row
 * is not a stream — [asSelection] returns null for it, deliberately — so the press has
 * to land somewhere, and this is that somewhere: the seasons the provider actually
 * numbered, each episode playable.
 *
 * The read is bounded by the show rather than by the library, which is the one case
 * where holding a whole list is correct: a season list is tens of rows, and paging it
 * would cost a query per screenful to save nothing.
 */
@Composable
fun ShowScreen(
    show: SeriesSummary,
    onPlay: (CatalogSelection) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    model: BrowseViewModel = hiltViewModel(key = CatalogSection.Series.name),
) {
    BackHandler(onBack = onBack)
    val colors = CastivioTheme.colors
    // Remembered by show, because `seasons` builds a query: recreating it on every
    // recomposition would restart the collection and re-run the read each frame.
    val query = remember(show.seriesId) { model.seasons(show.seriesId) }
    val seasons by query.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .padding(horizontal = CastivioTheme.device.screenPadding, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionHeader(title = show.title, count = show.episodeCount)

        if (seasons.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.show_no_episodes_title),
                    detail = stringResource(R.string.show_no_episodes_detail),
                    actionLabel = stringResource(R.string.show_back),
                    onAction = onBack,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                contentPadding = PaddingValues(bottom = Spacing.xxl),
            ) {
                seasons.forEach { season ->
                    item(key = "season-${season.number}") {
                        Text(
                            stringResource(R.string.show_season, season.number),
                            style = CastivioType.titleMedium,
                            color = colors.onBackground,
                            modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs),
                        )
                    }
                    items(season.episodes, key = { it.id }) { episode ->
                        EpisodeRow(episode, onPlay)
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, onPlay: (CatalogSelection) -> Unit) {
    val selection = episode.asSelection() ?: return
    ChannelCard(
        name = episode.title,
        nowPlaying = selection.subtitle.orEmpty(),
        number = null,
        seed = episode.episodeNumber,
        onClick = { onPlay(selection) },
        modifier = Modifier.fillMaxWidth(),
    )
}
