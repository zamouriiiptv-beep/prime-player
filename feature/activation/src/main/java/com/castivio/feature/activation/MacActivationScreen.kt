package com.castivio.feature.activation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.castivioFocusScale
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import java.util.Locale

/**
 * The approved screen's numbers, per frame.
 *
 * `design/mockups/activation-mac.html` does not use one spacing scale for all
 * three frames — it states a different value for nearly every gap on each — and
 * the first Compose pass approximated all three with generic tokens. On the
 * 873dp frame that was close enough to look right; on the shortest frame it was
 * not. So the mockup's values are transcribed rather than approximated.
 *
 * The margins are thin, and thin means the arithmetic has to be right. A `Column`
 * whose children exceed the height it is given hands **zero** to the ones measured
 * last — not a scrollbar, not a clip, zero — so eight dp of overrun is not eight
 * dp of crowding, it is Add playlist and Refresh disappearing. That is the failure
 * mode `ActivationLayoutTest` exists to catch.
 *
 * The heights below are the **whole display**: `:app` calls `enableEdgeToEdge`, so
 * activation is given every dp of it. Worth stating, because the gate spent a
 * round of measurements believing a 393dp phone gives this screen 345 — it was
 * reading the height off a stock test activity that still keeps a navigation bar.
 *
 * The margins that result, with the type and target sizes this screen actually
 * uses:
 *
 * | frame | band | identity column | spare |
 * |---|---|---|---|
 * | 873×393 | 284dp | 230dp | 54dp |
 * | 800×360 | 259dp | 226dp | 33dp |
 * | TV 960×540 | 337dp | 276dp | 61dp |
 *
 * The shortest frame had nine millimetres before the capsules and has thirty-five
 * after: a 56dp pill replaced a 69dp label-above-value stack, twice. That is not
 * a saving for its own sake -- it is what lets the screen take the system-bar
 * insets it was ignoring, with a swiped-back navigation bar still leaving eleven.
 *
 * Those rows are not a comment: [bandHeight] and [identityHeight] compute them,
 * and `ActivationBudgetTest` fails if any of them goes negative.
 */
