package com.castivio.core.design.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import kotlin.coroutines.coroutineContext

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
 * ## What it costs to move, and why it costs so little now
 *
 * This is the one thing in the application that is always animating, which makes it
 * the one thing whose cost is paid on *every* screen, in every frame, whatever the
 * screen is doing. It is therefore held to four rules, each of which was once a thing
 * it did not do.
 *
 *  1. **The motion level decides.** The aurora moves at [MotionLevel.FULL] and holds
 *     still at the other two — [MotionLevel.backdropAnimates], which the design
 *     documents promised and nothing read. A device that cannot afford it is not a
 *     special case here: its *default* level is [MotionLevel.REDUCED], and a person
 *     who asks for more or for less in Settings gets it.
 *  2. **The clock is read where it is drawn.** One [BackdropClock] ticks for the whole
 *     window and is read inside the draw block, never in composition. Read in
 *     composition, a clock recomposed its reader sixty times a second and rebuilt the
 *     modifier chain each time; read in draw, a tick invalidates the drawing and
 *     nothing else.
 *  3. **The drawing is its own layer, and so is everything on top of it.** A tick
 *     invalidates "the nearest layer", which, with none in between, is the whole
 *     window: every text, every image, every row of every list re-recorded to move a
 *     faint line. [castivioBackdrop] puts its drawing in one layer and the node's
 *     content in another, so a tick re-records the backdrop and replays the content.
 *  4. **Nothing is rebuilt that has not changed.** The brushes and the glows' geometry
 *     are made when the ground or the colours change, not on each tick; one `Path` is
 *     reused for all seventeen waves; the motes are a table, not a generator seeded
 *     afresh. And it moves thirty times a second rather than sixty — see
 *     [BackdropCycle.TICK_NANOS] for why that is the same to the eye.
 *
 * None of it changes what is drawn: `BackdropMotion.kt` holds the numbers — the cycles,
 * the mesh, the motes — and `BackdropMotionTest` holds them to the arithmetic the
 * backdrop drew with before they were taken out of the draw function.
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
    // The level, not the device: the device chose the level's starting value, and a
    // person's choice in Settings is the level that arrives here.
    val moves = LocalMotionLevel.current.backdropAnimates
    val clock = if (moves) remember { BackdropClock() } else null
    if (clock != null) {
        LaunchedEffect(clock) { clock.run() }
    }

    // Provided to the whole tree, so a surface that asks for its own backdrop — a cover,
    // a popup — moves with this one and does not start a clock of its own.
    CompositionLocalProvider(LocalBackdropClock provides clock) {
        Box(Modifier.fillMaxSize().castivioBackdrop()) { content() }
    }
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
 * ## Two layers, and why a modifier can have them
 *
 * `graphicsLayer` wraps what comes *after* it in a chain, so this is three links:
 * a layer, the drawing, a layer. The drawing lands in the first; everything the node
 * holds — its children, and any modifier written after this one — lands in the second.
 * A move of the clock invalidates the first and replays the second. Nothing about how
 * a caller writes it changes: it is still one modifier, in the same place.
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
    val particles = LocalPerformanceProfile.current.backdropParticles
    // Null when nothing above is animating: a backdrop outside the theme, or at a level
    // that holds still. Read below, inside the draw block — never here.
    val clock = LocalBackdropClock.current
    val paint = remember { BackdropPaint() }

    return this
        .graphicsLayer { }
        .drawBehind {
            // Read at *draw* time, which is the whole reason this is a lambda rather than
            // a value. A popup only learns where it sits during the layout pass, and
            // `onGloballyPositioned` fires at the end of that pass — before drawing, in
            // the same frame. A `Rect` parameter would have been captured at composition,
            // one frame early, and the menu would have drawn its first frame against the
            // wrong ground and corrected itself on the second: a visible flash at exactly
            // the moment the menu appears.
            paint.draw(
                scope = this,
                ground = ground?.invoke() ?: Rect(Offset.Zero, size),
                colors = colors,
                particles = particles,
                wave = clock?.wave ?: STILL_WAVE,
                drift = clock?.drift ?: STILL_DRIFT,
            )
        }
        .graphicsLayer { }
}

