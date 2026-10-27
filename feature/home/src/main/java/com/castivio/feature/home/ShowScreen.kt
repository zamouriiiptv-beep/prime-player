package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CardShape
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.CastivioIconButton
import com.castivio.core.design.components.CastivioNoticeDialog
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.MediaCard
import com.castivio.core.design.components.PosterFrame
import com.castivio.core.design.components.RatingStar
import com.castivio.core.design.components.ShowBackdrop
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.components.ltrToken
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.castivioBackdrop
import com.castivio.core.design.theme.castivioStage
import com.castivio.domain.Episode
import com.castivio.domain.EpisodeMark
import com.castivio.domain.Season
import com.castivio.domain.SeriesSummary
import kotlinx.coroutines.launch

/**
 * One show's page: a rail of its sections beside one column that scrolls.
 *
 * The column opens on the show -- its poster, name, a row of facts (year, genre, rating, seasons), the plot, and the
 * two buttons, Play and the favourite -- over the provider's wide picture of it when it sent one. Under that, the
 * season chips with how many episodes each holds, and the chosen season's episodes: number, the provider's still, name,
 * a line of its plot, and its running time. Then the cast, and other shows of the same category. The rail names these
 * parts on a television and keeps their icons on a handset, takes the viewer to one with a press, and lights the one
 * the page has reached.
 *
 * Play continues the show from the episode last started, or starts its first. A press on an episode plays it.
 *
 * Nothing is drawn that the provider did not send, and nothing is made up. The first of what it says arrives after the
 * page is up, with the episodes, so until the provider has answered a quiet placeholder stands where the facts, the
 * plot and the episodes' plots will be; once it has, only what came is drawn, and a section with nothing in it is not
 * in the rail.
 *
 * Drawn left to right whatever the language, as the film's page is, because the words in it are the provider's.
 */
@Composable
fun ShowScreen(
    show: SeriesSummary,
    onPlay: (CatalogSelection) -> Unit,
    onBack: () -> Unit,
    onOpenShow: (SeriesSummary) -> Unit,
    modifier: Modifier = Modifier,
    model: ShowViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(show.seriesId) { model.open(show.seriesId) }
    // The player has been on top of this page, or has not: either way, what was watched is read again.
    LaunchedEffect(Unit) { model.reread() }

    val state by model.state.collectAsStateWithLifecycle()
    val target = state.target

    // The season the viewer picked, else the one the next episode is in, else the first.
    var picked by remember(show.seriesId) { mutableStateOf<Int?>(null) }
    val season = state.seasons.firstOrNull { it.number == (picked ?: target?.episode?.seasonNumber) }
        ?: state.seasons.firstOrNull()

    val playFocus = remember { FocusRequester() }
    LaunchedEffect(target != null) { if (target != null) runCatching { playFocus.requestFocus() } }

    val start: (Episode) -> Unit = { episode ->
        episode.asSelection()?.let { selection ->
            model.started(episode.id)
            onPlay(selection)
        }
    }

    // The plot, whole: a link under it and a small dialog, offered only where the page had to cut it.
    var plotOpen by remember(show.seriesId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val details = state.details
    val sections = showSections(
        hasSeasons = state.seasons.isNotEmpty(),
        episodes = season?.episodes?.size ?: 0,
        hasCast = details?.cast?.isNotEmpty() == true,
        hasSimilar = state.similar.isNotEmpty(),
    )
    val lit = litSection(sections, listState.firstVisibleItemIndex, atEnd = !listState.canScrollForward)

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // Drawn over the destination underneath it, so it brings the application's own ground.
            .castivioBackdrop()
            .statusBarsPadding(),
    ) {
        val m = catalogMetricsFor(tv = CastivioTheme.device.isTv, width = maxWidth, height = maxHeight)
        // Read here: the constraints' own scope is a layout scope, and the lists below are others.
        val surfaceHeight = maxHeight
        val tv = CastivioTheme.device.isTv
        val railWidth = if (tv) (maxWidth * RAIL_OF_WIDTH).coerceIn(MIN_RAIL, MAX_RAIL) else HANDSET_RAIL
        val posterHeight = (surfaceHeight * POSTER_OF_HEIGHT).coerceIn(MIN_POSTER, MAX_POSTER)
        // The wall's metrics, for the poster cards of the similar shows: the same card, the same sizes.
        val board = channelsMetricsFor(tv = tv, width = maxWidth, height = maxHeight)

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.fillMaxSize().castivioStage(m.frame),
                horizontalArrangement = Arrangement.spacedBy(m.paneGap),
            ) {
                ShowRail(
                    sections = sections.map { it.section },
                    lit = lit,
                    tv = tv,
                    m = m,
                    onBack = onBack,
                    onSection = { section ->
                        val at = sections.firstOrNull { it.section == section } ?: return@ShowRail
                        scope.launch { listState.animateScrollToItem(at.index) }
                    },
                    modifier = Modifier.width(railWidth).fillMaxHeight(),
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(m.rowGap),
                    contentPadding = PaddingValues(bottom = m.listBottom),
                ) {
                    item(key = "top") {
                        ShowTop(
                            show = show,
                            state = state,
                            m = m,
                            tv = tv,
                            posterHeight = posterHeight,
                            playFocus = playFocus,
                            onPlay = { target?.let { start(it.episode) } },
                            onFavorite = model::toggleFavorite,
                            onMorePlot = { plotOpen = true },
                        )
                    }
                    if (state.seasons.isEmpty()) {
                        item(key = "placeholder") {
                            EpisodesPlaceholder(state.load, m, model::retry, onBack, Modifier.fillMaxWidth())
                        }
                    } else {
                        item(key = "seasons") { SeasonChips(state.seasons, season, m, tv) { picked = it } }
                        items(season?.episodes.orEmpty(), key = { it.id }) { episode ->
                            EpisodeRow(
                                episode = episode,
                                mark = state.marks[episode.id] ?: EpisodeMark.None,
                                plot = state.plots[episode.id],
                                // The plots come with the same reply as the rest: until it is in, a bar stands there.
                                waiting = state.asking && state.plots.isEmpty(),
                                m = m,
                                onStart = start,
                            )
                        }
                    }
                    details?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
                        item(key = "cast") { CastSection(cast, m) }
                    }
                    if (state.similar.isNotEmpty()) {
                        item(key = "similar") { SimilarSection(state.similar, m, board, surfaceHeight, onOpenShow) }
                    }
                }
            }
        }

        // Outside the block above, which draws the page left to right: the dialog is the application's own, and
        // follows its language -- the plot inside it takes the direction of its words.
        if (plotOpen) {
            CastivioNoticeDialog(
                title = show.title,
                body = details?.plot.orEmpty(),
                closeLabel = stringResource(R.string.movie_plot_close),
                onClose = { plotOpen = false },
                maxFraction = PLOT_DIALOG_FRACTION,
            )
        }
    }
}

