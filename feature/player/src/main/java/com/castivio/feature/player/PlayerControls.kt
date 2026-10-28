package com.castivio.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.rememberIsFocused
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.playback.api.AspectMode
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.MediaKind
import java.util.Locale
import kotlinx.coroutines.delay

/* ============================================================================
 * The chrome, as the owner's reference picture draws it.
 *
 * `design/mockups/player-reference.png` is the drawing, and this file is it, in this order:
 *
 *     top     the way back, the title with what is playing written under it, and at the end
 *             the favourite, the list, the sound, the subtitles, the settings and the rest
 *     bottom  the time gone, the bar, the time left -- and under them the transport in the
 *             middle with the playlist at one end and the screen's two controls at the other
 *
 * Live and on-demand are still one screen. Live drops the bar's figures and draws
 * no speed and no subtitles. A control that has
 * nothing behind it for the thing playing is not drawn -- the favourite where there is no
 * item to mark, the playlist and the steps where there is no list to go back to or step
 * through -- because Castivio does not draw what it cannot act on.
 *
 * ## Left to right in every language
 *
 * The reference is drawn that way and a video player is read that way: the bar fills from the
 * left, the earlier arrow points left, the title is at the left. [PlayerScreen] sets the layout
 * direction once for the whole player, and the words inside the controls keep their own
 * direction. Nothing below mirrors anything.
 * ========================================================================= */

/**
 * The way back, the title with what is playing under it, and the controls that are about the
 * film rather than about the playback.
 *
 * The title is the item's own, passed in when the player opened. There is no lookup behind it
 * and no way to perform one -- see [PlayerRequest]. What is under it is [techChips]: the words
 * the engine has reported about the picture and the sound, each only once it is known.
 */
@Composable
internal fun TopBar(state: PlayerState, actions: PlayerActions) {
    val colors = CastivioTheme.colors
    val tv = CastivioTheme.device.isTv
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(PlayerTags.TOP),
        horizontalArrangement = Arrangement.spacedBy(barGap()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerButton(
            icon = CastivioIcons.ArrowBack,
            label = stringResource(R.string.player_back),
            tag = PlayerTags.BACK,
            onClick = actions.onBack,
            shape = ControlShape.Round,
        )

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = Spacing.xs)
                .testTag(PlayerTags.TITLE),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(
                text = state.request.title,
                style = (if (tv) CastivioType.headlineMedium else CastivioType.titleLarge).ltr(),
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ChipRow(state)
        }

        ActionCluster(state, actions)
    }
}

/**
 * What is under the title: the LIVE pill, an episode's number, and the words about the stream.
 *
 * The engine badge is here and nowhere else. A switch the user did not ask for and cannot act
 * on is a fact to report, not a decision to present -- so the second engine is a small word a
 * curious user can find, and nobody else will notice.
 *
 * **No channel number, and no bouquet.** The number was a pill beside LIVE and the owner asked
 * for it to go; the bouquet's name is how a channel was found, and the name above says which
 * channel this is.
 */
@Composable
private fun ChipRow(state: PlayerState) {
    val colors = CastivioTheme.colors
    val chips = techChips(state)
    ChipLine(Modifier.testTag(PlayerTags.CHIPS), gap = Spacing.sm) {
        if (state.request.isLive) {
            Chip(
                text = stringResource(R.string.player_live),
                fill = colors.live,
                ink = colors.onBackground,
                ringed = false,
            )
        }
        if (state.request.kind == MediaKind.SERIES_EPISODE) {
            state.request.subtitle?.let { Chip(text = it, fill = null, ink = colors.onBackgroundVariant, ringed = false) }
        }
        chips.forEach { chip ->
            Chip(
                text = chip.text,
                fill = null,
                ink = if (chip.highlighted) colors.onBackground else colors.onBackgroundVariant,
                ringed = chip.highlighted,
            )
        }
        if (state.engine == EngineId.BACKUP) {
            Chip(
                text = stringResource(R.string.player_engine_backup),
                fill = colors.glassFill,
                ink = colors.onBackgroundVariant,
                ringed = false,
                tag = PlayerTags.ENGINE_BADGE,
            )
        }
    }
}

/**
 * The chips in a line, whole ones only.
 *
 * Laid out in the order they are given and **cut between two chips, never through one**: a row that
 * ran out of width showed `2.0` as a `2` in a box with its edge sliced off, which reads as a fault. The
 * first of them -- the live pill and the class of the picture -- are the ones a narrow screen keeps.
 */
