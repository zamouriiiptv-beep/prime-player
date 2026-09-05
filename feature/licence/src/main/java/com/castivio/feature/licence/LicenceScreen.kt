package com.castivio.feature.licence

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.Language
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CapsuleMetrics
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.CastivioHeader
import com.castivio.core.design.components.CastivioHeaderTitle
import com.castivio.core.design.components.CastivioLockup
import com.castivio.core.design.components.IdentityCapsule
import com.castivio.core.design.components.PlanCard
import com.castivio.core.design.components.PlanCardMetrics
import com.castivio.core.design.components.QrPlate
import com.castivio.core.design.components.Skeleton
import com.castivio.core.design.components.StatusChip
import com.castivio.core.design.components.StatusLine
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.components.castivioChipStyle
import com.castivio.core.design.components.castivioDescriptionColor
import com.castivio.core.design.components.castivioFocusScale
import com.castivio.core.design.components.castivioTitleStyle
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.MotionLevel
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.core.design.theme.castivioStage
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.Plan
import com.castivio.domain.entitlement.PlanOffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The licence screen: what this device is entitled to, and how to change it.
 *
 * ## It is the sibling of Add Subscription, deliberately
 *
 * Same three bands, same two hairlines, same fixed viewport, same immersive
 * full-bleed frame, same identity capsules in the same corner, same QR in the
 * same place at the same size. A user moving between the two finds the same
 * information where they left it. Nothing here was designed fresh that already
 * existed there — `design/licence-spec.md` §3 is the contract and
 * `design/mockups/licence.html` is the drawing.
 *
 * ## The plan card is the button
 *
 * No radio, no selected state, no Continue. Pressing a card opens the portal for
 * that plan. On a television that removes an entire interaction; on a phone it
 * removes a step nobody wanted. Neither plan is marked as recommended — locked
 * by product decision — so `selectedFill` and `selectedBorder` carry focus and
 * nothing else.
 *
 * ## Castivio never takes the money
 *
 * Portal-first on every platform: the app presents the plans and hands the user
 * to the Castivio activation portal, which owns authentication, payment, licence
 * creation and MAC binding. This screen's entire part in a purchase is choosing
 * which link to open.
 */
@Composable
internal fun LicenceScreen(
    state: LicenceUiState,
    onPlan: (PlanOffer) -> Unit,
    onRetry: () -> Unit,
    onSupport: () -> Unit,
    onCopied: (Copied) -> Unit,
    onOpenLanguage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = CastivioTheme.device.isTv

    // The legal notice, open or not. Screen state rather than view-model state:
    // nothing outside this composition needs to know, and a modal that survived
    // a process death would reopen itself over a screen the user had moved on
    // from.
    var notice by remember { mutableStateOf(false) }

    // Back closes the notice and nothing else, and only while it is open.
    // Registered here rather than in the route because the route cannot see a
    // flag the screen owns, and adding a parameter so that it could would be
    // hoisting state for the benefit of one `if`.
    BackHandler(enabled = notice) { notice = false }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // The legal page replaces this screen rather than covering it.
        //
        // Covering it was the first version and it is subtly wrong twice: a
        // screen reader reads straight through an opaque page into the
        // controls behind it, and the D-pad can travel into a plan card nobody
        // can see. Replacing it costs nothing, because none of this screen's
        // state lives here -- the entitlement, the address, the QR and the
        // plans are all the view model's, one level up. That is the whole
        // reason this is an overlay in the composition rather than a second
        // Activity: coming back cannot land on a screen that has forgotten
        // what it was showing, because it was never asked to remember.
        if (notice) {
            LegalScreen(onClose = { notice = false })
            return@BoxWithConstraints
        }

        val m = licenceMetricsFor(tv, maxHeight)
        Column(
            Modifier
                .fillMaxSize()
                .testTag(LicenceTags.STAGE)
                .castivioStage(m.frame),
        ) {
            Header(m = m, state = state, onOpenLanguage = onOpenLanguage)
            Hairline()

            // `weight(1f)` needs a parent with a bounded height, and on the
            // sibling screen it once did not have one: inside a vertically
            // scrolling column the height constraint is infinite, a weight has
            // no remaining space to claim, and the whole band measured 0dp. See
            // LicenceRoute, which gives this screen the viewport.
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(LicenceTags.FIELD),
                horizontalArrangement = Arrangement.spacedBy(m.zoneGap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IdentityColumn(
                    modifier = Modifier.testTag(LicenceTags.IDENTITY),
                    state = state,
                    m = m,
                    tv = tv,
                    onPlan = onPlan,
                    onRetry = onRetry,
                    onSupport = onSupport,
                    onCopied = onCopied,
                )
                CodeZone(state = state, m = m)
            }

            Hairline()
            LegalFooter(m = m, onOpen = { notice = true })
        }
    }
}

