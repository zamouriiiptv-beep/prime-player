package com.castivio.feature.activation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CapsuleForm
import com.castivio.core.design.components.CapsuleMetrics
import com.castivio.core.design.components.CapsuleTint
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.CastivioHeader
import com.castivio.core.design.components.CastivioHeaderTitle
import com.castivio.core.design.components.CastivioLockup
import com.castivio.core.design.components.IdentityCapsule
import com.castivio.core.design.components.QrPlate
import com.castivio.core.design.components.StatusLine
import com.castivio.core.design.components.BODY_LEADING
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioDescriptionColor
import com.castivio.core.design.components.castivioFocusScale
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.components.emphasiseNumber
import com.castivio.core.design.theme.CastivioFrame
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Palette
import com.castivio.core.design.theme.SHORT_FRAME
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.core.design.theme.TABLET_FRAME
import com.castivio.core.design.theme.castivioStage

/**
 * The approved screen's numbers, per frame.
 *
 * `design/mockups/activation-mac.html` does not use one spacing scale for all
 * three frames — it states a different value for nearly every gap on each — and
 * an earlier Compose pass approximated all three with generic tokens. On the
 * tallest frame that was close enough to look right; on the shortest it was not.
 * So the mockup's values are transcribed rather than approximated.
 *
 * The margins are thin, and thin means the arithmetic has to be right. A `Column`
 * whose children exceed the height it is given hands **zero** to the ones measured
 * last — not a scrollbar, not a clip, zero — so eight dp of overrun is not eight
 * dp of crowding, it is Add playlist and Refresh disappearing. That is the failure
 * mode `ActivationLayoutTest` exists to catch and `ActivationBudgetTest` to
 * predict.
 *
 * The heights below are the **whole display**: `:app` calls `enableEdgeToEdge`, so
 * activation is given every dp of it.
 *
 * The margins that result, measured in the drawing at the type this screen
 * actually sets:
 *
 * | frame | band | identity column | code panel | spare |
 * |---|---|---|---|---|
 * | 960×540 TV | 344 | 290 | 270 | 54 / 74 |
 * | 873×393 | 267 | 240 | 232 | 27 / 35 |
 * | 800×360 | 257 | 222 | 218 | 35 / 39 |
 *
 * Measured in the drawing across four languages — Arabic, English, and the two
 * longest Latin translations, Spanish and Portuguese — because the header and the
 * caption are where a translation actually costs something. All twelve
 * combinations hold the caption to three lines at worst, clear the address by 30
 * to 35dp, and set the title at full size in every language but Portuguese and
 * Spanish on the television.
 *
 * The identity column's margins went up by half again in an earlier pass without
 * anything on it getting smaller to look at: the field cards gave 8dp of frame
 * each and the two buttons 6dp, and it was spent on the space between them rather
 * than taken back into the band. A screen is not crowded because its parts are
 * large; it is crowded because they are close.
 *
 * Some of it has since been spent on the outer margin, at both ends. The phone
 * frames stand off the glass by 15/11 and 11/8 rather than the 10/6 and 8/6 they
 * had: the screen runs edge to edge, so there is no status bar holding the header
 * away from the top of the display, and 10dp of padding read as a header glued
 * to it.
 *
 * **Both ends, and that is the part worth writing down.** Raising the top alone
 * did fix the header, and left 18 above against 6 below — a three-to-one outer
 * frame that read worse than the glued header had, because the eye judges a
 * composition by its margins and not by any one element's. A near-even 15/11
 * costs the band two dp more than 18/6 did and answers both. The two buttons
 * repaid some of it by coming down to their touch floors exactly.
 *
 * The television keeps its 24/22. It was already even, and it is not against an
 * edge in any sense that matters — a set is watched from three metres and most of
 * them overscan.
 *
 * Those rows are not a comment: [bandHeight], [identityHeight] and [codeHeight]
 * compute them, and `ActivationBudgetTest` fails if any of them goes negative.
 */
