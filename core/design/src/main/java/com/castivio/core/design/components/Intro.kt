package com.castivio.core.design.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.MotionLevel
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/* =============================================================================
   Castivio's startup mark: 1.2 seconds, silent, and the same on every screen.

   ## It lives in :core:design rather than in :app

   Because it is the brand, and the brand belongs to the design system.
   Everything it draws — the wordmark's gradient, the glow's colour, its
   falloff, the type — is a design decision, and invariants 1 and 2 say those
   live in one module. `:app` composes it and decides *when*; it does not decide
   what violet is.

   ## Approved as a rendered film, so it is reproduced as one

   The timeline was signed off frame by frame at 1920×1080/60. [introFrame] is
   that film as arithmetic — pure, so every number in it can be checked without
   a device — and [CastivioIntro] only draws what it returns.

   ## No sound

   Deliberate and final. Castivio's startup makes no noise: there is no asset,
   no player, and nothing that asks the audio system for anything.
   ========================================================================== */

/** The mark arrives. */
private const val ENTER_FROM = 200L
private const val ENTER_TO = 800L

/** …and is legible before it has finished settling, which is the point. */
private const val OPACITY_MS = 420L

/** Still, breathing. */
private const val HOLD_FROM = 800L
private const val HOLD_TO = 1100L

/** Into the application. */
private const val HANDOVER_FROM = 1100L
private const val HANDOVER_TO = 1200L

/** The whole of it. Nothing composes after this. */
const val INTRO_MS = HANDOVER_TO

/**
 * Whether the mark plays at all.
 *
 * At [MotionLevel.DISABLED] it does not, and the application starts directly.
 * A user who has turned animation off has said what they want, and 1.2 seconds
 * of brand is exactly the kind of thing they turned it off to stop; a brand
 * moment nobody asked for is not owed a hearing.
 *
 * Skipped, not shortened. There is no instant variant, no single frame of
 * black, no `onFinished` fired from the first composition — the caller asks
 * this *before* composing [CastivioIntro], so at `DISABLED` the intro is never
 * built and never draws. That distinction is the whole requirement: an intro
 * that plays in one frame still flashes black over the first screen.
 *
 * [MotionLevel.REDUCED] keeps it. Reduced-motion guidance is about *movement* —
 * travel, parallax, large scale changes — which is what makes people ill. This
 * is an opacity ramp and 4% of scale over six hundred milliseconds, and taking
 * it away would leave a user who asked for less motion with a harder cut than
 * everybody else gets. The same reasoning the licence screen's crossfade uses.
 */
fun playsIntro(level: MotionLevel): Boolean = level != MotionLevel.DISABLED

/**
 * Material 3's emphasised-decelerate. Fast out, long settle — the shape that
 * makes the mark read as *arriving* rather than as *growing*, and the one Apple,
 * Material and the PlayStation boot all share.
 */
private val Decelerate = CubicBezierEasing(0.05f, 0.70f, 0.10f, 1.00f)

/** The handover, which is a crossfade and wants to be even at both ends. */
private val Smooth = CubicBezierEasing(0.40f, 0.00f, 0.20f, 1.00f)

/**
 * Where every animated property is at a given moment.
 *
 * Alphas arrive already clamped to 0..1, and that matters for one of them: the
 * glow's breath pushes its alpha above 1 during the hold, and the approved
 * render clamped it. So the breath the eye actually sees is the 1.2% of scale,
 * not the opacity. Reproducing the clamp is reproducing what was approved.
 */
data class IntroFrame(
    val markAlpha: Float,
    val markScale: Float,
    val glowAlpha: Float,
    val glowScale: Float,
    val appAlpha: Float,
) {
    /** True once there is nothing left drawn over the application. */
    val finished: Boolean get() = appAlpha >= 1f
}

private fun span(t: Long, from: Long, to: Long): Float =
    ((t - from).toFloat() / (to - from).toFloat()).coerceIn(0f, 1f)