/**
 * A link, and nothing else.
 *
 * ## What used to be here
 *
 * The notice itself, as a paragraph. That was wrong for a reason worth writing
 * down rather than quietly fixing: this is an **activation** screen. Everything
 * drawn on it competes with the one thing a user came here to do, and eight
 * sections of legal prose is not a footnote to that job — it is a second screen
 * pretending to be a footer.
 *
 * The arithmetic said the same thing from the other direction. The content
 * responsibility clause alone is 479 characters, which `measure.js` renders as
 * four lines of `bodySmall` on every frame Castivio ships to — 90dp on 873×393,
 * 87dp on 800×360, 93dp on the television — against a 20dp slot and 35dp of
 * whole-band margin. The two ways to make that fit are to shrink the type,
 * which makes the one text nobody may misread the hardest to read, or to shrink
 * the activation controls, which trades the screen's purpose for its
 * disclaimer. Both were rejected. See [LegalScreen].
 *
 * ## What it is now
 *
 * One short label, centred, in the footer's existing slot. Nothing above it
 * moved: the band, the capsules, the plans, the QR and the status line are the
 * numbers `LicenceMetrics` already held.
 *
 * ## The target floor, honestly
 *
 * It is 27dp tall on the tightest frame, because that is the whole of the
 * footer's budget and the layout above it is fixed. Its hit area is widened
 * horizontally instead — the pill is the label plus 24dp either side, so a thumb
 * has something to land on even though the strip is short. That is a documented
 * exception to the 48dp floor, not an oversight; the floor exists to stop 24dp
 * icons, and the alternative is a legal page with no door.
 */
@Composable
private fun LegalFooter(m: LicenceMetrics, onOpen: () -> Unit) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }

    // Fill and ink together, because neither is enough on its own. A focus ring
    // would draw a hairline rectangle across the bottom of the screen, which
    // reads as a border on the design rather than a state of a control; a change
    // of ink alone is legible in the hand and too quiet at three metres. So the
    // pill takes the same glass fill every other focusable surface in Castivio
    // uses, on the same focus spec as the language chip in the header.
    val fill by animateColorAsState(
        if (focused) colors.glassFill else Color.Transparent,
        Motion.focusSpec(),
        label = "licenceLegalFill",
    )
    val ink by animateColorAsState(
        if (focused) colors.onBackground else colors.onBackgroundMuted,
        Motion.focusSpec(),
        label = "licenceLegalInk",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = m.footTop, bottom = m.footBottom),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .testTag(LicenceTags.FOOTER)
                .onFocusChanged { focused = it.isFocused || it.hasFocus }
                // Round by definition, like every pill in Castivio.
                .clip(RoundedCornerShape(percent = 50))
                .background(fill)
                .clickable(interaction, indication = null, onClick = onOpen)
                .semantics { role = Role.Button }
                .padding(horizontal = LINK_PADDING),
        ) {
            Text(
                text = stringResource(R.string.licence_legal_link),
                style = CastivioType.bodySmall,
                color = ink,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** What the short label borrows sideways, since it cannot borrow height. */
private val LINK_PADDING = 24.dp

/**
 * A hairline that fades at both ends.
 *
 * A rule that meets the screen edge squarely reads as a border on a box. Fading
 * it keeps the three bands reading as one surface divided rather than three
 * surfaces stacked.
 */
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
 * Title, condition, language — the sibling's header, and now literally it.
 *
 * It used to be a `Row` of its own: a title at `headlineMedium` (`headlineLarge` on a
 * television), the condition chip, and the language control, with no mark and a
 * spacing scale picked here. The activation screen a user reaches this one from wears
 * `CastivioHeader`, so this screen wore a second header — same job, different brand,
 * different title size, different rule about which end the control takes.
 *
 * Now it is the one component: the lockup pinned to the same physical edge in every
 * language, the screen's name beside it on the lockup's own baseline, and the two chips
 * at the far end. The pair is pinned left to right for the reason the sibling gives —
 * what mirrors is each chip's own text, not the order of the two — so the language
 * control keeps the outer end, where a thumb reaches and an eye returning to the screen
 * lands first.
 *
 * The condition chip is absent while the entitlement is still being read, rather than
 * showing a condition that might turn out to be another one. An instant with no chip is
 * honest; an instant of the wrong chip is not.
 */
@Composable
private fun Header(
    m: LicenceMetrics,
    state: LicenceUiState,
    onOpenLanguage: () -> Unit,
) {
    val colors = CastivioTheme.colors
    CastivioHeader(
        height = m.header,
        gap = m.headGap,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LicenceTags.HEADER)
            .padding(bottom = m.headBottom),
        lockup = {
            CastivioLockup(markSize = m.brand, wordSize = (m.fsTitle.value * WORD_RATIO).sp)
        },
        title = {
            CastivioHeaderTitle(
                text = stringResource(R.string.licence_title),
                style = castivioTitleStyle(m.fsTitle),
                color = colors.onBackground,
            )
        },
        chips = {
            val reading = LocalLayoutDirection.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides reading) {
                        // The condition, faded rather than swapped.
                        //
                        // A licence becoming active is the one moment on this screen
                        // worth marking, and the whole of the mark is 220ms of opacity
                        // on the chip and on the sentence beneath it. Nothing scales,
                        // nothing travels, and navigation is not waiting on any of it --
                        // the incoming chip is composed and hittable from the first
                        // frame of the fade.
                        //
                        // The one thing that is not fixed is the chip's own width,
                        // because a crossfade measures both children and takes the
                        // larger: a chip becoming a longer one grows to its final width
                        // immediately and fades within it. That is the cheap end of the
                        // trade -- the alternative is animating a width, which is the
                        // kind of motion this design does not use.
                        //
                        // Keyed on the whole `LicenceView`, so a change of *condition*
                        // animates and a change of sentence underneath does not. See
                        // `LicenceStatusLine`.
                        LicenceFade(fadeKey(state)) { condition ->
                            if (condition != null) {
                                val view = condition.view
                                StatusChip(
                                    // Plain text. The chip names a condition and has no
                                    // number in it to emphasise; the annotated type is
                                    // the activation screen's trial badge, which does.
                                    label = AnnotatedString(stringResource(view.chip)),
                                    dot = when (view.tone) {
                                        // The same brush the primary button is filled
                                        // with. A trial is the one condition on this
                                        // screen that is neither good news nor bad, and
                                        // the primary gradient is what the sibling
                                        // screen already uses to say so.
                                        LicenceTone.Neutral -> colors.primaryBrush
                                        LicenceTone.Good -> SolidColor(colors.success)
                                        LicenceTone.Warning -> SolidColor(colors.warning)
                                        LicenceTone.Bad -> SolidColor(colors.danger)
                                    },
                                )
                            }
                        }

                        LanguageChip(m, onOpenLanguage)
                    }
                }
            }
        },
    )
}

