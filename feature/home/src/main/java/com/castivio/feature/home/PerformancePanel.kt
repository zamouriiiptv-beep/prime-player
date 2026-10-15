package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
 * | `TTID`, in the title | section opened → first frame drawn | the gate's own composition |
 * | `TTFC` | section opened → first row on screen | the pager's first non-empty window |
 * | `∥` and `reader` | a pass's requests: asking, bytes arriving, parsing, waiting for the writer | the engine's reader |
 * | `writer` | the same pass: rows, search rows, commits, idle | the engine's writer and `RoomCatalogWriter` |
 * | `then` | after the last row: prune, counts, search rebuild | `RoomCatalogWriter.finish` |
 * | `calls` | each action's requests: time, wait for an answer, size | OkHttp's `EventListener` |
 * | `records`, `orphans` | the streams at each stage, and the category ids orphans named | the engine |
 * | `search` | the search index before the import and after it | `RoomCatalogWriter.check` |
 *
 * The bar's spans **are** addable: they are the full load cut at its moments, and their
 * figures sum to it to the hundredth. The lines under `∥` are deliberately **not** -- they
 * ran at the same time, inside the spans, the reader on its threads and the writer on its
 * own, and a panel that added them to the bar or to each other would invent a total
 * nothing took. Several readers at once are summed, and say so: 135.58 s of calls inside a
 * 48.75 s load is four workers, not a slow provider.
 *
 * A line whose moment was never reached is **absent**, never zero. A warm open has no
 * `Full Load` and no `Database` because it imported nothing and wrote nothing —
 * printing `0.00 s` there would read as "instant" for something that did not happen,
 * and somebody would write the zero down. Which lines exist is decided by
 * [performanceReadout], where it is a unit test; this file only draws them.
 *
 * The gate gives way to the section in the frame the run settles, so the finished readout
 * is not a screen anybody can read: the whole run is printed to `adb logcat -s
 * CastivioPerf` when it settles, and a debuggable build copies the same lines to the
 * clipboard -- see `MeasurementClipboard`.
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
                // A short phone held sideways has fewer lines than a finished reading: the
                // card stops at the stage's edge and the rest is a drag away, never cut off.
                .verticalScroll(rememberScrollState())
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
                    is ReadoutLine.Parallel -> ParallelHeading(line.pieces)
                    is ReadoutLine.Tagged -> TaggedLine(line)
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

/**
 * The heading over a pass's two sides, behind the mark that says they ran at the same time.
 */
@Composable
private fun ParallelHeading(pieces: List<Piece>) {
    val colors = CastivioTheme.colors
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = colors.secondary)) { append(PARALLEL_MARK) }
            appendPieces(pieces, figureStyle(colors))
        },
        style = TAG_STYLE,
        color = colors.onBackgroundVariant,
        modifier = Modifier.padding(top = Spacing.xxs),
    )
}

/**
 * A line under its tag: the tag in a column of its own, so the lines of a group start at the
 * same edge, and the line wrapping under itself rather than under the tag.
 */
@Composable
private fun TaggedLine(line: ReadoutLine.Tagged) {
    val colors = CastivioTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (line.opensGroup) Spacing.xxs else Spacing.none),
        horizontalArrangement = Arrangement.spacedBy(TAG_GAP),
    ) {
        Text(
            text = line.tag,
            style = TAG_STYLE,
            color = if (line.parallel) colors.secondary else colors.onBackgroundVariant,
            maxLines = 1,
            modifier = Modifier
                .width(TAG_WIDTH)
                .alignByBaseline(),
        )
        Text(
            text = buildAnnotatedString { appendPieces(line.pieces, figureStyle(colors)) },
            style = CastivioType.labelSmall,
            color = line.ink.color(colors),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .alignByBaseline(),
        )
    }
}

/** A line's pieces, its times in the figure face. */
private fun AnnotatedString.Builder.appendPieces(pieces: List<Piece>, figure: SpanStyle) {
    for (piece in pieces) {
        if (piece.figure) withStyle(figure) { append(piece.text) } else append(piece.text)
    }
}

/** Times in the readout's figure face, a step brighter than the words around them. */
private fun figureStyle(colors: CastivioColors) =
    SpanStyle(fontFamily = CastivioType.codeSmall.fontFamily, color = colors.onBackgroundVariant)

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

/**
 * Beside the gate's column there is a pane to fill, up to this: the widest a reading's
 * lines need, so a television does not stretch them into a single line nobody can follow.
 */
internal val READOUT_SPLIT_MAX_WIDTH = 480.dp

private val CUTS_HEIGHT = 6.dp
private val CUTS_ABOVE = 5.dp
private val CUTS_BELOW = 4.dp
private val CUTS_GAP = 1.dp

/** The span that is a part of the next one's work: the first rows of the streams. */
private const val SOFT_ALPHA = 0.55f

/**
 * The tags' column: room for the longest of them, `orphans` and `failures`, in the widest
 * face a device may substitute for the app's own, which cut them at 50.
 */
private val TAG_WIDTH = 58.dp
private val TAG_GAP = 6.dp

/** A tag, and the heading over a pass: the readout's small type, a weight heavier. */
private val TAG_STYLE = CastivioType.labelSmall.copy(fontWeight = FontWeight.SemiBold)

/** Marks what ran at the same time. */
private const val PARALLEL_MARK = "∥ "

private const val LEGEND_DOT = "●"
private const val LEGEND_GAP = "   "
private const val NO_BREAK = " "
