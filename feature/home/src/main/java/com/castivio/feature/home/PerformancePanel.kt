package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.theme.CastivioColors
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing
import com.castivio.core.platform.PerformanceLog
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

/**
 * What this section cost, on this device, in this run — **debug builds only**.
 *
 * ## Where it is drawn, and why that moved
 *
 * On the loading screen, and nowhere else.
 *
 * It began as private furniture inside `BrowseScreen`, which is why Live TV lost it the
 * moment Live stopped being a `BrowseScreen`. It then became a `Popup` over the board,
 * because as a row in the board's column it took eight lines of height from the rail,
 * the list and the preview. Both were answers to the same question asked in the wrong
 * place: *where does a readout go on a screen that is trying to show a catalogue?*
 *
 * It goes on the screen the waiting happens on. The loading gate has a viewer's whole
 * attention and nothing else competing for the space, the numbers are about the load
 * that gate is running, and the board gets its four columns back with nothing floating
 * over them. No popup, no corner, no expand control.
 *
 * Beside the gate's column on a surface wider than it is tall, under it otherwise — see
 * [LoadingGate]. Under it was the only place it had, and on a phone held sideways that
 * ran past the bottom of the screen: the last row cut off and the gate's footnote drawn
 * across the row above it, in the first photograph anyone took of it.
 *
 * ## Why a release build cannot draw it
 *
 * [PerformanceLog.isVisibleIn] asks the platform whether this package is debuggable and
 * returns before anything is composed when it is not. That is a property of the
 * installed APK rather than a flag anybody can set, so there is no build where this is
 * on by accident, and no debug build where it is off. It is read once and remembered:
 * it cannot change while the app runs.
 *
 * ## Why its own layout direction
 *
 * Every string here is a Latin identifier or a number — `TTFC`, `496 req`, `4 in
 * flight` — and none of it is product text. Mirrored into an Arabic composition,
 * `175.90 s · 496 req` is drawn as `s · 496 req 175,90`, which is how the first real
 * reading of this panel had to be decoded before it could be used. Invariant 9 is
 * about the product reading correctly in both directions; this is an instrument whose
 * content has one direction, and [Locale.ROOT] is the same decision for the decimal
 * separator.
 *
 * ## What each line means, and what it is measured at
 *
 * | line | boundary | measured at |
 * |---|---|---|
 * | `Full Load` | section opened → the import committed | `SectionLoad.Done` |
 * | the bar and its names | the full load, cut at each moment the load passed | `PerformanceLog.phase` |
 * | `TTID` | section opened → first frame drawn | the gate's own composition |
 * | `TTFC` | section opened → first row on screen | the pager's first non-empty window |
 * | `Network` | the calls' total time, and their wait for a first byte | OkHttp's `EventListener` |
 * | `Database · commit` | SQLite's commits, summed; the other steps under it | `RoomCatalogWriter` |
 * | `Requests` | attempts, by action, with failures by reason | the HTTP clients |
 *
 * The bar's spans **are** addable: they are the full load cut at its moments, and they sum
 * to it. `Network` and `Database` are deliberately **not** — they are where the time
 * went, overlapping each other and the spans, and a panel that summed them would invent
 * a total nothing took. `Network` is a *sum over calls* and not a span, so against a
 * concurrent import it exceeds the wall clock by roughly the overlap — 135.58 s of calls
 * inside a 48.75 s load is four workers, not a slow provider.
 *
 * A line whose moment was never reached is **absent**, never zero. A warm open has no
 * `Full Load` and no `Database` because it imported nothing and wrote nothing —
 * printing `0.00 s` there would read as "instant" for something that did not happen,
 * and somebody would write the zero down. Which lines exist is decided by
 * [performanceReadout], where it is a unit test; this file only draws them.
 *
 * The final figures are on screen for as long as the gate is, which ends with the load;
 * the whole run is also printed to `adb logcat -s CastivioPerf` when it settles.
 */
@Composable
internal fun PerformancePanel(
    modifier: Modifier = Modifier,
    /** Narrow enough to stay a panel under the gate; the split beside it allows more. */
    maxWidth: Dp = READOUT_MAX_WIDTH,
) {
    val context = LocalContext.current
    val visible = remember(context) { PerformanceLog.isVisibleIn(context) }
    if (!visible) return

    val report by PerformanceLog.current.collectAsStateWithLifecycle()
    val current = report ?: return

    // Built where the report changes, in the collector, never in composition: which
    // lines exist, in what order, and how wide each span is drawn.
    val readout by remember { PerformanceLog.current.map { it?.let(::performanceReadout) } }
        .collectAsStateWithLifecycle(initialValue = null)

    // The section's furniture is on screen the first time this composes, which is what
    // TTID means. Reported from here rather than from each screen so the four sections
    // cannot end up with four slightly different definitions of "first frame".
    LaunchedEffect(current.run) { PerformanceLog.firstFrame() }

    // A running clock rather than a frozen one: a first open takes long enough that a
    // blank panel would read as broken. 250ms is slow enough to cost nothing and fast
    // enough to look like a stopwatch.
    var elapsed by remember(current.run) { mutableStateOf(0L) }
    LaunchedEffect(current.run, current.running) {
        while (current.running) {
            elapsed = current.sinceStartMs()
            delay(TICK_MS)
        }
    }

    val lines = readout ?: return
    Readout(readout = lines, elapsed = elapsed, maxWidth = maxWidth, modifier = modifier)
}