internal data class Metrics(
    /**
     * The stage, the header and the shared type steps — from [CastivioFrame], which
     * every screen reads, so two screens cannot drift a dp apart without one edit.
     */
    val frame: CastivioFrame,
    /* the three bands: this screen's own shape */
    val bandBottom: Dp,
    val footer: Dp,
    /**
     * The four sizes this screen sets that are **not** one of the frame's four
     * steps, and the reason each is allowed to exist.
     *
     * [macSize] and [keySize] are the sanctioned functional exception: the address
     * is the one string here a user reads aloud to somebody else, and on a set that
     * happens from three metres. [fsStatus] and [fsButton] sit between `fsLabel` and
     * `fsTitle` because a button's label and a reserved status line are neither a
     * field's name nor the screen's, and the composition is approved at these
     * numbers.
     *
     * A fifth size that was *not* one of these has just been deleted: `fsFooter`
     * held `fsBody`'s value on all four frames — a copy of a step, agreeing only
     * until somebody edited one of the two.
     */
    val fsStatus: Dp,
    val fsButton: Dp,
    val macSize: Dp,
    val keySize: Dp,
    val chipsGap: Dp,
    /* the identity column */
    val capsule: Dp,
    val capsuleGap: Dp,
    val cardPad: Dp,
    val cardGap: Dp,
    val labelWidth: Dp,
    val actionsTop: Dp,
    val actionsGap: Dp,
    val button: Dp,
    val statusTop: Dp,
    val statusHeight: Dp,
    /* the code panel */
    val plate: Dp,
    val zoneWidth: Dp,
    val zonePad: Dp,
    val zoneGap: Dp,
    /* shared */
    val bandGap: Dp,
    val zoneRadius: Dp,
    val mark: Dp,
    val target: Dp,
) {
    /* The frame's numbers, reachable as this screen's own. Delegated rather than
       copied: one table, one edit, and every call site below unchanged. */
    val edge get() = frame.edge
    val stageTop get() = frame.stageTop
    val stageBottom get() = frame.stageBottom
    val header get() = frame.header
    val bandTop get() = frame.bandTop
    val headGap get() = frame.headGap
    val brand get() = frame.brand
    val chip get() = frame.chip
    val chipPad get() = frame.chipPad
    val radius get() = frame.radius
    val fsTitle get() = frame.fsTitle
    val fsLabel get() = frame.fsLabel
    val fsCaption get() = frame.fsBody
    val fsChip get() = frame.fsChip
}


/**
 * Below this, the frame is not one of the three that were drawn.
 *
 * It is reached one way only: a transient system bar coming back on the shortest
 * phone. Activation runs immersive, so on a settled screen the insets are zero —
 * but a bar is one swipe away, a device may refuse to hide them, and a layout
 * that only fits while the system is cooperating is a layout that breaks in the
 * photograph somebody sends us.
 *
 * What gives when it happens is stated rather than left to chance, and it is not
 * the plate: the panel's padding goes first, and only then the code, by the
 * height the bar actually took. The alternative is worse in both directions —
 * letting the panel overrun hands the caption zero height, and shrinking the
 * plate on the drawn frame would pay a bar's cost on every device that never
 * shows one.
 */
internal val CRAMPED_PHONE = 345.dp

internal fun metricsFor(tv: Boolean, available: Dp): Metrics = when {
    tv -> Metrics(
        frame = CastivioFrame.Television,
        bandBottom = 20.dp, footer = 54.dp,
        fsStatus = 15.dp, fsButton = 15.5.dp,
        macSize = 30.dp, keySize = 27.dp, chipsGap = 6.dp,
        capsule = 72.dp, capsuleGap = 20.dp, cardPad = 17.dp, cardGap = 15.dp, labelWidth = 106.dp,
        actionsTop = 30.dp, actionsGap = 20.dp, button = 56.dp,
        statusTop = 16.dp, statusHeight = 24.dp,
        plate = 192.dp, zoneWidth = 244.dp, zonePad = 14.dp, zoneGap = 10.dp,
        bandGap = 32.dp, zoneRadius = 26.dp, mark = 32.dp, target = 56.dp,
    )
    // The tablet, which fell into the phone's branch until now: a phone-sized
    // composition floating in twice the frame. Its type sits between the phone's
    // and the set's, and the room the larger frame buys goes into margin.
    available >= TABLET_FRAME -> Metrics(
        frame = CastivioFrame.Tablet,
        bandBottom = 22.dp, footer = 52.dp,
        fsStatus = 14.5.dp, fsButton = 16.dp,
        macSize = 26.dp, keySize = 23.dp, chipsGap = 6.dp,
        capsule = 76.dp, capsuleGap = 20.dp, cardPad = 18.dp, cardGap = 16.dp, labelWidth = 104.dp,
        actionsTop = 28.dp, actionsGap = 20.dp, button = 52.dp,
        statusTop = 14.dp, statusHeight = 22.dp,
        plate = 200.dp, zoneWidth = 250.dp, zonePad = 14.dp, zoneGap = 10.dp,
        bandGap = 32.dp, zoneRadius = 24.dp, mark = 30.dp, target = 48.dp,
    )
    available < SHORT_FRAME -> shortPhone(available)
    else -> Metrics(
        frame = CastivioFrame.Phone,
        bandBottom = 8.dp, footer = 40.dp,
        fsStatus = 14.dp, fsButton = 14.5.dp,
        macSize = 28.dp, keySize = 25.dp, chipsGap = 6.dp,
        capsule = 60.dp, capsuleGap = 16.dp, cardPad = 15.dp, cardGap = 13.dp, labelWidth = 99.dp,
        actionsTop = 22.dp, actionsGap = 18.dp, button = 48.dp,
        statusTop = 12.dp, statusHeight = 22.dp,
        plate = 174.dp, zoneWidth = 234.dp, zonePad = 7.dp, zoneGap = 5.dp,
        bandGap = 24.dp, zoneRadius = 20.dp, mark = 26.dp, target = 48.dp,
    )
}

