package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.theme.castivioBackdrop
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.castivioStage
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

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // This one *is* drawn over the destination underneath it, so it needs
            // a background of its own — and the one it needs is the application's,
            // not the flat colour underneath the application's.
            .castivioBackdrop()
            .statusBarsPadding(),
    ) {
    val m = catalogMetricsFor(tv = CastivioTheme.device.isTv, width = maxWidth, height = maxHeight)

    Column(
        Modifier
            .fillMaxSize()
            .castivioStage(m.frame),
        verticalArrangement = Arrangement.spacedBy(m.bandGap),
    ) {
        SectionHeader(
            title = show.title,
            count = show.episodeCount,
            titleStyle = castivioTitleStyle(m.frame.fsTitle),
            countStyle = castivioBodyStyle(m.frame.fsBody),
            gap = m.rowGap,
        )

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
                verticalArrangement = Arrangement.spacedBy(m.rowGap),
                contentPadding = PaddingValues(bottom = m.listBottom),
            ) {
                seasons.forEach { season ->
                    item(key = "season-${season.number}") {
                        Text(
                            stringResource(R.string.show_season, season.number),
                            // The title face at the step below the screen's own name:
                            // a season is a heading inside the list, not a second
                            // title for the page.
                            style = castivioTitleStyle(m.frame.fsLabel),
                            color = colors.onBackground,
                            modifier = Modifier.padding(top = m.bandGap, bottom = m.entryGap),
                        )
                    }
                    items(season.episodes, key = { it.id }) { episode ->
                        EpisodeRow(episode, m, onPlay)
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    m: CatalogMetrics,
    onPlay: (CatalogSelection) -> Unit,
) {
    val selection = episode.asSelection() ?: return
    ChannelCard(
        name = episode.title,
        nowPlaying = selection.subtitle.orEmpty(),
        number = null,
        seed = episode.episodeNumber,
        onClick = { onPlay(selection) },
        modifier = Modifier.fillMaxWidth(),
        logo = m.logo,
        pad = m.cardPad,
        minHeight = m.rowMin,
        nameStyle = castivioChipStyle(m.frame.fsLabel),
        captionStyle = castivioBodyStyle(m.frame.fsBody),
    )
}