/** The wordmark against the screen's name — the activation screen's figure. */
private const val WORD_RATIO = 0.80f

/**
 * Fade one small thing into another, at the level of motion the device allows.
 *
 * 220ms, inside the 200–250ms the design calls for and short enough that a user
 * pressing on through it is never held up: `Crossfade` composes the incoming
 * content immediately and only animates its alpha, so the new state is hittable
 * and focusable from the first frame.
 *
 * At [MotionLevel.DISABLED] the swap is instant. That is not a degradation to
 * apologise for — a user who has turned animation off has said what they want,
 * and a 220ms fade is exactly the kind of thing they turned it off to stop.
 */
@Composable
private fun <T> LicenceFade(target: T, content: @Composable (T) -> Unit) {
    val millis = stateFadeMillis(CastivioTheme.motionLevel)
    if (millis == 0) {
        content(target)
        return
    }
    Crossfade(
        targetState = target,
        animationSpec = tween(millis, easing = Motion.standard),
        label = "licenceState",
        content = content,
    )
}

/**
 * How long the condition takes to cross over, at a given level of motion.
 *
 * A function rather than a constant read at the call site, because it is the
 * one part of this that can be checked without a device: `LicenceMotionTest`
 * asserts the duration is inside the 200-250ms the design calls for and that
 * turning motion off produces a swap rather than a fast fade.
 *
 * Frame-stepping the real animation was tried and abandoned. Driving Compose's
 * test clock by hand needs the recomposition after a state write to happen
 * before the clock is advanced, and getting that ordering wrong produces a test
 * that fails saying the incoming content is missing when nothing has asked for
 * it yet -- which is what it did, twice. The rendering is covered by asserting
 * the outcome with the clock left alone; the timing is covered here, where it is
 * a number rather than a race.
 *
 * @return 0 when the swap must be instant.
 */
internal fun stateFadeMillis(level: MotionLevel): Int =
    if (level == MotionLevel.DISABLED) 0 else STATE_FADE_MS

/** Long enough to be seen, short enough not to be waited on. */
internal const val STATE_FADE_MS = 220

/**
 * The language control.
 *
 * The same chip the sibling screen carries, at the same size, in the same
 * corner. Its height is the frame's target rather than a drawn size: a control
 * that looks like a button has to answer like one, and the declared minimum is
 * the one enforced.
 */