/**
 * The 800x360 drawing, and what a system bar takes off it.
 *
 * The panel's padding yields first and the plate only after that, because the
 * plate is the one thing on this screen a camera has to resolve.
 */
private fun shortPhone(available: Dp): Metrics {
    val drawn = Metrics(
        frame = CastivioFrame.ShortPhone,
        bandBottom = 6.dp, footer = 34.dp,
        fsStatus = 13.5.dp, fsButton = 14.dp,
        macSize = 25.dp, keySize = 22.dp, chipsGap = 6.dp,
        capsule = 56.dp, capsuleGap = 14.dp, cardPad = 14.dp, cardGap = 12.dp, labelWidth = 95.dp,
        actionsTop = 18.dp, actionsGap = 16.dp, button = 48.dp,
        statusTop = 10.dp, statusHeight = 20.dp,
        plate = 164.dp, zoneWidth = 220.dp, zonePad = 6.dp, zoneGap = 5.dp,
        bandGap = 20.dp, zoneRadius = 18.dp, mark = 24.dp, target = 48.dp,
    )
    if (available >= CRAMPED_PHONE) return drawn

    // A bar is on screen. Take it out of the panel's own padding first, then off
    // the plate -- twelve dp, which is what a gesture bar costs the panel once the
    // padding has given what it can.
    return drawn.copy(plate = 144.dp, zonePad = 6.dp, zoneGap = 5.dp)
}

/**
 * How much of the frame is left for the middle band.
 *
 * ## Why this is arithmetic and not a measurement
 *
 * It should be a measurement. It cannot be one here, because the only harness
 * that can run Compose without a device does not lay text out: under Robolectric
 * every `Text` measures the same height whatever its style, which inflates this
 * column by more than the margin the design has, so a runtime assertion about fit
 * would be an assertion about the harness.
 *
 * So the fit is checked where the numbers are real: from the [Metrics] the screen
 * is built from. `ActivationLayoutTest` still asserts that Compose *places* all of
 * it — that is the bug that shipped — and this says the places it puts them add
 * up. Two claims, each measured where it can be measured honestly.
 *
 * Simpler than it used to be, and for a good reason: the header and the footer
 * are now containers with heights of their own rather than bare type separated by
 * hairlines, so their contribution is a number instead of a line height that has
 * to be looked up per script.
 *
 * @param frame the whole display. `:app` is edge-to-edge; nothing is subtracted.
 */
internal fun Metrics.bandHeight(frame: Dp): Dp =
    frame - stageTop - header - bandTop - bandBottom - footer - stageBottom

/**
 * What the identity column needs, from the same numbers that build it.
 *
 * Mirrors `IdentityZone` child for child: two cards, the actions, and the
 * reserved status line, with the gap each one is given above it. Nothing here is
 * text-driven — the cards and the buttons are fixed heights — which is why this
 * arithmetic is trustworthy where a Robolectric measurement is not.
 */
internal fun Metrics.identityHeight(): Dp =
    capsule + capsuleGap + capsule + actionsTop + button + statusTop + statusHeight

/**
 * What the code panel needs: the plate, its padding, the gap and the caption.
 *
 * @param captionLines budgeted at **three**, which is one more than any of the
 *   twelve frame-and-language combinations in the drawing actually produces. The
 *   caption is a sentence and Castivio ships thirty-seven of them; two is what
 *   Arabic, English, Spanish and Portuguese take, and the spare line is for the
 *   one nobody measured. A budget that is exactly the measurement is a budget
 *   that fails the first time a translator is generous.
 */
internal fun Metrics.codeHeight(captionLines: Int = CAPTION_LINES): Dp =
    plate + zonePad * 2 + zoneGap + fsCaption * BODY_LEADING * captionLines

/**
 * The first screen, where a subscription is added.
 *
 * ## The composition, and what it is made of
 *
 * Three bands: a header, a field band, a footer. They used to be divided by two
 * full-width hairlines, on the argument that a rule reads as part of the surface
 * where a container reads as a component dropped onto it. The approved drawing
 * makes them out of containers instead — a framed code panel, two framed fields,
 * a footer bar — and the reason it works is that the containers are *darker* than
 * the ground rather than lighter. A pane sunk into the surface is still part of
 * the surface; it is the pale glass card laid on top that reads as clutter.
 *
 * The address lives in a field card, the largest type on the screen, because it
 * is the one thing here a user has to read to someone else. The code keeps its
 * plate, because a QR is only scannable on a light ground.
 *
 * The composition is approved and locked. `design/mockups/activation-mac.html` is
 * the drawing, `design/activation-spec.md` the contract.
 */
