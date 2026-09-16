package com.castivio.feature.home

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.components.formatCount
import com.castivio.core.design.theme.CastivioMetrics
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.MotionLevel
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.boundedFraction
import com.castivio.core.design.theme.castivioMetrics
import com.castivio.domain.SectionLoad
import java.util.Locale
import com.castivio.core.design.R as DesignR

/**
 * The screen a section is loaded on, before the section itself.
 *
 * ## Why the wait moved out of the board
 *
 * Because a viewer who presses **Channels** is going there to watch something, not to
 * watch a list assemble itself. The board used to draw its own "fetching" state inside
 * the panel — toolbar above it, empty columns beside it — and then fill in around the
 * viewer while they tried to use it. Rows appeared under a moving cursor, the category
 * rail grew as they scrolled it, and the count in the header changed while they read it.
 *
 * So the load happens here and the board opens finished. One screen is honest about
 * waiting; the other is honest about being ready. Neither pretends to be the other.
 *
 * ## The cost of that, stated plainly
 *
 * What a viewer waits through is now the **whole** import rather than the time to the
 * first row. On the provider this was measured against that is 287s instead of 78s, and
 * no amount of design fixes it — the fix is the request architecture, and it is a
 * separate change. This screen's job is to make the wait legible, not shorter: a real
 * count, a real size, a bar that means something, and the numbers underneath for anyone
 * who wants to know where the time went.
 *
 * ## Every figure on it is real
 *
 * | shown | from |
 * |---|---|
 * | `120 / 834` | [SectionLoad.Loading.groupsDone] over `groups` — the engine's own counters |
 * | `7.2 MB` | `CallMetrics.totalBytes`, counted per response since it existed |
 * | the bar | the same pair, not a timer pretending to be progress |
 * | the address | `DeviceIdentity`, the same one the activation screen shows |
 * | the provider | the stored registration's own label |
 * | the version | the installed package's own, read from the platform |
 *
 * There is no fake progress here. A bar that advances on a timer is the one thing a
 * loading screen must never do, because the moment it reaches the end and nothing has
 * happened, every number on the screen stops being believed.
 */
@Composable
internal fun LoadingGate(
    section: CatalogSection,
    fetch: SectionLoad.Loading?,
    mac: String,
    /** The registration's own label, or null before the source has been read. */
    provider: String?,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val tv = CastivioTheme.device.isTv
    val context = LocalContext.current
    val version = remember(context) { context.versionName() }

    // No background of its own. The theme draws Castivio's backdrop behind every
    // screen, and this one is not covering another -- it *is* the section's screen
    // until the section arrives. See `Backdrop.kt`.
    BoxWithConstraints(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val m = castivioMetrics(maxWidth, maxHeight, tv)
        val logo = maxHeight.boundedFraction(LOGO, 48.dp, 132.dp)
        val gap = maxHeight.boundedFraction(GAP, 6.dp, 20.dp)
        // Read out here, not inside the column. `ColumnScope` and `BoxWithConstraints`
        // are both `@LayoutScopeMarker`, so the inner scope hides the outer one's
        // `maxWidth` -- which is the marker doing its job: a size read through two
        // nested scopes is rarely the size the author meant.
        val track = maxWidth * TRACK_OF_WIDTH

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = m.edge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            Wordmark(logo = logo, version = version, m = m)

            Spinner(size = logo * SPINNER_OF_LOGO)

            Text(
                text = stringResource(R.string.browse_fetch_title, stringResource(section.label)),
                style = castivioTitleStyle(m.fsBody * SAY_OF_BODY),
                color = colors.onBackgroundVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Tally(fetch = fetch, m = m)

            Track(fetch = fetch, width = track, height = gap / 2)

            Spacer(Modifier.height(gap / 2))

            Facts(mac = mac, provider = provider, m = m)

            // Debug builds only, and this is the screen it belongs on: the wait it
            // measures is the wait happening right here. See `PerformancePanel`.
            PerformancePanel(Modifier.padding(top = gap))
        }

        Text(
            text = stringResource(R.string.browse_fetch_once),
            style = castivioBodyStyle(m.fsBody * LEGAL_OF_BODY),
            color = colors.onBackgroundMuted,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = m.edge, vertical = m.stageTop),
        )
    }
}