/** The rail: the way back with the application's name, and the page's sections, the one the page is at lit. */
@Composable
private fun ShowRail(
    sections: List<ShowSection>,
    lit: ShowSection,
    tv: Boolean,
    m: CatalogMetrics,
    onBack: () -> Unit,
    onSection: (ShowSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(RAIL_GAP)) {
        Row(
            Modifier.padding(bottom = RAIL_GAP),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RAIL_GAP),
        ) {
            CastivioIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.show_back),
                onClick = onBack,
                modifier = if (tv) Modifier else Modifier.size(BACK_SIZE),
            )
            // The name beside the arrow: a television has the width for it, a handset's rail has not.
            if (tv) {
                Text(
                    text = stringResource(R.string.show_brand),
                    style = castivioTitleStyle(m.frame.fsTitle * BRAND_OF_TITLE),
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        for (section in sections) {
            val label = stringResource(section.label)
            RailEntry(
                icon = section.icon,
                label = label,
                m = m,
                showLabel = tv,
                selected = section == lit,
                onClick = { onSection(section) },
            )
        }
    }
}

private val ShowSection.label: Int
    get() = when (this) {
        ShowSection.Overview -> R.string.show_nav_overview
        ShowSection.Seasons -> R.string.show_nav_seasons
        ShowSection.Cast -> R.string.show_nav_cast
        ShowSection.Similar -> R.string.show_nav_similar
    }

private val ShowSection.icon: ImageVector
    get() = when (this) {
        ShowSection.Overview -> Icons.Rounded.Info
        ShowSection.Seasons -> Icons.Rounded.VideoLibrary
        ShowSection.Cast -> Icons.Rounded.Person
        ShowSection.Similar -> Icons.Rounded.ThumbUp
    }