@Composable
internal fun MacActivationScreen(
    identity: ActivationIdentityState,
    onAddPlaylist: () -> Unit,
    onRefresh: () -> Unit,
    onCopied: (Copied) -> Unit,
    onOpenLanguage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = CastivioTheme.device.isTv

    BoxWithConstraints(modifier.fillMaxSize()) {
        val m = metricsFor(tv, maxHeight)
        Column(
            Modifier
                .fillMaxSize()
                .testTag(ActivationTags.STAGE)
                .castivioStage(m.frame),
        ) {
            Header(m = m, trialDays = identity.trialDaysRemaining, onOpenLanguage = onOpenLanguage)

            // `weight(1f)`, and the reason it is safe here is stated rather than
            // assumed: this screen is composed into a parent with a bounded height.
            // It was not, once -- it was inside a vertically scrolling column, where
            // the height constraint is infinite, there is no remaining space for a
            // weight to claim, and this entire band measured 0dp. The screen looked
            // like a header sitting on a legal line. See ActivationRoute, which now
            // gives this screen the viewport rather than a scroller.
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = m.bandTop, bottom = m.bandBottom)
                    .testTag(ActivationTags.FIELD),
                horizontalArrangement = Arrangement.spacedBy(m.bandGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IdentityZone(
                    modifier = Modifier.weight(1f).testTag(ActivationTags.IDENTITY),
                    identity = identity,
                    m = m,
                    tv = tv,
                    onAddPlaylist = onAddPlaylist,
                    onRefresh = onRefresh,
                    onCopied = onCopied,
                )
                CodeZone(identity = identity, m = m)
            }

            FooterBar(m)
        }
    }
}

/* -------------------------------------------------------------------- header */

@Composable
private fun Header(m: Metrics, trialDays: Int?, onOpenLanguage: () -> Unit) {
    val colors = CastivioTheme.colors
    CastivioHeader(
        height = m.header,
        gap = m.headGap,
        modifier = Modifier.fillMaxWidth().testTag(ActivationTags.HEADER),
        lockup = {
            CastivioLockup(markSize = m.brand, wordSize = (m.fsTitle.value * WORD_RATIO).sp)
        },
        title = {
            CastivioHeaderTitle(
                text = stringResource(R.string.activation_title),
                style = castivioTitleStyle(m.fsTitle),
                color = Palette.White,
            )
        },
        chips = {
            // **The pair's order is part of the row, not part of the sentence.**
            //
            // The row is pinned left to right, so what mirrors here is each chip's
            // own text — and letting the *group* mirror as well put the fact at the
            // screen's edge and the control inside it, which is that priority
            // backwards. Read from the outer end inward the header now says the
            // same thing in every language: the language control, the trial, the
            // page's name, the mark. The control takes the outer end because it is
            // the only thing in the header anyone presses, and an edge is where a
            // thumb reaches and an eye returning to the screen lands first.
            //
            // Pinned the way the lockup is — by declaring a direction for the
            // subtree rather than by putting a `start` on one of them — and each
            // chip is handed the reader's own direction back, because the words
            // inside a chip are language and the order of the two is not.
            val reading = LocalLayoutDirection.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(m.chipsGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The trial, as one sentence rather than a name and a count.
                    //
                    // The count comes from `EntitlementRepository` and is null until
                    // the sealed record has been read, so the chip is absent for an
                    // instant rather than announcing a seven that might turn out to
                    // be a two.
                    if (trialDays != null) {
                        // Quantity and argument are the same number, which is what a
                        // plural resource needs: Arabic and Polish choose different
                        // forms for 1, 2, a few and many, and a string built here
                        // would get all of that wrong.
                        val badge = androidx.compose.ui.res.pluralStringResource(
                            R.plurals.trial_badge, trialDays, trialDays,
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides reading) {
                            TrialChip(m, badge, trialDays)
                        }
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides reading) {
                        LanguageChip(m, onOpenLanguage)
                    }
                }
            }
        },
    )
}

/**
 * The trial, drawn as information rather than as a control.
 *
 * It and the language chip used to be the same neutral glass, which said they
 * were the same kind of thing. They are not: one is a fact the user needs and the
 * other is a control they may never touch. So this one carries a tint of the
 * brand and its numeral is picked out in the azure end of the mark's own ramp —
 * a highlight, not a glow, and nowhere near the filled button.
 */