@Composable
private fun LanguageChip(m: LicenceMetrics, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    // A pill is round by definition, so half its own height — not the frame's
    // `radius`, which is the corner the rectangles on the stage are cut with.
    val shape = RoundedCornerShape(percent = 50)
    val border by animateColorAsState(
        if (focused) colors.focusRing else colors.glassBorder,
        Motion.focusSpec(),
        label = "licenceLanguageBorder",
    )
    val label = stringResource(R.string.licence_language)

    // Two boxes, the arrangement `CastivioBackChip` uses and for the same reason: the
    // pill is what a reader sees and is the frame's `chip`; the box around it is what a
    // thumb presses and a remote lands on, and is the frame's `touchTarget`. This chip
    // was `m.target` tall before -- the licence screen's own field, which happens to
    // hold the floor -- so it grew the *visual* to satisfy a rule about fingers. Now it
    // draws what the frame draws and answers at what the frame requires.
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
                .heightIn(min = m.chip)
                .castivioFocusScale(Motion.focusScaleIcon, interaction)
                .clip(shape)
                .background(colors.glassFill)
                .border(BorderStroke(1.dp, border), shape)
                .padding(horizontal = m.chipPad),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = null,
                tint = colors.onBackgroundVariant,
                modifier = Modifier.size(Sizing.iconSm),
            )
            Text(
                text = label,
                style = castivioChipStyle(m.fsChip),
                color = colors.onBackground,
            )
        }
    }
}

/**
 * The identity, the choice, and the one reserved line that reports on both.
 *
 * The gaps are explicit paddings rather than an `Arrangement.spacedBy`, because
 * the three of them are three different numbers and the device-key capsule is
 * absent in a release build. A single spacing would either be wrong twice or
 * would move the plans when the key is not composed.
 */
@Composable
private fun IdentityColumn(
    state: LicenceUiState,
    m: LicenceMetrics,
    tv: Boolean,
    onPlan: (PlanOffer) -> Unit,
    onRetry: () -> Unit,
    onSupport: () -> Unit,
    onCopied: (Copied) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val address = state.address
    val capsule = CapsuleMetrics(
        height = m.capsule,
        startPadding = m.capsuleStart,
        gap = m.copyGap,
        target = m.target,
    )

    Column(modifier) {
        IdentityCapsule(
            modifier = Modifier.testTag(LicenceTags.MAC_CAPSULE),
            metrics = capsule,
            label = stringResource(R.string.licence_mac_label),
            value = address ?: ADDRESS_PLACEHOLDER,
            valueStyle = if (tv) CastivioType.codeHero else CastivioType.codeCompact,
            copyLabel = stringResource(R.string.licence_copy_mac),
            isCopied = state.addressCopied,
            onCopy = {
                clipboard.setText(AnnotatedString(address.orEmpty()))
                onCopied(Copied.Address)
            },
            spoken = address?.let { stringResource(R.string.licence_mac_spoken, spaced(it)) },
            copyEnabled = address != null,
        )

        // Absent until an issuing contract exists. Nothing derives one, and an
        // empty slot where a credential belongs reads as a fault, so the row is
        // not composed at all rather than composed empty.
        state.deviceKey?.let { key ->
            IdentityCapsule(
                modifier = Modifier
                    .padding(top = m.rowGap)
                    .testTag(LicenceTags.KEY_CAPSULE),
                metrics = capsule,
                label = stringResource(R.string.licence_key_label),
                value = key,
                valueStyle = if (tv) CastivioType.codeKeyTv else CastivioType.codeKey,
                copyLabel = stringResource(R.string.licence_copy_key),
                isCopied = state.keyCopied,
                onCopy = {
                    clipboard.setText(AnnotatedString(key))
                    onCopied(Copied.Key)
                },
            )
        }

        Choice(
            modifier = Modifier.padding(top = m.plansTop),
            state = state,
            m = m,
            onPlan = onPlan,
            onRetry = onRetry,
            onSupport = onSupport,
        )

        LicenceStatusLine(
            modifier = Modifier.padding(top = m.statusTop),
            state = state,
            m = m,
        )
    }
}

/**
 * What the crossfade watches, and deliberately not everything on the screen.
 *
 * The entitlement, and nothing transient. A licence turning active is a change
 * of condition and is worth marking; "MAC address copied" is a confirmation of
 * something the user did a moment ago and must appear at once, because a fade
 * between a press and its answer is a delay dressed as a flourish. Keying on
 * this rather than on the rendered sentence is what separates the two without a
 * second code path, and `LicenceMotionTest` pins it.
 */
