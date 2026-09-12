package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing
import com.castivio.core.platform.PerformanceLog
import com.castivio.core.platform.SectionReport
import kotlinx.coroutines.delay

/**
 * What this section cost, on this device, in this run — **debug builds only**.
 *
 * ## Why it lives in its own file now
 *
 * It was private inside `BrowseScreen`, which is why Live TV lost it the moment Live
 * stopped being a `BrowseScreen`. A measurement the product depends on is not a
 * screen's private furniture: it is a component, it has one declaration, and every
 * screen that loads a section draws it. That is invariant 6's rule applied to the one
 * thing that had been quietly exempt from it.
 *
 * ## Why it is on the screen rather than in a log
 *
 * Because the person who needs the number is holding the television. The numbers that
 * matter are about one real subscription on one real box, and neither exists on a CI
 * runner or in a benchmark harness. A logcat line would need a cable and a computer; a
 * panel needs a remote.
 *
 * ## Why a release build cannot draw it
 *
 * [PerformanceLog.isVisibleIn] asks the platform whether this package is debuggable and
 * returns before anything is composed when it is not. That is a property of the
 * installed APK rather than a flag anybody can set, so there is no build where this is
 * on by accident, and no debug build where it is off. It is read once and remembered:
 * it cannot change while the app runs.
 *
 * ## What each line means, and what it is measured at
 *
 * | line | boundary | measured at |
 * |---|---|---|
 * | `TTID` | section opened → first frame drawn | the screen's own composition |
 * | `TTFC` | section opened → first row on screen | the pager's first non-empty window |
 * | `Network` | the provider's total time, and the call count | OkHttp's `EventListener` |
 * | `Database` | SQLite's total, summed over every batch | `RoomCatalogWriter.commit` |
 * | `Full Load` | section opened → the import committed | `SectionLoad.Done` |
 *
 * `Network`, `Database` and `TTFC` are deliberately **not addable**. The first two are
 * where the time went, the third is what the viewer waited; an import overlaps all
 * three, and a panel that summed them would invent a total nothing took.
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
    val colors = CastivioTheme.colors

    // The screen's furniture is on screen the first time this composes, which is what
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

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.glassFill)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(
            text = "${current.section.name} · run ${current.run} · ${current.mode.name}",
            style = CastivioType.overline,
            color = colors.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (current.running) {
            PerformanceRow("Elapsed", seconds(elapsed), colors.onBackgroundMuted)
        }

        // TTID and TTFC together, in that order, because the gap between them is the
        // measurement Fast Content Loading is judged by.
        current.ttidMs?.let { PerformanceRow("TTID", seconds(it), colors.onBackground) }
        current.firstContentMs?.let {
            PerformanceRow("TTFC · First Content", seconds(it), colors.onBackground)
        }

        current.serverMs?.let {
            val calls = current.requests ?: 0
            PerformanceRow("Network", "${seconds(it)}  ·  $calls req", colors.onBackgroundVariant)
        }
        current.databaseMs?.let {
            PerformanceRow("Database", seconds(it), colors.onBackgroundVariant)
        }
        current.fullLoadMs?.let { PerformanceRow("Full Load", seconds(it), colors.onBackground) }

        // The import window. Shown only once the import has reported, so a warm open --
        // which imports nothing -- draws none of these rather than a column of zeroes.
        current.categories?.let { categories ->
            PerformanceRow(
                "Categories · Records",
                "$categories  ·  ${current.records ?: 0}",
                colors.onBackgroundVariant,
            )
        }
        current.requestsStarted?.let { started ->
            PerformanceRow(
                "Requests  (fail · retry)",
                "$started  (${current.requestsFailed ?: 0} · ${current.retries ?: 0})",
                colors.onBackgroundVariant,
            )
        }
        current.concurrency?.takeIf { it > 0 }?.let {
            PerformanceRow("Concurrency", "$it in flight", colors.onBackgroundMuted)
        }

        // Parsing has no row because nothing measures it yet, and a row reading "--"
        // beside five real numbers is the kind of blank somebody eventually fills with a
        // guess. `XtreamImportEngine` is in :data:parsing, which may not import the
        // platform clock -- the invariant script fails the build on it -- so timing it
        // needs a pure seam rather than a call. Stated here so the absence is a known
        // gap with a named cause instead of an oversight.
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

/** `8.37 s`, from milliseconds. Two decimals is the precision the clock actually has. */
private fun seconds(ms: Long): String = "%.2f s".format(ms / 1000.0)

/** How often the running clock redraws. Not a measurement; only how it is displayed. */
private const val TICK_MS = 250L

/** True when this run has something worth showing beside its own clock. */
internal val SectionReport.hasReadings: Boolean
    get() = ttidMs != null || firstContentMs != null || fullLoadMs != null || serverMs != null