internal data class Metrics(
    val edge: Dp,
    val stageTop: Dp,
    val stageBottom: Dp,
    val headBottom: Dp,
    val zoneGap: Dp,
    val rowGap: Dp,
    /** Inset from the pill's leading edge to its label. */
    val capsuleStart: Dp,
    /** The pill's height. Not one number: see the note on [Metrics.target]. */
    val capsule: Dp,
    val copyGap: Dp,
    val actionsGap: Dp,
    val actionsTop: Dp,
    val statusHeight: Dp,
    val statusTop: Dp,
    val footTop: Dp,
    val footBottom: Dp,
    val plate: Dp,
    val platePadding: Dp,
    val captionTop: Dp,
    val captionWidth: Dp,
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

internal fun metricsFor(tv: Boolean, available: Dp): Metrics = when {
    tv -> Metrics(
        edge = 48.dp, stageTop = 48.dp, stageBottom = 48.dp, headBottom = 16.dp,
        zoneGap = 52.dp, rowGap = 17.dp, capsuleStart = 24.dp, capsule = 64.dp, copyGap = 22.dp,
        actionsGap = 20.dp, actionsTop = 42.dp,
        statusHeight = 24.dp, statusTop = 16.dp,
        footTop = 13.dp, footBottom = 0.dp,
        plate = 208.dp, platePadding = 12.dp, captionTop = 15.dp, captionWidth = 236.dp,
        target = 56.dp,
    )
    available < SHORT_PHONE -> Metrics(
        edge = 26.dp, stageTop = 10.dp, stageBottom = 4.dp, headBottom = 9.dp,
        zoneGap = 34.dp, rowGap = 13.dp, capsuleStart = 18.dp, capsule = 52.dp, copyGap = 14.dp,
        actionsGap = 12.dp, actionsTop = 28.dp,
        statusHeight = 20.dp, statusTop = 12.dp,
        footTop = 7.dp, footBottom = 1.dp,
        plate = 138.dp, platePadding = 8.dp, captionTop = 9.dp, captionWidth = 162.dp,
        target = 48.dp,
    )
    else -> Metrics(
        edge = 30.dp, stageTop = 12.dp, stageBottom = 6.dp, headBottom = 11.dp,
        zoneGap = 40.dp, rowGap = 13.dp, capsuleStart = 20.dp, capsule = 52.dp, copyGap = 16.dp,
        actionsGap = 14.dp, actionsTop = 32.dp,
        statusHeight = 20.dp, statusTop = 12.dp,
        footTop = 8.dp, footBottom = 2.dp,
        plate = 157.dp, platePadding = 9.dp, captionTop = 11.dp, captionWidth = 180.dp,
        target = 48.dp,
    )
}

/**
 * How much of the frame is left for the middle band.
 *
 * ## Why this is arithmetic and not a measurement
 *
 * It should be a measurement. It cannot be one here, because the only harness
 * that can run Compose without a device does not lay text out: under Robolectric
 * every `Text` measures 35dp tall whatever its style — the 32dp headline, the
 * 20dp legal line and the 18dp overline all identical — and native graphics does
 * not change it. That inflates this column by about 40dp, which is more than the
 * margin the design has, so a runtime assertion about fit would be an assertion
 * about the harness.
 *
 * So the fit is checked where the numbers are real: from the [Metrics] the screen
 * is built from and the line heights `CastivioType` declares. `ActivationLayoutTest`
 * still asserts that Compose *places* all of it — that is the bug that shipped —
 * and this says the places it puts them add up. Two claims, each measured where it
 * can be measured honestly.
 *
 * @param frame the whole display. `:app` is edge-to-edge; nothing is subtracted.
 * @param title the title's declared line height, which the language chip usually
 *   exceeds — the header is the taller of the two, not the sum.
 * @param legal the legal line's declared line height, at one line.
 */
internal fun Metrics.bandHeight(frame: Dp, title: Dp, legal: Dp): Dp =
    frame - stageTop - stageBottom -
        (maxOf(title, target) + headBottom) - // header
        HAIRLINES -
        (footTop + legal + footBottom) // footer

/**
 * What the identity column needs, from the same numbers that build it.
 *
 * Mirrors `IdentityZone` child for child: two label-and-value rows, the actions,
 * the reserved status line, and the `rowGap` the Column puts between each pair.
 * The value rows are as tall as the copy control beside them, and the buttons are
 * a touch target — neither is text-driven, which is why this arithmetic is
 * trustworthy where a Robolectric measurement is not.
 *
 * @param overline the declared line height of the MAC ADDRESS / DEVICE KEY labels.
 */
internal fun Metrics.identityHeight(): Dp {
    val actions = (actionsTop - rowGap).coerceAtLeast(0.dp) + Sizing.minTouchTarget
    val status = (statusTop - rowGap).coerceAtLeast(0.dp) + statusHeight
    return capsule + rowGap + capsule + rowGap + actions + rowGap + status
}

/**
 * What the QR side of the band needs: the plate, the gap, and the caption.
 *
 * The plate grew 6% in the final polish pass and the caption did not move, so
 * this exists to say out loud that the taller zone still fits between the
 * hairlines. The identity column is the tall one on every frame today; that is a
 * fact about the current numbers, not a law, and a gate that only measured the
 * column would go on passing while the QR quietly overran.
 *
 * @param captionLines budgeted at two. The caption is a sentence at a fixed
 *   width, it wraps in most of the 37 languages, and budgeting for one line would
 *   be budgeting for English.
 */
internal fun Metrics.codeHeight(caption: Dp, captionLines: Int = 2): Dp =
    plate + captionTop + caption * captionLines

/** The two full-bleed rules that bracket the field band, at a pixel each. */
private val HAIRLINES = 2.dp

/**
 * The first screen, where a subscription is added.
 *
 * ## There is no card, and one is not to be reintroduced
 *
 * An earlier version of this file put the address, its actions and the code in
 * one centred glass rectangle. Everything fitted and nothing overflowed, and it
 * still read as a component dropped onto a canvas: clutter in the middle, unused
 * screen around it.
 *
 * The screen is three bands divided by two full-width hairlines — a header, a
 * field, a footer — which is what makes the background read as part of the
 * interface rather than as margin. The address lives directly on the aurora as
 * typography, because it is information and must never be mistaken for a
 * control. The code keeps a surface, because a QR is only scannable on a light
 * ground; that is the one container on the screen and the only one that earns
 * its keep.
 *
 * Both zones size to their content and the **pair** is centred. Neither
 * stretches: a stretching identity column is what produced the gulf down the
 * middle that the earlier composition was rejected for.
 *
 * The composition is approved and locked. `design/mockups/activation-mac.html`
 * is the drawing, `design/activation-spec.md` the contract.
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
    val colors = CastivioTheme.colors
    val tv = CastivioTheme.device.isTv

    BoxWithConstraints(modifier.fillMaxSize()) {
        val m = metricsFor(tv, maxHeight)
        Column(
            Modifier
                .fillMaxSize()
                .testTag(ActivationTags.STAGE)
                .padding(start = m.edge, end = m.edge, top = m.stageTop, bottom = m.stageBottom),
        ) {
            Header(m = m, tv = tv, trialDays = identity.trialDaysRemaining, onOpenLanguage = onOpenLanguage)
            Hairline()

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
                    .testTag(ActivationTags.FIELD),
                horizontalArrangement = Arrangement.spacedBy(m.zoneGap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IdentityZone(
                    modifier = Modifier.testTag(ActivationTags.IDENTITY),
                    identity = identity,
                    m = m,
                    tv = tv,
                    onAddPlaylist = onAddPlaylist,
                    onRefresh = onRefresh,
                    onCopied = onCopied,
                )
                CodeZone(identity = identity, m = m)
            }

            Hairline()
            Text(
                text = stringResource(R.string.legal_player_only),
                style = CastivioType.bodySmall,
                color = colors.onBackgroundMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ActivationTags.FOOTER)
                    .padding(top = m.footTop, bottom = m.footBottom),
            )
        }
    }
}

/**
 * A hairline that fades at both ends.
 *
 * A rule that meets the screen edge squarely reads as a border on a box. Fading
 * it is what keeps the three bands reading as one surface divided rather than
 * three surfaces stacked.
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

@Composable
private fun Header(m: Metrics, tv: Boolean, trialDays: Int?, onOpenLanguage: () -> Unit) {
    val colors = CastivioTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(ActivationTags.HEADER)
            .padding(bottom = m.headBottom),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.activation_title),
            style = if (tv) CastivioType.headlineLarge else CastivioType.headlineMedium,
            color = colors.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        Box(Modifier.weight(1f))

        // Castivio's trial, carrying Castivio's name. The word "subscription"
        // belongs to the provider and is never used here: a screen that said
        // "Add your subscription" beside "Your subscription: 7 days" would
        // contradict itself.
        //
        // The count comes from `EntitlementRepository` and is null until the
        // sealed record has been read -- so the chip is absent for an instant
        // rather than announcing a seven that might turn out to be a two. It was
        // a hardcoded 7 until this commit, which is a number that would have been
        // wrong on every launch after the first.
        if (trialDays != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(TRIAL_DOT)
                        .clip(RoundedCornerShape(Radius.pill))
                        // The same brush the primary button is filled with, not a
                        // flat token that happens to be near it. Two blues that
                        // are almost the same is worse than one, and this dot and
                        // Add playlist are the only two primary-coloured things
                        // on the screen.
                        .background(colors.primaryBrush),
                )
                Text(
                    text = stringResource(R.string.trial_name),
                    style = CastivioType.bodyMedium,
                    color = colors.onBackgroundVariant,
                )
                // Quantity and argument are the same number, which is what a
                // plural resource needs: Arabic and Polish choose different forms
                // for 1, 2, a few and many, and a string built here would get all
                // of that wrong.
                val days = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.trial_days, trialDays, trialDays,
                )
                Text(
                    text = emphasiseCount(days, trialDays),
                    style = CastivioType.bodyMedium,
                    color = colors.primary,
                )
            }
        }

        LanguageChip(m, onOpenLanguage)
    }
}

/**
 * The language control.
 *
 * Drawn at 32dp and answering at 48: a chip that looks like a button is a target
 * that has to behave like one. The declared minimum is the one enforced, never
 * the drawn size.
 */
@Composable
private fun LanguageChip(m: Metrics, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.pill)
    val border by animateColorAsState(
        if (focused) colors.focusRing else colors.glassBorder,
        Motion.focusSpec(),
        label = "languageChipBorder",
    )
    val label = stringResource(R.string.language)

    Row(
        Modifier
            .heightIn(min = m.target)
            .castivioFocusScale(Motion.focusScaleIcon, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(shape)
            .background(colors.glassFill)
            .border(BorderStroke(1.dp, border), shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.md),
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
            style = CastivioType.bodySmall,
            color = colors.onBackground,
            modifier = Modifier.clearAndSetSemantics { contentDescription = label },
        )
    }
}

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

    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.rowGap)) {
        IdentityCapsule(
            modifier = Modifier.testTag(ActivationTags.MAC_CAPSULE),
            m = m,
            label = stringResource(R.string.mac_label),
            value = address ?: ADDRESS_PLACEHOLDER,
            spoken = address?.let { stringResource(R.string.mac_spoken, spaced(it)) },
            style = if (tv) CastivioType.codeHero else CastivioType.codeCompact,
            copyLabel = stringResource(R.string.copy_mac),
            isCopied = identity.addressCopied,
            enabled = address != null,
            onCopy = {
                clipboard.setText(AnnotatedString(address.orEmpty()))
                onCopied(Copied.Address)
            },
        )

        // Absent until an issuing contract exists. Nothing derives one, and an
        // empty slot where a credential belongs reads as a fault, so the row is
        // not composed at all rather than composed empty.
        identity.deviceKey?.let { key ->
            IdentityCapsule(
                modifier = Modifier.testTag(ActivationTags.KEY_CAPSULE),
                m = m,
                label = stringResource(R.string.key_label),
                value = key,
                spoken = null,
                style = if (tv) CastivioType.codeKeyTv else CastivioType.codeKey,
                copyLabel = stringResource(R.string.copy_key),
                isCopied = identity.keyCopied,
                enabled = true,
                onCopy = {
                    clipboard.setText(AnnotatedString(key))
                    onCopied(Copied.Key)
                },
            )
        }

        Row(
            // `actionsTop` is measured from the row above, and the Column already
            // adds `rowGap`, so the padding carries only the difference.
            Modifier
                .padding(top = (m.actionsTop - m.rowGap).coerceAtLeast(0.dp))
                .testTag(ActivationTags.ACTIONS),
            horizontalArrangement = Arrangement.spacedBy(m.actionsGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CastivioButton(
                text = stringResource(R.string.add_playlist),
                weight = ButtonWeight.Primary,
                onClick = onAddPlaylist,
            )
            CastivioButton(
                text = stringResource(refreshLabel(identity.refresh)),
                weight = ButtonWeight.Secondary,
                icon = Icons.Rounded.Refresh,
                enabled = identity.refresh != RefreshState.Checking,
                onClick = onRefresh,
            )
        }

        StatusLine(identity, m)
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
private fun StatusLine(identity: ActivationIdentityState, m: Metrics) {
    val colors = CastivioTheme.colors
    val status = statusMessage(identity)

    Box(
        Modifier
            .padding(top = (m.statusTop - m.rowGap).coerceAtLeast(0.dp))
            .heightIn(min = m.statusHeight)
            .testTag(ActivationTags.STATUS)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (status != null) {
            val tone = when (status.tone) {
                Tone.Good -> colors.success
                Tone.Missing -> colors.warning
                Tone.Broken -> colors.danger
                Tone.Copied -> colors.success
                Tone.Neutral -> colors.onBackgroundMuted
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(STATUS_DOT)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(tone),
                )
                Text(
                    text = stringResource(status.message),
                    // A step down from bodySmall. The line is a caption under two
                    // buttons, not a paragraph, and 12sp in Inter's Medium is
                    // still comfortably readable at arm's length.
                    style = CastivioType.labelMedium,
                    // The sentence carries the tone too, not just the dot. A
                    // six-dp circle is the whole signal otherwise, and it is the
                    // part a colour-blind reader is least likely to get.
                    //
                    // A copy confirmation is drawn at full strength rather than
                    // the muted variant, deliberately: the operating system draws
                    // its own clipboard toast a moment later, in the system's
                    // language, and Castivio's answer is the one the user should
                    // be reading. Size went down; presence went up.
                    color = when (status.tone) {
                        Tone.Copied -> colors.onBackground
                        Tone.Neutral -> colors.onBackgroundVariant
                        else -> tone
                    },
                )
            }
        }
    }
}

/**
 * One identifier, in a glass pill.
 *
 * ## Why a capsule and not the loose text it replaces
 *
 * The address used to sit directly on the aurora as typography, on the argument
 * that information must never be mistaken for a control. That argument was right
 * about the address and wrong about the group: with a copy button floating beside
 * it and nothing tying the two together, the eye had to work out that the square
 * belonged to the digits above it rather than to the label below.
 *
 * The pill states the grouping. It is a container for one fact and its one
 * action, and it earns its keep the way the QR plate does.
 *
 * ## The shape
 *
 * `RoundedCornerShape(50)` -- a percentage, not a dp. It is a true pill at 52dp
 * and still a true pill at the television's 64, which a fixed 26dp corner would
 * not be. A capsule whose corners are nearly-but-not-quite its half-height is
 * the detail that reads as cheap, and the height is not one number.
 *
 * Horizontal rather than the label-above-value it replaces, and that is also what
 * makes the vertical budget work: 56dp instead of 69, twice, which is the 26dp
 * the system-bar insets need. The composition did not change to make room; the
 * grouping did, and the room came with it.
 */
@Composable
private fun IdentityCapsule(
    m: Metrics,
    label: String,
    value: String,
    spoken: String?,
    style: androidx.compose.ui.text.TextStyle,
    copyLabel: String,
    isCopied: Boolean,
    enabled: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(percent = 50)

    Row(
        modifier
            .height(m.capsule)
            .clip(shape)
            // Semi-transparent over the aurora, so the gradient shows through and
            // the pill belongs to the background rather than sitting on it. The
            // border is the soft token, not the loud one: at this size a strong
            // outline turns a container into a button.
            .background(colors.glassFill)
            .border(BorderStroke(1.dp, colors.glassBorderSoft), shape)
            .padding(start = m.capsuleStart, end = CAPSULE_END),
        horizontalArrangement = Arrangement.spacedBy(m.copyGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = CastivioType.overline,
            color = colors.onBackgroundVariant,
        )

        // The address is Latin and digits inside a paragraph that may run right
        // to left. Left to inherit the paragraph, the bidi algorithm reorders its
        // runs -- and the all-neutral placeholder is reordered outright. A licence
        // address that reads differently in Arabic is a support case in every RTL
        // market. The pill is laid out with start/end, so the capsule mirrors and
        // the value inside it does not.
        Text(
            text = ltrIsolate(value),
            style = style,
            color = colors.onBackground,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = spoken ?: value
            },
        )

        if (enabled) {
            CopyControl(m = m, label = copyLabel, isCopied = isCopied, onClick = onCopy)
        }
    }
}