internal fun fadeKey(state: LicenceUiState): LicenceCondition? = state.licence?.let { licence ->
    LicenceCondition(
        view = licenceView(licence),
        // An *active* annual licence, and only that one. A lapsed licence also
        // has a date and does not travel here: it says so inside its own
        // sentence, so its date rides in `LicenceView.statusDateMs` and is
        // already part of the view this condition carries. This field is the
        // second line specifically, and nothing else on the screen draws one.
        expiresAtMs = (licence as? EntitlementState.AnnualActive)?.expiresAtMs,
    )
}

/**
 * The condition, and the one fact about it that is not in [LicenceView].
 *
 * The expiry travels with the view rather than being read from the live state
 * inside the crossfade, so the *outgoing* half of a fade keeps the date it was
 * drawn with. Reading `state.licence` in there instead would blank the line the
 * instant the entitlement changed, a fifth of a second before the sentence
 * above it finished fading -- a small wrongness, and the kind that is easier to
 * design out than to notice.
 */
internal data class LicenceCondition(val view: LicenceView, val expiresAtMs: Long?)

/**
 * What the middle of the column offers, which is not the same in every state.
 *
 * Three shapes and a fourth that is nothing at all:
 *
 *  - **The plans**, in the states where buying one changes something.
 *  - **One action**, in the states where it does not. Selling a licence to
 *    somebody whose licence was withdrawn, or whose licence server we cannot
 *    reach, takes money for a problem the money does not solve.
 *  - **Skeletons**, while the sealed record is being read — the shape the
 *    content will take, so nothing jumps when it arrives.
 *  - **Nothing**, for a lifetime licence. There is nothing left to sell and a
 *    plan card here would be asking for money twice.
 */
@Composable
private fun Choice(
    state: LicenceUiState,
    m: LicenceMetrics,
    onPlan: (PlanOffer) -> Unit,
    onRetry: () -> Unit,
    onSupport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val licence = state.licence
    val focus = remember { FocusRequester() }

    if (licence == null) {
        Row(
            modifier.testTag(LicenceTags.PLANS),
            horizontalArrangement = Arrangement.spacedBy(m.plansGap),
        ) {
            repeat(SKELETON_CARDS) {
                Skeleton(
                    height = m.planMinHeight,
                    width = SKELETON_CARD_WIDTH,
                    // The card's own corner, so the placeholder is the shape of the
                    // thing arriving rather than a rectangle beside it.
                    cornerRadius = m.radius,
                )
            }
        }
        return
    }

    val view = licenceView(licence)

    if (view.offersPlans) {
        // `purchasable` is a list, so three plans is expressible in the domain
        // today. This screen was designed for one or two: one card centred, two
        // side by side. A third would be drawn and would overrun the band, so
        // `LicenceLayoutTest` asserts the rendered count equals the offered
        // count and the budget is computed for the widest pair. Designing for a
        // third now would be designing for a decision nobody has made.
        Row(
            modifier.testTag(LicenceTags.PLANS),
            horizontalArrangement = Arrangement.spacedBy(m.plansGap),
        ) {
            state.plans.forEachIndexed { index, offer ->
                val id = offer.plan.name.lowercase()
                PlanCard(
                    modifier = Modifier
                        .testTag(LicenceTags.plan(id))
                        // The remote starts on the decision, not on the language
                        // control. A user sent here by the gate has one thing to
                        // do and the first press of a D-pad should not be spent
                        // travelling to it.
                        .then(if (index == 0) Modifier.focusRequester(focus) else Modifier),
                    metrics = PlanCardMetrics(
                        minHeight = m.planMinHeight,
                        horizontalPadding = m.planPaddingH,
                        verticalPadding = m.planPaddingV,
                        priceStyle = m.priceStyle,
                    ),
                    name = stringResource(planName(offer.plan)),
                    price = price(offer),
                    period = stringResource(planPeriod(offer.plan)),
                    description = planDescription(offer),
                    working = state.opening == id,
                    onClick = { onPlan(offer) },
                )
            }
        }
        LaunchedFocus(focus, enabled = state.plans.isNotEmpty())
        return
    }

    val action = view.action ?: return

    Row(modifier.testTag(LicenceTags.PLANS)) {
        CastivioButton(
            modifier = Modifier
                .testTag(LicenceTags.ACTION)
                .focusRequester(focus),
            text = stringResource(
                when (action) {
                    LicenceAction.Retry -> R.string.licence_retry
                    LicenceAction.Support -> R.string.licence_support
                },
            ),
            weight = ButtonWeight.Secondary,
            enabled = state.opening == null,
            onClick = when (action) {
                LicenceAction.Retry -> onRetry
                LicenceAction.Support -> onSupport
            },
        )
    }
    LaunchedFocus(focus, enabled = true)
}

