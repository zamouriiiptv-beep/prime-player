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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
import com.castivio.core.design.components.castivioFocusScale
import com.castivio.core.design.components.emphasiseNumber
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Palette
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing

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
 * | 960×540 TV | 344 | 307 | 311 | 37 / 33 |
 * | 873×393 | 277 | 248 | 269 | 29 / 8 |
 * | 800×360 | 262 | 222 | 253 | 40 / 9 |
 *
 * Those rows are not a comment: [bandHeight], [identityHeight] and [codeHeight]
 * compute them, and `ActivationBudgetTest` fails if any of them goes negative.
 */
internal data class Metrics(
    /* the stage */
    val edge: Dp,
    val stageTop: Dp,
    val stageBottom: Dp,
    /* the three bands */
    val header: Dp,
    val bandTop: Dp,
    val bandBottom: Dp,
    val footer: Dp,
    /* type, per frame rather than per token: three frames, three reading distances */
    val fsTitle: Dp,
    val fsChip: Dp,
    val fsLabel: Dp,
    val fsCaption: Dp,
    val fsStatus: Dp,
    val fsFooter: Dp,
    val fsButton: Dp,
    val macSize: Dp,
    val keySize: Dp,
    /* the header */
    val headGap: Dp,
    val chip: Dp,
    val chipPad: Dp,
    val chipsGap: Dp,
    val brand: Dp,
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
    val radius: Dp,
    val zoneRadius: Dp,
    val mark: Dp,
    val target: Dp,
)

/**
 * Which frame this is, decided by the height the screen actually has.
 *
 * Height rather than width or a device class, because height is the dimension
 * that ran out. `DeviceClass` would call 800dp "Medium" and 873dp "Expanded",
 * which is a fact about width and says nothing about whether the band fits.
 */
internal val SHORT_PHONE = 380.dp

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
        edge = 46.dp, stageTop = 24.dp, stageBottom = 22.dp,
        header = 54.dp, bandTop = 22.dp, bandBottom = 20.dp, footer = 54.dp,
        fsTitle = 30.dp, fsChip = 14.dp, fsLabel = 15.8.dp, fsCaption = 13.5.dp,
        fsStatus = 15.dp, fsFooter = 13.5.dp, fsButton = 17.5.dp,
        macSize = 30.dp, keySize = 27.dp,
        headGap = 26.dp, chip = 44.dp, chipPad = 11.dp, chipsGap = 6.dp, brand = 40.dp,
        capsule = 80.dp, capsuleGap = 18.dp, cardPad = 19.dp, cardGap = 16.dp, labelWidth = 102.dp,
        actionsTop = 26.dp, actionsGap = 15.dp, button = 64.dp,
        statusTop = 15.dp, statusHeight = 24.dp,
        plate = 216.dp, zoneWidth = 268.dp, zonePad = 20.dp, zoneGap = 14.dp,
        bandGap = 32.dp, radius = 20.dp, zoneRadius = 26.dp, mark = 32.dp,
        target = 56.dp,
    )
    available < SHORT_PHONE -> shortPhone(available)
    else -> Metrics(
        edge = 32.dp, stageTop = 10.dp, stageBottom = 6.dp,
        header = 42.dp, bandTop = 10.dp, bandBottom = 8.dp, footer = 40.dp,
        fsTitle = 23.dp, fsChip = 13.dp, fsLabel = 14.7.dp, fsCaption = 13.dp,
        fsStatus = 14.dp, fsFooter = 13.dp, fsButton = 16.dp,
        macSize = 28.dp, keySize = 25.dp,
        headGap = 24.dp, chip = 36.dp, chipPad = 12.dp, chipsGap = 6.dp, brand = 31.dp,
        capsule = 66.dp, capsuleGap = 12.dp, cardPad = 17.dp, cardGap = 14.dp, labelWidth = 95.dp,
        actionsTop = 18.dp, actionsGap = 13.dp, button = 54.dp,
        statusTop = 10.dp, statusHeight = 22.dp,
        plate = 198.dp, zoneWidth = 258.dp, zonePad = 12.dp, zoneGap = 8.dp,
        bandGap = 24.dp, radius = 17.dp, zoneRadius = 20.dp, mark = 26.dp,
        target = 48.dp,
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
        edge = 26.dp, stageTop = 8.dp, stageBottom = 6.dp,
        header = 36.dp, bandTop = 8.dp, bandBottom = 6.dp, footer = 34.dp,
        fsTitle = 21.dp, fsChip = 12.5.dp, fsLabel = 14.1.dp, fsCaption = 12.5.dp,
        fsStatus = 13.5.dp, fsFooter = 12.5.dp, fsButton = 15.dp,
        macSize = 25.dp, keySize = 22.dp,
        headGap = 20.dp, chip = 34.dp, chipPad = 11.dp, chipsGap = 6.dp, brand = 28.dp,
        capsule = 60.dp, capsuleGap = 10.dp, cardPad = 15.dp, cardGap = 13.dp, labelWidth = 91.dp,
        actionsTop = 14.dp, actionsGap = 12.dp, button = 50.dp,
        statusTop = 8.dp, statusHeight = 20.dp,
        plate = 188.dp, zoneWidth = 242.dp, zonePad = 10.dp, zoneGap = 7.dp,
        bandGap = 20.dp, radius = 16.dp, zoneRadius = 18.dp, mark = 24.dp,
        target = 48.dp,
    )
    if (available >= CRAMPED_PHONE) return drawn

    // A bar is on screen. Take it out of the panel's own padding first, then off
    // the plate — twelve dp, which is what a gesture bar costs the panel once the
    // padding has given what it can.
    return drawn.copy(plate = 176.dp, zonePad = 8.dp, zoneGap = 6.dp)
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
 * @param captionLines budgeted at **two**, measured rather than reasoned. The
 *   caption became a longer sentence — it now says to use a phone — and at the
 *   panel widths this screen gives it, Arabic and English both take two lines on
 *   all three frames. Budgeting one would be budgeting for a caption this screen
 *   no longer has.
 */