/** The card: the run's identity, then every line the readout holds, in its order. */
@Composable
private fun Readout(
    readout: PerformanceReadout,
    elapsed: Long,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.sm)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier
                .widthIn(max = maxWidth)
                .clip(shape)
                .background(colors.glassFill)
                .border(1.dp, colors.glassBorderSoft, shape)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        ) {
            Text(
                text = readout.title,
                style = CastivioType.overline,
                color = colors.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            for (line in readout.lines) {
                when (line) {
                    ReadoutLine.Elapsed -> PerformanceRow("Elapsed", seconds(elapsed), colors.onBackgroundMuted)
                    is ReadoutLine.Figure -> PerformanceRow(
                        label = line.label,
                        value = line.value,
                        ink = line.ink.color(colors),
                        part = line.part,
                        opensGroup = line.opensGroup,
                    )
                    is ReadoutLine.Note -> Note(line.text)
                    is ReadoutLine.Cuts -> Cuts(line.spans)
                    is ReadoutLine.Legend -> Legend(line.spans)
                }
            }
        }
    }
}

@Composable
private fun PerformanceRow(
    label: String,
    value: String,
    ink: Color,
    part: Boolean = false,
    opensGroup: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = if (part) Spacing.md else Spacing.none,
                top = if (opensGroup) Spacing.xxs else Spacing.none,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // The label gives way, the figure never does: a long action name is cut before
        // the count beside it is.
        Text(
            text = label,
            style = if (part) CastivioType.labelSmall else CastivioType.bodySmall,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(end = Spacing.md)
                .alignByBaseline(),
        )
        Text(
            text = value,
            style = CastivioType.codeSmall,
            color = ink,
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/** A fact that is a sentence: which way the streams came, the database's steps. */
@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = CastivioType.labelSmall,
        color = CastivioTheme.colors.onBackgroundMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = Spacing.md),
    )
}

/**
 * The full load to scale, one mark per span, in the order the load passed them.
 *
 * Not progress: it is drawn only once the load has ended, and measures where the time
 * went rather than how much is left. The gate's own bar is the progress.
 */
@Composable
private fun Cuts(spans: List<PhaseSpan>) {
    val colors = CastivioTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = CUTS_ABOVE, bottom = CUTS_BELOW)
            .height(CUTS_HEIGHT)
            .clip(RoundedCornerShape(Radius.pill)),
        horizontalArrangement = Arrangement.spacedBy(CUTS_GAP),
    ) {
        for (span in spans) {
            Box(
                Modifier
                    .weight(span.weight)
                    .fillMaxHeight()
                    .background(span.tone.color(colors)),
            )
        }
    }
}

/**
 * The spans named, each beside a dot in its colour on the bar.
 *
 * One text rather than a row of items, so it wraps like a sentence. Within a span the
 * spaces do not break, so `first commit 0.52` is never split across two lines.
 */
@Composable
private fun Legend(spans: List<PhaseSpan>) {
    val colors = CastivioTheme.colors
    val figures = SpanStyle(fontFamily = CastivioType.codeSmall.fontFamily, color = colors.onBackgroundVariant)
    Text(
        text = buildAnnotatedString {
            spans.forEachIndexed { index, span ->
                if (index > 0) append(LEGEND_GAP)
                withStyle(SpanStyle(color = span.tone.color(colors))) { append(LEGEND_DOT) }
                append(NO_BREAK + span.label.replace(" ", NO_BREAK) + NO_BREAK)
                withStyle(figures) { append(span.figure) }
            }
        },
        style = CastivioType.labelSmall,
        color = colors.onBackgroundMuted,
    )
}

private fun ReadoutInk.color(colors: CastivioColors): Color = when (this) {
    ReadoutInk.STRONG -> colors.onBackground
    ReadoutInk.VARIANT -> colors.onBackgroundVariant
    ReadoutInk.QUIET -> colors.onBackgroundMuted
    ReadoutInk.WARNING -> colors.warning
}

private fun SpanTone.color(colors: CastivioColors): Color = when (this) {
    SpanTone.QUIET -> colors.onBackgroundMuted
    SpanTone.PRIMARY -> colors.primary
    SpanTone.LIVE -> colors.live
    SpanTone.SECONDARY_SOFT -> colors.secondary.copy(alpha = SOFT_ALPHA)
    SpanTone.SECONDARY -> colors.secondary
}

/** How often the running clock redraws. Not a measurement; only how it is displayed. */
private const val TICK_MS = 250L

/** Wide enough for the longest row, narrow enough to stay a panel on the gate. */
private val READOUT_MAX_WIDTH = 340.dp

/** Beside the gate's column there is a pane to fill, up to this. */
internal val READOUT_SPLIT_MAX_WIDTH = 400.dp

private val CUTS_HEIGHT = 6.dp
private val CUTS_ABOVE = 5.dp
private val CUTS_BELOW = 4.dp
private val CUTS_GAP = 1.dp

/** The span that is a part of the next one's work: the first rows of the streams. */
private const val SOFT_ALPHA = 0.55f

private const val LEGEND_DOT = "●"
private const val LEGEND_GAP = "   "
private const val NO_BREAK = " "
