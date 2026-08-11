package com.castivio.core.design.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Palette
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing

/**
 * The device identity components, shared by every screen that shows one.
 *
 * ## Why these live here and not in the screen that first drew them
 *
 * They were private to `:feature:activation` until the licence screen needed the
 * same pill, the same copy control and the same plate. Invariant 6 says a
 * component has one declaration and expresses its variants as parameters, and
 * two subtly different capsules is exactly the decay it exists to prevent —
 * the second one gets a 1dp border because somebody preferred it, and six months
 * later nobody can say which is correct.
 *
 * Moving them changed no pixels. The activation screen passes the same numbers it
 * always did; they arrive as [CapsuleMetrics] instead of as a private `Metrics`,
 * because a shared component may not know what an activation frame is.
 */

/**
 * The four numbers a capsule needs, which differ per frame and per screen.
 *
 * Not a `Metrics` and not a device class: this component has no opinion about
 * televisions, it is told a height and a target. The caller owns the decision,
 * which is what lets the activation screen and the licence screen size their
 * pills from their own per-frame tables without either knowing about the other.
 */
data class CapsuleMetrics(
    /**
     * 52dp on a phone, 64 on a television.
     *
     * Not one number, and the reason is a bug that shipped: `Sizing.minTvTarget`
     * is 56dp, a D-pad target may not be smaller, and a 56dp control does not fit
     * inside a 52dp pill. The pill grows to hold the target rather than the
     * target shrinking to fit the pill.
     */
    val height: Dp,
    /** Inset from the leading edge to the label. */
    val startPadding: Dp,
    /** Between the label, the value and the control. */
    val gap: Dp,
    /** The copy control's size: [Sizing.minTouchTarget] or [Sizing.minTvTarget]. */
    val target: Dp,
)

/**
 * The temperature a capsule is drawn at.
 *
 * [None] is the pill exactly as it has always been: `glassFill`, the soft white
 * border, and a copy control on `glassFillStrong`. Every screen that does not
 * ask for a temperature keeps it, which is how the licence screen is untouched
 * by a change made for the activation screen.
 *
 * The two tinted cases sort two identifiers that sit one above the other. On
 * "Add a playlist" the address is the device saying what it is and the key is
 * what a licence is issued against, and they were the same white slab. So the
 * address takes [Azure] and the key takes [Violet] — one hue each, from the two
 * ends of the brand, and no third option: a variant list is a place where
 * "just one more shade" accumulates.
 */
enum class CapsuleTint { None, Azure, Violet }

/**
 * One identifier, its label and its copy control, in a glass pill.
 *
 * ## The shape
 *
 * `RoundedCornerShape(50)` — a percentage, not a dp. It is a true pill at 52dp
 * and still one at 64, which a fixed 26dp corner would not be. A capsule whose
 * corners are nearly-but-not-quite its half-height is the detail that reads as
 * cheap.
 *
 * ## Horizontal, and why that is structural
 *
 * The label sits beside the value rather than above it. That is 52dp instead of
 * the 69 a stacked label needed, twice — and those 26dp are what let the
 * activation screen take its system-bar insets without the identity column
 * overrunning its band. The grouping paid for the room.
 *
 * @param spoken what a screen reader says instead of the raw value. A MAC address
 *   read character by character is punctuation; `mac_spoken` spaces the pairs.
 */
