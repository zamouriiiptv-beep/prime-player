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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing

/* ============================================================================
 * The three bands.
 *
 * Live and on-demand are one screen, and that is a product decision rather than an
 * economy: a user who watches both should not have to learn the player twice. Live swaps
 * three things and adds one — the timeline becomes a live position, previous/next step
 * channels rather than chapters, the title gains a number and a LIVE pill, and a programme
 * strip appears above the timeline.
 * ========================================================================= */

/**
 * Title, and the three controls that are not about playback.
 *
 * The title is the item's own, passed in when the player opened. There is no lookup behind
 * it and no way to perform one — see [PlayerRequest].
 */
@Composable
internal fun TopBar(state: PlayerState, actions: PlayerActions) {
    val colors = CastivioTheme.colors
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
            mirror = true,
        )

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = Spacing.xs)
                .testTag(PlayerTags.TITLE),
        ) {
            Text(
                text = state.request.title,
                style = CastivioType.titleMedium,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SubtitleRow(state)
        }

        PlayerButton(
            icon = if (state.locked) CastivioIcons.Unlock else CastivioIcons.Lock,
            label = stringResource(
                if (state.locked) R.string.player_unlock else R.string.player_lock,
            ),
            tag = PlayerTags.LOCK,
            onClick = { actions.onLock(!state.locked) },
        )
        // Was a cast control, and cast is not built: the button carried the mark for
        // sending a picture to a television and did nothing at all, because nothing on the
        // route ever bound `onCast`. It shares what is playing, which is a thing this
        // product can actually do today, and it says so with its own icon.
        PlayerButton(
            icon = CastivioIcons.Share,
            label = stringResource(R.string.player_share),
            tag = PlayerTags.CAST,
            onClick = actions.onShare,
        )
        PlayerButton(
            icon = CastivioIcons.More,
            label = stringResource(R.string.player_more),
            tag = PlayerTags.MORE,
            onClick = { actions.onSheet(Sheet.Settings) },
        )
    }
}

/**
 * What is under the title: the LIVE pill and a channel number, or the item's own subtitle.
 *
 * The engine badge is here and nowhere else. A switch the user did not ask for and cannot
 * act on is a fact to report, not a decision to present — so the second engine is a small
 * word a curious user can find, and nobody else will notice.
 */
@Composable
private fun SubtitleRow(state: PlayerState) {
    val colors = CastivioTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.request.isLive) {
            Pill(
                text = stringResource(R.string.player_live),
                fill = colors.live,
                ink = colors.onBackground,
            )
        }
        state.request.channelNumber?.let {
            Pill(text = it, fill = colors.glassFillStrong, ink = colors.onBackground)
        }
        state.request.subtitle?.let {
            Text(
                text = it,
                style = CastivioType.bodySmall,
                color = colors.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.engine == com.castivio.playback.api.EngineId.BACKUP) {
            Pill(
                text = stringResource(R.string.player_engine_backup),
                fill = colors.glassFill,
                ink = colors.onBackgroundVariant,
                tag = PlayerTags.ENGINE_BADGE,
            )
        }
    }
}

@Composable
private fun Pill(text: String, fill: Color, ink: Color, tag: String? = null) {
    Box(
        Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(fill)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Text(text = text, style = CastivioType.labelSmall, color = ink, maxLines = 1)
    }
}

/**
 * The centre: step back, play, step on.
 *
 * ## Why the ten-second jumps are no longer here
 *
 * They were, either side of the play control, and they were asked to move to the bottom row
 * — where the timeline and every other control already live. Over the picture the middle of
 * the screen is the worst place for something pressed repeatedly: the thumb covers the part
 * of the film the viewer is looking at while trying to find the moment they want. The bar
 * and the controls that move along it now sit together, out of the way of the frame.
 *
 * The play control is [Sizing.minTarget] plus a margin rather than the same size as its
 * neighbours: it is the one control a thumb finds without looking, and it is the one thing
 * that has earned the middle of the picture.
 */
