package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.core.platform.PerformanceLog
import com.castivio.core.platform.SectionReport
import java.util.Locale
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
 * ## Why it is a [Popup] and not a row in the screen's column
 *
 * Because it was a row in the screen's column, and on a real board that cost the user
 * the screen. Both callers invoke this as a `Column` child — `ChannelsScreen` inside
 * the panel's padded column, `BrowseScreen` inside one with `Arrangement.spacedBy` —
 * so eight lines of readout took eight lines of *height*, the categories rail, the
 * channel list and the preview divided what was left, and the measured board that the
 * reference describes was drawn into a strip. A debug instrument had become a term in
 * the responsive composition.
 *
 * A `Popup` node measures to zero in its parent and places its content in a window of
 * its own. That is the whole of the fix and it is why neither screen changed: the
 * column now allots this nothing, the metrics that size the rail, the rows and the
 * preview see exactly the surface they saw before the panel existed, and no dimension
 * here can ever reach them again. Pinning it to a corner by hand would not have done
 * that — an overlay inside the column is still measured by the column.
 *
 * `focusable = false` deliberately: the window takes no key events, so a remote keeps
 * arrowing down the channel list with the panel on screen. The consequence is stated
 * rather than hidden — the expand control answers a tap and not a D-pad — and the
 * compact card is sized so the three numbers a test is judged by need no expanding.
 *
 * ## Compact by default, and bounded either way
 *
 * Collapsed it is the run's identity and the two numbers that say whether content
 * arrived: TTID and TTFC. Expanded it adds the import window. Both are capped at
 * [READOUT_MAX_WIDTH] and sit in the top corner, which on this board is the preview's
 * corner rather than the rail's or the list's.
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

    // The screen's furniture is on screen the first time this composes, which is what
    // TTID means. Reported from here rather than from each screen so the four sections
    // cannot end up with four slightly different definitions of "first frame" -- and
    // from *outside* the popup, so moving the readout into its own window did not move
    // the instant TTID is taken at.
    LaunchedEffect(current.run) { PerformanceLog.firstFrame() }

    // Survives rotation and the screen being left and re-entered; the measurement does
    // not, and that asymmetry is right. Which numbers somebody wants to see is a
    // preference, and each open is a new experiment.
    var expanded by rememberSaveable { mutableStateOf(false) }

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

    // **The right-hand corner, in every language.**
    //
    // This was `TopEnd` resolved against the reading direction, which put it on the left
    // in Arabic -- and the Channels board does not mirror, so its left is the action
    // strip and the bouquets. A debug overlay sitting on the two columns a viewer
    // navigates by is the same defect this file was rewritten to remove, arrived at from
    // the other side.
    //
    // Pinning the direction rather than reaching for one of the direction-absolute
    // alignment APIs keeps invariant 9 intact -- those are banned outright, and the ban
    // is right. This is the same override `Columns` uses, for the same reason: the
    // corner this has to stay clear of is fixed, so the corner it sits in is fixed too.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Popup(
            // The board's preview corner: of the four columns, the one a reading can
            // afford to cover.
            alignment = Alignment.TopEnd,
            properties = PopupProperties(focusable = false),
        ) {
            Readout(
                report = current,
                elapsed = elapsed,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                modifier = modifier,
            )
        }
    }
}

/**
 * The card itself: the run's identity, two numbers, and the rest behind a tap.
 *
 * Every size here is the card's own and reaches nothing outside the popup window.
 */
@Composable
private fun Readout(
    report: SectionReport,
    elapsed: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.sm)
    val interaction = remember { MutableInteractionSource() }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier
                // Clear of the window's own edge, and of a status bar drawn over it.
                .padding(Spacing.sm)
                // Bounded, always. The one rule this file exists to keep is that no
                // dimension of the readout is decided by how much room the screen has.
                .widthIn(max = READOUT_MAX_WIDTH)
                // The whole card is the control, which is what makes the target the
                // card's size rather than a glyph's. `Sizing.minTarget` because a
                // remote lands on 56dp and a thumb on 48.
                .heightIn(min = Sizing.minTarget(CastivioTheme.device.isTv))
                .clip(shape)
                // Opaque rather than glass: this is read over a moving list of channel
                // rows, and a translucent fill puts artwork behind the digits.
                .background(colors.backgroundElevated)
                .border(1.dp, colors.glassBorder, shape)
                .clickable(interaction, indication = null, onClick = onToggle)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${report.section.name} · run ${report.run} · ${report.mode.name}",
                    style = CastivioType.overline,
                    color = colors.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = if (expanded) "PERF $COLLAPSE" else "PERF $EXPAND",
                    style = CastivioType.labelSmall,
                    color = colors.secondary,
                    maxLines = 1,
                )
            }

            if (report.running) {
                PerformanceRow("Elapsed", seconds(elapsed), colors.onBackgroundMuted)
            }

            // TTID and TTFC together, in that order, because the gap between them is the
            // measurement Fast Content Loading is judged by. They are the two the
            // collapsed card keeps: TTFC against Full Load is the whole verdict, and
            // TTFC alone already says whether content arrived before the import ended.
            report.ttidMs?.let { PerformanceRow("TTID", seconds(it), colors.onBackground) }
            report.firstContentMs?.let {
                PerformanceRow("TTFC · First Content", seconds(it), colors.onBackground)
            }

            if (expanded) {
                report.serverMs?.let {
                    val calls = report.requests ?: 0
                    PerformanceRow("Network", "${seconds(it)}  ·  $calls req", colors.onBackgroundVariant)
                }
                report.databaseMs?.let {
                    PerformanceRow("Database", seconds(it), colors.onBackgroundVariant)
                }
                report.fullLoadMs?.let { PerformanceRow("Full Load", seconds(it), colors.onBackground) }

                // The import window. Shown only once the import has reported, so a warm
                // open -- which imports nothing -- draws none of these rather than a
                // column of zeroes.
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

                // Parsing has no row because nothing measures it yet, and a row reading
                // "--" beside five real numbers is the kind of blank somebody eventually
                // fills with a guess. `XtreamImportEngine` is in :data:parsing, which may
                // not import the platform clock -- the invariant script fails the build on
                // it -- so timing it needs a pure seam rather than a call. Stated here so
                // the absence is a known gap with a named cause instead of an oversight.
            }
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

/** Wide enough for the longest row, narrow enough to stay a corner of the board. */
private val READOUT_MAX_WIDTH = 260.dp

private const val EXPAND = "▾"
private const val COLLAPSE = "▴"

/** True when this run has something worth showing beside its own clock. */
internal val SectionReport.hasReadings: Boolean
    get() = ttidMs != null || firstContentMs != null || fullLoadMs != null || serverMs != null