/**
 * Where the wave and the drift are when nothing moves them: the wave at its start, the
 * drift a fifth of the way round.
 *
 * These are the positions the still backdrop has always been drawn at — the one the
 * weakest devices have had from the start, and what a person who chooses
 * [MotionLevel.REDUCED] or [MotionLevel.DISABLED] now sees. Not chosen here: kept.
 */
private const val STILL_WAVE = 0f
private const val STILL_DRIFT = 0.2f

/**
 * The backdrop's clock, shared by everything on the screen that draws one.
 *
 * Absent (null) is the still backdrop, which is a different thing from a clock that is
 * stopped: nothing reads state, so nothing is ever invalidated, and a still backdrop
 * is drawn once and then only replayed.
 *
 * Internal, and provided by [CastivioBackdrop] alone — a screen cannot start a second
 * clock, which is how the cost of the backdrop stays one clock's worth however many
 * covers and popups are open at once.
 */
internal val LocalBackdropClock: ProvidableCompositionLocal<BackdropClock?> =
    staticCompositionLocalOf { null }

/**
 * One position in the backdrop's cycles, advanced thirty times a second.
 *
 * The state holds only elapsed time. [wave] and [drift] turn it into the two positions
 * that are wanted, on demand, so that the reader pays for the arithmetic it uses and a
 * tick writes one number.
 */
internal class BackdropClock {

    private val elapsedNanos = mutableLongStateOf(0L)

    /** Radians into the wave's cycle. Reads the clock: call it from a draw block. */
    val wave: Float
        get() = BackdropCycle.radians(BackdropCycle.fraction(elapsedNanos.longValue, Motion.ambientWave))

    /** How far round the drift's cycle, 0..1. Reads the clock: call it from a draw block. */
    val drift: Float
        get() = BackdropCycle.fraction(elapsedNanos.longValue, Motion.ambientDrift)

    /**
     * Runs the clock until the composition that started it leaves.
     *
     * One callback per display frame, and a write on one in two. The callback is the
     * cheap part — a frame that is skipped touches nothing — and it is what ties the
     * backdrop to the display: it stops with the screen, pauses with the activity, and
     * never draws a frame nobody would see.
     *
     * The *infinite* kind of frame callback, which is what `rememberInfiniteTransition`
     * asked for and a plain one does not: a test that composes the theme with motion on
     * installs a policy that ends loops like this one, and a loop that never asked to
     * be ended is a test that never goes idle.
     */
    suspend fun run() {
        var origin = BackdropCycle.NEVER
        var last = BackdropCycle.NEVER
        while (true) {
            withInfiniteFrameNanos { now ->
                if (BackdropCycle.due(now, last)) {
                    // The first frame is the start of the cycle, as it was when the cycle
                    // began at zero on every launch.
                    if (origin == BackdropCycle.NEVER) origin = now
                    last = now
                    elapsedNanos.longValue = now - origin
                }
            }
        }
    }
}

/**
 * A frame callback that, like `rememberInfiniteTransition`'s, can be ended by a policy.
 *
 * A Compose test installs an [InfiniteAnimationPolicy] in the coroutine context that ends
 * any loop which says it never finishes, because a loop that never finishes is a test that
 * never goes idle. Outside a test there is no policy and this is [withFrameNanos].
 *
 * Written out here, three lines, rather than imported: the helper `:ui` has for it is
 * internal to `:ui`, and this uses only what a test framework itself is given.
 */
private suspend fun <R> withInfiniteFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
    val policy = coroutineContext[InfiniteAnimationPolicy]
    return if (policy == null) {
        withFrameNanos(onFrame)
    } else {
        policy.onInfiniteOperation { withFrameNanos(onFrame) }
    }
}

/**
 * What one backdrop keeps between two draws.
 *
 * The ground is made of three brushes whose geometry depends only on the rectangle and
 * the colours, so they are made when either changes and then reused: a native shader is
 * created the first time a brush is used at a size, and a brush made afresh on every
 * draw is a shader made afresh on every draw. One `Path`, rewound between waves, is the
 * rest of it. Held by the modifier that draws, on the UI thread that draws — nothing
 * here is shared.
 */
