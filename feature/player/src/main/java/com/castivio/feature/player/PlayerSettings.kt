package com.castivio.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.rememberIsFocused
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.playback.api.Track
import java.util.Locale

/* ============================================================================
 * The settings, as the reference draws them: a small panel hanging from the gear, five rows, each with
 * what it is called, what it is now, and an arrow.
 *
 * It is not a sheet. A sheet is a list of choices that covers the end edge of the film; this is a list of
 * *questions*, each answered by the row itself or by the sheet it opens, and it covers a corner. Speed and
 * the shape of the picture are answered by the row -- one press steps to the next value and the value
 * beside the name says where it is -- and the quality, the sound and the subtitles are answered by the
 * sheets they always had.
 * ========================================================================= */

/** One line of the popover. [value] is what the setting is now; null when nothing has been reported. */
private data class SettingRow(
    val key: String,
    val icon: ImageVector,
    val name: String,
    val value: String?,
    val onPick: () -> Unit,
)

/**
 * The panel under the gear.
 *
 * Laid out in the picture's own box by the caller, so it hangs from the gear even when the film is
 * letterboxed and the top bar is narrower than the window. The first row takes the remote when the panel
 * opens: the viewer pressed the gear to reach what is in it, and a panel whose first move is another press
 * to find it would be the player's one control that needed two.
 */
@Composable
internal fun BoxScope.SettingsPopover(state: PlayerState, actions: PlayerActions) {
    val colors = CastivioTheme.colors
    val m = LocalPlayerMetrics.current
    val shape = RoundedCornerShape(Radius.lg)
    val rows = settingRows(state, actions)
    val first = remember { FocusRequester() }

    // `requestFocus` throws when the node is not attached yet, which a first frame can be; the next
    // press of the remote then finds the panel by position, as it would have anyway.
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(
        Modifier
            .align(Alignment.TopEnd)
            // Under the top bar, not over it: its inset, its height and the gap the bar keeps from
            // what is below it, so the gear stays visible and lit above the panel it opened.
            .padding(
                top = m.inset + Sizing.minTarget(CastivioTheme.device.isTv) + barGap(),
                end = m.inset,
            )
            .width(m.popover)
            .clip(shape)
            // Over a ground of its own: the film and the controls under it must not show through the rows.
            .background(colors.background.copy(alpha = POPOVER_ALPHA))
            .border(HAIRLINE, colors.glassBorder, shape)
            .padding(Spacing.xs)
            .testTag(PlayerTags.POPOVER),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        rows.forEachIndexed { index, row ->
            SettingLine(row, if (index == 0) Modifier.focusRequester(first) else Modifier)
        }
    }
}