@Composable
private fun TrialChip(m: Metrics, badge: String, days: Int) {
    Row(
        Modifier
            .height(m.chip)
            .clip(RoundedCornerShape(percent = 50))
            .background(CastivioTheme.colors.trialChipBrush)
            .border(BorderStroke(1.dp, Palette.EdgeCard), RoundedCornerShape(percent = 50))
            .padding(horizontal = m.chipPad),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(CHIP_DOT)
                .clip(RoundedCornerShape(percent = 50))
                .background(Palette.Azure70),
        )
        // **The colour is stated, and that is the whole of the first fix here.**
        //
        // It was not, and the sentence came out black on a dark chip. `bodyMedium`
        // declares a weight and a size and no colour, so `Text` fell through to
        // `LocalContentColor` — which, outside a Material `Surface`, is black.
        // Every other string on this screen is handed a colour explicitly; this
        // one was the exception, and an exception is exactly what it looked like.
        //
        // The sentence is white. **The count is not**, and it is the only thing in
        // it that is not: [Palette.Azure50], the saturated end of the mark's own
        // ramp, at the size and weight the numeral already carried. What a reader
        // needs from this chip is the number, and a number set apart by weight
        // alone inside a white sentence is a number that has to be looked for.
        //
        // Azure50 rather than the paler Azure70 the dot beside it uses: at 13dp,
        // inside a phrase of white, a near-white blue reads as white. The dot
        // stays where it is — it is a marker, not a value, and two saturations of
        // one hue is what an accent and its echo look like.
        //
        // Nothing else on the screen takes a hue from this. The address and the
        // device key are white, and they stay white.
        Text(
            text = emphasiseNumber(badge, days, Palette.Azure50, FontWeight.Bold),
            style = castivioChipStyle(m.fsChip).copy(fontWeight = FontWeight.SemiBold),
            color = Palette.White,
            maxLines = 1,
        )
    }
}

/**
 * The language control: a pill at the frame's chip, pressed at the frame's floor.
 *
 * The doc above this used to say it was "drawn at the chip's height and answering at
 * the frame's target". It was not — both the height and the minimum were `chip`, so on
 * a television the control a viewer aims a remote at was 44dp against a 56dp floor. The
 * sentence was true of the intent and false of the code, which is the worst of the two
 * ways for a comment to be wrong.
 *
 * It is now two boxes, the same arrangement `CastivioBackChip` uses and for the same
 * reason: the click, the focus and the label on the outer one, which is
 * [CastivioFrame.touchTarget] tall; the fill, the border, the corner and the focus
 * scale on the pill, which is [CastivioFrame.chip] and unchanged. Nothing looks
 * different. What changed is what answers.
 */
@Composable
private fun LanguageChip(m: Metrics, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(percent = 50)
    val border by animateColorAsState(
        if (focused) colors.focusRing else Palette.EdgeQuiet,
        Motion.focusSpec(),
        label = "languageChipBorder",
    )
    val label = stringResource(R.string.language)

    Box(
        Modifier
            .heightIn(min = m.frame.touchTarget)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(interaction, indication = null, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(m.chip)
                .castivioFocusScale(Motion.focusScaleIcon, interaction)
                .clip(shape)
                .background(colors.glassFill)
                .border(BorderStroke(1.dp, border), shape)
                .padding(horizontal = m.chipPad),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = castivioChipStyle(m.fsChip),
                color = colors.onBackgroundVariant,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = null,
                tint = colors.onBackgroundMuted,
                modifier = Modifier.size(Sizing.iconMd),
            )
        }
    }
}

/* ----------------------------------------------------------- identity column */