internal fun Metrics.codeHeight(captionLines: Int = CAPTION_LINES): Dp =
    plate + zonePad * 2 + zoneGap + fsCaption * CAPTION_LEADING * captionLines

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
                .padding(start = m.edge, end = m.edge, top = m.stageTop, bottom = m.stageBottom),
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
        title = {
            CastivioHeaderTitle(
                text = stringResource(R.string.activation_title),
                style = CastivioType.headlineMedium.copy(
                    fontSize = m.fsTitle.value.sp,
                    lineHeight = (m.fsTitle.value * TITLE_LEADING).sp,
                    // Zero, and stated rather than inherited. The token carries a
                    // small negative track, which is a sensible Latin display
                    // correction and wrong for Arabic at any size: the script
                    // joins, and pulling the letters together closes the joins
                    // rather than tightening the word.
                    letterSpacing = 0.sp,
                ),
                color = Palette.White,
            )
        },
        lockup = { CastivioLockup(markSize = m.brand, wordSize = (m.fsTitle.value * WORD_RATIO).sp) },
        chips = {
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
                    TrialChip(m, badge, trialDays)
                }
                LanguageChip(m, onOpenLanguage)
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
        Text(
            text = emphasiseNumber(badge, days, Palette.Azure70),
            style = CastivioType.bodyMedium.copy(
                fontSize = m.fsChip.value.sp,
                lineHeight = (m.fsChip.value * CHIP_LEADING).sp,
                letterSpacing = 0.sp,
            ),
            maxLines = 1,
        )
    }
}

/**
 * The language control.
 *
 * Drawn at the chip's height and answering at the frame's target: a chip that
 * looks like a button is a target that has to behave like one. The declared
 * minimum is the one enforced, never the drawn size.
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

    Row(
        Modifier
            .height(m.chip)
            .heightIn(min = m.chip)
            .castivioFocusScale(Motion.focusScaleIcon, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(shape)
            .background(colors.glassFill)
            .border(BorderStroke(1.dp, border), shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = m.chipPad),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = CastivioType.bodyMedium.copy(
                fontSize = m.fsChip.value.sp,
                lineHeight = (m.fsChip.value * CHIP_LEADING).sp,
                letterSpacing = 0.sp,
            ),
            color = colors.onBackgroundVariant,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { contentDescription = label },
        )
        Icon(
            imageVector = Icons.Rounded.Language,
            contentDescription = null,
            tint = colors.onBackgroundMuted,
            modifier = Modifier.size(Sizing.iconMd),
        )
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
            // The one filled control on the screen, and it takes the width that
            // is left. Refresh sizes to its own label, so a narrowing frame costs
            // Refresh its width and never the call to action's.
            CastivioButton(
                text = stringResource(R.string.add_playlist),
                weight = ButtonWeight.Primary,
                onClick = onAddPlaylist,
                modifier = Modifier.weight(1f),
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
 * The plate holds its size across the three frames — 216 / 198 / 188dp — and what
 * gives way on a short screen is the space around it. The white margin inside the
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
                style = CastivioType.bodySmall.copy(
                    fontSize = m.fsCaption.value.sp,
                    // Arabic hangs marks above the line and drops tails below it,
                    // so leading that looks generous in a Latin face is tight
                    // here. One and a half, on every frame.
                    lineHeight = (m.fsCaption.value * CAPTION_LEADING).sp,
                    letterSpacing = 0.sp,
                ),
                color = colors.onBackgroundVariant,
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
            style = CastivioType.bodySmall.copy(
                fontSize = m.fsFooter.value.sp,
                lineHeight = (m.fsFooter.value * CAPTION_LEADING).sp,
                letterSpacing = 0.sp,
            ),
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

/* ------------------------------------------------------------------- ratios */
/*
 * Ratios, not dp, wherever a number is a proportion of another number that the
 * frame already sets. A fixed 13dp gap that is right beside a 40dp mark is loose
 * beside a 28dp one, and the frame table is long enough already.
 */

/** The wordmark against the title: the mark reads a shade larger than the words. */
private const val WORD_RATIO = 1.07f
private const val TITLE_LEADING = 1.35f
private const val CHIP_LEADING = 1.45f
private const val LABEL_LEADING = 1.4f
private const val CAPTION_LEADING = 1.5f
private const val CODE_LEADING = 1.3f

/** Between the six pairs, and between the six digits, which want more air. */
private const val CODE_TRACKING = 1.5f
private const val KEY_TRACKING = 4f

/** A button's corner against the card's, and Refresh's label against the CTA's. */
private const val BUTTON_CORNER = 0.9f
private const val SECONDARY_LABEL = 0.92f

/** The QR's quiet zone, as a share of the plate. */
private const val QUIET_ZONE = 0.062f

/** The caption's phone glyph and its two-line budget. */
private const val CAPTION_ICON = 1.3f
private const val CAPTION_LINES = 2

/** The footer bar's inset, and the information glyph inside its disc. */
private const val FOOTER_PAD = 0.34f
private const val MARK_ICON = 0.56f

/** The dot in the trial chip. */
private val CHIP_DOT = 8.dp