/** One section in the rail: its icon, and on a television its name; violet when the page is at it. */
@Composable
private fun RailEntry(icon: ImageVector, label: String, m: CatalogMetrics, showLabel: Boolean, selected: Boolean, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.md)
    var focused by remember { mutableStateOf(false) }
    val tv = CastivioTheme.device.isTv
    Row(
        Modifier
            .then(if (showLabel) Modifier.fillMaxWidth() else Modifier.size(BACK_SIZE))
            .height(if (tv) Sizing.minTarget(true) else BACK_SIZE)
            .clip(shape)
            .background(if (selected) colors.selectedFill else colors.glassFill.copy(alpha = 0f))
            .border(
                if (focused) 2.dp else 1.dp,
                when {
                    focused -> colors.focusRing
                    selected -> colors.selectedBorder
                    else -> colors.glassFill.copy(alpha = 0f)
                },
                shape,
            )
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = if (showLabel) RAIL_PAD else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (showLabel) Arrangement.spacedBy(RAIL_PAD) else Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = if (showLabel) null else label,
            tint = if (selected) colors.onBackground else colors.onBackgroundVariant,
            modifier = Modifier.size(RAIL_ICON),
        )
        if (showLabel) {
            Text(
                text = label,
                style = castivioChipStyle(m.frame.fsLabel),
                color = if (selected) colors.onBackground else colors.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The show: its poster and name, the facts, the plot, and the buttons, over the provider's wide picture. */
@Composable
private fun ShowTop(
    show: SeriesSummary,
    state: ShowState,
    m: CatalogMetrics,
    tv: Boolean,
    posterHeight: Dp,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onMorePlot: () -> Unit,
) {
    val colors = CastivioTheme.colors
    val details = state.details
    // The provider has not answered yet, and may have something to say: quiet bars stand where it would go.
    val waiting = state.asking && details == null
    val target = state.target
    var plotCut by remember(details?.plot) { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        details?.backdropUrl?.let { url ->
            // As wide as the page and as tall as this part of it, however tall the words make it.
            Box(Modifier.matchParentSize()) {
                ShowBackdrop(url, Modifier.align(Alignment.TopEnd).fillMaxWidth(BACKDROP_SHARE).fillMaxHeight())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(m.paneGap)) {
            PosterFrame(
                url = show.artworkUrl,
                seed = 0,
                modifier = Modifier.height(posterHeight).aspectRatio(CATALOG_POSTER_ASPECT),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.rowGap)) {
                Text(
                    text = show.title,
                    style = castivioTitleStyle(m.frame.fsTitle * TITLE_OF_PANE),
                    color = colors.onBackground,
                    maxLines = SHOW_TITLE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                ShowFacts(state, waiting, m)
                val plot = details?.plot
                if (plot != null) {
                    Text(
                        text = plot,
                        style = castivioBodyStyle(m.frame.fsBody),
                        color = colors.onBackgroundVariant,
                        maxLines = if (tv) PLOT_LINES_TV else PLOT_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { plotCut = it.hasVisualOverflow },
                        modifier = if (plotCut) {
                            Modifier.focusProperties { canFocus = false }.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = stringResource(R.string.movie_plot_more),
                                onClick = onMorePlot,
                            )
                        } else {
                            Modifier
                        },
                    )
                    // Where the plot was cut, the link to the whole of it: a press on the text is for a thumb, and
                    // the remote reaches this.
                    if (plotCut) PlotMore(m, onClick = onMorePlot)
                } else if (waiting) {
                    PlotPlaceholder(m, tv)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(m.rowGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CastivioButton(
                        text = when {
                            target == null -> stringResource(R.string.show_play)
                            target.resume -> stringResource(R.string.show_continue, episodeText(target.episode))
                            else -> stringResource(R.string.show_play_episode, episodeText(target.episode))
                        },
                        onClick = onPlay,
                        weight = ButtonWeight.Primary,
                        icon = Icons.Rounded.PlayArrow,
                        enabled = target != null,
                        modifier = Modifier.focusRequester(playFocus),
                    )
                    CastivioButton(
                        text = stringResource(if (state.favorite) R.string.show_favorite_remove else R.string.show_favorite_add),
                        onClick = onFavorite,
                        weight = ButtonWeight.Secondary,
                        icon = if (state.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    )
                }
            }
        }
    }
}

/** The facts in one row, wrapping where the width is short: year, genre, rating, seasons -- each only if it is known. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShowFacts(state: ShowState, waiting: Boolean, m: CatalogMetrics) {
    val details = state.details
    val iconSize = m.frame.fsBody * FACT_ICON_OF_BODY
    val style = castivioBodyStyle(m.frame.fsBody)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(m.paneGap),
        verticalArrangement = Arrangement.spacedBy(m.rowGap / 2),
    ) {
        details?.releaseYear?.let { Fact(Icons.Rounded.CalendarToday, ltrToken(it.toString()), iconSize, style) }
        details?.genres?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { genres ->
            Fact(Icons.Rounded.TheaterComedy, genres.joinToString(" | "), iconSize, style)
        }
        details?.rating?.let { RatingStar(ltrToken(ratingText(it)), style, iconSize) }
        if (state.seasons.isNotEmpty()) {
            val count = state.seasons.size
            Fact(Icons.Rounded.Layers, plural(R.plurals.show_seasons, count), iconSize, style)
        }
        if (waiting) Bar(Modifier.width(m.frame.fsBody * FACTS_BAR).height(m.frame.fsBody))
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String, iconSize: Dp, style: androidx.compose.ui.text.TextStyle) {
    val colors = CastivioTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FACT_GAP)) {
        Icon(icon, contentDescription = null, tint = colors.onBackgroundMuted, modifier = Modifier.size(iconSize))
        Text(text = text, style = style, color = colors.onBackgroundVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlotPlaceholder(m: CatalogMetrics, tv: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(m.rowGap / 2)) {
        Bar(Modifier.fillMaxWidth().height(m.frame.fsBody))
        Bar(Modifier.fillMaxWidth(PLOT_BAR_SECOND).height(m.frame.fsBody))
        if (tv) Bar(Modifier.fillMaxWidth(PLOT_BAR_THIRD).height(m.frame.fsBody))
    }
}

/** A quiet bar: where something will be, once the provider has said it. */
@Composable
private fun Bar(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(Radius.xs)).background(CastivioTheme.colors.glassFill))
}

/** The seasons as a row of two-line chips; the chosen one is lit, and kept in view when there are many. */
@Composable
private fun SeasonChips(seasons: List<Season>, chosen: Season?, m: CatalogMetrics, tv: Boolean, onSeason: (Int) -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val index = seasons.indexOfFirst { it.number == chosen?.number }
    LaunchedEffect(index) { if (index >= 0) listState.animateScrollToItem(index) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.rowGap)) {
        LazyRow(Modifier.weight(1f), state = listState, horizontalArrangement = Arrangement.spacedBy(m.rowGap)) {
            items(seasons, key = { it.number }) { season ->
                SeasonChip(season, selected = season.number == chosen?.number, m = m, tv = tv) { onSeason(season.number) }
            }
        }
        // A television's remote has no swipe: an arrow at the end of the row takes it on.
        if (tv && listState.canScrollForward) {
            CastivioIconButton(
                icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(R.string.show_nav_seasons),
                onClick = {
                    scope.launch { listState.animateScrollToItem(listState.firstVisibleItemIndex + CHIPS_STEP) }
                },
            )
        }
    }
}

@Composable
private fun SeasonChip(season: Season, selected: Boolean, m: CatalogMetrics, tv: Boolean, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.md)
    var focused by remember { mutableStateOf(false) }
    val episodes = season.episodes.size
    Column(
        Modifier
            .height(Sizing.minTarget(tv).coerceAtLeast(m.frame.fsChip * CHIP_OF_TYPE))
            .clip(shape)
            .background(if (selected) colors.selectedFill else colors.glassFill)
            .border(
                if (focused) 2.dp else 1.dp,
                when {
                    focused -> colors.focusRing
                    selected -> colors.selectedBorder
                    else -> colors.glassBorder
                },
                shape,
            )
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = m.entryPadH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.show_season, season.number),
            style = castivioChipStyle(m.frame.fsChip).copy(fontWeight = FontWeight.Bold),
            color = colors.onBackground,
            maxLines = 1,
        )
        Text(
            text = plural(R.plurals.show_episodes, episodes),
            style = castivioChipStyle(m.frame.fsChip * CHIP_SUB_OF_CHIP),
            color = if (selected) colors.onBackground else colors.onBackgroundMuted,
            maxLines = 1,
        )
    }
}