@Composable
private fun SettingLine(row: SettingRow, modifier: Modifier) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.md)
    val (focused, focusModifier) = rememberIsFocused()
    // The remote's row is the one the reference draws in the brand's ramp with the focus colour round it.
    val lit = focused.value
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Sizing.minTarget(CastivioTheme.device.isTv))
            .clip(shape)
            .then(
                if (lit) {
                    Modifier.background(
                        Brush.horizontalGradient(
                            listOf(colors.hueAzure.copy(alpha = LIT_ALPHA), colors.hueViolet.copy(alpha = LIT_ALPHA)),
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .then(if (lit) Modifier.border(LIT_RING, colors.focusRing, shape) else Modifier)
            .then(focusModifier)
            .clickable(role = Role.Button, onClick = row.onPick)
            .padding(horizontal = Spacing.md)
            .testTag(PlayerTags.popoverRow(row.key)),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = row.icon,
            contentDescription = null,
            tint = colors.onBackground,
            modifier = Modifier.size(Sizing.iconMd),
        )
        Text(
            text = row.name,
            style = CastivioType.bodyLarge.ltr(),
            color = colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        row.value?.let {
            Text(
                text = it,
                style = CastivioType.bodySmall.ltr(),
                color = colors.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(VALUE_WEIGHT, fill = false),
            )
        }
        Icon(
            imageVector = CastivioIcons.Chevron,
            contentDescription = null,
            tint = colors.onBackgroundVariant,
            modifier = Modifier.size(Sizing.iconSm),
        )
    }
}

/**
 * The rows, in the reference's order, for this stream.
 *
 * **Speed and subtitles are a film's.** A channel has no length to play faster across and plays with its
 * subtitles off in both engines, so the rows are not drawn rather than drawn and refused -- the same rule
 * the top bar applies to the same two controls.
 */
@Composable
private fun settingRows(state: PlayerState, actions: PlayerActions): List<SettingRow> {
    val live = state.request.isLive
    val quality = stringResource(R.string.player_quality_auto)
    val off = stringResource(R.string.player_setting_off)
    // Worked out when the tracks change and not on every tick of the clock the state also carries.
    val locale = Locale.getDefault()
    val soundValue = remember(state.audioTracks, locale) { soundValue(state.audioTracks, locale) }
    val subtitleValue = state.downloadedSubtitle
        ?: remember(state.subtitleTracks, locale) {
            state.subtitleTracks.firstOrNull { it.selected }?.let { soundName(it, locale) }
        }
        ?: off
    val aspectValue = aspectLabel(state.aspect)
    val speedValue = speedLabel(state.speed)

    return buildList {
        add(
            SettingRow(
                key = "quality",
                icon = CastivioIcons.Quality,
                name = stringResource(R.string.player_sheet_quality),
                value = quality,
                onPick = { actions.onSheet(Sheet.Quality) },
            ),
        )
        add(
            SettingRow(
                key = "audio",
                icon = CastivioIcons.AudioTrack,
                name = stringResource(R.string.player_sheet_audio),
                value = soundValue,
                onPick = { actions.onSheet(Sheet.Audio) },
            ),
        )
        if (!live) {
            add(
                SettingRow(
                    key = "subtitles",
                    icon = CastivioIcons.Subtitles,
                    name = stringResource(R.string.player_sheet_subtitles),
                    value = subtitleValue,
                    onPick = { actions.onSheet(Sheet.Subtitles) },
                ),
            )
        }
        add(
            SettingRow(
                key = "aspect",
                icon = CastivioIcons.Aspect,
                name = stringResource(R.string.player_aspect),
                value = aspectValue,
                // Reads the mode and sets the next one, exactly as the speed row does.
                onPick = { actions.onAspect(nextAspect(state.aspect)) },
            ),
        )
        if (!live) {
            add(
                SettingRow(
                    key = "speed",
                    icon = CastivioIcons.Speed,
                    name = stringResource(R.string.player_speed),
                    value = speedValue,
                    onPick = { actions.onSpeed(nextSpeed(state.speed)) },
                ),
            )
        }
    }
}

/**
 * A sound or a subtitle as a person would name it: the container's label when it says more than the
 * language code, otherwise the language written out in the viewer's own language.
 *
 * `fr` becomes `French`, or `Français` for a viewer who reads French; a label the container wrote --
 * `Director's commentary` -- is kept as written. A track that names neither is shown under whatever
 * label it has, which is the engine's own word for it.
 */
internal fun soundName(track: Track, locale: Locale): String {
    val language = track.language?.trim().orEmpty()
    val label = track.label.trim()
    if (label.isNotEmpty() && !label.equals(language, ignoreCase = true)) return label
    val written = language
        .takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(locale) }
        ?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }
    return written?.replaceFirstChar { it.titlecase(locale) } ?: label.ifEmpty { language }
}

/**
 * The selected sound as the row shows it: its name, as the reference draws `English`. Its layout is under
 * the title, in the chips, and is not said twice. Null when the engine has reported no sound at all, which
 * leaves the row with its name alone.
 */
internal fun soundValue(tracks: List<Track>, locale: Locale): String? {
    val track = tracks.firstOrNull { it.selected } ?: tracks.firstOrNull() ?: return null
    return soundName(track, locale).ifEmpty { null }
}

/** The line round the row the remote is on, the same weight the top bar's controls take. */
private val LIT_RING = 2.dp

/** The share of the focused row's fill that is the brand's ramp; the rest is the panel behind it. */
private const val LIT_ALPHA = 0.85f

/** How much of the film shows through the panel: a little, so it reads as glass and not as a hole. */
private const val POPOVER_ALPHA = 0.94f

/** A value may take the room a long name leaves it, and is ellipsised before it pushes the arrow out. */
private const val VALUE_WEIGHT = 1f