/**
 * The film, as arithmetic. Pure: no clock, no composition, no device.
 *
 * [elapsedMs] is clamped to the film's length first. `withFrameNanos` does not
 * promise to land on 1200 — a dropped frame delivers 1216 — and without the
 * clamp the breath term would carry on past the end of the timeline and make
 * the last frame depend on how busy the device was.
 */
fun introFrame(elapsedMs: Long): IntroFrame {
    val t = elapsedMs.coerceIn(0L, INTRO_MS)
    val arriving = Decelerate.transform(span(t, ENTER_FROM, ENTER_FROM + OPACITY_MS))
    val settling = Decelerate.transform(span(t, ENTER_FROM, ENTER_TO))
    val out = Smooth.transform(span(t, HANDOVER_FROM, HANDOVER_TO))

    // One slow half-cycle across the hold, not a loop. A pulse whose rhythm the
    // eye can count is a pulse the eye starts watching.
    val breath =
        if (t < HOLD_FROM) 0f
        else sin(PI * span(t, HOLD_FROM, HOLD_TO + 350L)).toFloat()

    return IntroFrame(
        markAlpha = (arriving * (1f - out)).coerceIn(0f, 1f),
        markScale = 0.96f + 0.04f * settling,
        glowAlpha = (arriving * (1f + 0.08f * breath) * (1f - out)).coerceIn(0f, 1f),
        glowScale = (0.92f + 0.08f * settling) * (1f + 0.012f * breath),
        appAlpha = out,
    )
}

/**
 * The mark, over whatever is behind it, until it is done.
 *
 * @param onFinished called once, when the handover completes. The caller stops
 *   composing this; it does not hide itself, because a transparent full-screen
 *   `Box` still eats every touch that lands on it.
 */
@Composable
fun CastivioIntro(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    var elapsed by remember { mutableLongStateOf(0L) }

    // Driven off the frame clock rather than five `Animatable`s, because the
    // timeline is five properties on three curves sharing one breath: a single
    // elapsed time keeps them exactly as coupled as the approved film had them,
    // and cannot drift apart the way five animations can.
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (elapsed < INTRO_MS) {
            elapsed = (withFrameNanos { it } - start) / 1_000_000L
        }
        onFinished()
    }

    val frame = introFrame(elapsed)

    // Pinned left-to-right. The wordmark is a Latin lockup and does not mirror
    // in Arabic, and pinning it is also what lets the optical centring below be
    // a start-ward offset with an unambiguous direction.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                // The application shows through as the black clears. `1 - appAlpha`
                // rather than a second animation, so the two halves of the
                // handover can never disagree about where they are.
                .background(Color.Black.copy(alpha = 1f - frame.appAlpha))
                // Announced as nothing at all: a screen reader has no use for a
                // logo that is gone in a second, and reading the wordmark aloud
                // would delay the first announcement that matters.
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            // The composition is preserved by scaling off the frame's width. The
            // approved film is 1920 wide, so the mark takes the same share of any
            // screen it takes there — which is the part of this design that has
            // to survive a phone, unlike its pixel sizes.
            val k = maxWidth / REFERENCE_WIDTH

            Glow(frame = frame, k = k)
            Wordmark(frame = frame, k = k)
        }
    }
}

@Composable
private fun Glow(frame: IntroFrame, k: Float) {
    val halfWidth = GLOW_WIDTH * k / 2f
    val halfHeight = GLOW_HEIGHT * k / 2f

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                if (frame.glowAlpha <= 0f) return@drawBehind
                val rx = halfWidth.toPx() * frame.glowScale
                val ry = halfHeight.toPx() * frame.glowScale
                val centre = Offset(size.width / 2f, size.height / 2f)

                // A circle drawn into a horizontally stretched space, which is
                // how a radial gradient becomes an ellipse without a second
                // brush and without `blur` — the thing that printed a square
                // edge into the field the first time this was drawn.
                scale(scaleX = rx / ry, scaleY = 1f, pivot = centre) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = glowStops(frame.glowAlpha),
                            center = centre,
                            radius = ry,
                        ),
                        radius = ry,
                        center = centre,
                    )
                }
            },
    )
}

