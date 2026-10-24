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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.CastivioIconButton
import com.castivio.core.design.components.PosterFrame
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
 * The name, the poster, the running time, the year where it gave one, and the category it filed the film
 * under. There is no rating, synopsis, cast or "similar films" -- none is stored, so none is drawn, and
 * nothing here is a placeholder for a fact that is not there.
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
            val facts = movieFacts(shown)
            val category = movie.groupId?.let { state.categoryNames[it] }

            val info: @Composable (Modifier) -> Unit = { infoModifier ->
                Column(infoModifier, verticalArrangement = Arrangement.spacedBy(m.bandGap)) {
                    Text(
                        text = movie.title,
                        style = castivioTitleStyle(m.frame.fsTitle),
                        color = colors.onBackground,
                        maxLines = TITLE_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The category the provider filed the film under, as the reference's pill beneath the name.
                    category?.let { Fact(it, m) }
                    // One row of what is known, and no row at all where nothing is: the year, the running
                    // time, and the genres the provider gave.
                    val genres = shown.genres.filter { it.isNotBlank() }.joinToString(" | ")
                    if (facts.isNotEmpty() || genres.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(m.rowGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            facts.forEach { Fact(ltrToken(it), m) }
                            if (genres.isNotEmpty()) Fact(genres, m)
                        }
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
                    // What the provider said beyond the list row. Each line is drawn only when the provider
                    // sent it: a fact it did not send is no line at all, never a stand-in.
                    known?.let { d ->
                        d.rating?.let { Fact(stringResource(R.string.movie_rating, ratingText(it)), m) }
                        d.addedEpochSeconds?.let { Fact(stringResource(R.string.movie_added, addedDateText(it)), m) }
                        d.plot?.let {
                            Text(
                                text = stringResource(R.string.movie_plot_title),
                                style = castivioChipStyle(m.frame.fsChip),
                                color = colors.onBackground,
                            )
                            Text(
                                text = it,
                                style = castivioBodyStyle(m.frame.fsBody),
                                color = colors.onBackgroundVariant,
                            )
                        }
                        d.director?.let {
                            Text(
                                text = stringResource(R.string.movie_director, it),
                                style = castivioBodyStyle(m.frame.fsBody),
                                color = colors.onBackgroundVariant,
                            )
                        }
                        if (d.cast.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.movie_cast, d.cast.joinToString(", ")),
                                style = castivioBodyStyle(m.frame.fsBody),
                                color = colors.onBackgroundVariant,
                            )
                        }
                    }
                }
            }

            // The way back sits in the top corner opposite the poster, as the reference has it.
            val back: @Composable () -> Unit = {
                CastivioIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.show_back),
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopEnd).padding(m.paneGap),
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
                    info(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
                }
                back()
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
                    info(Modifier.fillMaxWidth())
                }
                back()
                }
            }
        }
    }
}

/** One fact about the film, as a pill: a year, a running time, a category. */
@Composable
private fun Fact(text: String, m: CatalogMetrics) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.pill)
    Box(
        Modifier
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(horizontal = m.entryPadH, vertical = m.entryPadV),
    ) {
        Text(
            text = text,
            style = castivioChipStyle(m.frame.fsChip),
            color = colors.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** From here the poster stands beside the page; narrower, the page is a column under it. */
private val SIDE_BY_SIDE = 600.dp

/** How much of a narrow page's width the poster takes. */
private const val STACKED_POSTER = 0.5f

private const val TITLE_LINES = 3