/** What stands in the list's place while no episode is held: placeholders, a way to ask again, or the plain fact. */
@Composable
private fun EpisodesPlaceholder(load: ShowLoad, m: CatalogMetrics, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier) {
    when (load) {
        ShowLoad.Loading -> Column(modifier, verticalArrangement = Arrangement.spacedBy(m.rowGap)) {
            repeat(PLACEHOLDER_ROWS) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(m.rowMin)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(CastivioTheme.colors.glassFill),
                )
            }
        }
        ShowLoad.Failed -> Box(modifier.heightIn(min = m.rowMin * PLACEHOLDER_ROWS), contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.show_failed_title),
                detail = stringResource(R.string.show_failed_detail),
                actionLabel = stringResource(R.string.show_retry),
                onAction = onRetry,
                secondaryActionLabel = stringResource(R.string.show_back),
                onSecondaryAction = onBack,
            )
        }
        ShowLoad.Ready -> Box(modifier.heightIn(min = m.rowMin * PLACEHOLDER_ROWS), contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.show_no_episodes_title),
                detail = stringResource(R.string.show_no_episodes_detail),
                actionLabel = stringResource(R.string.show_back),
                onAction = onBack,
            )
        }
    }
}

/**
 * One episode: its number, its picture, its name and a line of its plot, and its running time in an outline at the
 * end; checked when watched, barred when part-watched. The row the remote is on is violet with its edge.
 */