@Composable
private fun IdentityZone(
    identity: ActivationIdentityState,
    m: Metrics,
    tv: Boolean,
    onAddPlaylist: () -> Unit,
    onRefresh: () -> Unit,
    onCopied: (Copied) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val address = identity.address

    Column(modifier) {
        ActivationCard(
            modifier = Modifier.testTag(ActivationTags.MAC_CAPSULE),
            m = m,
            label = stringResource(R.string.mac_label),
            value = address ?: ADDRESS_PLACEHOLDER,
            spoken = address?.let { stringResource(R.string.mac_spoken, spaced(it)) },
            style = codeStyle(tv, m.macSize),
            copyLabel = stringResource(R.string.copy_mac),
            isCopied = identity.addressCopied,
            enabled = address != null,
            // The device saying what it is: the cool half of the brand.
            tint = CapsuleTint.Azure,
            onCopy = {
                clipboard.setText(AnnotatedString(address.orEmpty()))
                onCopied(Copied.Address)
            },
        )

        // Absent until an issuing contract exists. Nothing derives one, and an
        // empty slot where a credential belongs reads as a fault, so the row is
        // not composed at all rather than composed empty.
        identity.deviceKey?.let { key ->
            Box(Modifier.height(m.capsuleGap))
            ActivationCard(
                modifier = Modifier.testTag(ActivationTags.KEY_CAPSULE),
                m = m,
                label = stringResource(R.string.key_label),
                value = key,
                spoken = null,
                style = codeStyle(tv, m.keySize, KEY_TRACKING),
                copyLabel = stringResource(R.string.copy_key),
                isCopied = identity.keyCopied,
                enabled = true,
                icon = Icons.Rounded.Key,
                // What a licence is issued against: the warm half.
                tint = CapsuleTint.Violet,
                onCopy = {
                    clipboard.setText(AnnotatedString(key))
                    onCopied(Copied.Key)
                },
            )
        }

        Row(
            Modifier
                .padding(top = m.actionsTop)
                .fillMaxWidth()
                .testTag(ActivationTags.ACTIONS),
            horizontalArrangement = Arrangement.spacedBy(m.actionsGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // **Both are sized by the row, in a fixed proportion.**
            //
            // Refresh used to size to its own label and Add playlist to take what
            // was left. That was right while Refresh was the lesser of the two,
            // and it is not: a user who has just pasted a playlist URL on their
            // phone presses Refresh, and a user who has not presses Add. Neither
            // is the other's fallback, and a control drawn at a third of its
            // neighbour's width says it is.
            //
            // 1.1 to 1 — near enough to equal that the two read as a pair, with
            // just enough left over that the order of them is still visible
            // without the fill having to say it a second time. The weights are a
            // proportion of the row rather than of the labels, so Portuguese and
            // Arabic get the same pair of buttons.
            CastivioButton(
                text = stringResource(R.string.add_playlist),
                weight = ButtonWeight.Primary,
                onClick = onAddPlaylist,
                modifier = Modifier.weight(ACTION_LEAD),
                fill = CastivioTheme.colors.ctaBrush,
                corner = m.radius * BUTTON_CORNER,
                labelStyle = buttonStyle(m.fsButton),
                minHeight = m.button,
            )
            CastivioButton(
                text = stringResource(refreshLabel(identity.refresh)),
                weight = ButtonWeight.Secondary,
                icon = Icons.Rounded.Refresh,
                enabled = identity.refresh != RefreshState.Checking,
                onClick = onRefresh,
                modifier = Modifier.weight(1f),
                corner = m.radius * BUTTON_CORNER,
                labelStyle = buttonStyle(m.fsButton * SECONDARY_LABEL),
                minHeight = m.button,
                tint = CastivioTheme.colors.onBackgroundVariant,
            )
        }

        ActivationStatusLine(identity, m)
    }
}

/**
 * One line under the actions, at a height it keeps whether or not it has anything
 * to say.
 *
 * Reserved rather than appearing, because a line that appears pushes the two
 * buttons up the instant a user presses one of them — and a control that moves
 * under the thumb that pressed it is a control that gets pressed twice.
 */
@Composable
private fun ActivationStatusLine(identity: ActivationIdentityState, m: Metrics) {
    val colors = CastivioTheme.colors
    val status = statusMessage(identity)
    val tone = when (status?.tone) {
        Tone.Good, Tone.Copied -> colors.success
        Tone.Missing -> colors.warning
        Tone.Broken -> colors.danger
        Tone.Neutral, null -> colors.onBackgroundMuted
    }

    StatusLine(
        modifier = Modifier
            .padding(top = m.statusTop)
            .testTag(ActivationTags.STATUS),
        height = m.statusHeight,
        text = status?.let { stringResource(it.message) },
        tone = tone,
        // The sentence carries the tone too, not just the dot. A six-dp circle
        // is the whole signal otherwise, and it is the part a colour-blind
        // reader is least likely to get.
        //
        // A copy confirmation is drawn at full strength rather than in the
        // success green, deliberately: the operating system draws its own
        // clipboard toast a moment later, in the *system's* language, and
        // Castivio's answer is the one the user should be reading.
        textColor = when (status?.tone) {
            Tone.Copied -> colors.onBackground
            Tone.Neutral -> colors.onBackgroundVariant
            else -> tone
        },
    )
}

/**
 * The screen's field: the shared component, told this frame's numbers.
 *
 * The card, the copy control and the bidi isolation all live in `:core:design`
 * because the licence screen needs the same three. Nothing about the drawing is
 * decided here — the same values that used to be read off [Metrics] inside the
 * component are handed to it — and the layout and budget gates are what prove it.
 */
@Composable
private fun ActivationCard(
    m: Metrics,
    label: String,
    value: String,
    spoken: String?,
    style: TextStyle,
    copyLabel: String,
    isCopied: Boolean,
    enabled: Boolean,
    tint: CapsuleTint,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The glyph on the control, which names the **field** rather than the action.
     *
     * Both discs carried the copy glyph and the drawing has never said they
     * should: two circles a card's width apart, holding the same picture, read as
     * one control repeated rather than as two different things, and the two
     * things are the whole content of this screen. The address keeps copy; the
     * device key takes a key.
     *
     * The action is unchanged and so is what a screen reader is told — "Copy
     * device key" — and pressing it still swaps to a tick. Worth stating plainly,
     * because a control whose picture describes its field and whose label
     * describes its action is a compromise: it is the drawing's, it is what makes
     * the pair legible at a glance, and the description is what a user who cannot
     * see the glyph actually receives.
     */
    icon: ImageVector = Icons.Rounded.ContentCopy,
) {
    IdentityCapsule(
        metrics = CapsuleMetrics(
            height = m.capsule,
            startPadding = m.cardPad,
            gap = m.cardGap,
            // The frame's floor, not the circle's drawn size. The drawing has a
            // 52dp disc on a television and 42 on the shortest phone, and both
            // are under the target a D-pad and a thumb need -- which is the
            // defect that was called blocking the last time this control shipped
            // 8dp short. The card grows to hold the target; the target does not
            // shrink to fit the card. The inset and the inner gap stopped riding
            // on it in the same change, so holding the floor widens the card once
            // rather than three times.
            target = m.target,
            corner = m.radius,
            labelWidth = m.labelWidth,
        ),
        label = label,
        value = value,
        valueStyle = style,
        copyLabel = copyLabel,
        isCopied = isCopied,
        onCopy = onCopy,
        modifier = modifier.fillMaxWidth(),
        spoken = spoken,
        copyEnabled = enabled,
        tint = tint,
        form = CapsuleForm.Card,
        icon = icon,
        labelStyle = CastivioType.labelMedium.copy(
            fontSize = m.fsLabel.value.sp,
            lineHeight = (m.fsLabel.value * LABEL_LEADING).sp,
            letterSpacing = 0.sp,
        ),
    )
}

/* --------------------------------------------------------------- code panel */

/**
 * The plate, its panel and the caption that says what to do with it.
 *
 * The plate is 192 / 174 / 164dp — an 11% step down from the first drawing, which
 * read as a poster rather than as a code to point a phone at — and what gives way
 * on a short screen is the space around it, not the symbol. The white margin inside the
 * plate is not decoration: it is the symbol's quiet zone, and a reader that cannot
 * find the quiet zone cannot find the symbol, so it scales with the plate rather
 * than being a fixed number.
 */
@Composable
private fun CodeZone(identity: ActivationIdentityState, m: Metrics) {
    val colors = CastivioTheme.colors
    val bitmap = remember(identity.qr) { identity.qr?.asImageBitmap() } ?: return
    val shape = RoundedCornerShape(m.zoneRadius)

    Column(
        Modifier
            .width(m.zoneWidth)
            .clip(shape)
            .background(colors.codePanelBrush)
            .border(BorderStroke(1.dp, Palette.EdgeAccent), shape)
            .padding(m.zonePad)
            .testTag(ActivationTags.CODE_ZONE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.zoneGap, Alignment.CenterVertically),
    ) {
        QrPlate(
            code = bitmap,
            plate = m.plate,
            padding = m.plate * QUIET_ZONE,
            modifier = Modifier.testTag(ActivationTags.QR),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Smartphone,
                contentDescription = null,
                tint = colors.onBackgroundMuted,
                modifier = Modifier.size(m.fsCaption * CAPTION_ICON),
            )
            Text(
                text = stringResource(R.string.qr_caption),
                style = castivioBodyStyle(m.fsCaption),
                color = castivioDescriptionColor,
                textAlign = TextAlign.Center,
                maxLines = CAPTION_LINES,
            )
        }
    }
}

