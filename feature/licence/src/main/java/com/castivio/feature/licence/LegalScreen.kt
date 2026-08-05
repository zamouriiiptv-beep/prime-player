package com.castivio.feature.licence

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing

/**
 * Castivio's legal information, as a page rather than a paragraph.
 *
 * ## Why it is a page
 *
 * Because the licence screen is an activation screen. Its job is to say what
 * this device is entitled to and let somebody change that, and everything drawn
 * on it competes with that job. The legal text is not short — the content
 * responsibility clause alone is 479 characters, four lines of `bodySmall` on
 * every frame Castivio ships to — and there is no honest way to fit eight
 * sections of it into a 27dp footer.
 *
 * Two bad answers were considered and rejected. Compressing the type until it
 * fits makes the one text nobody may misread the hardest to read. Shrinking the
 * activation controls to buy the room trades the screen's actual purpose for its
 * disclaimer. So the notice moved off the screen entirely and the footer carries
 * a link, which is what commercial applications do and for this reason.
 *
 * ## It is not another Activity
 *
 * It is a full-bleed surface drawn over the licence screen in the same
 * composition, which is how the shell already draws a detail page or the player
 * — see `Overlay` and `BackPolicy.fromShell`. That is not only an architectural
 * preference: the licence view model, the entitlement, the QR and the resolved
 * address all stay exactly as they were, so coming back cannot land on a screen
 * that has forgotten what it was showing. A second `Activity` would have to
 * re-read all of it, and would flash while it did.
 *
 * ## Reading it with a remote
 *
 * The scrolling column takes focus when the page opens, so the first press of
 * down scrolls rather than travelling somewhere to find something that will.
 * Back closes, on every device, and the hint under the title says so — the same
 * arrangement the language picker uses, because a user who has learnt one
 * modal in this app has learnt them all. Close is drawn as well, for a thumb.
 */
@Composable
internal fun LegalScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val tv = CastivioTheme.device.isTv
    val body = remember { FocusRequester() }

    // The remote lands on the text, because scrolling is the only thing to do
    // here. `runCatching` because a focus request against a subtree that has not
    // been placed yet throws, and a legal page that crashes on open would be a
    // remarkable way to fail a store review.
    LaunchedEffect(Unit) { runCatching { body.requestFocus() } }

    Box(
        modifier
            .fillMaxSize()
            .testTag(LicenceTags.LEGAL)
            // Opaque, not a scrim. This is a page: the screen behind it is not
            // partly visible and not partly available.
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val edge = if (tv) TV_EDGE else PHONE_EDGE
        Column(Modifier.fillMaxSize().padding(horizontal = edge, vertical = edge / 2)) {
            Header(tv = tv, onClose = onClose)
            Hairline()

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // Bounded so a line of legal text is not 900dp wide on a
                    // television. Reading is the only thing this page is for and
                    // a line nobody can track back is not readable.
                    .widthIn(max = MEASURE)
                    .align(Alignment.CenterHorizontally)
                    .verticalScroll(rememberScrollState())
                    .focusRequester(body)
                    .focusable(),
                verticalArrangement = Arrangement.spacedBy(if (tv) TV_SECTION_GAP else SECTION_GAP),
            ) {
                for ((heading, text) in LEGAL_SECTIONS) {
                    Section(heading = stringResource(heading), body = stringResource(text), tv = tv)
                }
                // The scroll ends on air rather than on the last full stop, so
                // the final section is not read as clipped.
                Box(Modifier.height(if (tv) TV_SECTION_GAP else SECTION_GAP))
            }
        }
    }
}

/**
 * The page's title, the way out, and the sentence that says Back is the way out.
 *
 * The hint is not decoration. On a television the Close button is a thumb's
 * affordance that a remote reaches only by travelling up out of the text, and
 * the key a user will actually press is Back. Telling them it works is cheaper
 * than making them find out.
 */
@Composable
private fun Header(tv: Boolean, onClose: () -> Unit) {
    val colors = CastivioTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = if (tv) TV_HEAD_BOTTOM else HEAD_BOTTOM),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                text = stringResource(R.string.licence_legal_title),
                style = if (tv) CastivioType.headlineLarge else CastivioType.headlineMedium,
                color = colors.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.licence_legal_back_hint),
                style = CastivioType.bodySmall,
                color = colors.onBackgroundMuted,
            )
        }
        CastivioButton(
            text = stringResource(R.string.licence_legal_close),
            weight = ButtonWeight.Secondary,
            onClick = onClose,
            modifier = Modifier.testTag(LicenceTags.LEGAL_CLOSE),
        )
    }
}

/**
 * One section: an overline that names it and the paragraph that is it.
 *
 * A title style rather than the `overline` the capsules use, because this is a
 * document and those are labels. Eight all-caps markers down a page read as
 * eight fragments; eight titles read as one thing with parts. The heading takes
 * the full ink and the paragraph the variant, so the hierarchy is carried by
 * weight and colour rather than by size alone -- which is what keeps it working
 * in scripts that have no case.
 */
@Composable
private fun Section(heading: String, body: String, tv: Boolean) {
    val colors = CastivioTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = heading,
            style = if (tv) CastivioType.titleLarge else CastivioType.titleMedium,
            color = colors.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = body,
            style = if (tv) CastivioType.bodyLarge else CastivioType.bodyMedium,
            color = colors.onBackgroundVariant,
        )
    }
}

/** The same fading rule the licence screen uses, so the two pages agree. */
@Composable
private fun Hairline() {
    val divider = CastivioTheme.colors.divider
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.06f to divider,
                    0.94f to divider,
                    1f to Color.Transparent,
                ),
            ),
    )
}

/**
 * The eight sections, in the order they are read.
 *
 * A list rather than eight calls, so that the page cannot be given a heading
 * without a body or a body without a heading, and so the order is one line to
 * change. Content responsibility is third because the two before it establish
 * what Castivio is and what the licence buys — the clause reads as a definition
 * there and as a disclaimer anywhere else.
 */
private val LEGAL_SECTIONS: List<Pair<Int, Int>> = listOf(
    R.string.licence_legal_about_title to R.string.licence_legal_about_body,
    R.string.licence_legal_scope_title to R.string.licence_legal_scope_body,
    R.string.licence_legal_content_title to R.string.licence_legal_full,
    R.string.licence_legal_copyright_title to R.string.licence_legal_copyright_body,
    R.string.licence_legal_privacy_title to R.string.licence_legal_privacy_body,
    R.string.licence_legal_refund_title to R.string.licence_legal_refund_body,
    R.string.licence_legal_support_title to R.string.licence_legal_support_body,
    R.string.licence_legal_acceptance_title to R.string.licence_legal_acceptance_body,
)

/**
 * Roughly seventy characters of `bodyMedium`.
 *
 * Not the width of the screen. A 960dp television line is about 150 characters
 * and the eye loses the start of the next line somewhere around ninety.
 */
private val MEASURE: Dp = 720.dp

private val PHONE_EDGE: Dp = 30.dp
private val TV_EDGE: Dp = 56.dp
private val HEAD_BOTTOM: Dp = 12.dp
private val TV_HEAD_BOTTOM: Dp = 20.dp
private val SECTION_GAP: Dp = 20.dp
private val TV_SECTION_GAP: Dp = 28.dp
