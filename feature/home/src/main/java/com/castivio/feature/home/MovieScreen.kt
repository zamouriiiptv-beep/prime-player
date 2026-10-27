package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.CastivioIconButton
import com.castivio.core.design.components.PosterFrame
import com.castivio.core.design.components.RatingBadge
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.components.ltrToken
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.castivioBackdrop
import com.castivio.core.design.theme.castivioStage
import com.castivio.domain.Movie

/**
 * One film's page: its poster, its name, what the provider said about it, and the two things a viewer
 * does here -- play it, and keep it among their favourites.
 *
 * A poster that does nothing was a defect of its own: a press on a card used to start the film at once,
 * with no way to see which film it had picked or to mark it for later. This is the somewhere the press
 * lands, and the film starts from the button that says so.
 *
 * ## Only what the provider sent
 *
 * The name, the poster, the category, the year, the running time and the genres in one bar; the IMDb rating
 * and the day the film was added; the plot, the director and the cast. Each is drawn where the provider sent
 * it -- from its list, or from the details asked for when the page opens -- and a fact it did not send is
 * no part of the page: nothing here is a placeholder.
 *
 * ## One screen, no scrolling
 *
 * Beside the poster the text column fits the height it is given: the plot takes the lines left between the
 * buttons and the credits and is cut with an ellipsis where it runs on. Only a surface too narrow to put the
 * poster beside the text stacks them, and scrolls.
 *
 * ## The favourite
 *
 * The button and the heart are drawn from the one set the channels board uses -- every favourite of any
 * kind, shown from the press and written behind it -- so a film marked here is in the films' Favourites
 * list the moment the viewer goes back.
 *
 * ## The remote
 *
 * Focus opens on Play, the action the page is for. Back returns to the wall as it was left, and Back out
 * of the player returns here -- see the shell.
 */