@Composable
internal fun CentreCluster(state: PlayerState, actions: PlayerActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(PlayerTags.CENTRE),
        horizontalArrangement = Arrangement.spacedBy(barGapLarge(), Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerButton(
            icon = CastivioIcons.Previous,
            label = stringResource(R.string.player_previous),
            tag = PlayerTags.PREVIOUS,
            onClick = actions.onPrevious,
            mirror = true,
        )
        PlayControl(state, actions)
        PlayerButton(
            icon = CastivioIcons.Next,
            label = stringResource(R.string.player_next),
            tag = PlayerTags.NEXT,
            onClick = actions.onNext,
            mirror = true,
        )
    }
}

@Composable
private fun PlayControl(state: PlayerState, actions: PlayerActions) {
    val colors = CastivioTheme.colors
    val playing = state.picture is Picture.Playing
    Box(
        Modifier
            .size(playSize())
            .clip(CircleShape)
            .background(colors.glassFillStrong)
            .border(HAIRLINE, colors.glassBorder, CircleShape)
            .clickable(role = Role.Button, onClick = actions.onPlayPause)
            .testTag(PlayerTags.PLAY),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (playing) CastivioIcons.Pause else CastivioIcons.Play,
            contentDescription = stringResource(
                if (playing) R.string.player_pause else R.string.player_play,
            ),
            tint = colors.onBackground,
            modifier = Modifier.size(Sizing.iconXl),
        )
    }
}

/**
 * The bottom: programme strip, timeline, tools.
 *
 * The strip is first because it belongs to the channel rather than to the playback, and
 * because putting it above the timeline is what lets it change height without moving the
 * controls — which it never does, but the arrangement is what makes that true rather than
 * lucky.
 */
@Composable
internal fun BottomBar(state: PlayerState, actions: PlayerActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(PlayerTags.BOTTOM),
        verticalArrangement = Arrangement.spacedBy(barGap()),
    ) {
        if (state.request.isLive) ProgrammeStrip(state)
        Timeline(state, actions)
        ToolsRow(state, actions)
    }
}

/**
 * What is on now — and the height it takes whether or not anybody knows yet.
 *
 * ## The single most important thing in this file
 *
 * The strip is composed at a **fixed height**, and the guide fills words into it later. It
 * is not composed when the guide arrives; it is there from the first frame, as a skeleton.
 *
 * That is what makes the EPG genuinely off the critical path rather than nominally off it.
 * A strip that appeared when the data did would push the timeline and the tools row down
 * by 44dp a second after the channel opened — the user's thumb already moving toward a
 * control that is no longer where they saw it. Reserving the height costs 44dp of picture
 * and buys the guarantee that nothing moves.
 *
 * `PlayerEpgTest` measures this element in both states and fails if the two differ.
 */
@Composable
private fun ProgrammeStrip(state: PlayerState) {
    val colors = CastivioTheme.colors
    val programme = state.programme

    Row(
        Modifier
            .fillMaxWidth()
            .height(stripHeight())
            .clip(RoundedCornerShape(Radius.md))
            .background(colors.glassFill)
            .border(HAIRLINE, colors.glassBorder, RoundedCornerShape(Radius.md))
            .padding(horizontal = Spacing.md)
            .testTag(PlayerTags.EPG),
        horizontalArrangement = Arrangement.spacedBy(barGapLarge()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SkeletonText(
                text = programme?.now,
                style = CastivioType.bodyLarge,
                colour = colors.onBackground,
                width = SKELETON_TITLE,
            )
            SkeletonText(
                text = programme?.window,
                style = CastivioType.bodySmall,
                colour = colors.onBackgroundVariant,
                width = SKELETON_WINDOW,
            )
        }

        // The progress bar is drawn either way and is simply empty when unknown. A bar
        // that appears with the words would be a second thing moving.
        Box(
            Modifier
                .width(PROGRESS_WIDTH)
                .height(PROGRESS_HEIGHT)
                .clip(RoundedCornerShape(Radius.pill))
                .background(colors.glassFillStrong),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(programme?.progress ?: 0f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(colors.focusRing),
            )
        }

        programme?.next?.let {
            Text(
                text = it,
                style = CastivioType.bodySmall,
                color = colors.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(NEXT_WEIGHT),
            )
        }
    }
}

