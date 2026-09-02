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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.ChannelCard
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.Episode
import com.castivio.domain.SeriesSummary

/**
 * One show's seasons, and the episodes inside them.
 *
 * The last step before something plays, and the last request a browse costs:
 * `get_series_info` for this show and no other. Fetching them up front would be one
 * request per show — hundreds of them, for episode lists almost none of which are
 * opened — which is why this screen exists rather than the data arriving with the
 * catalogue.
 *
 * A show is not a stream. The episode is the playable thing, which the model already
 * guarantees: there is nothing on a `Series` for a request to be built from.
 */
@Composable
fun ShowScreen(
    show: SeriesSummary,
    onPlay: (CatalogSelection) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    model: ShowViewModel = hiltViewModel(key = show.seriesId),
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(show.seriesId) { model.open(show.seriesId) }

    val seasons by model.seasons.collectAsStateWithLifecycle()
    val load by model.load.collectAsStateWithLifecycle()
    val colors = CastivioTheme.colors

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = CastivioTheme.device.screenPadding, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionTitle(
            title = show.title,
            subtitle = stringResource(R.string.show_seasons_count, seasons.size),
        )

        SectionBody(
            load = load,
            empty = seasons.isEmpty(),
            emptyTitle = stringResource(R.string.show_no_episodes_title),
            emptyDetail = stringResource(R.string.show_no_episodes_detail),
            onRetry = model::retry,
            onBack = onBack,
        ) {
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
        logoUrl = episode.artworkUrl,
        onClick = { onPlay(selection) },
        modifier = Modifier.fillMaxWidth(),
    )
}