@Composable
fun MovieScreen(
    movie: Movie,
    onPlay: (CatalogSelection) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The films' own holder, for the category's name. Keyed as the wall keys it. */
    model: BrowseViewModel = hiltViewModel(key = CatalogSection.Movies.name),
    /** Every favourite, of whatever kind -- the same set the channels' star is drawn from. */
    favorites: ChannelFavoritesViewModel = hiltViewModel(),
    /** What the provider says about this film beyond its list row. Asked for once, when the page opens. */
    detailsModel: MovieDetailsViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onBack)
    val colors = CastivioTheme.colors
    val ids by favorites.ids.collectAsStateWithLifecycle()
    val known by detailsModel.details.collectAsStateWithLifecycle()
    val favorite = movie.id in ids
    val state by model.state.collectAsStateWithLifecycle()
    val selection = remember(movie) { movie.asSelection() }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(movie.id) { runCatching { playFocus.requestFocus() } }
    LaunchedEffect(movie.id) { detailsModel.load(movie) }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // Drawn over the wall beneath it, so it brings the application's own ground: see `ShowScreen`.
            .castivioBackdrop()
            .statusBarsPadding(),
    ) {
        val m = catalogMetricsFor(tv = CastivioTheme.device.isTv, width = maxWidth, height = maxHeight)
        val side = maxWidth >= SIDE_BY_SIDE

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            val shown = movie.withDetails(known)
            val category = movie.groupId?.let { state.categoryNames[it] }

            val info: @Composable (Modifier, Boolean, (@Composable () -> Unit)?) -> Unit = { infoModifier, fit, titleEnd ->
                val iconSize = m.frame.fsBody * ICON_OF_BODY
                val bodyStyle = castivioBodyStyle(m.frame.fsBody)
                val genres = shown.genres.filter { it.isNotBlank() }.joinToString(" | ")
                val year = shown.year?.takeIf { it > 0 }?.toString()
                val duration = movieDuration(shown.durationMinutes)

                // Everything above the plot: the name, the facts in one bar, the two buttons, the rating and
                // the day the film was added, and the plot's heading. A row is drawn only where there is
                // something to put in it, and a fact the provider did not send is no part of it.
                val top: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(m.bandGap)) {
                        // The name, and at the end of the same row the way back when the poster stands beside the
                        // page: in the corner it is the top of the page, and under no other row. The way back is
                        // smaller than a touch target on a handset (see `back`) so that the row is little taller
                        // than the name, and a gap of one row's width keeps it off the facts bar below -- the bar
                        // had touched it, and the gap is paid for by the height the smaller button gives back.
                        Column(verticalArrangement = Arrangement.spacedBy(if (titleEnd != null) m.rowGap else m.bandGap)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(m.rowGap),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = movie.title,
                                    style = castivioTitleStyle(m.frame.fsTitle),
                                    color = colors.onBackground,
                                    maxLines = TITLE_LINES,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                titleEnd?.invoke()
                            }
                            FactsBar(category, year?.let(::ltrToken), duration?.let(::ltrToken), genres, iconSize, m)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(m.rowGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CastivioButton(
                                text = stringResource(R.string.movie_play),
                                onClick = { selection?.let(onPlay) },
                                weight = ButtonWeight.Primary,
                                icon = Icons.Rounded.PlayArrow,
                                enabled = selection != null,
                                modifier = Modifier.focusRequester(playFocus),
                            )
                            CastivioButton(
                                text = stringResource(
                                    if (favorite) R.string.movie_favorite_remove else R.string.movie_favorite_add,
                                ),
                                onClick = { favorites.flip(movie.id) },
                                weight = ButtonWeight.Secondary,
                                icon = if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            )
                        }
                        val rating = known?.rating
                        val added = known?.addedEpochSeconds
                        if (rating != null || added != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(m.rowGap),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                rating?.let {
                                    RatingBadge(
                                        rating = ratingText(it),
                                        labelStyle = castivioChipStyle(m.frame.fsChip),
                                        iconSize = iconSize,
                                        padH = m.entryPadH,
                                        padV = m.entryPadV,
                                    )
                                }
                                added?.let {
                                    Fact(
                                        text = stringResource(R.string.movie_added, addedDateText(it)),
                                        m = m,
                                        icon = Icons.Rounded.CalendarMonth,
                                        iconSize = iconSize,
                                    )
                                }
                            }
                        }
                        if (known?.plot != null) {
                            Text(
                                text = stringResource(R.string.movie_plot_title),
                                style = castivioChipStyle(m.frame.fsLabel).copy(fontWeight = FontWeight.Bold),
                                color = colors.onBackground,
                            )
                        }
                    }
                }

                // The plot takes the lines the height has left, and is cut with an ellipsis where it runs on:
                // the page does not scroll. On a surface too narrow to sit beside the poster it is whole.
                val plot: (@Composable (Int) -> Unit)? = known?.plot?.let { text ->
                    { lines ->
                        Text(
                            text = text,
                            style = bodyStyle,
                            color = colors.onBackgroundVariant,
                            maxLines = lines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                val director = known?.director
                val cast = known?.cast.orEmpty()
                val bottom: (@Composable () -> Unit)? = if (director != null || cast.isNotEmpty()) {
                    {
                        Column(verticalArrangement = Arrangement.spacedBy(m.rowGap)) {
                            director?.let {
                                Credit(Icons.Rounded.Person, stringResource(R.string.movie_director), it, 1, iconSize, bodyStyle, m)
                            }
                            if (cast.isNotEmpty()) {
                                Credit(
                                    Icons.Rounded.Groups, stringResource(R.string.movie_cast), cast.joinToString(", "),
                                    CAST_LINES, iconSize, bodyStyle, m,
                                )
                            }
                        }
                    }
                } else {
                    null
                }

                if (fit) {
                    val lineHeight = with(LocalDensity.current) { bodyStyle.lineHeight.toPx() }
                    FitInfo(infoModifier, m.bandGap, lineHeight, top, plot, bottom)
                } else {
                    Column(infoModifier, verticalArrangement = Arrangement.spacedBy(m.bandGap)) {
                        top()
                        plot?.invoke(Int.MAX_VALUE)
                        bottom?.invoke()
                    }
                }
            }

            // The way back sits in the top corner opposite the poster, as the reference has it: at the end of
            // the name's row beside the poster, and in the page's corner when the page is a column.
            val tv = CastivioTheme.device.isTv
            val back: @Composable (Modifier) -> Unit = { backModifier ->
                CastivioIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.show_back),
                    onClick = onBack,
                    // A handset's button is smaller than its 48dp floor, by the owner's leave: the way back is also
                    // the hardware Back, and the page is not a place a thumb has to find it. A television keeps
                    // the remote's floor.
                    modifier = if (tv) backModifier else backModifier.size(BACK_SIZE),
                )
            }

            if (side) {
                Box(Modifier.fillMaxSize().castivioStage(m.frame)) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(m.paneGap),
                ) {
                    PosterFrame(
                        url = movie.artworkUrl,
                        seed = 0,
                        modifier = Modifier.fillMaxHeight()
                            .aspectRatio(CATALOG_POSTER_ASPECT, matchHeightConstraintsFirst = true),
                    )
                    info(Modifier.weight(1f).fillMaxHeight(), true, { back(Modifier) })
                }
                }
            } else {
                // A narrow surface stacks them, the poster first and at a width that leaves the buttons
                // on the screen with it; the page scrolls where the name is long.
                Box(Modifier.fillMaxSize().castivioStage(m.frame)) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(m.bandGap),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PosterFrame(
                        url = movie.artworkUrl,
                        seed = 0,
                        modifier = Modifier.fillMaxWidth(STACKED_POSTER).aspectRatio(CATALOG_POSTER_ASPECT),
                    )
                    info(Modifier.fillMaxWidth(), false, null)
                }
                back(Modifier.align(Alignment.TopEnd).padding(m.paneGap))
                }
            }
        }
    }
}