/**
 * A line of type, or the space it will take.
 *
 * The skeleton is the same height as the text because it is the same text node with a
 * transparent ink and a filled background — not a `Box` sized by hand to something that
 * looks about right. Two ways of computing one height is how the reserved height stops
 * being reserved.
 */
@Composable
private fun SkeletonText(
    text: String?,
    style: androidx.compose.ui.text.TextStyle,
    colour: Color,
    width: Dp,
) {
    val colors = CastivioTheme.colors
    Text(
        text = text ?: " ",
        style = style,
        color = if (text != null) colour else Color.Transparent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (text != null) {
            Modifier
        } else {
            Modifier
                .width(width)
                .clip(RoundedCornerShape(Radius.xs))
                .background(colors.glassFillStrong)
        },
    )
}

/**
 * Position, bar, duration — and the bar is a control, not a picture.
 *
 * ## Why there is a head on it now
 *
 * The bar was a 4dp read-out: it showed where the film was and there was no way to move it.
 * The only way to reach a different part of a film was the two jump controls, so a viewer
 * who wanted the last ten minutes of an hour pressed a button sixty times — or, when those
 * controls did not answer, had no way at all. A head that can be dragged is the way every
 * player on the device does this, and it is what was asked for.
 *
 * The touch area is [Sizing.minTarget] tall while the bar stays 4dp. A 4dp target is a
 * quarter of the floor and would be missed more often than hit; the bar people see and the
 * region they touch are deliberately different sizes, which is the ordinary way to build a
 * slider and the reason the floor gate keeps passing.
 *
 * ## Seeking happens when the finger lifts
 *
 * Not continuously while dragging. The head follows the finger — [dragged] is what the row
 * displays while a drag is in flight, so it never lags — but the engine is asked once. A
 * seek per frame is a decode from a new position per frame, which on a stream over a
 * mobile connection is a request per frame; the picture would never settle long enough to
 * show you where you are, which is the whole purpose of dragging it.
 *
 * ## Live has no future to scrub into
 *
 * So the bar is full, the head is absent and the figure says "live now" or how far behind
 * the edge you are, rather than a timestamp — a timestamp on a live stream is a number
 * about the provider's window and not about the viewer.
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

    val position = when {
        dragged != null && duration != null -> (dragged!! * duration).toLong()
        else -> state.positionMs
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(barGapLarge()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                live && state.isTimeshifted ->
                    stringResource(R.string.player_behind_live, clock(state.behindLiveMs))
                live -> stringResource(R.string.player_live_now)
                else -> clock(position)
            },
            style = CastivioType.labelMedium,
            color = colors.onBackground,
            maxLines = 1,
            modifier = Modifier.testTag(PlayerTags.POSITION),
        )

        // The jumps live on the bar, one at each end of it.
        //
        // They were in the tools row, among the aspect, the speed and the subtitles — the
        // controls you touch while setting something up rather than while watching. A jump
        // is not one of those: it is the bar, in ten-second steps, for the times when
        // dragging is too coarse. Put either side of the thing they move, what they do is
        // legible from where they are, which is most of what "understood at a glance" means.
        //
        // `mirror`, because the bar itself mirrors: in Arabic it fills from the right, so
        // later in the film is to the left and a chevron that pointed left in both
        // directions would be pointing backwards in one of them.
        PlayerButton(
            icon = CastivioIcons.Replay10,
            label = stringResource(R.string.player_replay_10),
            tag = PlayerTags.REPLAY,
            onClick = { actions.onSeekBy(-JUMP_MS) },
            text = JUMP_SECONDS,
            mirror = true,
        )

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
                    .height(TRACK_HEIGHT)
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
                        .background(colors.primaryBrush),
                )
            }

            if (scrubbable) {
                // `offset` and not `absoluteOffset`: the head travels from the start edge,
                // which is the right-hand side in Arabic. The invariant script rejects the
                // direction-absolute one, and this is exactly the reason it does.
                Box(
                    Modifier
                        .offset(x = (maxWidth - THUMB) * played)
                        .size(THUMB)
                        .clip(CircleShape)
                        .background(colors.primaryBrush)
                        .testTag(PlayerTags.THUMB),
                )
            }
        }

        PlayerButton(
            icon = CastivioIcons.Forward10,
            label = stringResource(R.string.player_forward_10),
            tag = PlayerTags.FORWARD,
            onClick = { actions.onSeekBy(JUMP_MS) },
            text = JUMP_SECONDS,
            mirror = true,
        )

        if (!live) {
            Text(
                text = duration?.let(::clock) ?: stringResource(R.string.stat_unknown),
                style = CastivioType.labelMedium,
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
 * Mirrored in Arabic, and that is not a detail: the bar fills from the right, so a touch
 * near the right-hand edge is the *beginning* of the film. Reading it left-to-right would
 * make every drag seek to the opposite end, which looks like a broken control rather than
 * a mirrored one.
 */
