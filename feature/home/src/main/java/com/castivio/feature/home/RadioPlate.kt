package com.castivio.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.CastivioIconButton
import com.castivio.core.design.components.Equaliser
import com.castivio.core.design.components.ProviderArtwork
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioFocusScale
import com.castivio.core.design.components.StreamQualityIcon
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.common.StreamQuality
import com.castivio.domain.Channel
import com.castivio.domain.RadioPhase

/**
 * The plate that keeps the sound in view: what is playing, and the few things a viewer does about it.
 *
 * ## One plate, two places
 *
 * The plate at the board's end, on every device ([RadioSide]); and, from it on a handset, the plate opened -- the
 * same station and the same controls, larger. The controls are the same on all of
 * them and are the whole of what the radio offers: the station before, play or pause, the station after, the
 * favourite and a sleep timer. A control that has nothing to do is not drawn dim but not drawn -- the station
 * before the first one of a list has no meaning -- and its place is kept, so the play control does not move under
 * a thumb that is about to press it.
 *
 * ## What is not on it
 *
 * No bitrate, codec, country or waveform: a panel sends a station as a name, a logo, a number and a category,
 * and a mark that moved to the sound without being the sound would be a measurement made up. The mark that
 * moves says "sounding", as the equaliser on the playing card does, and stands still when the sound is held or
 * motion is off.
 */
@Immutable
internal data class RadioPlateState(
    val station: Channel,
    val category: String?,
    val phase: RadioPhase,
    val favorite: Boolean,
    /** A sleep timer is running, so its button says so. */
    val sleeping: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean,
    /**
     * How the station's stream is arriving, or null while there is no rating -- see [com.castivio.domain.RadioStatus.quality].
     * Drawn as the same mark the other players draw, at the head of the plate's status and controls.
     */
    val quality: StreamQuality? = null,
)

/** What a press on each control of the plate does. [onOpen] is null where the plate is already as large as it gets. */
@Immutable
internal class RadioPlateActions(
    val onToggle: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onFavorite: () -> Unit,
    val onSleep: () -> Unit,
    val onOpen: (() -> Unit)? = null,
)

/** The station before, play or pause, and the station after: the three controls the sound is driven with. */
@Composable
private fun RadioControls(plate: RadioPlateState, actions: RadioPlateActions, button: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (plate.canPrevious) {
            CastivioIconButton(
                icon = Icons.Rounded.SkipPrevious,
                contentDescription = stringResource(R.string.radio_previous),
                onClick = actions.onPrevious,
            )
        } else {
            Spacer(Modifier.size(button))
        }
        RadioPlayButton(plate.phase, actions.onToggle, size = button)
        if (plate.canNext) {
            CastivioIconButton(
                icon = Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.radio_next),
                onClick = actions.onNext,
            )
        } else {
            Spacer(Modifier.size(button))
        }
    }
}

/**
 * The control the sound is held and let go with: a pause while it sounds or is being asked for -- the plate's second
 * line says "connecting", and a press holds the station as on any other -- a play while it is held, and a try again
 * on a stream that failed. One control for the three, so what it shows is what the next press does.
 *
 * No spinner of its own: the line beside it already says the station is on its way, and a ring that turns for as
 * long as a slow stream takes is a second answer to the question that line answers.
 */