/* -------------------------------------------------------------------- footer */

/**
 * The legal line, in a bar of its own.
 *
 * It used to be bare type under a hairline, which put the least important
 * sentence on the screen on the same footing as the background. In a bar it is
 * plainly a footnote — and the mark at its trailing edge is what stops the
 * centred sentence reading as a stranded caption.
 */
@Composable
private fun FooterBar(m: Metrics) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(m.radius * BUTTON_CORNER)
    Row(
        Modifier
            .fillMaxWidth()
            .height(m.footer)
            .clip(shape)
            .background(colors.glassFill)
            .border(BorderStroke(1.dp, Palette.EdgeQuiet), shape)
            .padding(horizontal = m.footer * FOOTER_PAD)
            .testTag(ActivationTags.FOOTER),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.legal_player_only),
            // The size token, and deliberately not its colour. A legal line is
            // present without asking to be read, which is what `onBackgroundMuted`
            // is for; recolouring it to the description's ink would make the
            // disclaimer louder, and that is a decision about the product rather
            // than about typography.
            style = castivioBodyStyle(m.fsCaption),
            color = colors.onBackgroundMuted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(m.mark)
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.infoMarkFill)
                .border(BorderStroke(1.dp, Palette.EdgeAccent), RoundedCornerShape(percent = 50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = Palette.Violet80,
                modifier = Modifier.size(m.mark * MARK_ICON),
            )
        }
    }
}

/* --------------------------------------------------------------------- type */

/**
 * The code, at this frame's size.
 *
 * Built from the shipped token rather than declared here: the family, the weight
 * and the numeral behaviour are the design system's decision and only the size is
 * the frame's. Tracking is the one place on this screen that keeps a positive
 * value — between six pairs of hexadecimal it is the difference between an
 * address and a word.
 */
private fun codeStyle(tv: Boolean, size: Dp, tracking: Float = CODE_TRACKING): TextStyle {
    val base = if (tv) CastivioType.codeHero else CastivioType.codeCompact
    return base.copy(
        fontSize = size.value.sp,
        lineHeight = (size.value * CODE_LEADING).sp,
        letterSpacing = tracking.sp,
    )
}

private fun buttonStyle(size: Dp): TextStyle = CastivioType.labelLarge.copy(
    fontSize = size.value.sp,
    lineHeight = (size.value * CHIP_LEADING).sp,
    letterSpacing = 0.sp,
)