/**
 * Put the remote somewhere useful, once, without fighting the user for it.
 *
 * Requested on first composition only. Re-requesting on every recomposition
 * would drag focus back to the first card each time the status line changed,
 * which is exactly when a user is reading somewhere else.
 */
@Composable
private fun LaunchedFocus(focus: FocusRequester, enabled: Boolean) {
    LaunchedEffect(enabled) {
        if (enabled) runCatching { focus.requestFocus() }
    }
}

/**
 * One line under the choice, at a height it keeps whether or not it has
 * anything to say.
 *
 * The precedence is the sibling's, and it is the sibling's because of a defect:
 * a copy confirmation lasts a second and a half and then gives the line back,
 * while a licence condition is true until it changes. When both have something
 * to say, the one about to disappear is the one the user just caused. Ordering
 * them the other way round meant a copy was acknowledged only by the operating
 * system's own clipboard toast, in the *system's* language rather than
 * Castivio's.
 */
@Composable
private fun LicenceStatusLine(state: LicenceUiState, m: LicenceMetrics, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors

    // Keyed on the entitlement, not on the sentence.
    //
    // Both matter and only one is worth animating. A licence turning active is a
    // change of condition and gets the fade; "MAC address copied" is a
    // confirmation of something the user did a moment ago and appears at once,
    // because a fade on it would be a delay between the press and its answer.
    // Keying the crossfade on the view rather than the text is what separates
    // the two without a second code path.
    LicenceFade(fadeKey(state)) { condition ->
        val resting = condition?.view
        val message: Pair<String, Color>? = when {
            state.lastCopied == Copied.Address ->
                stringResource(R.string.licence_copied_mac) to colors.success
            state.lastCopied == Copied.Key ->
                stringResource(R.string.licence_copied_key) to colors.success

            // A handoff that did not start. Said plainly rather than left as a
            // card that stopped looking busy for no visible reason.
            state.failed -> stringResource(R.string.licence_status_failed) to colors.danger

            state.opening != null ->
                stringResource(R.string.licence_status_opening) to colors.onBackgroundVariant

            // The trial's line is a plural rather than a fixed sentence, so it
            // is composed here where the day count is in hand.
            resting != null && resting.countsDays ->
                trialCountdown(state.licence) to toneColor(resting.tone)

            // Null while the record is being read: the line keeps its height and
            // says nothing, rather than guessing at a condition.
            //
            // Nested `let` rather than `resting?.status != null ->` and two smart
            // casts. The compiler would probably grant both; "probably" is not a
            // thing to find out from a seven-minute CI round trip.
            else -> resting?.let { view ->
                view.status?.let { statusSentence(view, it) to toneColor(view.tone) }
            }
        }

        Column(modifier) {
            StatusLine(
                modifier = Modifier.testTag(LicenceTags.STATUS),
                height = m.statusHeight,
                text = message?.first,
                tone = message?.second ?: colors.onBackgroundMuted,
                // The sentence carries the tone as well as the dot. A 6dp circle
                // is the whole signal otherwise, and it is the part a
                // colour-blind reader is least likely to get.
                textColor = message?.second ?: colors.onBackgroundMuted,
            )
            ExpiryLine(expiresAtMs = condition?.expiresAtMs, m = m)
        }
    }
}

/**
 * The condition's sentence, with its date in it where it has one.
 *
 * Two resources and one of them takes an argument, which is exactly the shape
 * that goes wrong if the choice is made twice. It is made once, in `licenceView`,
 * which hands back the resource **and** the timestamp that belongs to it: a
 * non-null [LicenceView.statusDateMs] is the view saying "this sentence has a
 * `%s` in it". Nothing here re-inspects the entitlement to work that out.
 *
 * @param status [LicenceView.status], already known to be non-null at the call
 *   site. Passed rather than re-read so the smart cast is the caller's problem.
 */
@Composable
private fun statusSentence(view: LicenceView, status: Int): String {
    val at = view.statusDateMs ?: return stringResource(status)
    return stringResource(status, expiryDate(at))
}

/**
 * A timestamp as the interface's own date, memoised on both of its inputs.
 *
 * One function for the two places that need it — the expired sentence and the
 * "valid until" line — because a screen that formats the same kind of value two
 * ways is a screen where one of them eventually stops matching the other.
 */
@Composable
private fun expiryDate(atMs: Long): String {
    val locale = interfaceLocale()
    return remember(atMs, locale) { formatExpiry(atMs, locale) }
}

