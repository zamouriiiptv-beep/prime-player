package com.castivio.feature.home

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.CastivioTextField
import com.castivio.core.design.components.ChannelCard
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.SectionHeader
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing

/**
 * Search over the imported catalogue.
 *
 * One box, no button. What is typed goes to the state holder unchanged and comes back
 * as results 120 ms later; there is nothing here that decides when to search, because
 * that decision is not a screen's to make.
 */
@Composable
fun CatalogSearchScreen(
    onPlay: (CatalogSelection) -> Unit,
    modifier: Modifier = Modifier,
    model: SearchViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val colors = CastivioTheme.colors

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = CastivioTheme.device.screenPadding, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        CastivioTextField(
            value = state.query,
            onValueChange = model::type,
            label = stringResource(R.string.search_label),
            placeholder = stringResource(R.string.search_placeholder),
            imeAction = ImeAction.Search,
            modifier = Modifier.fillMaxWidth(),
        )

        when {
            !state.asked -> Text(
                stringResource(R.string.search_prompt),
                style = CastivioType.bodyMedium,
                color = colors.onBackgroundMuted,
            )

            state.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.search_none_title, state.query),
                    detail = stringResource(R.string.search_none_detail),
                    actionLabel = stringResource(R.string.search_clear),
                    onAction = model::clear,
                )
            }

            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                contentPadding = PaddingValues(bottom = Spacing.xxl),
            ) {
                item(key = "header") {
                    SectionHeader(
                        title = stringResource(R.string.search_results),
                        count = state.results.size,
                    )
                }
                items(state.results, key = { it.id }) { item ->
                    // Every searchable row is a stream: the index is over the media
                    // table, where a series is stored as its episodes. A show as such
                    // is an aggregate that only the Series grid asks for, so nothing
                    // unplayable can arrive here -- and `asSelection` returning null
                    // still skips it rather than trusting that.
                    val selection = item.asSelection() ?: return@items
                    ChannelCard(
                        name = item.title,
                        nowPlaying = selection.subtitle.orEmpty(),
                        seed = item.id.hashCode(),
                        onClick = { onPlay(selection) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
