package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing
import com.castivio.core.platform.PerformanceLog
import com.castivio.core.platform.SectionReport
import java.util.Locale
import kotlinx.coroutines.delay

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
 * over them. No popup, no corner, no expand control — the gate has room for every row.
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
 * | `TTID` | section opened → first frame drawn | the gate's own composition |
 * | `TTFC` | section opened → first row on screen | the pager's first non-empty window |
 * | `Network` | the provider's total time, and the call count | OkHttp's `EventListener` |
 * | `Database` | SQLite's total, summed over every batch | `RoomCatalogWriter.commit` |
 * | `Full Load` | section opened → the import committed | `SectionLoad.Done` |
 *
 * `Network`, `Database` and `TTFC` are deliberately **not addable**. The first two are
 * where the time went, the third is what the viewer waited; an import overlaps all
 * three, and a panel that summed them would invent a total nothing took. `Network` is
 * a *sum over calls* and not a span, so against a concurrent import it exceeds the
 * wall clock by roughly the overlap — 175.90s of calls inside a 54.28s load is four
 * workers, not a slow provider.
 *
 * A line whose moment was never reached is **absent**, never zero. A warm open has no
 * `Full Load` and no `Database` because it imported nothing and wrote nothing —
 * printing `0.00 s` there would read as "instant" for something that did not happen,
 * and somebody would write the zero down.
 */
@Composable
internal fun PerformancePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val visible = remember(context) { PerformanceLog.isVisibleIn(context) }
    if (!visible) return

    val report by PerformanceLog.current.collectAsStateWithLifecycle()
    val current = report ?: return

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

    Readout(report = current, elapsed = elapsed, modifier = modifier)
}

/** The card: the run's identity, then every reading that has a moment behind it. */
@Composable
private fun Readout(report: SectionReport, elapsed: Long, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.sm)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier
                .widthIn(max = READOUT_MAX_WIDTH)
                .clip(shape)
                .background(colors.glassFill)
                .border(1.dp, colors.glassBorderSoft, shape)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(
                text = "${report.section.name} · run ${report.run} · ${report.mode.name}",
                style = CastivioType.overline,
                color = colors.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (report.running) {
                PerformanceRow("Elapsed", seconds(elapsed), colors.onBackgroundMuted)
            }

            // TTID and TTFC together, in that order, because the gap between them is the
            // measurement Fast Content Loading is judged by.
            report.ttidMs?.let { PerformanceRow("TTID", seconds(it), colors.onBackground) }
            report.firstContentMs?.let {
                PerformanceRow("TTFC · First Content", seconds(it), colors.onBackground)
            }

            report.serverMs?.let {
                val calls = report.requests ?: 0
                PerformanceRow("Network", "${seconds(it)}  ·  $calls req", colors.onBackgroundVariant)
            }
            report.databaseMs?.let {
                PerformanceRow("Database", seconds(it), colors.onBackgroundVariant)
            }
            report.fullLoadMs?.let { PerformanceRow("Full Load", seconds(it), colors.onBackground) }

            // The import window. Shown only once the import has reported, so a warm open
            // -- which imports nothing -- draws none of these rather than a column of
            // zeroes.
            report.categories?.let { categories ->
                PerformanceRow(
                    "Categories · Records",
                    "$categories  ·  ${report.records ?: 0}",
                    colors.onBackgroundVariant,
                )
            }
            report.requestsStarted?.let { started ->
                PerformanceRow(
                    "Requests  (fail · retry)",
                    "$started  (${report.requestsFailed ?: 0} · ${report.retries ?: 0})",
                    colors.onBackgroundVariant,
                )
            }
            report.concurrency?.takeIf { it > 0 }?.let {
                PerformanceRow("Concurrency", "$it in flight", colors.onBackgroundMuted)
            }

            // Parsing has no row because nothing measures it yet, and a row reading "--"
            // beside five real numbers is the kind of blank somebody eventually fills with
            // a guess. `XtreamImportEngine` is in :data:parsing, which may not import the
            // platform clock -- the invariant script fails the build on it -- so timing it
            // needs a pure seam rather than a call. Stated here so the absence is a known
            // gap with a named cause instead of an oversight.
        }
    }
}

@Composable
private fun PerformanceRow(label: String, value: String, ink: Color) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = CastivioType.bodySmall, color = ink, maxLines = 1)
        Text(text = value, style = CastivioType.codeSmall, color = ink, maxLines = 1)
    }
}

/**
 * `8.37 s`, from milliseconds. Two decimals is the precision the clock actually has.
 *
 * [Locale.ROOT] so the separator is the one the rest of the readout is written in. The
 * device's own locale formats this as `8,37` in Arabic and in most of Europe, and a
 * figure that changes shape with the user's language is a figure that gets transcribed
 * wrongly into a report.
 */
private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.2f s", ms / 1000.0)

/** How often the running clock redraws. Not a measurement; only how it is displayed. */
private const val TICK_MS = 250L

/** Wide enough for the longest row, narrow enough to stay a panel on the gate. */
private val READOUT_MAX_WIDTH = 340.dp