/* ------------------------------------------------------------------ the mark */

@Composable
private fun Wordmark(
    logo: Dp,
    version: String?,
    m: CastivioMetrics,
) {
    val colors = CastivioTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.edge / 2),
    ) {
        Image(
            painter = painterResource(DesignR.drawable.castivio_logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(logo),
        )
        Column {
            Text(
                text = stringResource(R.string.gate_wordmark),
                style = castivioTitleStyle(m.fsTitle * WORD_OF_TITLE),
                color = colors.onBackgroundStrong,
                maxLines = 1,
            )
            // Absent rather than invented when the platform will not say. A version
            // line reading "Version" with nothing after it is worse than no line.
            if (version != null) {
                Text(
                    text = stringResource(R.string.gate_version, version),
                    style = castivioBodyStyle(m.fsBody),
                    color = colors.secondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/* --------------------------------------------------------------- the spinner */

/**
 * Seven dots on a circle, turning.
 *
 * It says "something is happening" and nothing else — deliberately, because the
 * *progress* is stated by [Track] and [Tally] from real counters. A spinner that also
 * implied a proportion would be a second answer to a question already answered
 * truthfully.
 *
 * Still at [MotionLevel.DISABLED], where it becomes a ring of dots that simply exists.
 * The screen still says what it is doing in words and in numbers.
 */
@Composable
private fun Spinner(size: Dp) {
    val colors = CastivioTheme.colors
    val animated = CastivioTheme.motionLevel != MotionLevel.DISABLED
    val turn = if (animated) {
        rememberInfiniteTransition(label = "gateSpinner").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(SPIN_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "gateSpinnerAngle",
        ).value
    } else {
        0f
    }

    val dot = size / DOT_OF_SPINNER

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        repeat(DOTS) { index ->
            val step = index.toFloat() / DOTS
            Box(
                Modifier
                    .fillMaxSize()
                    .rotate(turn + step * 360f),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    Modifier
                        .size(dot)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(
                            (if (index % 2 == 0) colors.secondary else colors.primary)
                                .copy(alpha = 1f - step * FADE),
                        ),
                )
            }
        }
    }
}

/* ------------------------------------------------------ the count and the bar */

/**
 * `120 / 834 categories · 7.2 MB`, or the honest shape of not knowing yet.
 *
 * The denominator is the category count, not the channel count, and that is not a
 * simplification: how many channels a provider carries is unknown until the last one
 * has arrived, while how many categories it has is known from the first reply. A bar
 * against a total nobody knows is a bar that jumps.
 *
 * There are three shapes, and which one is drawn is decided by what the importer can
 * actually answer — see [SectionLoad.Loading.groupsDone]:
 *
 *  - **A fraction**, when categories are addressable and one has finished. Xtream.
 *  - **A row count**, when they are not. An M3U playlist is one file and its groups are
 *    discovered as it is read, so there is nothing to be `120 / 834` *of*; what rises
 *    honestly is the number of rows committed.
 *  - **A sentence**, before the provider has said anything at all.
 */
@Composable
private fun Tally(fetch: SectionLoad.Loading?, m: CastivioMetrics) {
    val colors = CastivioTheme.colors
    val done = fetch?.groupsDone
    val total = fetch?.groups ?: 0

    val text = when {
        done != null && total > 0 -> stringResource(
            R.string.gate_tally,
            formatCount(done),
            formatCount(total),
            formatBytes(fetch.bytes),
        )

        fetch != null && fetch.items > 0 -> stringResource(
            R.string.gate_items,
            formatCount(fetch.items),
            formatBytes(fetch.bytes),
        )

        // Nothing countable has come back yet. The row is held rather than collapsed,
        // so the column does not jump when the first number arrives.
        else -> stringResource(R.string.gate_starting)
    }

    Text(
        text = text,
        style = castivioChipStyle(m.fsBody * TALLY_OF_BODY),
        color = colors.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The bar, filled only where a real proportion exists.
 *
 * An import that cannot report a fraction gets the empty track and nothing else: it is
 * the frame the [Spinner] and [Tally] are saying "working" inside, and filling it from
 * anything other than the counters would be the one thing a loading screen must not do.
 */
@Composable
private fun Track(
    fetch: SectionLoad.Loading?,
    width: Dp,
    height: Dp,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.pill)
    val done = fetch?.groupsDone
    val total = fetch?.groups ?: 0
    val fraction = if (done != null && total > 0) {
        (done.toFloat() / total).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(colors.divider),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(colors.secondary),
            )
        }
    }
}

/* -------------------------------------------------------------- the two facts */

/**
 * The address this licence binds to, and whose catalogue is arriving.
 *
 * Two facts rather than a status light. The approved drawing had a `Connection ·
 * Connected` chip in the second slot and this is the provider's label instead, because
 * "connected" is not a thing this screen knows: it is composed from a progress event,
 * not from a socket, and it would go on reading *Connected* through a stall. The
 * provider's name is a fact the registration actually holds, and it answers the
 * question a viewer looking at a MAC address is usually asking next.
 *
 * Each one is absent rather than blank when it is not known — a chip captioned
 * `Provider` over nothing is a bug report waiting to be filed.
 */
@Composable
private fun Facts(mac: String, provider: String?, m: CastivioMetrics) {
    val colors = CastivioTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(m.edge / 2)) {
        if (mac.isNotBlank()) {
            Fact(
                label = stringResource(R.string.gate_mac),
                value = mac,
                tint = colors.secondary,
                m = m,
            )
        }
        if (!provider.isNullOrBlank()) {
            Fact(
                label = stringResource(R.string.gate_provider),
                value = provider,
                tint = colors.live,
                m = m,
            )
        }
    }
}

@Composable
private fun Fact(
    label: String,
    value: String,
    tint: Color,
    m: CastivioMetrics,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.sm)
    Column(
        Modifier
            .clip(shape)
            .background(colors.glassFill)
            .border(1.dp, colors.glassBorderSoft, shape)
            .padding(horizontal = m.edge / 2, vertical = m.edge / 4),
    ) {
        Text(
            text = label,
            style = castivioBodyStyle(m.fsBody * LEGAL_OF_BODY),
            color = colors.onBackgroundMuted,
            maxLines = 1,
        )
        Text(
            text = value,
            style = castivioChipStyle(m.fsBody),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ------------------------------------------------------------------ helpers */

/**
 * The installed package's own version, or null when the platform will not say.
 *
 * Read from the platform rather than from a `BuildConfig`, and that is the point: this
 * module is a library, and its `BuildConfig.VERSION_NAME` is the *library's*, which is
 * empty. The number a viewer is owed is the one on the APK they installed.
 */
internal fun Context.versionName(): String? = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName
}.getOrNull()

/**
 * `7.2 MB`, from bytes.
 *
 * Binary units, because that is what a download is measured in and what every other
 * tool on the device will agree with. [Locale.ROOT] for the separator, for the reason
 * `PerformancePanel` gives: a figure that changes shape with the interface language is
 * a figure that gets transcribed wrongly.
 */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < MEGABYTE -> "${bytes / KILOBYTE} KB"
    else -> String.format(Locale.ROOT, "%.1f MB", bytes.toDouble() / MEGABYTE)
}

private const val KILOBYTE = 1024L
private const val MEGABYTE = 1024L * 1024L

/* ------------------------------------------------------------------- the shares */

/** The mark's height as a share of the surface. */
private const val LOGO = 118f / 720f

/** The rhythm the column is spaced on. */
private const val GAP = 14f / 720f

private const val SPINNER_OF_LOGO = 0.46f
private const val DOT_OF_SPINNER = 7f
private const val DOTS = 7
private const val FADE = 0.82f
private const val SPIN_MS = 1500

private const val TRACK_OF_WIDTH = 0.42f

/* Type steps, as ratios of the frame's own, so the gate tracks the product's scale. */
private const val WORD_OF_TITLE = 1.5f
private const val SAY_OF_BODY = 1.15f
private const val TALLY_OF_BODY = 1.1f
private const val LEGAL_OF_BODY = 0.82f
