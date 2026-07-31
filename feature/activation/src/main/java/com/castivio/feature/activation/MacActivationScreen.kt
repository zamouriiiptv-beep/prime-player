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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
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

    Column(modifier.fillMaxSize()) {
        Header(tv = tv, onOpenLanguage = onOpenLanguage)
        Hairline()

        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = if (tv) Spacing.xxl else Spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(
                if (tv) Spacing.xxxl else Spacing.xxl,
                Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IdentityZone(
                identity = identity,
                tv = tv,
                onAddPlaylist = onAddPlaylist,
                onRefresh = onRefresh,
                onCopied = onCopied,
            )
            CodeZone(identity = identity, tv = tv)
        }

        Hairline()
        Text(
            text = stringResource(R.string.legal_player_only),
            style = CastivioType.bodySmall,
            color = colors.onBackgroundMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
        )
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
private fun Header(tv: Boolean, onOpenLanguage: () -> Unit) {
    val colors = CastivioTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (tv) Spacing.xxl else Spacing.xl,
                vertical = Spacing.md,
            ),
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

        LanguageChip(onOpenLanguage)
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
private fun LanguageChip(onClick: () -> Unit) {
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
            .heightIn(min = Sizing.minTouchTarget)
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
    tv: Boolean,
    onAddPlaylist: () -> Unit,
    onRefresh: () -> Unit,
    onCopied: (Copied) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val address = identity.address

    Column(verticalArrangement = Arrangement.spacedBy(if (tv) Spacing.md else Spacing.sm)) {
        IdentityRow(
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
            Modifier.padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
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

        StatusLine(identity)
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
private fun StatusLine(identity: ActivationIdentityState) {
    val colors = CastivioTheme.colors
    val tv = CastivioTheme.device.isTv
    val message = statusMessage(identity)

    Box(
        Modifier
            .heightIn(min = if (tv) STATUS_TV else STATUS_PHONE)
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
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(
            text = label,
            style = CastivioType.overline,
            color = colors.onBackgroundVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
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
                CopyControl(label = copyLabel, isCopied = isCopied, onClick = onCopy)
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
private fun CopyControl(label: String, isCopied: Boolean, onClick: () -> Unit) {
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
            .size(Sizing.minTouchTarget)
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
private fun CodeZone(identity: ActivationIdentityState, tv: Boolean) {
    val colors = CastivioTheme.colors
    val bitmap = remember(identity.qr) { identity.qr?.asImageBitmap() } ?: return

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            Modifier
                .size(if (tv) PLATE_TV else PLATE_PHONE)
                .clip(RoundedCornerShape(Radius.md))
                .background(Color.White)
                .padding(if (tv) Spacing.sm else Spacing.xs),
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
            modifier = Modifier.width(if (tv) CAPTION_TV else CAPTION_PHONE),
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
private val STATUS_PHONE = 20.dp
private val STATUS_TV = 24.dp
private val PLATE_PHONE = 148.dp
private val PLATE_TV = 196.dp
private val CAPTION_PHONE = 180.dp
private val CAPTION_TV = 236.dp