private class BackdropPaint {

    private val path = Path()
    private val stroke = Stroke(width = BackdropMesh.STROKE)
    private var built: Ground? = null

    /** The four layers, stated once: both entry points are plumbing around this. */
    fun draw(
        scope: DrawScope,
        ground: Rect,
        colors: CastivioColors,
        particles: Boolean,
        wave: Float,
        drift: Float,
    ) {
        val g = groundFor(ground, colors)
        with(scope) {
            drawRect(g.base)
            // A glow of no radius is not a glow, and the platform refuses to make one.
            if (g.warmRadius > 0f) drawCircle(g.warm, g.warmRadius, g.warmCentre)
            if (g.coolRadius > 0f) drawCircle(g.cool, g.coolRadius, g.coolCentre)
            mesh(wave, colors.primary, ground)
            if (particles) motes(drift, colors.backdropMote, ground)
        }
    }

    private fun groundFor(rect: Rect, colors: CastivioColors): Ground {
        val cached = built
        if (cached != null && cached.rect == rect && cached.colors === colors) return cached
        return buildGround(rect, colors).also { built = it }
    }

    /** Perspective contour waves across the lower half. */
    private fun DrawScope.mesh(phase: Float, color: Color, ground: Rect) {
        val steps = BackdropMesh.STEPS
        for (line in 0..BackdropMesh.LINES) {
            val depth = BackdropMesh.depth(line)
            val row = BackdropMesh.row(ground.top, ground.bottom, depth)
            val amplitude = BackdropMesh.amplitude(depth)
            path.rewind()
            for (step in 0..steps) {
                val across = step / steps.toFloat()
                val x = ground.left + ground.width * across
                val y = row + amplitude * BackdropMesh.swing(across, line, phase)
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color.copy(alpha = BackdropMesh.alpha(depth)), style = stroke)
        }
    }

    /** A handful of slow motes that rise, wrap and twinkle. */
    private fun DrawScope.motes(drift: Float, tint: Color, ground: Rect) {
        val motes = BackdropMotes.all
        for (k in motes.indices) {
            val mote = motes[k]
            drawCircle(
                color = tint.copy(alpha = BackdropMotes.ALPHA * BackdropMotes.twinkle(mote, drift)),
                radius = mote.radius,
                center = ground.topLeft +
                    Offset(ground.width * mote.x, ground.height * BackdropMotes.height(mote, drift)),
            )
        }
    }
}

/** The gradient and the two glows for one [rect] in one set of [colors]. */
private class Ground(
    val rect: Rect,
    val colors: CastivioColors,
    val base: Brush,
    val warm: Brush,
    val warmCentre: Offset,
    val warmRadius: Float,
    val cool: Brush,
    val coolCentre: Offset,
    val coolRadius: Float,
)

private fun buildGround(ground: Rect, colors: CastivioColors): Ground {
    // The geometry is the theme's business, not the ground's: both glows keep their
    // corner, their radius and their alpha in either mode, and only the hue is asked
    // for again. A light page with the aurora somewhere else would be a second layout.
    val warmCentre = ground.topLeft + Offset(ground.width * 0.05f, ground.height)
    val warmRadius = ground.width * 0.55f
    val coolCentre = ground.topLeft + Offset(ground.width * 0.95f, ground.height * 0.30f)
    val coolRadius = ground.width * 0.52f
    return Ground(
        rect = ground,
        colors = colors,
        base = Brush.linearGradient(
            colors = colors.backdropStops,
            start = ground.topLeft,
            end = ground.bottomRight,
        ),
        warm = glow(colors.backdropWarmGlow, colors.backdropGlowWarm, warmCentre, warmRadius),
        warmCentre = warmCentre,
        warmRadius = warmRadius,
        cool = glow(colors.backdropCoolGlow, colors.backdropGlowCool, coolCentre, coolRadius),
        coolCentre = coolCentre,
        coolRadius = coolRadius,
    )
}

private fun glow(color: Color, alpha: Float, centre: Offset, radius: Float): Brush =
    Brush.radialGradient(
        colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
        center = centre,
        radius = radius,
    )