@Composable
private fun Wordmark(frame: IntroFrame, k: Float) {
    val density = LocalDensity.current
    val size = MARK_SIZE * k
    val tracking = MARK_TRACKING * k

    Text(
        text = MARK,
        style = CastivioType.displayLarge.copy(
            fontWeight = FontWeight.Bold,
            fontSize = with(density) { size.toSp() },
            lineHeight = with(density) { size.toSp() },
            letterSpacing = with(density) { tracking.toSp() },
            brush = Brush.horizontalGradient(MARK_COLOURS),
        ),
        maxLines = 1,
        modifier = Modifier
            // Tracking is applied after the last glyph as well as between them,
            // so a centred run sits half a gap to the left of true centre. Half
            // the tracking back is what puts it where the eye expects it.
            .offset(x = tracking / 2f)
            // One layer for both, rather than `scale` then `alpha`: two layers
            // composite twice, and the mark is the one thing on screen.
            .graphicsLayer {
                scaleX = frame.markScale
                scaleY = frame.markScale
                alpha = frame.markAlpha
            },
    )
}

/**
 * The falloff, as a Gaussian rather than a hand-picked list of stops.
 *
 * `a(r) = PEAK · (e^-Kr² − e^-K) / (1 − e^-K)` — normalised so it is *exactly*
 * zero at the rim and its slope there is nearly zero too, which is the only way
 * a radial field ends without an ending. A curve that still carries a fraction
 * of a percent at 90% has a boundary whether or not anyone meant it to.
 *
 * Twenty-one stops, one every 5%. The count is not decoration: a 1512dp ramp
 * crosses an 8-bit quantisation step roughly every 90dp, and a stop inside each
 * of those intervals is what stops the steps printing as concentric rings.
 */
private fun glowStops(alpha: Float): Array<Pair<Float, Color>> {
    val floor = exp(-GLOW_K)
    return Array(GLOW_STOPS + 1) { i ->
        val r = i.toFloat() / GLOW_STOPS
        val a = (exp(-GLOW_K * r * r) - floor) / (1f - floor)
        r to GLOW_COLOUR.copy(alpha = GLOW_PEAK * a * alpha)
    }
}

/** The frame the film was approved at. Everything scales off its width. */
private val REFERENCE_WIDTH = 1920.dp

private val GLOW_WIDTH = 1512.dp
private val GLOW_HEIGHT = 840.dp
private const val GLOW_K = 3.2f
/**
 * The strongest alpha anywhere in the field, at its centre.
 *
 * `internal` so `IntroTest` can state the "never brighter than the mark" rule
 * in the terms it is actually true in — the rendered peak — rather than in
 * terms of the layer alpha, which is a different number and briefly a larger
 * one. See that test; it was written the wrong way round first.
 */
internal const val GLOW_PEAK = 0.155f
private const val GLOW_STOPS = 20

/**
 * The glow's violet, and the one colour in Castivio that is not in the palette.
 *
 * Chosen for the startup field specifically: Violet50 is the *mark's* colour,
 * and lighting a mark with its own hue flattens it. This sits a step deeper, so
 * the field reads as being behind the word rather than as a spill from it.
 */
private val GLOW_COLOUR = Color(0xFF7C4DFF)

private val MARK_SIZE = 104.dp
private val MARK_TRACKING = 26.dp
private const val MARK = "CASTIVIO"

/** Violet into azure — the wordmark's fill everywhere Castivio draws it. */
private val MARK_COLOURS = listOf(Color(0xFF9B6BFF), Color(0xFF4C9BFF))
