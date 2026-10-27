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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.CastivioIconButton
import com.castivio.core.design.components.EmptyState
import com.castivio.core.design.components.PosterFrame
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

/**
 * One show's page: the show beside its episodes.
 *
 * The start pane is the show -- its poster, name, how many seasons and episodes it has, and Play -- and the end
 * pane is the episodes: a row of season chips with the way back at its end, and under it the chosen season's
 * list. Play continues the show from the episode last started, or starts its first; the page opens on the season
 * that episode is in, with the list scrolled to it. A press on an episode plays it.
 *
 * Nothing is drawn that the provider did not send: the page has the show's picture and name, and each episode's
 * number, name, picture and running time -- no synopsis, no rating, no cast -- and a show whose episodes are not
 * held yet shows quiet placeholders where they will be, then the list, or a way to ask again.
 *
 * Drawn left to right whatever the language, as the film's page is, because the words in it are the provider's.
 */
@Composable
fun ShowScreen(
    show: SeriesSummary,
    onPlay: (CatalogSelection) -> Unit,
    onBack: () -> Unit,
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

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // Drawn over the destination underneath it, so it brings the application's own ground.
            .castivioBackdrop()
            .statusBarsPadding(),
    ) {
        val m = catalogMetricsFor(tv = CastivioTheme.device.isTv, width = maxWidth, height = maxHeight)
        // Read here: the constraints' own scope is a layout scope, and the row and the lazy list below are others.
        val surfaceHeight = maxHeight
        val tv = CastivioTheme.device.isTv
        val back: @Composable (Modifier) -> Unit = { backModifier ->
            CastivioIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.show_back),
                onClick = onBack,
                modifier = if (tv) backModifier else backModifier.size(BACK_SIZE),
            )
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            if (m.twoPane) {
                Row(
                    Modifier.fillMaxSize().castivioStage(m.frame),
                    horizontalArrangement = Arrangement.spacedBy(m.paneGap),
                ) {
                    ShowPane(
                        show = show,
                        state = state,
                        m = m,
                        posterHeight = (surfaceHeight * POSTER_OF_HEIGHT).coerceIn(MIN_POSTER, MAX_POSTER),
                        playFocus = playFocus,
                        onPlay = { target?.let { start(it.episode) } },
                        modifier = Modifier.width(m.pane).fillMaxHeight(),
                    )
                    EpisodesPane(
                        state = state,
                        season = season,
                        m = m,
                        onSeason = { picked = it },
                        onStart = start,
                        onRetry = model::retry,
                        onBack = onBack,
                        back = back,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else {
                // A narrow surface stacks them: the show, then the chips, then the episodes, in one scrolling list.
                Box(Modifier.fillMaxSize().castivioStage(m.frame)) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(m.rowGap),
                        contentPadding = PaddingValues(bottom = m.listBottom),
                    ) {
                        item(key = "show") {
                            ShowPane(
                                show = show,
                                state = state,
                                m = m,
                                posterHeight = (surfaceHeight * STACKED_POSTER_OF_HEIGHT).coerceIn(MIN_POSTER, MAX_POSTER),
                                playFocus = playFocus,
                                onPlay = { target?.let { start(it.episode) } },
                                modifier = Modifier.fillMaxWidth().padding(end = BACK_SIZE + m.paneGap),
                            )
                        }
                        stackedEpisodes(state, season, m, { picked = it }, start, model::retry, onBack)
                    }
                    back(Modifier.align(Alignment.TopEnd).padding(m.paneGap))
                }
            }
        }
    }
}

