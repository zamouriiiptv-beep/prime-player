package com.castivio.feature.home

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.CastivioIconButton
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.core.platform.DevDiagnostics
import com.castivio.core.platform.LoadTrace
import com.castivio.core.platform.PerfSection
import com.castivio.core.platform.StripLinger
import com.castivio.core.platform.StripLine
import com.castivio.core.platform.StripSummary
import com.castivio.core.platform.StripTone
import kotlinx.coroutines.delay

/**
 * **Temporary, for development.** The measurement of a section's load, as a small card over the foot of Home's stage or
 * over the rows at the top of the section -- see [LoadTrace] for what each figure is and what it is counted from.
 *
 * Draws nothing unless there is a session to show, and there is none unless the instruments are on: a debuggable
 * build, and nothing else. [section] null is Home's, the session pressed last; a section's own shows only while its
 * session runs ([runningOnly]).
 *
 * ## Two sizes, one report
 *
 * **Collapsed**, a card about 70dp tall: what was measured, the total, how it stands and its categories, in a glance. A
 * press opens it to **every line the measurement has**, none left out -- the stages, the wire, the work, the pipeline,
 * the processor by thread -- and a second press closes it again. The card is only another way to read the same lines;
 * [LoadTrace] measures and records exactly as it did, and nothing is formatted here.
 *
 * ## When it is there
 *
 * A new measurement brings the card, collapsed. It stays while the measurement runs and for [StripLinger.LINGER_MS]
 * after it ends; while it is open, or focused with a remote, it does not go, and the time begins again when it is let go.
 * The small button hides it for the measurement it is showing: nothing is un-recorded -- the log keeps its lines and the
 * report its figures -- and the next measurement brings the card back.
 *
 * ## With a remote
 *
 * On Home the card sits over the footer, below the board, and it and its button are focusable: Down from the board reaches
 * the card and the press key opens it; closing hands the focus back up to the board. The board is not moved or resized by
 * it, and what it covers is the footer's facts, which are not controls. A section's own strip is only on screen while it loads and goes by itself, so there it is for a finger and takes no
 * focus: nothing the viewer is on can disappear under the remote.
 *
 * Always left to right: it is an instrument, written in Latin figures a photograph carries intact, and in a
 * right-to-left layout a line of figures reads in an order nobody wrote.
 */
@Composable
internal fun LoadStrip(
    section: PerfSection?,
    runningOnly: Boolean,
    fontSize: Dp,
    modifier: Modifier = Modifier,
) {
    // Off in every build that is not debuggable: nothing is collected, and nothing is drawn.
    if (!DevDiagnostics.enabled) return
    val lines by remember(section, runningOnly) { LoadTrace.lines(section, runningOnly) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val summary = lines.firstOrNull()?.summary ?: return
    LoadCard(section, summary, lines, fontSize, modifier)
}

@Composable
private fun LoadCard(
    section: PerfSection?,
    summary: StripSummary,
    lines: List<StripLine>,
    fontSize: Dp,
    modifier: Modifier,
) {
    // A measurement is one card: a new session begins again, collapsed, shown and unfocused.
    val key = section to summary.sessionId
    var expanded by remember(key) { mutableStateOf(false) }
    var closed by remember(key) { mutableStateOf(key in closedCards) }
    var focused by remember(key) { mutableStateOf(false) }
    var letGoAt by remember(key) { mutableStateOf<Long?>(null) }
    val firstSeenAt = remember(key) { SystemClock.elapsedRealtimeNanos() }

    // Gone when its time is up. Computed once for what the card is now (a measurement that ended long ago is not drawn at all,
    // not drawn and then removed) and waited out for what it will be.
    val reading = expanded || focused
    var gone by remember(key, reading, letGoAt, summary.running) {
        mutableStateOf(StripLinger.hideInMs(summary, SystemClock.elapsedRealtimeNanos(), reading, letGoAt, firstSeenAt) == 0L)
    }
    LaunchedEffect(key, reading, letGoAt, summary.running) {
        val left = StripLinger.hideInMs(summary, SystemClock.elapsedRealtimeNanos(), reading, letGoAt, firstSeenAt)
        if (left != null && left > 0L) {
            delay(left)
            gone = true
        }
    }
    if (closed || gone) return

    val colors = CastivioTheme.colors
    val focus = LocalFocusManager.current
    val ink = toneInk(summary.tone)
    val navigable = section == null

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        InteractiveGlassCard(
            onClick = {
                expanded = !expanded
                letGoAt = SystemClock.elapsedRealtimeNanos()
            },
            modifier = modifier
                .fillMaxWidth()
                // The strip of a section is for a finger: see above. Placed before everything it governs.
                .then(if (navigable) Modifier else Modifier.focusProperties { canFocus = false })
                .onFocusChanged { state ->
                    if (focused && !state.hasFocus) letGoAt = SystemClock.elapsedRealtimeNanos()
                    focused = state.hasFocus
                },
            shape = RoundedCornerShape(Radius.md),
            // The ground of the screen, a little see-through: what is behind it shows faintly, and does not run into the text
            // as it did through the bare glass, which is clear enough to read the footer through.
            fill = SolidColor(colors.background.copy(alpha = CARD_OPACITY)),
        ) {
            val close: @Composable () -> Unit = {
                CastivioIconButton(
                    icon = CastivioIcons.Close,
                    contentDescription = "Hide this measurement",
                    onClick = {
                        closedCards += key
                        // Focus goes back to what is above before the card is gone, rather than to nowhere.
                        focus.moveFocus(FocusDirection.Up)
                        closed = true
                    },
                )
            }
            if (expanded) {
                ExpandedCard(lines, fontSize, close)
            } else {
                CollapsedCard(summary, ink, fontSize, close)
            }
        }
    }
}

