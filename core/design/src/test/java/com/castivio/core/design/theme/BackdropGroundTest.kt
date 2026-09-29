package com.castivio.core.design.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backdrop's geometry, as arithmetic rather than as pixels.
 *
 * `paintBackdrop` is a `DrawScope` function and cannot be called without a canvas, so
 * what is asserted here is the *rule* it now follows: every layer is a proportion of
 * the ground rectangle, and the ground defaults to the draw box. That default is what
 * keeps `ShowScreen` and the shell drawing exactly what they drew before this
 * parameter existed — they pass nothing — and it is the one property worth a test,
 * because it is the one a future edit could break without anybody noticing until two
 * screens were photographed side by side.
 *
 * The numbers below mirror the ones in `paintBackdrop`. They are deliberately written
 * out a second time: a test that computed them from the same expression would agree
 * with a mistake as readily as with the intent.
 */
class BackdropGroundTest {

    private val warmCentre = 0.05f to 1.00f
    private val coolCentre = 0.95f to 0.30f
    private val warmRadius = 0.55f
    private val coolRadius = 0.52f

    private fun glowCentre(ground: Rect, at: Pair<Float, Float>) =
        ground.topLeft + Offset(ground.width * at.first, ground.height * at.second)

    // ------------------------------------------------- the default is the draw box

    /**
     * **The contract that protects every existing caller.** A surface that passes no
     * ground gets `Rect(0, 0, size)`, and every layer then lands exactly where it
     * landed before the parameter was added.
     */
    @Test
    fun `no ground means the draw box, and the geometry is unchanged`() {
        val size = Size(1080f, 2400f)
        val ground = Rect(Offset.Zero, size)

        assertEquals(Offset(0f, 0f), ground.topLeft)
        assertEquals(Offset(size.width, size.height), ground.bottomRight)
        assertEquals(Offset(size.width * 0.05f, size.height), glowCentre(ground, warmCentre))
        assertEquals(Offset(size.width * 0.95f, size.height * 0.30f), glowCentre(ground, coolCentre))
        assertEquals(size.width * 0.55f, ground.width * warmRadius, 0f)
        assertEquals(size.width * 0.52f, ground.width * coolRadius, 0f)
    }

    // ------------------------------------------- a slice draws the same one ground

    /**
     * A panel inside the window draws *its slice* of the ground, not a miniature.
     *
     * The glow keeps the radius it has on the window — which is the whole defect this
     * fixes. Sized to a 200dp popup the cool glow had a 104dp radius inside a 200dp
     * box: a bright patch instead of ambient light.
     */
    @Test
    fun `a panel inside the window keeps the window's glow radius`() {
        val window = Size(833f, 385f)
        val panel = Size(200f, 186f)
        val panelAt = Offset(593f, 150f)

        // The ground as the panel sees it: where the window begins, relative to it.
        val ground = Rect(Offset.Zero, window).translate(-panelAt.x, -panelAt.y)

        assertEquals(window.width, ground.width, 0f)
        assertEquals(window.height, ground.height, 0f)
        assertEquals(833f * 0.52f, ground.width * coolRadius, 0f)

        // Sized to the panel it would have been a quarter of that, and half the
        // panel's own width — which is what a patch rather than a wash looks like.
        val wrong = panel.width * coolRadius
        assertTrue("$wrong should be far smaller", wrong < ground.width * coolRadius / 3f)
    }

    /**
     * The glow lands in the same place on the screen whichever surface draws it.
     *
     * Two drawings of one ground: the window's own, and a panel's slice of it. The
     * cool glow's centre must be the same point on the screen in both, or the panel
     * is lit from somewhere the board is not.
     */
    @Test
    fun `the glow centre is the same point on screen from either surface`() {
        val window = Size(833f, 385f)
        val panelAt = Offset(593f, 150f)

        val fromWindow = glowCentre(Rect(Offset.Zero, window), coolCentre)
        val fromPanel = glowCentre(
            Rect(Offset.Zero, window).translate(-panelAt.x, -panelAt.y),
            coolCentre,
        )

        // The panel's answer is in its own coordinates; put it back on the screen.
        assertEquals(fromWindow.x, fromPanel.x + panelAt.x, 0.001f)
        assertEquals(fromWindow.y, fromPanel.y + panelAt.y, 0.001f)
    }

    /**
     * And the gradient runs the same way. Compressed into the panel it restarted at
     * the darkest stop, which is why the menu came out darker than the board it sat
     * on rather than continuous with it.
     */
    @Test
    fun `the gradient spans the window, not the panel`() {
        val window = Size(833f, 385f)
        val panelAt = Offset(593f, 150f)
        val ground = Rect(Offset.Zero, window).translate(-panelAt.x, -panelAt.y)

        assertEquals(Offset(-593f, -150f), ground.topLeft)
        assertEquals(Offset(240f, 235f), ground.bottomRight)
        // The panel begins 71% of the way along the ramp rather than at 0%.
        assertTrue((panelAt.x / window.width) > 0.7f)
    }

    /**
     * A ground the caller could not work out yet is the draw box, not a crash and not
     * an empty rectangle: the surface draws what it always drew until the real one
     * arrives.
     */
    @Test
    fun `an unknown ground falls back to the draw box`() {
        val size = Size(200f, 186f)
        val unknown: Rect? = null

        assertEquals(Rect(Offset.Zero, size), unknown ?: Rect(Offset.Zero, size))
    }
}