/** One fact about the film, as a pill: a year, a running time, a category -- with its mark when it has one. */
@Composable
private fun Fact(text: String, m: CatalogMetrics, icon: ImageVector? = null, iconSize: Dp = Dp.Unspecified) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        Modifier
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(horizontal = m.entryPadH, vertical = m.entryPadV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.rowGap / 2),
    ) {
        if (icon != null) Icon(icon, null, tint = colors.onBackgroundVariant, modifier = Modifier.size(iconSize))
        Text(
            text = text,
            style = castivioChipStyle(m.frame.fsChip),
            color = colors.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The facts of a film in one bar, divided by rules: its category, its year, its running time, its genres.
 * A fact that is not there is not drawn, nor its rule. The year and the time are short and keep their
 * width; the category and the genres are the words a provider chose, and share what is left, so a long
 * list of genres is cut where it meets the bar's edge and moves nothing else.
 */
@Composable
private fun FactsBar(
    category: String?,
    year: String?,
    duration: String?,
    genres: String,
    iconSize: Dp,
    m: CatalogMetrics,
) {
    val facts = listOfNotNull(
        category?.let { Triple(it, Icons.Rounded.Category, true) },
        year?.let { Triple(it, Icons.Rounded.CalendarMonth, false) },
        duration?.let { Triple(it, Icons.Rounded.Schedule, false) },
        genres.takeIf { it.isNotEmpty() }?.let { Triple(it, Icons.Rounded.TheaterComedy, true) },
    )
    if (facts.isEmpty()) return
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.pill)
    // One flag per child below, the rules between facts included, so the layout knows which may be cut.
    val shrinks = buildList {
        facts.forEachIndexed { index, fact ->
            if (index > 0) add(false)
            add(fact.third)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(horizontal = m.entryPadH, vertical = m.entryPadV),
    ) {
        FactsRow(shrinks, Modifier.fillMaxWidth()) {
            facts.forEachIndexed { index, (text, icon, _) ->
                if (index > 0) {
                    Box(
                        Modifier
                            .padding(horizontal = m.rowGap)
                            .width(1.dp)
                            .height(iconSize)
                            .background(colors.glassBorderSoft),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(m.rowGap / 2),
                ) {
                    Icon(icon, null, tint = colors.onBackgroundVariant, modifier = Modifier.size(iconSize))
                    Text(
                        text = text,
                        style = castivioChipStyle(m.frame.fsChip),
                        color = colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The facts in a line, each at the width it needs, and where they do not all fit the words a provider chose are cut
 * and the rest keep theirs. A `Row` with weights could not do it: it gives each weighted child an equal share of what
 * is left whether the child needs it or not, so a short category kept room it did not use and the genres were cut with
 * empty bar beside them.
 *
 * [shrinks] has one flag for each child, in order. The widths are worked out by [shareWidths].
 */
@Composable
private fun FactsRow(shrinks: List<Boolean>, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val natural = measurables.map { it.maxIntrinsicWidth(0) }
        val widths = shareWidths(natural, shrinks, constraints.maxWidth)
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints(maxWidth = widths[index], maxHeight = constraints.maxHeight))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, constraints.constrainHeight(height)) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width
            }
        }
    }
}

/**
 * The width each fact is given: the ones that may not be cut keep their own, and what is left is shared among the
 * ones that may -- equally, except that one that needs less than its share keeps only what it needs and the rest is
 * shared again among the others. So the longest is cut first and no bar is left with room in it that a cut word wanted.
 *
 * [natural] is each child's width uncut and [shrinks] says which may be cut. A bar too narrow for even the fixed
 * ones gives the cuttable ones none.
 */
internal fun shareWidths(natural: List<Int>, shrinks: List<Boolean>, available: Int): List<Int> {
    val widths = natural.toMutableList()
    var room = available - natural.indices.filter { !shrinks[it] }.sumOf { natural[it] }
    val open = natural.indices.filter { shrinks[it] }.toMutableList()
    while (open.isNotEmpty()) {
        val share = room.coerceAtLeast(0) / open.size
        val fits = open.filter { natural[it] <= share }
        if (fits.isEmpty()) {
            open.forEach { widths[it] = share }
            break
        }
        fits.forEach { room -= natural[it] }
        open.removeAll(fits.toSet())
    }
    return widths
}

/** A credit line -- the director, the cast: a mark, its label in bold, and the names after it. */
@Composable
private fun Credit(
    icon: ImageVector,
    label: String,
    names: String,
    lines: Int,
    iconSize: Dp,
    style: TextStyle,
    m: CatalogMetrics,
) {
    val colors = CastivioTheme.colors
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(m.rowGap)) {
        Icon(icon, null, tint = colors.onBackground, modifier = Modifier.size(iconSize))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.onBackground)) { append(label) }
                append(" ")
                append(names)
            },
            style = style,
            color = colors.onBackgroundVariant,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The film's text column, laid out to the height it is given and never taller: what stands above the plot
 * and what stands below it are measured first, and the plot is given the whole lines that are left between
 * them. A page that scrolled to reach its cast has become a page that shows the cast and cuts the plot.
 */
@Composable
private fun FitInfo(
    modifier: Modifier,
    gap: Dp,
    lineHeight: Float,
    top: @Composable () -> Unit,
    plot: (@Composable (Int) -> Unit)?,
    bottom: (@Composable () -> Unit)?,
) {
    SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gapPx = gap.roundToPx()
        val topPlaced = subcompose("top", top).map { it.measure(loose) }
        val bottomPlaced = bottom?.let { content -> subcompose("bottom", content).map { it.measure(loose) } }.orEmpty()
        val topHeight = topPlaced.sumOf { it.height }
        val bottomHeight = bottomPlaced.sumOf { it.height }
        val gaps = (if (plot != null) 1 else 0) + (if (bottom != null) 1 else 0)
        val room = constraints.maxHeight - topHeight - bottomHeight - gaps * gapPx
        val lines = (room / lineHeight).toInt().coerceAtLeast(1)
        val plotPlaced = plot?.let { content -> subcompose("plot") { content(lines) }.map { it.measure(loose) } }.orEmpty()
        val plotHeight = plotPlaced.sumOf { it.height }

        layout(constraints.maxWidth, constraints.maxHeight) {
            var y = 0
            topPlaced.forEach { it.placeRelative(0, y) }
            y += topHeight
            if (plot != null) {
                y += gapPx
                plotPlaced.forEach { it.placeRelative(0, y) }
                y += plotHeight
            }
            if (bottom != null) {
                y += gapPx
                bottomPlaced.forEach { it.placeRelative(0, y) }
            }
        }
    }
}

/** From here the poster stands beside the page; narrower, the page is a column under it. */
private val SIDE_BY_SIDE = 600.dp

/** How much of a narrow page's width the poster takes. */
private const val STACKED_POSTER = 0.5f

private const val TITLE_LINES = 1

/**
 * The cast takes one line, and a longer list is cut with an ellipsis: the plot has first call on the height, and a
 * second line of names was the line it was cut for.
 */
private const val CAST_LINES = 1

/** The way back's side on a handset: under the 48dp floor, which a television keeps. */
private val BACK_SIZE = 40.dp

/** A mark's size as a share of the body type, so the marks scale with the page's own type. */
private const val ICON_OF_BODY = 1.4f
