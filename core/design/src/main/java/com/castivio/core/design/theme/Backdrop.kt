package com.castivio.core.design.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * **The Castivio background. The only one.**
 *
 * Four layers, drawn in code so it costs nothing to ship and scales to any panel
 * size: a deep navy gradient, two soft aurora glows, a slow mesh of contour waves,
 * and a scattering of drifting motes. Deliberately restrained — every layer sits
 * below 12% opacity, so content always wins.
 *
 * ## The architecture, in one line
 *
 * [CastivioTheme] wraps the application in [CastivioBackdrop], which paints
 * [castivioBackdrop], which is the four layers. There is one `setContent` in
 * Castivio and one `CastivioTheme` call in it, so every screen in the product is
 * already standing on this and almost none of them should say anything at all
 * about a background.
 *
 * A screen drawn **over** another one is the exception that needs an entry point:
 * an overlay whose destination shows through is not translucent, it is broken. It
 * asks for [castivioBackdrop] by name rather than approximating it — three of them
 * used to paint `colors.background`, a flat `Palette.Void`, which is not the
 * Castivio background but the colour that happens to sit underneath it.
 *
 * ## Everything else in the product that fills a region, and why none of it is this
 *
 * Audited across `app`, `core`, `data`, `domain`, `feature`, `playback` and
 * `benchmark`, over every mechanism: `setContent`, `Surface`, `Scaffold`,
 * `Modifier.background`, `Canvas`, `drawBehind`, `drawWithCache`, `Modifier.paint`,
 * a full-bleed `Image`, and the window itself.
 *
 * | What | Where | Why it is not a page background |
 * |---|---|---|
 * | The letterbox | `PlayerScreen` | What surrounds a picture is black, in every player ever made. An aurora beside a film is the one place in an application where nothing else may move — and this is the surface with the least fill rate to spare. |
 * | A fade from black | `CastivioIntro` | A transition, not a ground: its alpha is `1 - appAlpha`, so it *is* the handover to this. |
 * | Scrims and panels | `CastivioDialog`, `LanguagePicker`, `CrashReportSheet` | A dialog sits over a page. It dims what is behind it and lifts a panel above it; neither is a background. |
 * | Badge and subtitle plates | `StateMarks`, `PlayerSubtitles` | A pill behind two words, so the words survive whatever is under them. |
 * | `android:windowBackground` | `app/res/values/themes.xml` | Before Compose exists, and during every activity teardown. It cannot be a composable, so it is a colour resource in this module — and it is now this gradient's own first stop rather than the `#0B0620` nobody could trace. |
 *
 * Anything not in that table which paints a page is a defect, and
 * `check-invariants.sh` fails the build on the shape it took last time.
 */
@Composable
fun CastivioBackdrop(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().castivioBackdrop()) { content() }
}

/**
 * The same four layers, behind whatever this is applied to.
 *
 * For an overlay drawn over a destination, which genuinely needs a ground of its
 * own. The first thing painted is an opaque gradient, so this covers what is
 * behind it exactly as a flat colour did.
 *
 * A screen that is *not* covering another one should use neither this nor
 * [CastivioBackdrop]: the theme has already put the backdrop behind it, and a
 * second copy is a second full-screen canvas for a pixel-identical result.
 *
 * ## [ground], and why a small surface needs it
 *
 * Every layer below is a proportion of the box it is drawn in, which is right while
 * that box is the window. It stops being right for a *small* surface: a popup menu
 * 200dp wide gets the cool glow at a 104dp radius instead of 433dp, and a light meant
 * to be wider than the screen becomes a bright patch inside a panel. The gradient
 * suffers the same compression — the full three-stop ramp in 200dp, so the panel
 * starts at the darkest stop while the board around it has long since reached the
 * lightest.
 *
 * [ground] is the rectangle the *whole* backdrop occupies, expressed in this node's
 * own coordinates. A surface that is the window passes nothing and gets
 * `Rect(0, 0, size)` — which is what this has always drawn, so every existing caller
 * is unchanged by construction rather than by inspection. A surface that is a slice
 * of the window passes where the window is relative to itself, and then draws *its
 * slice of the one ground* rather than a miniature of it: same gradient, same glow
 * centres, same radii, same mesh, same motes, clipped to its own bounds by the canvas.
 *
 * It is a lambda, not a `Rect`, because the callers that need it only learn their
 * position during layout — see the read site for why that matters.
 */