/**
 * Copy, and its confirmation.
 *
 * The confirmation is a glyph swap inside a box of unchanged size, so nothing on
 * the screen moves when it happens. Two of these exist and they are independent:
 * copying the address must not clear the tick on the key.
 */
@Composable
private fun CopyControl(m: Metrics, label: String, isCopied: Boolean, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(percent = 50)
    val border by animateColorAsState(
        when {
            focused -> colors.focusRing
            isCopied -> colors.success
            // Transparent, not a hairline: inside a bordered pill a second
            // outline four dp away from the first is the thing that made the
            // control look bolted on rather than built in.
            else -> Color.Transparent
        },
        Motion.focusSpec(),
        label = "copyBorder",
    )

    Box(
        Modifier
            // `m.target`, which is 48dp on a phone and 56 on a television --
            // `Sizing.minTvTarget`, and a D-pad target may not be smaller.
            //
            // This was pinned to the phone constant during a polish pass, which
            // shrank the television's control by 8dp and shipped. No gate caught
            // it: the budget test asserted the *phone* floor on every frame
            // including the TV, so the one number that was wrong was the one
            // number nothing checked. It does now.
            .size(m.target)
            .castivioFocusScale(Motion.focusScaleIcon, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(shape)
            // Quieter than the pill it sits in, deliberately. A control drawn at
            // the same weight as its container reads as a second container; this
            // one is a utility inside one, so it takes the softer fill and shows
            // no border at all until it has something to say -- focus, or a
            // confirmation. At rest the icon and the shape carry it.
            .background(colors.glassFillStrong)
            .border(BorderStroke(1.dp, border), shape)
            .clickable(interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = null,
            tint = if (isCopied) colors.success else colors.onBackgroundVariant,
            modifier = Modifier.size(Sizing.iconSm),
        )
    }
}

@Composable
private fun CodeZone(identity: ActivationIdentityState, m: Metrics) {
    val colors = CastivioTheme.colors
    val bitmap = remember(identity.qr) { identity.qr?.asImageBitmap() } ?: return

    Column(
        Modifier.testTag(ActivationTags.CODE_ZONE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.captionTop),
    ) {
        Box(
            Modifier
                .size(m.plate)
                .testTag(ActivationTags.QR)
                // A soft lift, not a card. The plate is white on a dark gradient
                // and reads as a hole punched in the background without it; a few
                // dp of shadow is enough to make it sit *on* the screen. No
                // border, no second surface -- the size and position are
                // unchanged and the plate is still the only container here.
                .shadow(QR_LIFT, RoundedCornerShape(Radius.md))
                .clip(RoundedCornerShape(Radius.md))
                .background(Color.White)
                .padding(m.platePadding),
        ) {
            Image(
                bitmap = bitmap,
                // The caption below says what the code is for; a reader that
                // announces the image as well says the same thing twice.
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
            )
        }
        Text(
            text = stringResource(R.string.qr_caption),
            style = CastivioType.bodySmall,
            color = colors.onBackgroundVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(m.captionWidth),
        )
    }
}

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
 *
 * So the two are kept apart, `danger` stays reserved for [RefreshState.Error],
 * and the tone travels with the message rather than being re-derived from the
 * refresh state at the point of drawing.
 */
internal fun statusMessage(identity: ActivationIdentityState): Status? = when {
    // **The transient answers come first, and that ordering is the fix for a
    // real defect.** Copying used to be listed below the refresh outcomes, so a
    // user who had pressed Refresh once -- leaving a permanent "no subscription
    // yet" -- could copy the address and see no acknowledgement at all. The tick
    // on the capsule lit, the line underneath went on describing the
    // subscription, and the only visible confirmation was the one the operating
    // system draws, in the system's language rather than Castivio's.
    //
    // A copy confirmation lasts 1.5 seconds and then yields the line back. A
    // subscription status is true until it changes. When both have something to
    // say, the one that is about to disappear is the one the user just caused.
    identity.lastCopied == Copied.Address -> Status(R.string.copied_mac, Tone.Copied)
    identity.lastCopied == Copied.Key -> Status(R.string.copied_key, Tone.Copied)

    // Checking is its own sentence on the button, not here; the line keeps
    // describing the subscription while the check runs.
    identity.refresh == RefreshState.Found -> Status(R.string.refresh_found, Tone.Good)

    identity.refresh == RefreshState.Error -> Status(R.string.refresh_error, Tone.Broken)

    // Asked and told no, or never asked at all: the user's situation is the same
    // either way, so it is the same sentence in the same tone. A colour that
    // changed because a button had been pressed would be describing the button
    // rather than the subscription.
    //
    // The resting case is not a guess. This screen is only reached when no
    // provider is usable -- `startDestination` sends a device that has one to
    // Home -- so "nothing added yet" is the precondition for being here.
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
     * the screen that competes with something the operating system draws. The
     * platform's clipboard toast arrives a moment later in the *system* language,
     * which may not be the app's, so Castivio's confirmation is the one that has
     * to be believed -- full-strength text and a green dot, not a murmur.
     */
    Copied,

    Missing,
    Broken,
}

/**
 * The count inside "Castivio trial 7 days", one weight heavier than the words.
 *
 * The number is the only part anybody reads twice, so it carries the emphasis and
 * the sentence around it stays quiet. One `Text`, one span — not two composables
 * with a gap between them, which would break the moment a language puts the
 * numeral in the middle or at the end.
 *
 * **Found rather than assumed.** The plural has already been formatted by the
 * resource system in the interface's locale, and that locale may not use Western
 * digits — Arabic renders 7 as ٧. So the same number is formatted the same way
 * and looked up in the result, with the ASCII spelling as a fallback. If neither
 * is present the sentence is drawn unemphasised, which is the right failure: a
 * missing bold is invisible, a bold applied at the wrong offset is a typo.
 */
private fun emphasiseCount(text: String, count: Int): AnnotatedString {
    val spellings = listOf(
        String.format(Locale.getDefault(), "%d", count),
        count.toString(),
    )
    return buildAnnotatedString {
        append(text)
        for (spelling in spellings) {
            val at = text.indexOf(spelling)
            if (spelling.isNotEmpty() && at >= 0) {
                addStyle(
                    SpanStyle(fontWeight = FontWeight.SemiBold),
                    at,
                    at + spelling.length,
                )
                return@buildAnnotatedString
            }
        }
    }
}

/** `2F:19:EB:20:44:7C` read as separated pairs rather than one long number. */
private fun spaced(address: String): String = address.replace(":", " ")

/**
 * Left-to-right, isolated from whatever paragraph it lands in.
 *
 * `LEFT-TO-RIGHT ISOLATE` … `POP DIRECTIONAL ISOLATE`. The address keeps its own
 * order in an Arabic interface, and cannot reorder the row around it either.
 */
private fun ltrIsolate(value: String): String = "⁦$value⁩"

/** Shown for the instant before the identity resolves. Never a real address. */
private const val ADDRESS_PLACEHOLDER = "··:··:··:··:··:··"

/**
 * The gap between the copy control and the pill's trailing edge.
 *
 * Two dp, which is what a 48dp control leaves inside a 52dp pill and what a 56dp
 * control leaves inside a 64dp one -- four on the television, because a remote
 * needs the bigger target and the pill grew to hold it rather than the target
 * shrinking to fit the pill. That was the bug.
 */
private val CAPSULE_END = 2.dp

private val TRIAL_DOT = 6.dp
private val STATUS_DOT = 6.dp

/** Enough to separate white from the aurora, not enough to read as a card. */
private val QR_LIFT = 10.dp