@Composable
fun IdentityCapsule(
    metrics: CapsuleMetrics,
    label: String,
    value: String,
    valueStyle: TextStyle,
    copyLabel: String,
    isCopied: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    spoken: String? = null,
    copyEnabled: Boolean = true,
    tint: CapsuleTint = CapsuleTint.None,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(percent = 50)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Row(
        modifier
            .height(metrics.height)
            .clip(shape)
            // Semi-transparent over the aurora, so the gradient shows through and
            // the pill belongs to the background rather than sitting on it. The
            // soft border, not the loud one: at this size a strong outline turns
            // a container into a button.
            .background(capsuleFill(tint, colors.glassFill, rtl))
            .border(BorderStroke(1.dp, capsuleEdge(tint, colors.glassBorderSoft)), shape)
            .padding(start = metrics.startPadding, end = CAPSULE_END),
        horizontalArrangement = Arrangement.spacedBy(metrics.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = CastivioType.overline,
            color = colors.onBackgroundVariant,
        )

        // The value is Latin and digits inside a paragraph that may run right to
        // left. Left to inherit the paragraph, the bidi algorithm reorders its
        // runs -- and an all-neutral placeholder is reordered outright. An
        // address that reads differently in Arabic is a support case in every
        // RTL market. The pill is laid out with start/end, so the capsule mirrors
        // and the value inside it does not.
        Text(
            text = ltrIsolate(value),
            style = valueStyle,
            color = colors.onBackground,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = spoken ?: value
            },
        )

        if (copyEnabled) {
            CopyButton(
                size = metrics.target,
                label = copyLabel,
                isCopied = isCopied,
                onClick = onCopy,
                fill = capsuleWell(tint),
            )
        }
    }
}

/* -----------------------------------------------------------------------------
   How a tint is drawn.

   ## The hue is mixed into the white, not laid over it

   The palette colour is moved toward white and *then* given an alpha, rather
   than laid at a low alpha over the existing glass. Colour on top of glass adds
   saturation and the pill stops reading as a surface the background shows
   through; mixing moves its temperature and leaves the material alone. That
   distinction is the whole difference between a tinted pill and a coloured one.

   ## The ramp descends toward the copy control

   [TINT_HI] at the label end, [TINT_LO] at the control. Deep because there is
   *less* light there, which is what "deeper" means — an earlier draft raised the
   alpha toward that end and leaned on a hue rotation to look deeper, which is
   how you get a gradient the eye can follow across a pill.

   The two average 0.041 against the 0.039 `glassFill` has always been, so the
   pill is the brightness it was; what changed is that the brightness is no
   longer evenly spread.
   -------------------------------------------------------------------------- */

/** Toward white, far enough that Azure50 and Violet50 stop being saturated. */
private const val WHITEN = 0.58f

/**
 * The hue, moved toward white — channel by channel, in sRGB.
 *
 * **Not `androidx.compose.ui.graphics.lerp`.** That interpolates in a perceptual
 * space, which is the right answer for a colour *animation* and the wrong one
 * here: at this fraction it puts Azure50's red at 205 where an sRGB mix puts it
 * at 180, a 25-level difference, and the drawing these values were approved
 * against mixes in sRGB. A tint that renders lighter than the picture it was
 * signed off on is the exact failure this repository has already paid for twice
 * by measuring through the wrong font.
 */
private fun Color.whitened(t: Float) = Color(
    red = red + (1f - red) * t,
    green = green + (1f - green) * t,
    blue = blue + (1f - blue) * t,
)

/** The label end, and the control end. Their mean is `glassFill`'s alpha. */
private const val TINT_HI = 0.060f
private const val TINT_LO = 0.022f

/** Enough for the rim to have a temperature; short of drawing a coloured ring. */
private const val EDGE_ALPHA = 0.28f

/**
 * The control, one step below the surface it sits in.
 *
 * `glassFillStrong` made it *brighter* than the pill — a raised disc beside the
 * value rather than a well cut into it. Drawn in the dark end of the pill's own
 * family, so it reads as the same material going down instead of as grey.
 */
private const val WELL_ALPHA = 0.30f

private fun CapsuleTint.hue(): Color? = when (this) {
    CapsuleTint.None -> null
    CapsuleTint.Azure -> Palette.Azure50
    CapsuleTint.Violet -> Palette.Violet50
}

private fun CapsuleTint.floor(): Color? = when (this) {
    CapsuleTint.None -> null
    CapsuleTint.Azure -> Palette.Azure10
    CapsuleTint.Violet -> Palette.Violet10
}