private fun fractionAt(x: Float, width: Float, direction: LayoutDirection): Float {
    if (width <= 0f) return 0f
    val fromStart = (x / width).coerceIn(0f, 1f)
    return if (direction == LayoutDirection.Rtl) 1f - fromStart else fromStart
}

/**
 * The one row whose contents vary by state.
 *
 * Live adds a guide and a channel list; a stream that has fallen behind adds the way back
 * to the edge. On a television at 956dp with Arabic labels this ran past the safe area, so
 * the row is allowed to **shrink its items rather than push them out**: the labels
 * ellipsis, the targets never do. That is the whole of the fix, and it is why the wide
 * buttons carry `weight` with a floor rather than a fixed width.
 *
 * "Back to live" is in this row and not in the time row above it. It was drawn inline there
 * first, at 32dp, which measured as the only control in the player under the touch floor.
 */
@Composable
private fun ToolsRow(state: PlayerState, actions: PlayerActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(PlayerTags.TOOLS),
        horizontalArrangement = Arrangement.spacedBy(barGap()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerButton(
            icon = CastivioIcons.Speed,
            label = stringResource(R.string.player_speed),
            tag = PlayerTags.SPEED,
            onClick = { actions.onSheet(Sheet.Settings) },
            text = speedLabel(state.speed),
            shrinkable = true,
        )
        PlayerButton(
            icon = CastivioIcons.Subtitles,
            label = stringResource(R.string.player_sheet_subtitles),
            tag = PlayerTags.SUBTITLES,
            onClick = { actions.onSheet(Sheet.Subtitles) },
        )
        PlayerButton(
            icon = CastivioIcons.AudioTrack,
            label = stringResource(R.string.player_sheet_audio),
            tag = PlayerTags.AUDIO,
            onClick = { actions.onSheet(Sheet.Audio) },
        )
        // One press, one change, and the button says which fit is on — the same shape as
        // the speed control beside it. It used to open the settings sheet onto a row that
        // printed "Fit" and did nothing, so the picture could not be corrected from
        // anywhere in the player.
        PlayerButton(
            icon = CastivioIcons.Aspect,
            label = stringResource(R.string.player_aspect),
            tag = PlayerTags.ASPECT,
            onClick = { actions.onAspect(nextAspect(state.aspect)) },
            text = aspectLabel(state.aspect),
            shrinkable = true,
        )

        if (state.request.isLive) {
            PlayerButton(
                icon = CastivioIcons.Guide,
                label = stringResource(R.string.player_guide),
                tag = PlayerTags.GUIDE,
                onClick = actions.onGuide,
            )
            PlayerButton(
                icon = CastivioIcons.Channels,
                label = stringResource(R.string.player_channels),
                tag = PlayerTags.CHANNELS,
                onClick = actions.onChannels,
            )
        }

        // Only when there is something to return *from*. Castivio never draws a disabled
        // control, so a stream at the live edge does not carry this at all.
        if (state.request.isLive && state.isTimeshifted) {
            PlayerButton(
                icon = CastivioIcons.Live,
                label = stringResource(R.string.player_to_live),
                tag = PlayerTags.TO_LIVE,
                onClick = actions.onReturnToLive,
                text = stringResource(R.string.player_to_live),
                shrinkable = true,
            )
        }

        Box(Modifier.weight(1f))

        PlayerButton(
            icon = CastivioIcons.Quality,
            label = stringResource(R.string.player_sheet_quality),
            tag = PlayerTags.QUALITY,
            onClick = { actions.onSheet(Sheet.Quality) },
        )
        PlayerButton(
            icon = CastivioIcons.Fullscreen,
            label = stringResource(R.string.player_fullscreen),
            tag = PlayerTags.FULLSCREEN,
            onClick = actions.onFullscreen,
        )
    }
}