private fun refreshLabel(state: RefreshState): Int =
    if (state == RefreshState.Checking) R.string.refresh_checking else R.string.refresh

/**
 * What the reserved line says, and in which register.
 *
 * ## Why "nothing yet" is a warning and not an error
 *
 * The design system has three semantic colours and they answer different
 * questions. `danger` means *something failed* — a refresh that could not reach
 * anywhere is a fault, and it is red because the user needs to know the app tried
 * and could not. `warning` means *something is missing and you can supply it*,
 * which is exactly the state of a device with no subscription on it: nothing has
 * broken, the screen is simply not finished being set up.
 *
 * Painting that red would be telling a new user their brand-new install is
 * faulty on first launch. Painting it muted, which is what it used to be, says
 * nothing at all — and §8 of the contract is precisely that this screen must not
 * be ambiguous about it.
 */
internal fun statusMessage(identity: ActivationIdentityState): Status? = when {
    // **The transient answers come first, and that ordering is the fix for a
    // real defect.** Copying used to be listed below the refresh outcomes, so a
    // user who had pressed Refresh once -- leaving a permanent "no subscription
    // yet" -- could copy the address and see no acknowledgement at all.
    identity.lastCopied == Copied.Address -> Status(R.string.copied_mac, Tone.Copied)
    identity.lastCopied == Copied.Key -> Status(R.string.copied_key, Tone.Copied)

    // Checking is its own sentence on the button, not here; the line keeps
    // describing the subscription while the check runs.
    identity.refresh == RefreshState.Found -> Status(R.string.refresh_found, Tone.Good)

    identity.refresh == RefreshState.Error -> Status(R.string.refresh_error, Tone.Broken)

    // Asked and told no, or never asked at all: the user's situation is the same
    // either way, so it is the same sentence in the same tone.
    else -> Status(R.string.refresh_none, Tone.Missing)
}

internal data class Status(val message: Int, val tone: Tone)

/** The four registers the one status line speaks in. */
internal enum class Tone {
    Neutral,
    Good,

    /**
     * A copy just happened.
     *
     * Its own register rather than [Neutral], because this is the one message on
     * the screen that competes with something the operating system draws.
     */
    Copied,

    Missing,
    Broken,
}

/** `2F:19:EB:20:44:7C` read as separated pairs rather than one long number. */
private fun spaced(address: String): String = address.replace(":", " ")

/** Shown for the instant before the identity resolves. Never a real address. */
private const val ADDRESS_PLACEHOLDER = "··:··:··:··:··:··"

/**
 * The name against the screen's title.
 *
 * Under one, and it was over one until the header put the lockup at the leading
 * edge. Centred, a wordmark larger than the page's own name reads as the brand
 * announcing itself; at the edge, next to the title, the same proportion reads as
 * a splash screen that forgot to leave. The mark's job in a header is to say
 * whose screen this is, once, and then get out of the way of what the screen is
 * for.
 *
 * Down again, from .88, because the header carries four things and the wordmark
 * is the only one that can give width without losing anything. A control cannot
 * shrink below a thumb, a fact cannot be abbreviated, and a title that steps down
 * is a title the reader notices stepping down. A signature is recognised by its
 * shape rather than read at size, and the mark beside it is what carries the
 * brand across a room.
 */
private const val WORD_RATIO = 0.80f

/* ------------------------------------------------------------------- ratios */
/*
 * Ratios, not dp, wherever a number is a proportion of another number that the
 * frame already sets. A fixed 13dp gap that is right beside a 40dp mark is loose
 * beside a 28dp one, and the frame table is long enough already.
 */

private const val CHIP_LEADING = 1.45f
private const val LABEL_LEADING = 1.4f
private const val CODE_LEADING = 1.3f

/** Between the six pairs, and between the six digits, which want more air. */
private const val CODE_TRACKING = 1.5f
private const val KEY_TRACKING = 4f

/** A button's corner against the card's, and Refresh's label against the CTA's. */
private const val BUTTON_CORNER = 0.9f

/**
 * How much wider the call to action is than Refresh.
 *
 * Not "how much of the row it takes": both buttons are weighted, so this is the
 * ratio between them and nothing in the row is sized by its own label. Ten
 * percent is the smallest difference that still reads as an order when the two
 * sit side by side — below it they look like a mistake in the arithmetic, and far
 * above it Refresh looks like the thing you do when Add playlist has failed.
 */
private const val ACTION_LEAD = 1.1f
private const val SECONDARY_LABEL = 0.92f

/** The QR's quiet zone, as a share of the plate. */
private const val QUIET_ZONE = 0.062f

/** The caption's phone glyph and its two-line budget. */
private const val CAPTION_ICON = 1.3f
private const val CAPTION_LINES = 3

/** The footer bar's inset, and the information glyph inside its disc. */
private const val FOOTER_PAD = 0.34f
private const val MARK_ICON = 0.56f

/** The dot in the trial chip. */
private val CHIP_DOT = 8.dp