@Composable
fun Modifier.castivioBackdrop(ground: (() -> Rect?)? = null): Modifier {
    val colors = CastivioTheme.colors
    val profile = LocalPerformanceProfile.current
    val (wave, drift) = backdropPhase(profile)
    return drawBehind {
        // Read at *draw* time, which is the whole reason this is a lambda rather than
        // a value. A popup only learns where it sits during the layout pass, and
        // `onGloballyPositioned` fires at the end of that pass — before drawing, in
        // the same frame. A `Rect` parameter would have been captured at composition,
        // one frame early, and the menu would have drawn its first frame against the
        // wrong ground and corrected itself on the second: a visible flash at exactly
        // the moment the menu appears.
        paintBackdrop(
            wave, drift, colors, profile.backdropParticles,
            ground = ground?.invoke() ?: Rect(Offset.Zero, size),
        )
    }
}

/**
 * The four layers, stated once.
 *
 * Private, and the only place any of these numbers appears. Both entry points above
 * are three lines of plumbing around this call — which is what makes "change the
 * background in one place" a property of the code rather than a convention someone
 * has to keep.
 */
private fun DrawScope.paintBackdrop(
    wave: Float,
    drift: Float,
    colors: CastivioColors,
    particles: Boolean,
    ground: Rect,
) {
    drawRect(
        Brush.linearGradient(
            colors = colors.backdropStops,
            start = ground.topLeft,
            end = ground.bottomRight,
        )
    )
    // The geometry is the theme's business, not the ground's: both glows keep their
    // corner, their radius and their alpha in either mode, and only the hue is asked
    // for again. A light page with the aurora somewhere else would be a second layout.
    glow(
        ground.topLeft + Offset(ground.width * 0.05f, ground.height),
        ground.width * 0.55f,
        colors.backdropWarmGlow, colors.backdropGlowWarm,
    )
    glow(
        ground.topLeft + Offset(ground.width * 0.95f, ground.height * 0.30f),
        ground.width * 0.52f,
        colors.backdropCoolGlow, colors.backdropGlowCool,
    )
    mesh(wave, colors.primary, ground)
    if (particles) motes(drift, colors.backdropMote, ground)
}

/**
 * Where the wave and the drift are in their cycles.
 *
 * On a weak GPU this canvas competes with the scroll for fill rate, so the animation
 * is a capability decision rather than a taste one. The static fallback still reads
 * as Castivio — it just costs one draw instead of sixty a second.
 */
@Composable
private fun backdropPhase(profile: PerformanceProfile): Pair<Float, Float> {
    if (!profile.animatedBackdrop) return 0f to 0.2f

    val transition = rememberInfiniteTransition(label = "backdrop")
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(Motion.ambientWave, easing = LinearEasing), RepeatMode.Restart,
        ),
        label = "wave",
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(Motion.ambientDrift, easing = LinearEasing), RepeatMode.Restart,
        ),
        label = "drift",
    )
    return wave to drift
}

private fun DrawScope.glow(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** Perspective contour waves across the lower half. */
private fun DrawScope.mesh(phase: Float, color: Color, ground: Rect) {
    val horizon = ground.top + ground.height * 0.40f
    val lines = 16
    for (i in 0..lines) {
        val t = i / lines.toFloat()
        val y = horizon + (ground.bottom - horizon) * (t * t)
        val amp = 7f + 44f * t
        val alpha = 0.025f + 0.07f * t
        val path = Path()
        val steps = 64
        for (s in 0..steps) {
            val p = s / steps.toFloat()
            val x = ground.left + ground.width * p
            val yy = y + amp * sin(p * 11f + i * 0.6f + phase)
            if (s == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        drawPath(path, color = color.copy(alpha = alpha), style = Stroke(width = 1.2f))
    }
}

/** A handful of slow motes that rise, wrap and twinkle. */
private fun DrawScope.motes(drift: Float, tint: Color, ground: Rect) {
    val rng = Random(7)
    repeat(13) {
        val baseX = rng.nextFloat()
        val baseY = rng.nextFloat()
        val speed = 0.5f + rng.nextFloat()
        val radius = 1.4f + rng.nextFloat() * 2.2f
        val phase = rng.nextFloat()
        val y = ((baseY - drift * speed) % 1f + 1f) % 1f
        val twinkle = 0.25f + 0.55f * abs(sin((drift + phase) * 2f * PI.toFloat()))
        drawCircle(
            color = tint.copy(alpha = 0.20f * twinkle),
            radius = radius,
            center = ground.topLeft + Offset(ground.width * baseX, ground.height * y),
        )
    }
}