@Composable
private fun ChipLine(modifier: Modifier, gap: Dp, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val loose = Constraints(maxWidth = Constraints.Infinity, maxHeight = constraints.maxHeight)
        val shown = mutableListOf<Placeable>()
        var used = 0
        for (measurable in measurables) {
            val placeable = measurable.measure(loose)
            val next = if (shown.isEmpty()) placeable.width else used + gapPx + placeable.width
            if (next > constraints.maxWidth) break
            used = next
            shown += placeable
        }
        val height = shown.maxOfOrNull { it.height } ?: 0
        layout(used, height) {
            var x = 0
            for (placeable in shown) {
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width + gapPx
            }
        }
    }
}

/** One word in a small rounded box. [ringed] is the first of them, which the reference draws in the focus colour. */
@Composable
private fun Chip(text: String, fill: Color?, ink: Color, ringed: Boolean, tag: String? = null) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.sm)
    Box(
        Modifier
            .clip(shape)
            .then(if (fill != null) Modifier.background(fill) else Modifier.background(colors.glassFill))
            .border(HAIRLINE, if (ringed) colors.focusRing else colors.glassBorder, shape)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Text(text = text, style = CastivioType.labelMedium.ltr(), color = ink, maxLines = 1)
    }
}

/**
 * The controls at the end of the top bar, in the reference's order: the favourite, the sound, the
 * subtitles, the settings, and the rest. The list is not here: it did what Back does, and the owner asked
 * for the one way into the list of channels to be the playlist pill at the foot.
 *
 * Each only where it can act. The favourite needs an item to mark; subtitles are a film's and not a channel's -- a channel plays with its subtitles
 * switched off in both engines, so a button that opened a list of them would offer what is not there.
 */
@Composable
private fun ActionCluster(state: PlayerState, actions: PlayerActions) {
    val live = state.request.isLive
    Row(
        horizontalArrangement = Arrangement.spacedBy(barGap()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val favorite = state.favorite
        val onFavorite = actions.onFavorite
        if (favorite != null && onFavorite != null) {
            PlayerButton(
                icon = if (favorite) CastivioIcons.FavoriteFilled else CastivioIcons.Favorite,
                label = stringResource(if (favorite) R.string.player_favorite_remove else R.string.player_favorite_add),
                tag = PlayerTags.FAVORITE,
                onClick = onFavorite,
                tint = if (favorite) CastivioTheme.colors.secondary else null,
            )
        }
        PlayerButton(
            icon = CastivioIcons.Volume,
            label = stringResource(R.string.player_sheet_audio),
            tag = PlayerTags.AUDIO,
            onClick = { actions.onSheet(Sheet.Audio) },
        )
        if (!live) {
            PlayerButton(
                icon = CastivioIcons.Subtitles,
                label = stringResource(R.string.player_sheet_subtitles),
                tag = PlayerTags.SUBTITLES,
                onClick = { actions.onSheet(Sheet.Subtitles) },
            )
        }
        PlayerButton(
            icon = CastivioIcons.Settings,
            label = stringResource(R.string.player_sheet_settings),
            tag = PlayerTags.SETTINGS,
            // The same press closes it: the popover hangs from this button, and a button that
            // opened something and could not close it would send the viewer looking for a way out.
            onClick = { actions.onSheet(if (state.sheet == Sheet.Settings) null else Sheet.Settings) },
            active = state.sheet == Sheet.Settings,
        )
        PlayerButton(
            icon = CastivioIcons.More,
            label = stringResource(R.string.player_more),
            tag = PlayerTags.MORE,
            onClick = { actions.onSheet(Sheet.More) },
        )
    }
}

/**
 * The bottom: the bar, then the transport. Nothing above the bar -- the reference leaves the picture
 * its whole middle, and a channel's programme strip, which sat there, was removed at the owner's request.
 */
@Composable
internal fun BottomBar(state: PlayerState, actions: PlayerActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(PlayerTags.BOTTOM),
        verticalArrangement = Arrangement.spacedBy(barGap()),
    ) {
        Timeline(state, actions)
        Transport(state, actions)
    }
}