/* ------------------------------------------------------------------- the control */

/**
 * One control, and the floor that makes it one.
 *
 * Every interactive thing on this screen is this composable, so "every target is at least
 * the frame's floor" is a property of one function rather than of thirty call sites. The
 * floor is [Sizing.minTarget], which is 48dp under a thumb and 56dp under a remote — the
 * distinction that was missed twice elsewhere in this product and found both times by eye
 * on a photograph.
 *
 * [shrinkable] is the tools-row concession: the *label* may be squeezed and ellipsised, the
 * target may not. `widthIn(min = …)` rather than `width(…)`, so the minimum survives the
 * squeeze.
 */
@Composable
internal fun RowScope.PlayerButton(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
    text: String? = null,
    mirror: Boolean = false,
    shrinkable: Boolean = false,
) {
    val colors = CastivioTheme.colors
    val target = Sizing.minTarget(CastivioTheme.device.isTv)
    val mirrored = mirror && LocalLayoutDirection.current == LayoutDirection.Rtl

    Row(
        modifier = Modifier
            .then(if (shrinkable) Modifier.weight(1f, fill = false) else Modifier)
            .defaultMinSize(minWidth = target, minHeight = target)
            .height(target)
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.glassFillStrong)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (text == null) Spacing.xs else Spacing.md)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.onBackground,
            modifier = Modifier
                .size(Sizing.iconMd)
                .scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f),
        )
        if (text != null) {
            Text(
                text = text,
                style = CastivioType.labelMedium,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ---------------------------------------------------------------------- measures */

/** A hairline is one device-independent pixel, everywhere in the product. */
internal val HAIRLINE = 1.dp

/** Ten seconds, in both directions. */
private const val JUMP_MS = 10_000L

/**
 * The same ten, as the label beside the chevrons.
 *
 * A bare numeral rather than a string resource, and deliberately: it is a figure, it is
 * derived from [JUMP_MS] so the two cannot drift, and every language writes it the same way
 * — the digit shapes come from the locale's own font, which is what a translation of "10"
 * would have had to reproduce by hand.
 */
private val JUMP_SECONDS = (JUMP_MS / 1000L).toString()

private val TRACK_HEIGHT = 4.dp

/** The head on the bar. Large enough to see against a bright frame, small enough not to hide it. */
private val THUMB = 14.dp
private val PROGRESS_WIDTH = 96.dp
private val PROGRESS_HEIGHT = 3.dp
private val SKELETON_TITLE = 148.dp
private val SKELETON_WINDOW = 92.dp
private const val NEXT_WEIGHT = 0.9f

@Composable
@ReadOnlyComposable
internal fun barGap(): Dp = if (CastivioTheme.device.isTv) Spacing.lg else Spacing.sm

@Composable
@ReadOnlyComposable
internal fun barGapLarge(): Dp = if (CastivioTheme.device.isTv) Spacing.xl else Spacing.lg

/**
 * The play control, larger than its neighbours on purpose.
 *
 * The drawing puts it at 64dp on a handset and 80 on a television — the one control a thumb
 * finds without looking, and the one a remote lands on first.
 */
@Composable
@ReadOnlyComposable
private fun playSize(): Dp = if (CastivioTheme.device.isTv) 80.dp else 64.dp

/** The strip's reserved height. A constant, because "reserved" means it cannot vary. */
@Composable
@ReadOnlyComposable
private fun stripHeight(): Dp = if (CastivioTheme.device.isTv) 56.dp else 44.dp

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
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "1.0×" — a figure, so it is built rather than translated. */
internal fun speedLabel(speed: Float): String = "%.1f×".format(speed)