/**
 * "Valid until 1 August 2027", under the confirmation and only under that one.
 *
 * ## Why only an active annual licence gets a *line*
 *
 * Two states hold a date worth showing and only one of them gets a line of its
 * own. A lapsed licence names the day it lapsed too — the user's own request,
 * and the right one: "your licence expired" leaves somebody who last opened the
 * app in March unable to tell whether that was yesterday or eight months ago.
 * It says so **inside its sentence** rather than under it, because it is the
 * state that still shows the plan cards. An active annual licence is offered
 * nothing further, so this line is spent out of the room the card row would have
 * taken; an expired one has the cards as well, and on the 800×360 frame with the
 * transient navigation bar showing the extra line takes the band 10dp negative.
 * A `Column` that does not fit hands zero height to the child it measured last,
 * so the line that would disappear for those seconds is the one telling the user
 * what to do about it. One sentence with a `%s` in it costs nothing and was
 * measured to still be one line in all nine stress languages on all three
 * frames.
 *
 * The remaining states have nothing to put here. Lifetime has no expiry at all;
 * a trial says how many days are left in sentence form, and a second rendering
 * of the same fact as a date would be two countdowns disagreeing about their
 * units; missing, revoked and unverified are conditions rather than moments.
 *
 * ## Why it does not move anything
 *
 * The states that show it are the states with no plan cards -- an active annual
 * licence is offered nothing further -- so this line occupies part of the space
 * the card row would have taken. Every other state composes nothing here at all:
 * not an empty box, not a reserved height. The status line above keeps its own
 * reserved height exactly as before, so the column is unchanged everywhere the
 * date is absent, which is everywhere but one.
 *
 * The date is formatted by the platform in the interface's locale, never by a
 * pattern of ours. See [formatExpiry] for which platform call, and why it is not
 * the obvious one.
 */
@Composable
private fun ExpiryLine(expiresAtMs: Long?, m: LicenceMetrics) {
    if (expiresAtMs == null || expiresAtMs <= 0L) return
    val colors = CastivioTheme.colors
    Text(
        text = stringResource(R.string.licence_valid_until, expiryDate(expiresAtMs)),
        style = CastivioType.bodySmall,
        color = colors.onBackgroundMuted,
        maxLines = 1,
        modifier = Modifier
            .padding(top = m.expiryTop)
            .testTag(LicenceTags.EXPIRY),
    )
}

/**
 * The expiry, as a date with the month **named** rather than numbered.
 *
 * ## Why not `DateFormat.MEDIUM`, which is the obvious call
 *
 * Because "medium" is not a shape, it is a slot, and CLDR fills that slot with
 * whatever each language conventionally uses — which for a good many of them is
 * all digits:
 *
 * | locale | `MEDIUM` |
 * |---|---|
 * | en-GB | `19 Feb 2027` |
 * | ar | `19‏/02‏/2027` |
 * | de | `19.02.2027` |
 * | ja | `2027/02/19` |
 *
 * Arabic is the one that made this visible. Its medium pattern is `dd‏/MM‏/y`,
 * with a **right-to-left mark after each field**; those marks break the three
 * numbers into separate runs, and right-to-left layout then orders the runs
 * right to left. The string is "19/02/2027" and the screen reads `2027/02/19`.
 * Nothing is wrong with it — it is what an Arabic reader's platform produces —
 * and it is still not what a licence screen wants, because a row of numbers
 * separated by slashes is ambiguous in exactly the way a date on a receipt must
 * not be. Is that the 2nd of December or the 12th of February?
 *
 * ## What this asks for instead
 *
 * A **skeleton**: day, abbreviated month, year, with no opinion about order or
 * separators. `getBestDateTimePattern` hands that to ICU, which answers with the
 * pattern that language actually uses for those three fields —
 * `19 Feb 2027`, `Feb 19, 2027`, `19 févr. 2027`, `2027年2月19日`,
 * `١٩ فبراير ٢٠٢٧`. Still entirely the platform's decision, still localised in
 * all 38; the only thing specified is that the month is a word, which is what
 * removes the ambiguity.
 *
 * Reverting to plain `MEDIUM` is one line, if a numeric date is ever preferred.
 */
internal fun formatExpiry(expiresAtMs: Long, locale: Locale): String {
    // Fully qualified: `android.text.format.DateFormat` and `java.text.DateFormat`
    // are one import away from being confused for each other, and only one of
    // them knows what a skeleton is.
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, EXPIRY_SKELETON)
    return SimpleDateFormat(pattern, locale).format(Date(expiresAtMs))
}

/** Day, month as a word, year. Order and separators are the locale's business. */
private const val EXPIRY_SKELETON = "dMMMy"