/** The card in a glance: a dot in the measurement's colour, what and how it stands, an arrow to open it, and the button. */
@Composable
private fun CollapsedCard(summary: StripSummary, ink: Color, fontSize: Dp, close: @Composable () -> Unit) {
    val colors = CastivioTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = COLLAPSED_MIN, max = COLLAPSED_MAX)
            .padding(horizontal = fontSize, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(Modifier.size(Spacing.md).clip(CircleShape).background(ink))
        Column(Modifier.weight(1f)) {
            Text(
                text = summary.name,
                style = castivioBodyStyle(fontSize).copy(fontWeight = FontWeight.SemiBold),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary.detail,
                style = castivioBodyStyle(fontSize),
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Pointing at the trailing edge, turned to point down: there is more under it.
        Icon(
            CastivioIcons.Chevron,
            contentDescription = null,
            tint = colors.onBackgroundMuted,
            modifier = Modifier.size(Sizing.iconSm).rotate(OPEN_ARROW_DEGREES),
        )
        close()
    }
}

/** Every line of the measurement, as the strip has always drawn them, with the button at the corner and the arrow turned up. */
@Composable
private fun ExpandedCard(lines: List<StripLine>, fontSize: Dp, close: @Composable () -> Unit) {
    val colors = CastivioTheme.colors
    // What the corner holds, kept clear of the text: the arrow, a gap and the button, which is at the floor of a target.
    val corner = Sizing.minTarget(CastivioTheme.device.isTv) + Sizing.iconSm + Spacing.lg
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = fontSize, end = corner, top = fontSize / 2, bottom = fontSize / 2),
        ) {
            lines.forEach { line ->
                Text(
                    text = line.text,
                    style = castivioBodyStyle(fontSize),
                    color = toneInk(line.tone),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                CastivioIcons.Chevron,
                contentDescription = null,
                tint = colors.onBackgroundMuted,
                modifier = Modifier.size(Sizing.iconSm).rotate(CLOSE_ARROW_DEGREES),
            )
            close()
        }
    }
}

/** The ink of a line: warning while it runs, success when it is done, danger when it failed, muted for the detail. */
@Composable
private fun toneInk(tone: StripTone): Color {
    val colors = CastivioTheme.colors
    return when (tone) {
        StripTone.RUNNING -> colors.warning
        StripTone.DONE -> colors.success
        StripTone.FAILED -> colors.danger
        StripTone.MUTED -> colors.onBackgroundMuted
    }
}

/**
 * The measurements the viewer hid, by where the card is (null is Home's) and which measurement it was.
 *
 * Outside the composition, because the card leaves it with the screen and a viewer who hid one has not asked to see it
 * again on the way back to Home. Used on the main thread, from the card's own press, and nowhere else.
 */
private val closedCards = HashSet<Pair<PerfSection?, Int>>()

/** How much of the card is the ground of the screen: light enough to see Home through, firm enough to read over it. */
private const val CARD_OPACITY = 0.9f

/** The collapsed card is about this tall: the range a viewer can take in at a glance without losing the board beneath it. */
private val COLLAPSED_MIN = 65.dp
private val COLLAPSED_MAX = 75.dp

/** The arrow of a chevron that points at the trailing edge: a quarter turn to point down, three to point up. */
private const val OPEN_ARROW_DEGREES = 90f
private const val CLOSE_ARROW_DEGREES = 270f
