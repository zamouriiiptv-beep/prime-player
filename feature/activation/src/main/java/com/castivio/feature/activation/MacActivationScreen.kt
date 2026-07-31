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
 * | 873×393 | 284dp | 254dp | 30dp |
 * | 800×360 | 259dp | 250dp | 9dp |
 * | TV 960×540 | 337dp | 298dp | 39dp |
 *
 * Nine millimetres on the short phone is thin, and it is the frame to check first
 * whenever anything on this screen grows. Those three rows are not a comment:
 * [bandHeight] and [identityHeight] compute them, and `ActivationBudgetTest`
 * fails if any of them goes negative.
 */
internal data class Metrics(
    val edge: Dp,
    val stageTop: Dp,
    val stageBottom: Dp,
    val headBottom: Dp,
    val zoneGap: Dp,
    val rowGap: Dp,
    val labelGap: Dp,
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
        zoneGap = 52.dp, rowGap = 17.dp, labelGap = 7.dp, copyGap = 22.dp,
        actionsGap = 20.dp, actionsTop = 30.dp,
        statusHeight = 24.dp, statusTop = 16.dp,
        footTop = 13.dp, footBottom = 0.dp,
        plate = 196.dp, platePadding = 12.dp, captionTop = 15.dp, captionWidth = 236.dp,
        target = 56.dp,
    )
    available < SHORT_PHONE -> Metrics(
        edge = 26.dp, stageTop = 10.dp, stageBottom = 4.dp, headBottom = 9.dp,
        zoneGap = 34.dp, rowGap = 13.dp, labelGap = 3.dp, copyGap = 14.dp,
        actionsGap = 12.dp, actionsTop = 18.dp,
        statusHeight = 20.dp, statusTop = 12.dp,
        footTop = 7.dp, footBottom = 1.dp,
        plate = 130.dp, platePadding = 8.dp, captionTop = 9.dp, captionWidth = 162.dp,
        target = 48.dp,
    )
    else -> Metrics(
        edge = 30.dp, stageTop = 12.dp, stageBottom = 6.dp, headBottom = 11.dp,
        zoneGap = 40.dp, rowGap = 13.dp, labelGap = 3.dp, copyGap = 16.dp,
        actionsGap = 14.dp, actionsTop = 22.dp,
        statusHeight = 20.dp, statusTop = 12.dp,
        footTop = 8.dp, footBottom = 2.dp,
        plate = 148.dp, platePadding = 9.dp, captionTop = 11.dp, captionWidth = 180.dp,
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
internal fun Metrics.identityHeight(overline: Dp): Dp {
    val valueRow = overline + labelGap + target
    val actions = (actionsTop - rowGap).coerceAtLeast(0.dp) + Sizing.minTouchTarget
    val status = (statusTop - rowGap).coerceAtLeast(0.dp) + statusHeight
    return valueRow + rowGap + valueRow + rowGap + actions + rowGap + status
}

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
            Header(m = m, tv = tv, onOpenLanguage = onOpenLanguage)
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
private fun Header(m: Metrics, tv: Boolean, onOpenLanguage: () -> Unit) {
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

        // Castivio's seven days, carrying Castivio's name. The word
        // "subscription" belongs to the provider and is never used here: a screen
        // that said "Add your subscription" beside "Your subscription: 7 days"
        // would contradict itself.
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(TRIAL_DOT)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(colors.primary),
            )
            Text(
                text = stringResource(R.string.trial_name),
                style = CastivioType.bodyMedium,
                color = colors.onBackgroundVariant,
            )
            Text(
                text = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.trial_days, TRIAL_DAYS, TRIAL_DAYS,
                ),
                style = CastivioType.bodyMedium,
                color = colors.primary,
            )
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
        IdentityRow(
            m = m,
            label = stringResource(R.string.mac_label),
            value = address ?: ADDRESS_PLACEHOLDER,
            spoken = address?.let { stringResource(R.string.mac_spoken, spaced(it)) },
            style = if (tv) CastivioType.codeHero else CastivioType.codeCompact,
            copyLabel = stringResource(R.string.copy_mac),
            isCopied = identity.copied == Copied.Address,
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
            IdentityRow(
                m = m,
                label = stringResource(R.string.key_label),
                value = key,
                spoken = null,
                style = if (tv) CastivioType.codeKeyTv else CastivioType.codeKey,
                copyLabel = stringResource(R.string.copy_key),
                isCopied = identity.copied == Copied.Key,
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
    val message = statusMessage(identity)

    Box(
        Modifier
            .padding(top = (m.statusTop - m.rowGap).coerceAtLeast(0.dp))
            .heightIn(min = m.statusHeight)
            .testTag(ActivationTags.STATUS)
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (message != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(STATUS_DOT)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(
                            when (identity.refresh) {
                                RefreshState.Error -> colors.danger
                                RefreshState.Found -> colors.success
                                else -> colors.onBackgroundMuted
                            },
                        ),
                )
                Text(
                    text = stringResource(message),
                    style = CastivioType.bodySmall,
                    color = colors.onBackgroundVariant,
                )
            }
        }
    }
}

@Composable
private fun IdentityRow(
    m: Metrics,
    label: String,
    value: String,
    spoken: String?,
    style: androidx.compose.ui.text.TextStyle,
    copyLabel: String,
    isCopied: Boolean,
    enabled: Boolean,
    onCopy: () -> Unit,
) {
    val colors = CastivioTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(m.labelGap)) {
        Text(
            text = label,
            style = CastivioType.overline,
            color = colors.onBackgroundVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(m.copyGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The address is Latin and digits inside a paragraph that may run
            // right to left. Left to inherit the paragraph, the bidi algorithm
            // reorders its runs -- and the all-neutral placeholder is reordered
            // outright. A licence address that reads differently in Arabic is a
            // support case in every RTL market.
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
    val shape = RoundedCornerShape(Radius.xs)
    val border by animateColorAsState(
        when {
            focused -> colors.focusRing
            isCopied -> colors.success
            else -> colors.glassBorder
        },
        Motion.focusSpec(),
        label = "copyBorder",
    )

    Box(
        Modifier
            .size(m.target)
            .castivioFocusScale(Motion.focusScaleIcon, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(shape)
            .background(colors.glassFill)
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

private fun statusMessage(identity: ActivationIdentityState): Int? = when {
    identity.refresh == RefreshState.Found -> R.string.refresh_found
    identity.refresh == RefreshState.None -> R.string.refresh_none
    identity.refresh == RefreshState.Error -> R.string.refresh_error
    identity.copied == Copied.Address -> R.string.copied_mac
    identity.copied == Copied.Key -> R.string.copied_key
    else -> null
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

private const val TRIAL_DAYS = 7
private val TRIAL_DOT = 6.dp
private val STATUS_DOT = 6.dp