@Composable
private fun RadioPlayButton(phase: RadioPhase, onClick: () -> Unit, size: Dp, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val label = stringResource(
        when (phase) {
            RadioPhase.PLAYING, RadioPhase.OPENING -> R.string.radio_pause
            RadioPhase.FAILED -> R.string.radio_retry
            RadioPhase.PAUSED, RadioPhase.IDLE -> R.string.radio_play
        },
    )
    Box(
        modifier
            .size(size)
            .castivioFocusScale(Motion.focusScaleButton, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(CircleShape)
            .background(colors.primaryBrush)
            .then(if (focused) Modifier.border(FOCUS_RING, colors.focusRing, CircleShape) else Modifier)
            .clickable(interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = when (phase) {
                RadioPhase.PLAYING, RadioPhase.OPENING -> Icons.Rounded.Pause
                RadioPhase.FAILED -> Icons.Rounded.Refresh
                RadioPhase.PAUSED, RadioPhase.IDLE -> Icons.Rounded.PlayArrow
            },
            contentDescription = null,
            tint = colors.onBackground,
            modifier = Modifier.size(size / 2),
        )
    }
}

@Composable
private fun FavoriteButton(plate: RadioPlateState, actions: RadioPlateActions) {
    CastivioIconButton(
        icon = if (plate.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        contentDescription = stringResource(
            if (plate.favorite) R.string.radio_favorite_remove else R.string.radio_favorite_add,
        ),
        onClick = actions.onFavorite,
        tint = if (plate.favorite) CastivioTheme.colors.secondary else null,
    )
}

@Composable
private fun SleepButton(plate: RadioPlateState, actions: RadioPlateActions) {
    CastivioIconButton(
        icon = Icons.Rounded.Timer,
        contentDescription = stringResource(R.string.radio_sleep),
        onClick = actions.onSleep,
        // A running timer is lit in the chosen colour, as a favourite is: the button says it is doing something.
        tint = if (plate.sleeping) CastivioTheme.colors.secondary else null,
    )
}

/** What the plate's second line says: the category and that it is live, or what is wrong with the sound. */
@Composable
private fun plateLine(plate: RadioPlateState): String = when (plate.phase) {
    RadioPhase.PLAYING -> listOfNotNull(plate.category, stringResource(R.string.radio_live)).joinToString(SEPARATOR)
    RadioPhase.OPENING, RadioPhase.IDLE -> stringResource(R.string.radio_connecting)
    RadioPhase.PAUSED -> stringResource(R.string.radio_paused)
    RadioPhase.FAILED -> stringResource(R.string.radio_failed)
}

/** The station's logo, whole, in a frame of its own; the radio mark where the provider sent none. */
@Composable
private fun StationLogo(station: Channel, size: Dp, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.sm)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(size / LOGO_INSET),
        contentAlignment = Alignment.Center,
    ) {
        if (station.artworkUrl.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Rounded.Radio,
                contentDescription = null,
                tint = colors.onBackgroundMuted,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ProviderArtwork(url = station.artworkUrl, description = null, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * The board's end: a picture that says radio where the channels' board has its preview, and under it the plate of what is playing where
 * the guide's card stands -- on every device, as the board is the channels' own.
 *
 * It had the foot of the board on a handset (a bar laid over the wall) and a panel on a television; the stations are rows now, and what is
 * playing has the end of the board whatever the screen. The five buttons the channels keep under their guide card -- guide, favourites,
 * lock, hide, arrange -- are not a station's, and the plate takes their room. Drawn first in `design/mockups/radio-list-ar.html`.
 */
@Composable
internal fun RadioSide(
    plate: RadioPlateState?,
    actions: RadioPlateActions,
    m: ChannelsMetrics,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.guideGap)) {
        RadioPicture(
            live = plate?.phase == RadioPhase.PLAYING,
            m = m,
            modifier = Modifier.fillMaxWidth().weight(PICTURE_SHARE),
        )
        RadioPlateCard(plate, actions, m, Modifier.fillMaxWidth().weight(1f - PICTURE_SHARE))
    }
}

/**
 * What is playing: the station -- its logo, its name, what the sound is doing -- the controls the sound is driven with, and under them the
 * favourite, the sleep timer and, on a handset, the way into the full view. With nothing playing it says what to do instead.
 */
@Composable
private fun RadioPlateCard(
    plate: RadioPlateState?,
    actions: RadioPlateActions,
    m: ChannelsMetrics,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.previewRadius)
    Column(
        modifier
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(m.wellPad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.nameGap, Alignment.CenterVertically),
    ) {
        if (plate == null) {
            RadioIdle(m, Modifier.fillMaxSize())
            return@Column
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.nameGap),
        ) {
            StationLogo(plate.station, size = PLATE_LOGO)
            Column(Modifier.weight(1f)) {
                Text(
                    text = plate.station.title,
                    style = castivioChipStyle(m.frame.fsLabel),
                    color = colors.onBackgroundStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = plateLine(plate),
                    style = castivioBodyStyle(m.frame.fsBody),
                    color = colors.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StreamQualityIcon(quality = plate.quality)
            if (plate.phase == RadioPhase.PLAYING) Equaliser()
        }
        RadioControls(plate, actions, button = Sizing.minTarget(CastivioTheme.device.isTv))
        Row(horizontalArrangement = Arrangement.spacedBy(m.nameGap), verticalAlignment = Alignment.CenterVertically) {
            FavoriteButton(plate, actions)
            SleepButton(plate, actions)
            actions.onOpen?.let { open ->
                CastivioIconButton(
                    icon = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.radio_open_player),
                    onClick = open,
                )
            }
        }
    }
}

/** What the panel says before any station was pressed. */
@Composable
internal fun RadioIdle(m: ChannelsMetrics, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    Column(
        modifier.padding(m.wellPad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.nameGap, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Rounded.Radio,
            contentDescription = null,
            tint = colors.onBackgroundMuted,
            modifier = Modifier.size(PANEL_LOGO / 2),
        )
        Text(
            text = stringResource(R.string.radio_idle_title),
            style = castivioChipStyle(m.frame.fsLabel),
            color = colors.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.radio_idle_detail),
            style = castivioBodyStyle(m.frame.fsBody),
            color = colors.onBackgroundVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The plate opened on a handset: the station beside what it is and its controls, over the board, which keeps playing
 * underneath. Back closes it, and the sound goes on.
 */
@Composable
internal fun RadioNowPlaying(
    plate: RadioPlateState,
    actions: RadioPlateActions,
    m: ChannelsMetrics,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.panelRadius)
    BackHandler(onBack = onClose)
    Box(
        modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .fillMaxWidth(SHEET_WIDTH)
                .fillMaxHeight(SHEET_HEIGHT)
                .clip(shape)
                .background(colors.backgroundElevated)
                .border(1.dp, colors.glassBorderSoft, shape)
                // Presses inside the sheet are the sheet's, not the scrim's.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(m.panelPad),
            horizontalArrangement = Arrangement.spacedBy(m.panelPad),
        ) {
            Box(Modifier.weight(SHEET_LOGO_SHARE).fillMaxHeight(), contentAlignment = Alignment.Center) {
                StationLogo(plate.station, size = SHEET_LOGO)
            }
            RadioNowPlayingInfo(
                plate = plate,
                actions = actions,
                m = m,
                onBack = onClose,
                modifier = Modifier.weight(1f - SHEET_LOGO_SHARE).fillMaxHeight(),
            )
        }
    }
}

/** The name, what the sound is doing, the controls, and the two things to do about the station. */
@Composable
private fun RadioNowPlayingInfo(
    plate: RadioPlateState,
    actions: RadioPlateActions,
    m: ChannelsMetrics,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    // Opened over the board, the way back is at the top and what is played stands in the middle of what is left; in the
    // panel there is no way back to draw, and the whole column is the station's.
    val opened = onBack != null
    Column(modifier) {
        if (onBack != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CastivioIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.radio_close),
                    onClick = onBack,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().then(if (opened) Modifier.weight(1f) else Modifier),
            horizontalAlignment = if (opened) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(m.nameGap * 2, if (opened) Alignment.CenterVertically else Alignment.Top),
        ) {
            RadioNowPlayingBody(plate, actions, m, opened)
        }
    }
}