@Composable
private fun EpisodeRow(
    episode: Episode,
    mark: EpisodeMark,
    plot: String?,
    waiting: Boolean,
    m: CatalogMetrics,
    onStart: (Episode) -> Unit,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.md)
    var focused by remember { mutableStateOf(false) }
    val thumbHeight = m.rowMin
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) colors.selectedFill else colors.glassFill)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.selectedBorder else colors.glassBorderSoft, shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onStart(episode) }
            .padding(m.cardPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.rowGap),
    ) {
        Text(
            text = episode.episodeNumber.takeIf { it > 0 }?.let { ltrToken(it.toString()) }.orEmpty(),
            style = castivioChipStyle(m.frame.fsLabel * NUMBER_OF_LABEL).copy(fontWeight = FontWeight.SemiBold),
            color = colors.onBackgroundVariant,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(m.frame.fsLabel * NUMBER_WIDTH_OF_LABEL),
        )
        Box(Modifier.height(thumbHeight).aspectRatio(THUMB_ASPECT)) {
            PosterFrame(url = episode.artworkUrl, seed = episode.episodeNumber, modifier = Modifier.fillMaxSize())
            when (mark) {
                EpisodeMark.Watched -> Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = colors.success,
                    modifier = Modifier.align(Alignment.TopEnd).padding(MARK_PAD).size(MARK_SIZE),
                )
                is EpisodeMark.Partly -> Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(mark.percent.coerceIn(0f, 1f))
                        .height(BAR_HEIGHT)
                        .background(colors.primary),
                )
                EpisodeMark.None -> Unit
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.rowGap / 4)) {
            Text(
                text = episode.title,
                style = castivioChipStyle(m.frame.fsLabel).copy(fontWeight = FontWeight.Bold),
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                plot != null -> Text(
                    text = plot,
                    style = castivioBodyStyle(m.frame.fsBody),
                    color = colors.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                waiting -> Bar(Modifier.fillMaxWidth(PLOT_BAR_SECOND).height(m.frame.fsBody))
            }
        }
        movieDuration(episode.durationMinutes)?.let { duration ->
            Text(
                text = ltrToken(duration),
                style = castivioBodyStyle(m.frame.fsBody),
                color = colors.onBackground,
                maxLines = 1,
                modifier = Modifier
                    .border(1.dp, colors.glassBorder, RoundedCornerShape(Radius.xs))
                    .padding(horizontal = m.rowGap, vertical = m.rowGap / 4),
            )
        }
    }
}