/** The show: its poster, its name and what it holds, and Play. */
@Composable
private fun ShowPane(
    show: SeriesSummary,
    state: ShowState,
    m: CatalogMetrics,
    posterHeight: Dp,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val target = state.target
    val episodes = state.seasons.sumOf { it.episodes.size }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.bandGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(m.rowGap)) {
            PosterFrame(
                url = show.artworkUrl,
                seed = 0,
                modifier = Modifier.height(posterHeight).aspectRatio(CATALOG_POSTER_ASPECT),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.rowGap / 2)) {
                Text(
                    text = show.title,
                    style = castivioTitleStyle(m.frame.fsTitle * TITLE_OF_PANE),
                    color = colors.onBackground,
                    maxLines = SHOW_TITLE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.seasons.isNotEmpty()) {
                    val resources = LocalContext.current.resources
                    Text(
                        text = stringResource(
                            R.string.show_facts,
                            resources.getQuantityString(R.plurals.show_seasons, state.seasons.size, state.seasons.size),
                            resources.getQuantityString(R.plurals.show_episodes, episodes, episodes),
                        ),
                        style = castivioBodyStyle(m.frame.fsBody),
                        color = colors.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
            modifier = Modifier.fillMaxWidth().focusRequester(playFocus),
        )
    }
}

/** The end pane: the season chips with the way back, and the chosen season's episodes under them. */
@Composable
private fun EpisodesPane(
    state: ShowState,
    season: Season?,
    m: CatalogMetrics,
    onSeason: (Int) -> Unit,
    onStart: (Episode) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    back: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.rowGap)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.rowGap)) {
            Box(Modifier.weight(1f)) { SeasonChips(state.seasons, season, m, onSeason) }
            back(Modifier)
        }
        when {
            state.seasons.isEmpty() -> EpisodesPlaceholder(state.load, m, onRetry, onBack, Modifier.weight(1f))
            season != null -> EpisodeList(season, state, m, onStart, Modifier.weight(1f))
        }
    }
}

/** The seasons as a row of chips; the chosen one is lit, and kept in view when there are many. */
@Composable
private fun SeasonChips(seasons: List<Season>, chosen: Season?, m: CatalogMetrics, onSeason: (Int) -> Unit) {
    if (seasons.isEmpty()) return
    val listState = rememberLazyListState()
    val index = seasons.indexOfFirst { it.number == chosen?.number }
    LaunchedEffect(index) { if (index >= 0) listState.animateScrollToItem(index) }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(m.rowGap)) {
        items(seasons, key = { it.number }) { season ->
            SeasonChip(
                label = stringResource(R.string.show_season, season.number),
                selected = season.number == chosen?.number,
                m = m,
                onClick = { onSeason(season.number) },
            )
        }
    }
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, m: CatalogMetrics, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.pill)
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .height(if (CastivioTheme.device.isTv) Sizing.minTarget(true) else CHIP_HEIGHT)
            .clip(shape)
            .background(if (selected) colors.selectedFill else colors.glassFill)
            .border(
                1.dp,
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
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = castivioChipStyle(m.frame.fsChip),
            color = colors.onBackground,
            maxLines = 1,
        )
    }
}

/** The chosen season's episodes, opened scrolled to the one Play starts when it is in this season. */
@Composable
private fun EpisodeList(season: Season, state: ShowState, m: CatalogMetrics, onStart: (Episode) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    val next = state.target?.episode
    LaunchedEffect(season.number, next?.id) {
        val at = season.episodes.indexOfFirst { it.id == next?.id }
        listState.scrollToItem(if (at > 0) at else 0)
    }
    LazyColumn(
        modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(m.rowGap),
        contentPadding = PaddingValues(bottom = m.listBottom),
    ) {
        items(season.episodes, key = { it.id }) { episode ->
            EpisodeRow(episode, state.marks[episode.id] ?: EpisodeMark.None, m, onStart)
        }
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
        ShowLoad.Failed -> Box(modifier, contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.show_failed_title),
                detail = stringResource(R.string.show_failed_detail),
                actionLabel = stringResource(R.string.show_retry),
                onAction = onRetry,
                secondaryActionLabel = stringResource(R.string.show_back),
                onSecondaryAction = onBack,
            )
        }
        ShowLoad.Ready -> Box(modifier, contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.show_no_episodes_title),
                detail = stringResource(R.string.show_no_episodes_detail),
                actionLabel = stringResource(R.string.show_back),
                onAction = onBack,
            )
        }
    }
}