/**
 * The transport: the steps and the jumps round the play control, the playlist at the start edge, the
 * screen's two controls at the other.
 *
 * ## The middle is the middle
 *
 * The three groups share the row by weight, the two outer ones equal, so the play control is on the
 * centre line whatever the ends hold. When an end is too narrow for its labels they are dropped and
 * the icons stay -- the targets never shrink, which is the floor the gate measures.
 *
 * ## What is not drawn
 *
 * **The steps** only where there is a list to step through -- a channel that was pressed in one -- and
 * **the jumps** only where the source can be sought: a channel with no window behind it takes no jump,
 * and a press there was once counted, logged as refused and changed nothing on screen.
 */
@Composable
private fun Transport(state: PlayerState, actions: PlayerActions) {
    val live = state.request.isLive
    val jumps = !live || state.seekable
    val step = actions.onStep
    val tv = CastivioTheme.device.isTv
    val target = Sizing.minTarget(tv)
    val circle = LocalPlayerMetrics.current.step
    // The glyph grows with the circle it is in: the arrows are what the viewer reads, and a 20dp one in
    // an 80dp circle is a speck.
    val glyph = if (circle >= BIG_CIRCLE) Sizing.iconLg else Sizing.iconMd
    val arc = if (circle >= BIG_CIRCLE) BIG_ARC else Sizing.iconXl
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(PlayerTags.TRANSPORT),
        horizontalArrangement = Arrangement.spacedBy(barGapLarge()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(Modifier.weight(1f)) {
            val labelled = maxWidth >= pillGroupWidth(pills = 1, target = target)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(barGap(), Alignment.Start),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.onPlaylist?.let { onPlaylist ->
                    PlayerButton(
                        icon = CastivioIcons.Playlist,
                        label = stringResource(R.string.player_playlist),
                        tag = PlayerTags.PLAYLIST,
                        onClick = onPlaylist,
                        text = if (labelled) stringResource(R.string.player_playlist) else null,
                        shape = ControlShape.Pill,
                        shrinkable = true,
                        // Lit while its panel is open, as the gear is: the press that opened it closes it.
                        active = state.sheet == Sheet.Playlist,
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(barGapLarge()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step != null) {
                PlayerButton(
                    icon = CastivioIcons.PreviousSolid,
                    label = stringResource(R.string.player_previous),
                    tag = PlayerTags.PREVIOUS,
                    onClick = { step(-1) },
                    shape = ControlShape.Round,
                    size = circle,
                    iconSize = glyph,
                )
            }
            if (jumps) {
                PlayerButton(
                    icon = CastivioIcons.ReplayArc,
                    label = stringResource(R.string.player_replay_10),
                    tag = PlayerTags.REPLAY,
                    onClick = { actions.onSeekBy(-JUMP_MS) },
                    shape = ControlShape.Round,
                    size = circle,
                    iconSize = arc,
                    inside = JUMP_WORD,
                )
            }
            PlayControl(state, actions)
            if (jumps) {
                PlayerButton(
                    icon = CastivioIcons.ForwardArc,
                    label = stringResource(R.string.player_forward_10),
                    tag = PlayerTags.FORWARD,
                    onClick = { actions.onSeekBy(JUMP_MS) },
                    shape = ControlShape.Round,
                    size = circle,
                    iconSize = arc,
                    inside = JUMP_WORD,
                )
            }
            if (step != null) {
                PlayerButton(
                    icon = CastivioIcons.NextSolid,
                    label = stringResource(R.string.player_next),
                    tag = PlayerTags.NEXT,
                    onClick = { step(1) },
                    shape = ControlShape.Round,
                    size = circle,
                    iconSize = glyph,
                )
            }
        }

        BoxWithConstraints(Modifier.weight(1f)) {
            val pills = 2 + if (state.request.isLive && state.isTimeshifted) 1 else 0
            val labelled = maxWidth >= pillGroupWidth(pills = pills, target = target)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(barGap(), Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Only when there is something to return *from*. Castivio never draws a disabled
                // control, so a stream at the live edge does not carry this at all.
                if (state.request.isLive && state.isTimeshifted) {
                    PlayerButton(
                        icon = CastivioIcons.Live,
                        label = stringResource(R.string.player_to_live),
                        tag = PlayerTags.TO_LIVE,
                        onClick = actions.onReturnToLive,
                        text = if (labelled) stringResource(R.string.player_to_live) else null,
                        shape = ControlShape.Pill,
                        shrinkable = true,
                    )
                }
                // One press, one change, and the pill says which it is: on the fill the picture takes
                // the whole screen, and pressed again it is fitted to it. The other shapes are in the
                // settings, where the aspect row cycles them.
                PlayerButton(
                    icon = CastivioIcons.Fullscreen,
                    label = stringResource(R.string.player_fill),
                    tag = PlayerTags.FILL,
                    onClick = {
                        actions.onAspect(if (state.aspect == AspectMode.FILL) AspectMode.FIT else AspectMode.FILL)
                    },
                    text = if (labelled) stringResource(R.string.player_fill) else null,
                    shape = ControlShape.Pill,
                    shrinkable = true,
                    active = state.aspect == AspectMode.FILL,
                )
                PlayerButton(
                    icon = if (state.locked) CastivioIcons.Unlock else CastivioIcons.Lock,
                    label = stringResource(if (state.locked) R.string.player_unlock else R.string.player_lock),
                    tag = PlayerTags.LOCK,
                    onClick = { actions.onLock(!state.locked) },
                    text = if (labelled) stringResource(if (state.locked) R.string.player_unlock else R.string.player_lock) else null,
                    shape = ControlShape.Pill,
                    shrinkable = true,
                )
            }
        }
    }
}

/**
 * What [pills] labelled pills ask of the row they share: each its icon, its label's room and its
 * padding, and the gap between them. Below that the labels go and the icons stay.
 */
@Composable
@ReadOnlyComposable
private fun pillGroupWidth(pills: Int, target: Dp): Dp =
    (target + PILL_LABEL_ROOM) * pills + barGap() * (pills - 1)

@Composable
private fun PlayControl(state: PlayerState, actions: PlayerActions) {
    val colors = CastivioTheme.colors
    // What the engine has been *asked* to do, not the last transition it announced. A
    // stall, a rebuffer or a transition it did not classify leaves `picture` saying Playing
    // while nothing is playing, and the control then showed a pause bar over a paused film
    // — the one thing a play control must never do. `picture` remains the fallback for a
    // screen composed from a fixture, which has no engine to ask.
    val playing = state.playRequested ?: (state.picture is Picture.Playing)
    val (focused, focusModifier) = rememberIsFocused()
    // The ring is the brand's ramp, azure into violet, and it is what makes this the one control
    // the eye lands on: the reference draws it round the play control alone.
    val ring = Brush.horizontalGradient(listOf(colors.hueAzure, colors.hueViolet))
    Box(
        Modifier
            .size(playSize())
            .clip(CircleShape)
            .background(colors.glassFillStrong)
            .border(if (focused.value) FOCUS_RING else PLAY_RING, ring, CircleShape)
            .then(focusModifier)
            .clickable(role = Role.Button, onClick = actions.onPlayPause)
            .testTag(PlayerTags.PLAY),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (playing) CastivioIcons.PauseSolid else CastivioIcons.PlaySolid,
            contentDescription = stringResource(
                if (playing) R.string.player_pause else R.string.player_play,
            ),
            tint = colors.onBackground,
            modifier = Modifier.size(maxOf(Sizing.iconXl, playSize() * PLAY_GLYPH)),
        )
    }
}

/**
 * Time gone, bar, time left — and the bar is a control, not a picture.
 *
 * ## The figure at the end is what is left
 *
 * As the reference draws it: `2:17:27` at the start and `43:05` at the end of a film that is three
 * hours long. A length says how big the film is and a remainder says how much of it there is still to
 * watch, which is what a viewer looks at the end of a bar to know.
 *
 * ## Why there is a head on it
 *
 * A head that can be dragged is the way every player on the device does this: the only other way to
 * reach a different part of a film was the jump controls, so a viewer who wanted the last ten minutes
 * of an hour pressed a button sixty times.
 *
 * The touch area is [Sizing.minTarget] tall while the bar stays a few dp. A 4dp target is a quarter
 * of the floor and would be missed more often than hit; the bar people see and the region they touch
 * are deliberately different sizes, which is the ordinary way to build a slider.
 *
 * ## Seeking happens when the finger lifts
 *
 * Not only then: the picture follows the finger, throttled -- see below -- and the last position is
 * sought once more on release. A seek per pointer event is a decode per pointer event, and the picture
 * would never settle long enough to show you where you are.
 *
 * ## Live has no future to scrub into
 *
 * So the bar is full, the head is absent and the figure at its start is how far behind the edge you
 * are -- and no figure at all at the edge -- rather than a timestamp: a timestamp on a live stream is
 * a number about the provider's window and not about the viewer. And there is no figure at the end.
 */
@Composable
private fun Timeline(state: PlayerState, actions: PlayerActions) {
    val colors = CastivioTheme.colors
    val live = state.request.isLive
    val duration = state.durationMs
    val scrubbable = !live && duration != null && duration > 0

    // Where the head is while a finger is on it, and null the rest of the time. Held here
    // rather than pushed to the state holder because it is a gesture in progress and not a
    // fact about playback: nothing outside this row has any business knowing about it.
    var dragged by remember { mutableStateOf<Float?>(null) }
    val direction = LocalLayoutDirection.current
    val touch = Sizing.minTarget(CastivioTheme.device.isTv)

    // The picture follows the finger.
    //
    // Seeking only on release was wrong, and obviously so once it was on a device: dragging
    // a bar with no picture behind it is dragging a number. What a viewer is doing is
    // *looking for a moment*, and they can only recognise it by seeing it.
    //
    // Throttled rather than continuous, because a seek per pointer event is a decode per
    // pointer event and the picture would never settle long enough to be recognised. Keyed
    // on whether a drag is in flight, so this loop exists only while a finger is down.
    LaunchedEffect(dragged != null, duration) {
        if (dragged == null || duration == null) return@LaunchedEffect
        while (true) {
            dragged?.let { actions.onSeekTo((it * duration).toLong()) }
            delay(SCRUB_PREVIEW_MS)
        }
    }

    val position = when {
        dragged != null && duration != null -> (dragged!! * duration).toLong()
        else -> state.positionMs
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(barGapLarge()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A channel at its live edge says nothing here: the bar is full and the pill under the title says
        // LIVE, so a third word beside them was only something else on the screen. It speaks when there is
        // something to tell -- how far behind the edge the viewer is.
        if (!live || state.isTimeshifted) {
            Text(
                text = if (live) {
                    stringResource(R.string.player_behind_live, clock(state.behindLiveMs))
                } else {
                    clock(position)
                },
                style = clockStyle(),
                color = colors.onBackground,
                maxLines = 1,
                modifier = Modifier.testTag(PlayerTags.POSITION),
            )
        }

        val played = when {
            live -> 1f
            dragged != null -> dragged!!
            duration != null && duration > 0 ->
                (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            else -> 0f
        }
        val buffered = when {
            duration != null && duration > 0 ->
                ((state.positionMs + state.bufferedMs).toFloat() / duration).coerceIn(0f, 1f)
            else -> played
        }
        // Read outside the measure scope: `BoxWithConstraints`'s content lambda is not a
        // `@ReadOnlyComposable` context for an ambient read placed inside a modifier chain.
        val thumb = thumbSize()
        // From the first hue of the ramp into the second, and over the length of what is filled -- so
        // the head always sits in the violet and the start of the bar is always the azure.
        val fill = Brush.horizontalGradient(listOf(colors.hueAzure, colors.hueViolet))

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(touch)
                .testTag(PlayerTags.TIMELINE)
                .then(
                    if (!scrubbable) {
                        Modifier
                    } else {
                        // One gesture handler for the press and the drag together, because
                        // to a viewer they are one thing: a tap anywhere on the bar puts the
                        // head there, and keeping the finger down carries it. Two detectors
                        // would race for the same pointer and the tap would be the one to
                        // lose.
                        Modifier.pointerInput(duration, direction) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val width = size.width.toFloat()
                                dragged = fractionAt(down.position.x, width, direction)
                                down.consume()

                                var pointer = down
                                while (pointer.pressed) {
                                    val event = awaitPointerEvent()
                                    pointer = event.changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    dragged = fractionAt(pointer.position.x, width, direction)
                                    if (pointer.positionChanged()) pointer.consume()
                                }

                                val target = dragged
                                dragged = null
                                if (target != null) {
                                    actions.onSeekTo((target * duration!!).toLong())
                                }
                            }
                        }
                    },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(trackHeight())
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(colors.glassFillStrong),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(buffered)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(colors.glassBorder),
                )
                Box(
                    Modifier
                        .fillMaxWidth(played)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(fill),
                )
            }

            if (scrubbable) {
                // `offset` and not `absoluteOffset`: the head travels from the start edge, which is
                // the left in the player's one direction. The invariant script rejects the
                // direction-absolute one, and this is exactly the reason it does.
                Box(
                    Modifier
                        .offset(x = (maxWidth - thumb) * played)
                        .size(thumb)
                        .clip(CircleShape)
                        .background(colors.onBackground)
                        .border(THUMB_RING, colors.hueViolet, CircleShape)
                        .testTag(PlayerTags.THUMB),
                )
            }
        }

        if (!live) {
            Text(
                text = duration?.let { clock((it - position).coerceAtLeast(0)) } ?: stringResource(R.string.stat_unknown),
                style = clockStyle(),
                color = colors.onBackground,
                maxLines = 1,
                modifier = Modifier.testTag(PlayerTags.DURATION),
            )
        }
    }
}