/** The cast, as the providers send it: names, with no photographs, in a row that wraps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CastSection(cast: List<String>, m: CatalogMetrics) {
    val colors = CastivioTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(m.rowGap)) {
        SectionTitle(stringResource(R.string.show_nav_cast), m)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(m.rowGap),
            verticalArrangement = Arrangement.spacedBy(m.rowGap),
        ) {
            for (name in cast) {
                Text(
                    text = name,
                    style = castivioChipStyle(m.frame.fsChip),
                    color = colors.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(colors.glassFill)
                        .border(1.dp, colors.glassBorder, RoundedCornerShape(Radius.pill))
                        .padding(horizontal = m.entryPadH, vertical = m.rowGap / 2),
                )
            }
        }
    }
}

/** Other shows filed under the same category, as the poster wall draws a show: a press opens its page. */
@Composable
private fun SimilarSection(
    shows: List<SeriesSummary>,
    m: CatalogMetrics,
    board: ChannelsMetrics,
    surfaceHeight: Dp,
    onOpenShow: (SeriesSummary) -> Unit,
) {
    val titleStep = filmTitleStep(board)
    val poster = (surfaceHeight * SIMILAR_POSTER_OF_HEIGHT).coerceIn(MIN_POSTER, MAX_POSTER)
    Column(verticalArrangement = Arrangement.spacedBy(m.rowGap)) {
        SectionTitle(stringResource(R.string.show_similar_title), m)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(board.nameGap)) {
            items(shows, key = { it.seriesId }) { show ->
                MediaCard(
                    title = show.title,
                    shape = CardShape.Poster,
                    artworkSeed = show.seriesId.hashCode(),
                    onClick = { onOpenShow(show) },
                    width = poster * CATALOG_POSTER_ASPECT,
                    gap = board.nameGap,
                    titleStyle = castivioChipStyle(titleStep),
                    captionStyle = castivioBodyStyle(board.frame.fsBody),
                    artworkUrl = show.artworkUrl,
                    posterHeight = poster,
                    titleLines = TITLE_LINES,
                    titleHeight = titleStep * LABEL_LINE * TITLE_LINES,
                    focusBrush = focusBrush(),
                    focusScale = 1f,
                    artworkScale = ContentScale.Fit,
                    cardFill = CastivioTheme.colors.glassFill,
                    cinematicBackdrop = true,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, m: CatalogMetrics) {
    Text(
        text = text,
        style = castivioChipStyle(m.frame.fsLabel * SECTION_TITLE_OF_LABEL).copy(fontWeight = FontWeight.Bold),
        color = CastivioTheme.colors.onBackground,
        maxLines = 1,
    )
}

/** A count with its noun, in the number the language gives it. */
@Composable
private fun plural(id: Int, count: Int): String = LocalContext.current.resources.getQuantityString(id, count, count)

/** `S2 E5` for the button, or the half the provider numbered. */
internal fun episodeText(episode: Episode): String = when {
    episode.seasonNumber > 0 && episode.episodeNumber > 0 -> "S${episode.seasonNumber} E${episode.episodeNumber}"
    episode.episodeNumber > 0 -> "E${episode.episodeNumber}"
    episode.seasonNumber > 0 -> "S${episode.seasonNumber}"
    else -> episode.title
}

/** The way back's, and a handset's rail entries', side: under the 48dp floor, which a television keeps. */
private val BACK_SIZE = 40.dp

/** The rail's width on a handset, icons only; a television's is a share of its width, between a floor and a ceiling. */
private val HANDSET_RAIL = 56.dp
private const val RAIL_OF_WIDTH = 0.17f
private val MIN_RAIL = 150.dp
private val MAX_RAIL = 240.dp
private val RAIL_GAP = 8.dp
private val RAIL_PAD = 10.dp
private val RAIL_ICON = 20.dp
private const val BRAND_OF_TITLE = 0.7f

/** An episode's picture is 16:9. */
private const val THUMB_ASPECT = 16f / 9f

/** The show's name, as a share of the screen's own title step: it stands beside a poster, not over a page. */
private const val TITLE_OF_PANE = 0.82f

private const val SHOW_TITLE_LINES = 3

/** The plot's lines: a television has the room for three, a handset two. */
private const val PLOT_LINES = 2
private const val PLOT_LINES_TV = 3

/** How much of the page's width the wide picture takes, from its end edge. */
private const val BACKDROP_SHARE = 0.62f

/** How tall the show's poster is, as a share of the screen: beside the page's other things, not all of it. */
private const val POSTER_OF_HEIGHT = 0.34f
private const val SIMILAR_POSTER_OF_HEIGHT = 0.3f
private val MIN_POSTER = 96.dp
private val MAX_POSTER = 240.dp

private const val FACT_ICON_OF_BODY = 1.25f
private val FACT_GAP = 6.dp
private const val FACTS_BAR = 9f
private const val PLOT_BAR_SECOND = 0.92f
private const val PLOT_BAR_THIRD = 0.6f

private const val CHIP_OF_TYPE = 3.4f
private const val CHIP_SUB_OF_CHIP = 0.82f
private const val CHIPS_STEP = 3

private const val NUMBER_OF_LABEL = 1.2f
private const val NUMBER_WIDTH_OF_LABEL = 2.2f
private const val SECTION_TITLE_OF_LABEL = 1.1f

private const val PLACEHOLDER_ROWS = 5
private val MARK_PAD = 4.dp
private val MARK_SIZE = 18.dp
private val BAR_HEIGHT = 3.dp
