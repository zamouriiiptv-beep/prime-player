package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import com.castivio.core.design.components.castivioFocusScale
import com.castivio.core.design.components.formatCount
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.MediaGroup

/**
 * The categories of one section — the first thing fetched, and the only thing.
 *
 * Pressing Channels arrives here and one request goes out: the live categories. Films
 * and series are not touched, and neither is a single channel; those cost a request
 * each and are made when a category is opened.
 *
 * A category is a target rather than a row in a rail, because on this screen it is the
 * whole content — the user came here to choose one.
 */
@Composable
fun CategoriesScreen(
    section: CatalogSection,
    onOpen: (MediaGroup) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Keyed by section, so each keeps its own scroll position and its own fetch. */
    model: CategoriesViewModel = hiltViewModel(key = section.name),
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(section) { model.open(section) }

    val categories by model.categories.collectAsStateWithLifecycle()
    val load by model.load.collectAsStateWithLifecycle()
    val device = CastivioTheme.device

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = device.screenPadding, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionTitle(
            title = stringResource(section.label),
            subtitle = stringResource(R.string.browse_categories_count, categories.size),
        )

        SectionBody(
            load = load,
            empty = categories.isEmpty(),
            emptyTitle = stringResource(R.string.browse_no_categories_title),
            emptyDetail = stringResource(R.string.browse_no_categories_detail),
            onRetry = model::retry,
            onBack = onBack,
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(device.categoryColumns),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                contentPadding = PaddingValues(bottom = Spacing.xxl),
            ) {
                items(categories, key = { it.id }) { group ->
                    CategoryTile(group = group, onOpen = onOpen)
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(group: MediaGroup, onOpen: (MediaGroup) -> Unit) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Radius.md)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .castivioFocusScale(Motion.focusScaleButton, interaction)
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorder, shape)
            .clickable(interaction, indication = null) { onOpen(group) }
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Text(
            group.name,
            style = CastivioType.bodyMedium,
            color = colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Shown only once this category has been opened at least once. Before that
        // the number is genuinely unknown, and a zero would be a lie about the
        // provider rather than a fact about the app.
        if (group.itemsLoadedAtMs != null) {
            Text(
                formatCount(group.itemCount),
                style = CastivioType.labelSmall,
                color = colors.onBackgroundMuted,
            )
        }
    }
}

/** A heading and a count, in the one shape every screen on this path uses. */
@Composable
internal fun SectionTitle(title: String, subtitle: String) {
    val colors = CastivioTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(title, style = CastivioType.headlineSmall, color = colors.onBackground)
        Text(subtitle, style = CastivioType.bodySmall, color = colors.onBackgroundMuted)
    }
}

/** Wide targets on a television, narrower ones where a thumb does the pointing. */
private val com.castivio.core.design.theme.DeviceClass.categoryColumns: Int
    get() = when (this) {
        com.castivio.core.design.theme.DeviceClass.Compact -> 1
        com.castivio.core.design.theme.DeviceClass.Medium -> 2
        com.castivio.core.design.theme.DeviceClass.Expanded -> 3
        com.castivio.core.design.theme.DeviceClass.Television -> 4
    }