/**
 * Where along the bar a touch landed, as a fraction of the film.
 *
 * Kept direction-aware although the player is drawn in one direction: it costs one comparison, and a
 * bar placed in a right-to-left layout by some other caller would otherwise seek to the opposite end,
 * which looks like a broken control rather than a mirrored one.
 */
private fun fractionAt(x: Float, width: Float, direction: LayoutDirection): Float {
    if (width <= 0f) return 0f
    val fromStart = (x / width).coerceIn(0f, 1f)
    return if (direction == LayoutDirection.Rtl) 1f - fromStart else fromStart
}

/* ------------------------------------------------------------------- the control */

/** The three shapes the reference draws a control in: the way back and the transport, the top bar's squares, the pills. */
internal enum class ControlShape { Round, Square, Pill }

/**
 * One control, and the floor that makes it one.
 *
 * Every interactive thing on this screen but the play control is this composable, so "every target
 * is at least the frame's floor" is a property of one function rather than of thirty call sites. The
 * floor is [Sizing.minTarget], which is 48dp under a thumb and 56dp under a remote — the
 * distinction that was missed twice elsewhere in this product and found both times by eye on a
 * photograph.
 *
 * **Where the remote is.** A control that has focus, or is the one whose panel is open ([active]), is
 * drawn with the focus colour round it and a thicker line, as the reference draws the gear -- the
 * product's one answer to "where am I", and the player had none.
 *
 * [shrinkable] is the pill concession: the *label* may be squeezed and ellipsised, the target may
 * not. `defaultMinSize` rather than `width`, so the minimum survives the squeeze. [inside] is the
 * short figure set over the icon, for the two arrows that carry "10".
 */