/**
 * "3 days remaining in your trial.", in the plural form the language uses.
 *
 * Quantity and argument are the same number, which is what a plural resource
 * needs: Arabic and Polish choose different forms for 1, 2, a few and many, and
 * passing the count only as an argument would render "1 days" in every one of
 * them.
 */
@Composable
private fun trialCountdown(licence: EntitlementState?): String {
    val days = (licence as? EntitlementState.TrialActive)?.daysRemaining?.coerceAtLeast(0) ?: 0
    return pluralStringResource(R.plurals.licence_trial_days, days, days)
}

@Composable
private fun toneColor(tone: LicenceTone): Color = with(CastivioTheme.colors) {
    when (tone) {
        LicenceTone.Neutral -> onBackgroundVariant
        LicenceTone.Good -> success
        // Expiry lives here, not in danger. A trial that ended is not a fault --
        // nothing broke, there is simply a decision to make -- and painting it
        // red tells somebody their working app is broken.
        LicenceTone.Warning -> warning
        LicenceTone.Bad -> danger
    }
}

@Composable
private fun CodeZone(state: LicenceUiState, m: LicenceMetrics) {
    val colors = CastivioTheme.colors

    Column(
        Modifier.testTag(LicenceTags.CODE_ZONE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.captionTop),
    ) {
        val bitmap = remember(state.qr) { state.qr?.asImageBitmap() }
        if (bitmap == null) {
            // The plate's shape while the symbol is being drawn, so the band's
            // width does not change under the user when it arrives.
            Skeleton(height = m.plate, width = m.plate, cornerRadius = m.radius)
            return@Column
        }
        QrPlate(
            code = bitmap,
            plate = m.plate,
            padding = m.platePadding,
            modifier = Modifier.testTag(LicenceTags.QR),
        )
        Text(
            text = stringResource(R.string.licence_qr_caption),
            style = castivioBodyStyle(m.fsBody),
            color = castivioDescriptionColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(m.captionWidth),
        )
    }
}

/** The formatted amount, in the interface's locale and never from a resource. */
@Composable
private fun price(offer: PlanOffer): String {
    val locale = interfaceLocale()
    return remember(offer, locale) { formatPrice(offer.priceMinor, offer.currency, locale) }
}

/**
 * The locale the interface is in, which is not always the device's.
 *
 * The configuration's locale, not the system default: Castivio's language is
 * chosen in the app and applied by wrapping the activity's context, so
 * `Locale.getDefault()` would format a French interface's prices -- and its
 * dates -- in the system's conventions. One function, because two places
 * needing the same locale and resolving it twice is how they come to disagree.
 */
@Composable
private fun interfaceLocale(): Locale {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        ConfigurationCompat.getLocales(configuration).get(0) ?: Locale.getDefault()
    }
}

/**
 * The card as one sentence, for a reader that cannot see it as a card.
 *
 * Name, price and period joined, with the price read as money rather than as a
 * run of digits followed by a symbol. Three separate nodes would have a screen
 * reader announce an amount with nothing attached to it.
 */
@Composable
private fun planDescription(offer: PlanOffer): String {
    val name = stringResource(planName(offer.plan))
    val period = stringResource(planPeriod(offer.plan))
    return "$name, ${price(offer)}, $period"
}

private fun planName(plan: Plan): Int = when (plan) {
    Plan.ANNUAL -> R.string.licence_plan_annual
    Plan.LIFETIME -> R.string.licence_plan_lifetime
    // Unreachable: `PricingConfig.purchasable` filters the trial out, because a
    // trial is granted and not sold. Named rather than defaulted, so that adding
    // a fourth plan stops this compiling instead of labelling it "Annual".
    Plan.TRIAL -> R.string.licence_chip_trial
}

private fun planPeriod(plan: Plan): Int = when (plan) {
    Plan.ANNUAL -> R.string.licence_per_year
    Plan.LIFETIME -> R.string.licence_once
    Plan.TRIAL -> R.string.licence_chip_trial
}

/** `2F:19:EB:20:44:7C` read as separated pairs rather than one long number. */
private fun spaced(address: String): String = address.replace(":", " ")

/** Shown for the instant before the identity resolves. Never a real address. */
private const val ADDRESS_PLACEHOLDER = "··:··:··:··:··:··"

/** Two, because two plans are offered and the skeleton is their shape. */
private const val SKELETON_CARDS = 2

/**
 * Wide enough to stand for a card without claiming to be its exact width.
 *
 * A card's width comes from its content, which is not known while the content is
 * being loaded. A skeleton the size of the longest possible card would jump
 * inward; this is the measured English pair's width, and the jump is small in
 * every language and absent in most.
 */
private val SKELETON_CARD_WIDTH = 210.dp