/** The stacked layout's share of the list: the chips as one row, then the placeholders or the episodes. */
private fun androidx.compose.foundation.lazy.LazyListScope.stackedEpisodes(
    state: ShowState,
    season: Season?,
    m: CatalogMetrics,
    onSeason: (Int) -> Unit,
    onStart: (Episode) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    if (state.seasons.isEmpty()) {
        item(key = "placeholder") { EpisodesPlaceholder(state.load, m, onRetry, onBack, Modifier.fillMaxWidth()) }
        return
    }
    item(key = "chips") { SeasonChips(state.seasons, season, m, onSeason) }
    items(season?.episodes.orEmpty(), key = { it.id }) { episode ->
        EpisodeRow(episode, state.marks[episode.id] ?: EpisodeMark.None, m, onStart)
    }
}

/** One episode: its picture, its number and name, and its running time; checked when watched, barred when part-watched. */
@Composable
private fun EpisodeRow(episode: Episode, mark: EpisodeMark, m: CatalogMetrics, onStart: (Episode) -> Unit) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.md)
    var focused by remember { mutableStateOf(false) }
    val thumbHeight = m.rowMin
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) colors.selectedFill else colors.glassFill)
            .border(1.dp, if (focused) colors.focusRing else colors.glassBorderSoft, shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onStart(episode) }
            .padding(m.cardPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.rowGap),
    ) {
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
        Text(
            text = buildAnnotatedString {
                episodeNumberText(episode)?.let { number ->
                    withStyle(SpanStyle(color = colors.onBackgroundMuted, fontWeight = FontWeight.Bold)) { append("$number  ") }
                }
                append(episode.title)
            },
            style = castivioChipStyle(m.frame.fsLabel),
            color = colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        movieDuration(episode.durationMinutes)?.let { duration ->
            Text(
                text = ltrToken(duration),
                style = castivioBodyStyle(m.frame.fsBody),
                color = colors.onBackgroundMuted,
                maxLines = 1,
            )
        }
    }
}

/** `E5`, the part of an episode's label the list shows beside its name; null when the provider did not number it. */
internal fun episodeNumberText(episode: Episode): String? =
    episode.episodeNumber.takeIf { it > 0 }?.let { "E$it" }

/** `S2 E5` for the button, or the half the provider numbered. */
internal fun episodeText(episode: Episode): String = when {
    episode.seasonNumber > 0 && episode.episodeNumber > 0 -> "S${episode.seasonNumber} E${episode.episodeNumber}"
    episode.episodeNumber > 0 -> "E${episode.episodeNumber}"
    episode.seasonNumber > 0 -> "S${episode.seasonNumber}"
    else -> episode.title
}

/** The way back's side on a handset: under the 48dp floor, which a television keeps. */
private val BACK_SIZE = 40.dp

/** A season chip's height on a handset; a television's is the control floor. */
private val CHIP_HEIGHT = 40.dp

/** An episode's picture is 16:9. */
private const val THUMB_ASPECT = 16f / 9f

/** The show's name, as a share of the screen's own title step: it stands beside a poster, not over a page. */
private const val TITLE_OF_PANE = 0.82f

private const val SHOW_TITLE_LINES = 3

/** How tall the show's poster is, as a share of the screen: beside the page's other things, not all of it. */
private const val POSTER_OF_HEIGHT = 0.42f
private const val STACKED_POSTER_OF_HEIGHT = 0.3f
private val MIN_POSTER = 96.dp
private val MAX_POSTER = 240.dp

private const val PLACEHOLDER_ROWS = 5
private val MARK_PAD = 4.dp
private val MARK_SIZE = 18.dp
private val BAR_HEIGHT = 3.dp