@Composable
internal fun RowScope.PlayerButton(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
    text: String? = null,
    shape: ControlShape = ControlShape.Square,
    shrinkable: Boolean = false,
    active: Boolean = false,
    tint: Color? = null,
    inside: String? = null,
    size: Dp? = null,
    iconSize: Dp? = null,
) {
    val colors = CastivioTheme.colors
    val tv = CastivioTheme.device.isTv
    val target = Sizing.minTarget(tv)
    val outline: Shape = when (shape) {
        ControlShape.Round -> CircleShape
        ControlShape.Square -> RoundedCornerShape(Radius.md)
        ControlShape.Pill -> RoundedCornerShape(Radius.pill)
    }
    val (focused, focusModifier) = rememberIsFocused()
    val lit = focused.value || active
    val ink = tint ?: colors.onBackground

    Row(
        modifier = Modifier
            .then(if (shrinkable) Modifier.weight(1f, fill = false) else Modifier)
            .defaultMinSize(minWidth = target, minHeight = target)
            .then(if (size != null) Modifier.size(maxOf(size, target)) else Modifier.height(target))
            .clip(outline)
            .background(colors.glassFillStrong)
            .border(if (lit) FOCUS_RING else HAIRLINE, if (lit) colors.focusRing else colors.glassBorder, outline)
            .then(focusModifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (text == null) Spacing.xs else Spacing.sm)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = ink,
                modifier = Modifier.size(
                    iconSize ?: when {
                        inside != null -> Sizing.iconXl
                        // A remote is read from across a room: the glyph of an icon-only control grows with it.
                        tv && text == null -> Sizing.iconLg
                        else -> Sizing.iconMd
                    },
                ),
            )
            if (inside != null) {
                Text(
                    text = inside,
                    style = (if (tv) CastivioType.labelMedium else CastivioType.labelSmall).ltr()
                        .copy(fontWeight = FontWeight.Bold),
                    color = ink,
                    maxLines = 1,
                )
            }
        }
        if (text != null) {
            Text(
                text = text,
                style = (if (tv) CastivioType.bodyMedium else CastivioType.labelMedium).ltr(),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The words in a control keep their own direction, but start where the control does.
 *
 * The player is laid out left to right in every language and its labels are written in whichever one
 * the viewer chose. A paragraph whose direction is the content's would start at the other edge for
 * an Arabic word and sit away from the icon it belongs to; fixing the paragraph's direction puts it
 * beside the icon and leaves the word itself reading the way it reads.
 */
internal fun TextStyle.ltr(): TextStyle = copy(textDirection = TextDirection.Ltr)

/* ---------------------------------------------------------------------- measures */

/** A hairline is one device-independent pixel, everywhere in the product. */
internal val HAIRLINE = 1.dp

/** The line round a control that has the remote, or whose panel is open. */
private val FOCUS_RING = 2.dp

/** The ring round the play control: heavier than any other edge on the screen, because it is the one that is looked for. */
private val PLAY_RING = 4.dp

/** The ring round the head of the bar. */
private val THUMB_RING = 3.dp

/** Ten seconds, in both directions. */
private const val JUMP_MS = 10_000L

/** The figure inside the two jump arrows. A figure, so it is not translated. */
private const val JUMP_WORD = "10"

/**
 * What a pill's label asks for beyond its target: the longest of the words, at the size they are set.
 *
 * The labels are `Lock the screen`, `Fill the screen` and `Playlist` and their Arabic; when the row
 * they share has less than this per pill the words go, and the icons do not.
 */
private val PILL_LABEL_ROOM = 64.dp

/** The play glyph's share of the control it is in: the reference's solid bars fill a little over a third of the ring. */
private const val PLAY_GLYPH = 0.38f

/** The transport's circles are drawn with the larger glyph from this size up. */
private val BIG_CIRCLE = 64.dp

/** The jump arrows in those circles: the reference draws them about half the circle across, with the figure inside. */
private val BIG_ARC = 40.dp

@Composable
@ReadOnlyComposable
private fun trackHeight(): Dp = LocalPlayerMetrics.current.track

/** The head on the bar. Large enough to see against a bright frame, small enough not to hide it. */
@Composable
@ReadOnlyComposable
private fun thumbSize(): Dp = LocalPlayerMetrics.current.thumb

/**
 * How often the picture catches up with the finger during a drag.
 *
 * Six frames of a 24fps film. Fast enough that the picture reads as following the finger,
 * slow enough that each decode has time to produce something a person can recognise — a
 * seek per pointer event would leave the decoder permanently one request behind and the
 * screen permanently blank, which is the worse of the two failures.
 */
private const val SCRUB_PREVIEW_MS = 250L

/*
 * The four sizes the transport is built on.
 *
 * They were `if (CastivioTheme.device.isTv)` — 16 against 8, 24 against 16, 80 against
 * 64, 56 against 44 — which is a two-row device table asked of an ambient. They are
 * shares of the measured surface now, from the same ambient with the surface in it; a
 * television still draws 16, 24, 80 and 56, and every size between the two rows is
 * interpolated rather than rounded to whichever row the box falls into.
 */

@Composable
@ReadOnlyComposable
internal fun barGap(): Dp = LocalPlayerMetrics.current.barGap

@Composable
@ReadOnlyComposable
internal fun barGapLarge(): Dp = LocalPlayerMetrics.current.barGapLarge

/**
 * The play control, larger than its neighbours on purpose.
 *
 * The drawing puts it at 64dp on a handset and 80 on a television — the one control a thumb
 * finds without looking, and the one a remote lands on first. Its floor is 64 rather than
 * the device's own target, because "larger than its neighbours" is the design and a
 * control that shrank to the floor would stop being it.
 */
@Composable
@ReadOnlyComposable
private fun playSize(): Dp = LocalPlayerMetrics.current.play

/** The figures at the two ends of the bar: larger under a remote, which is read from across a room. */
@Composable
@ReadOnlyComposable
private fun clockStyle(): TextStyle =
    (if (CastivioTheme.device.isTv) CastivioType.bodyLarge else CastivioType.labelMedium).ltr()

/**
 * A duration as digits.
 *
 * Formatted here and not in composition for the reason `CLAUDE.md` gives, and hours are
 * dropped when there are none — "0:04:12" on a four-minute delay reads as a bug.
 */
internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    // Western digits in every language, as the reference draws them: `2:17:27`, never `٢:١٧:٢٧`.
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/** "1.0x" — a figure, so it is built rather than translated, and in Western digits like the clock. */
internal fun speedLabel(speed: Float): String = String.format(Locale.ROOT, "%.1fx", speed)
