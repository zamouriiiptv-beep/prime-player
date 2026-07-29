package com.castivio.feature.activation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.GlassCard
import com.castivio.core.design.components.GlassHeroCard
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing

/**
 * The first thing anyone sees, and the route most of them will take.
 *
 * The address is the screen. It is set in the code face at hero size, on its own, with
 * everything else arranged around it — because the only task here is reading six pairs
 * of characters off a television and sending them to somebody, and every other element
 * on the screen is competing with that.
 *
 * What it does **not** do is pretend. Castivio's activation service is not running, so
 * this screen says so in the same words a support agent would use, and points at the
 * route that does work. A spinner that waited for a subscription which cannot arrive
 * would be a lie told politely.
 */
@Composable
internal fun MacActivationScreen(
    identity: MacIdentityState,
    onAddManually: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val clipboard = LocalClipboardManager.current
    val address = identity.address

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                text = stringResource(R.string.activation_title),
                style = CastivioType.headlineLarge,
                color = colors.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.activation_subtitle),
                style = CastivioType.bodyLarge,
                color = colors.onBackgroundVariant,
            )
        }

        GlassHeroCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.mac_heading),
                        style = CastivioType.titleMedium,
                        color = colors.onBackground,
                        modifier = Modifier.semantics { heading() },
                    )
                    RecommendedTag()
                }

                // The one element this screen exists for. Announced as a whole phrase so a
                // screen reader says the address rather than spelling out punctuation.
                Text(
                    text = address ?: PLACEHOLDER,
                    style = CastivioType.codeHero,
                    color = colors.onBackground,
                    modifier = Modifier.semantics {
                        contentDescription = address?.let { readableAddress(it) } ?: ""
                    },
                )

                Text(
                    text = stringResource(R.string.mac_explainer),
                    style = CastivioType.bodyMedium,
                    color = colors.onBackgroundVariant,
                )

                if (address != null) {
                    CastivioButton(
                        text = stringResource(R.string.mac_copy),
                        weight = ButtonWeight.Primary,
                        onClick = { clipboard.setText(AnnotatedString(address)) },
                    )
                }
            }
        }

        if (identity.qr != null) {
            QrPanel(identity)
        }

        // Not an error state: nothing has failed. It is a statement of what is true
        // today, next to the route that works.
        GlassCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    text = stringResource(R.string.mac_portal_unavailable_title),
                    style = CastivioType.titleMedium,
                    color = colors.warning,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.mac_portal_unavailable_detail),
                    style = CastivioType.bodyMedium,
                    color = colors.onBackgroundVariant,
                )
                CastivioButton(
                    text = stringResource(R.string.add_manually),
                    weight = ButtonWeight.Primary,
                    onClick = onAddManually,
                )
            }
        }
    }
}

@Composable
private fun RecommendedTag() {
    val colors = CastivioTheme.colors
    Text(
        text = stringResource(R.string.mac_recommended),
        style = CastivioType.labelSmall,
        color = colors.onPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(colors.primary)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
    )
}

@Composable
private fun QrPanel(identity: MacIdentityState) {
    val colors = CastivioTheme.colors
    val bitmap = remember(identity.qr) { identity.qr?.asImageBitmap() }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(Spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (bitmap != null) {
                Box(
                    Modifier
                        .size(QR_SIZE)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(colors.onBackground),
                ) {
                    Image(
                        bitmap = bitmap,
                        // The caption beside it says what the code is for; a reader that
                        // announces the image as well says the same thing twice.
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                    )
                }
            }
            Text(
                text = stringResource(R.string.mac_qr_caption),
                style = CastivioType.bodyMedium,
                color = colors.onBackgroundVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.widthIn(max = QR_CAPTION_WIDTH),
            )
        }
    }
}

/**
 * `2F:19:EB:20:44:7C` read aloud as separated pairs.
 *
 * Without this a screen reader runs the pairs together into one long number, which is
 * unusable for the one thing this screen is for — reading the address out to somebody.
 */
private fun readableAddress(address: String): String = address.replace(":", " ")

/** Shown for the instant before the identity resolves. Never a real address. */
private const val PLACEHOLDER = "··:··:··:··:··:··"

private val QR_SIZE = Sizing.iconXl * 6
private val QR_CAPTION_WIDTH = Sizing.maxContentWidth / 4