/** What is under the way back: the name, what the sound is doing, the controls and the two things to do about the station. */
@Composable
private fun RadioNowPlayingBody(
    plate: RadioPlateState,
    actions: RadioPlateActions,
    m: ChannelsMetrics,
    opened: Boolean,
) {
    val colors = CastivioTheme.colors
    Text(
        text = plate.station.title,
        style = castivioTitleStyle(m.frame.fsTitle),
        color = colors.onBackgroundStrong,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (opened) TextAlign.Start else TextAlign.Center,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.nameGap)) {
        if (plate.phase == RadioPhase.PLAYING) Equaliser()
        Text(
            text = plateLine(plate),
            style = castivioBodyStyle(m.frame.fsBody),
            color = colors.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The stream-quality mark, with what the sound is doing: the same mark and the same slot as the bar's.
        StreamQualityIcon(quality = plate.quality)
    }
    RadioControls(plate, actions, button = Sizing.minTarget(CastivioTheme.device.isTv) + m.nameGap * 2)
    // Side by side where the plate is wide, and one above the other where it is the panel's narrow column: two
    // labelled buttons that share a narrow width leave the second a sliver.
    val favorite: @Composable (Modifier) -> Unit = { modifier ->
        CastivioButton(
            text = stringResource(if (plate.favorite) R.string.radio_favorite_remove else R.string.radio_favorite_add),
            onClick = actions.onFavorite,
            modifier = modifier,
            weight = ButtonWeight.Secondary,
            icon = if (plate.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            labelStyle = castivioChipStyle(m.frame.fsChip),
        )
    }
    val sleep: @Composable (Modifier) -> Unit = { modifier ->
        CastivioButton(
            text = stringResource(R.string.radio_sleep),
            onClick = actions.onSleep,
            modifier = modifier,
            weight = ButtonWeight.Secondary,
            icon = Icons.Rounded.Timer,
            labelStyle = castivioChipStyle(m.frame.fsChip),
        )
    }
    if (opened) {
        Row(horizontalArrangement = Arrangement.spacedBy(m.nameGap)) {
            favorite(Modifier)
            sleep(Modifier)
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(m.nameGap)) {
            favorite(Modifier.fillMaxWidth())
            sleep(Modifier.fillMaxWidth())
        }
    }
}

private const val SEPARATOR = " · "

/** How much of the board's end the picture takes, and the plate the rest: the channels' preview and its guide card, about. */
private const val PICTURE_SHARE = 0.46f

/** The station's logo on the plate: a touch target's size, so it is the thing the eye lands on and leaves the controls room. */
private val PLATE_LOGO = 40.dp

/** The share of a logo's frame left empty around it, as the divisor of the frame: a ninth each side. */
private const val LOGO_INSET = 9

private val FOCUS_RING = 2.dp

/** The panel's logo frame: large enough to be the thing the eye lands on, small enough to leave the controls room. */
private val PANEL_LOGO = 120.dp

private val SHEET_LOGO = 150.dp

/** How much of the board the opened plate takes: most of it, so it reads as the board's, not a page of its own. */
private const val SHEET_WIDTH = 0.86f
private const val SHEET_HEIGHT = 0.9f
private const val SHEET_LOGO_SHARE = 0.38f