/**
 * The pill's two stops, in the order the brush will paint them.
 *
 * `internal` and separate from [capsuleFill] because this is the one part of the
 * tint that can be silently wrong: `Brush.horizontalGradient` is absolute — left
 * to right, whatever the paragraph does — so the stops are swapped by hand for
 * a right-to-left layout. Get that backwards and the deep end lands under the
 * label in Arabic while the control sits on the pill's brightest point, which is
 * the one place it must not, in half the languages Castivio ships. A `LinearGradient`
 * does not expose its colours, so the list is tested rather than the brush.
 *
 * @param rtl which way the pill is laid out.
 */
internal fun capsuleStops(tint: CapsuleTint, rtl: Boolean): List<Color> {
    val hue = tint.hue() ?: return emptyList()
    val glass = hue.whitened(WHITEN)
    val stops = listOf(glass.copy(alpha = TINT_HI), glass.copy(alpha = TINT_LO))
    return if (rtl) stops.reversed() else stops
}

private fun capsuleFill(tint: CapsuleTint, plain: Color, rtl: Boolean): Brush {
    val stops = capsuleStops(tint, rtl)
    return if (stops.isEmpty()) SolidColor(plain) else Brush.horizontalGradient(stops)
}

internal fun capsuleEdge(tint: CapsuleTint, plain: Color): Color =
    tint.hue()?.copy(alpha = EDGE_ALPHA) ?: plain

internal fun capsuleWell(tint: CapsuleTint): Color? =
    tint.floor()?.copy(alpha = WELL_ALPHA)

/**
 * Copy, and its confirmation.
 *
 * The confirmation is a glyph swap inside a box of unchanged size, so nothing on
 * the screen moves when it happens. Instances are independent by construction:
 * the state is a parameter, so two of these on one screen cannot share it by
 * accident.
 *
 * **No border at rest.** Inside a bordered pill, a second outline a few dp from
 * the first is what makes the control look bolted on rather than built in. The
 * border appears for focus and for the confirmation, which are the two moments
 * it means something.
 */
@Composable
fun CopyButton(
    size: Dp,
    label: String,
    isCopied: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color? = null,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(percent = 50)
    val border by animateColorAsState(
        when {
            focused -> colors.focusRing
            isCopied -> colors.success
            else -> Color.Transparent
        },
        Motion.focusSpec(),
        label = "copyBorder",
    )

    Box(
        modifier
            .size(size)
            .castivioFocusScale(Motion.focusScaleIcon, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(shape)
            .background(fill ?: colors.glassFillStrong)
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

/**
 * A QR on the one light surface Castivio has.
 *
 * The plate exists because a camera needs a light ground; it is the only
 * container on either screen that earns its keep. A soft lift rather than a card:
 * white on a dark gradient reads as a hole punched in the background without it,
 * and a border would make it a second surface.
 *
 * The caption is the caller's — it differs per screen and belongs to the zone,
 * not to the plate.
 */
@Composable
fun QrPlate(
    code: ImageBitmap,
    plate: Dp,
    padding: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(plate)
            .shadow(QR_LIFT, RoundedCornerShape(Radius.md))
            .clip(RoundedCornerShape(Radius.md))
            .background(Color.White)
            .padding(padding),
    ) {
        Image(
            bitmap = code,
            // The caption below says what the code is for; a reader that
            // announces the image as well says the same thing twice.
            contentDescription = null,
            modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
        )
    }
}

/**
 * Left-to-right, isolated from whatever paragraph it lands in.
 *
 * `LEFT-TO-RIGHT ISOLATE` … `POP DIRECTIONAL ISOLATE`. The value keeps its own
 * order in an Arabic interface, and cannot reorder the row around it either.
 */
fun ltrIsolate(value: String): String = "⁦$value⁩"

/** 2dp: what a 48dp control leaves in a 52dp pill, and 4 in a 64dp one. */
private val CAPSULE_END = 2.dp

/** Enough to separate white from the aurora, not enough to read as a card. */
private val QR_LIFT = 10.dp
