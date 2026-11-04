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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.CastivioChip
import com.castivio.core.design.components.CastivioIconButton
import com.castivio.core.design.components.CastivioSpinner
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.components.ProviderArtwork
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.Channel

/**
 * The catch-up screen: one channel's archive, by day, and a programme to watch.
 *
 * A screen of its own that covers the same ground as the guide -- the rail and the list, stopping before the picture, so
 * the channel goes on playing beside it and the surface never moves -- and that replaces the guide's list while it is
 * open. Back leaves it, and **leaving is what ends it**: the request for the archive is cancelled with the screen, and
 * nothing about a channel's archive is asked again until the viewer opens it again.
 *
 * ## What it shows, and what it does not make up
 *
 * The programmes the provider's own archive lists, with the day, the start and the end. There is no programme artwork
 * because an archive listing has none, and the card does not borrow one: its picture is the channel's own logo, which is
 * what the viewer would recognise it by. A day with nothing in it is not drawn, and a week is not padded out: the
 * provider says how far back it goes, and that is how many days there are.
 *
 * ## What choosing does
 *
 * The programme's address is made -- no request -- and handed to the same `onPlay` a channel row uses, so the player that
 * is already on screen opens it. Nothing here plays anything itself.
 */
@Composable
internal fun CatchUpOverlay(
    channel: Channel,
    m: ChannelsMetrics,
    model: CatchUpViewModel,
    onPlay: (CatalogSelection) -> Unit,
    onClose: () -> Unit,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.panelRadius)
    val state by model.state.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }

    // Asked when the screen opens, cancelled when it is left: the screen owns the request for exactly as long as it is on.
    DisposableEffect(channel.id) {
        model.open(channel)
        onDispose { model.close() }
    }
    BackHandler(onBack = onClose)
    LaunchedEffect(state.phase, state.dayIndex) {
        if (state.phase == CatchUpPhase.READY) runCatching { first.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .padding(end = m.player + m.playerGap)
            .background(colors.scrim)
            // Swallows the press so a tap on the dimmed board behind does not reach a channel row, as the guide's does.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(m.panelPad)
                .clip(shape)
                .background(colors.backgroundElevated)
                .border(1.dp, colors.glassBorder, shape)
                .padding(m.wellPad),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.controlPadH)) {
                CastivioIconButton(
                    icon = CastivioIcons.ArrowBack,
                    contentDescription = stringResource(R.string.catchup_back),
                    onClick = onClose,
                )
                Text(
                    text = stringResource(R.string.catchup_title),
                    style = castivioTitleStyle(m.frame.fsLabel),
                    color = colors.onBackgroundStrong,
                    maxLines = 1,
                )
                ProviderArtwork(
                    url = channel.artworkUrl,
                    description = null,
                    modifier = Modifier.size(m.frame.fsLabel * LOGO_IN_LABELS),
                )
                Text(
                    text = channel.title,
                    style = castivioChipStyle(m.frame.fsLabel),
                    color = colors.onBackgroundStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.catchup_subtitle),
                    style = castivioBodyStyle(m.frame.fsBody),
                    color = colors.onBackgroundMuted,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.height(m.guideGap))

            when (state.phase) {
                CatchUpPhase.IDLE, CatchUpPhase.LOADING -> Message(m) {
                    CastivioSpinner()
                    Text(
                        text = stringResource(R.string.catchup_loading),
                        style = castivioBodyStyle(m.frame.fsBody),
                        color = colors.onBackgroundMuted,
                    )
                }

                CatchUpPhase.EMPTY -> Message(m) { Say(m, R.string.catchup_empty) }
                CatchUpPhase.UNSUPPORTED -> Message(m) { Say(m, R.string.catchup_unsupported) }
                CatchUpPhase.FAILED -> Message(m) {
                    Say(m, R.string.catchup_failed)
                    CastivioChip(text = stringResource(R.string.catchup_retry), onClick = model::retry)
                }

                CatchUpPhase.READY -> {
                    // The days, newest first. Only the days the archive has: nothing is padded out to a week.
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(m.guideGap)) {
                        itemsIndexed(state.days, key = { index, day -> "${index}_${day.date}" }) { index, day ->
                            val selected = index == state.dayIndex
                            InteractiveGlassCard(
                                onClick = { model.selectDay(index) },
                                shape = RoundedCornerShape(m.panelRadius),
                                fill = if (selected) SolidColor(colors.primaryContainer) else SolidColor(colors.glassFill),
                            ) {
                                Text(
                                    text = if (day.label == DayLabel.DATE) day.date else dayName(day.label),
                                    style = castivioChipStyle(m.frame.fsLabel),
                                    color = colors.onBackgroundStrong,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = m.controlPadH, vertical = Spacing.sm),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(m.guideGap))

                    val day = state.days.getOrNull(state.dayIndex)
                    if (day != null) {
                        // The programmes fill what is left of the panel, two rows at a time, and scroll up and down for the rest:
                        // the cards are sized from the room there is, not from a width, so that two rows are always whole.
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                            val cardHeight = ((maxHeight - m.guideGap * (VISIBLE_ROWS - 1)) / VISIBLE_ROWS)
                                .coerceAtLeast(CARD_MIN_HEIGHT)
                            // Another day begins at the top, not at wherever the last one was scrolled to.
                            val grid = remember(state.dayIndex) { LazyGridState() }
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(CARD_MIN_WIDTH),
                                state = grid,
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(m.guideGap),
                                verticalArrangement = Arrangement.spacedBy(m.guideGap),
                            ) {
                                gridItemsIndexed(day.cards, key = { _, card -> card.programme.locator }) { index, card ->
                                    ProgrammeCard(
                                        card = card,
                                        logo = channel.artworkUrl,
                                        m = m,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(cardHeight)
                                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                                        onClick = {
                                            model.choose(card) { address, channelTitle ->
                                                onPlay(
                                                    CatalogSelection(
                                                        url = address,
                                                        title = card.title,
                                                        live = false,
                                                        subtitle = "$channelTitle · ${card.timeText}",
                                                    ),
                                                )
                                                onClose()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }

                    if (state.opening) {
                        Spacer(Modifier.height(m.guideGap))
                        CastivioSpinner()
                    }
                    if (state.openFailed) {
                        Spacer(Modifier.height(m.guideGap))
                        Text(
                            text = stringResource(R.string.catchup_open_failed),
                            style = castivioBodyStyle(m.frame.fsBody),
                            color = colors.danger,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One programme, small enough for two rows of them to share the panel: when it was on, with the channel's logo beside
 * the time where a picture would be, and its title under both, which takes whatever height is left.
 *
 * The card's size is the caller's. The title is the one part that can be long, so it is the part that gives: it holds two
 * lines and then ends in an ellipsis, and the card never grows to make room for it.
 */
@Composable
private fun ProgrammeCard(
    card: CatchUpCard,
    logo: String?,
    m: ChannelsMetrics,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = CastivioTheme.colors
    InteractiveGlassCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(m.panelRadius),
        fill = SolidColor(colors.glassFill),
    ) {
        Column(
            Modifier.fillMaxSize().padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ProviderArtwork(
                    url = logo,
                    description = null,
                    modifier = Modifier.size(m.frame.fsLabel * LOGO_IN_CARD),
                )
                Text(
                    text = card.timeText,
                    style = castivioBodyStyle(m.frame.fsBody),
                    color = colors.onBackgroundMuted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = card.title,
                style = castivioChipStyle(m.frame.fsLabel),
                color = colors.onBackgroundStrong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Message(m: ChannelsMetrics, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.guideGap, Alignment.CenterVertically),
    ) { content() }
}

@Composable
private fun Say(m: ChannelsMetrics, text: Int) {
    Text(
        text = stringResource(text),
        style = castivioBodyStyle(m.frame.fsBody),
        color = CastivioTheme.colors.onBackgroundMuted,
    )
}

@Composable
private fun dayName(label: DayLabel): String = when (label) {
    DayLabel.TODAY -> stringResource(R.string.channels_guide_today)
    DayLabel.YESTERDAY -> stringResource(R.string.catchup_yesterday)
    DayLabel.DATE -> ""
}

/** A card is at least this wide: the grid fits as many columns as the panel holds, three on a television, fewer on a phone. */
private val CARD_MIN_WIDTH = 150.dp

/** Rows of programmes shown whole at a time; the rest are a scroll away. */
private const val VISIBLE_ROWS = 2

/** The floor under a card's height, so a very short panel still draws a card that can be read and scrolls instead. */
private val CARD_MIN_HEIGHT = 76.dp

/** The channel's logo in the header, a little taller than the label beside it. */
private const val LOGO_IN_LABELS = 1.6f

/** The logo on a programme card, beside its time: about as tall as two lines of the label's own size. */
private const val LOGO_IN_CARD = 1.8f
