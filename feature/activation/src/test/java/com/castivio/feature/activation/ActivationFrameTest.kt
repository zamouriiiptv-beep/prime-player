package com.castivio.feature.activation

import com.castivio.domain.ProviderStatus
import com.castivio.domain.activation.ActivationFailure
import com.castivio.domain.activation.ActivationPhase
import com.castivio.domain.activation.ActivationUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which container each activation state is given.
 *
 * The activation screen shipped inside a vertically scrolling column. Everything
 * about the screen itself was right; it was handed the wrong frame, and in a frame
 * with an unbounded height its middle band had no remaining space to weight and
 * measured 0dp. The address, the device key, both copy controls, Add playlist,
 * Refresh, the status line and the QR were all composed, all correct, and all
 * invisible.
 *
 * The choice between the two frames was three clauses inline in a composable —
 * nothing a test could reach. It is [isFixedViewport] now, it is pure, and this is
 * the cheapest gate in the set: no device, no Robolectric, no Compose runtime.
 *
 * `ActivationLayoutTest` proves what each frame does to the screen. This proves
 * which frame each state gets. Neither claim is worth much without the other.
 */
class ActivationFrameTest {

    /** The state the user lands in on first launch. The one that broke. */
    @Test
    fun `the address screen at rest owns the viewport, on both frames`() {
        for (tv in listOf(true, false)) {
            assertTrue(isFixedViewport(ActivationUiState(), ActivationStep.Mac, isTv = tv))
        }
    }

    /**
     * Every form scrolls, because every form can be taller than a landscape phone
     * once a keyboard is up.
     */
    @Test
    fun `the forms scroll`() {
        for (step in listOf(ActivationStep.Xtream, ActivationStep.Playlist)) {
            for (tv in listOf(true, false)) {
                assertFalse(
                    "$step on ${if (tv) "a television" else "a phone"} was given the fixed frame",
                    isFixedViewport(ActivationUiState(), step, isTv = tv),
                )
            }
        }
    }

    /**
     * The source choice is fixed on a television and scrolls everywhere else.
     *
     * Stacked, its header, two cards and Back came to more than the 444dp inside
     * a television's overscan, so the step scrolled and clipped at whichever end
     * the user was not looking at. Side by side it fits, and the fixed frame is
     * what turns "fits" into a property of the layout rather than of the scroll
     * position.
     *
     * The phone keeps the scrolling column deliberately: it has vertical room and
     * no horizontal room, so two cards abreast would be this fix turning into the
     * next defect. That is the whole reason the predicate knows about the device,
     * and it is why the qualifier is asserted rather than assumed.
     */
    @Test
    fun `the source choice owns the viewport on a television and scrolls on a phone`() {
        assertTrue(isFixedViewport(ActivationUiState(), ActivationStep.Choose, isTv = true))
        assertFalse(isFixedViewport(ActivationUiState(), ActivationStep.Choose, isTv = false))
    }

    /**
     * A running import replaces the screen that started it, so the address step
     * being the one underneath does not entitle it to the fixed frame.
     */
    @Test
    fun `a running attempt scrolls even from the address step`() {
        for (phase in listOf(
            ActivationPhase.Checking,
            ActivationPhase.Importing(itemsFound = 0, groupsReady = 0),
            ActivationPhase.Importing(itemsFound = 12_000, groupsReady = 40),
        )) {
            assertFalse(
                "$phase was given the fixed viewport",
                isFixedViewport(ActivationUiState(phase = phase), ActivationStep.Mac, isTv = true),
            )
        }
    }

    /** A failure replaces the screen too, and its text is long enough to need scrolling. */
    @Test
    fun `a failure scrolls`() {
        for (reason in ActivationFailure.entries) {
            assertFalse(
                "$reason was given the fixed viewport",
                isFixedViewport(
                    ActivationUiState(phase = ActivationPhase.Failed(reason)),
                    ActivationStep.Mac,
                    isTv = true,
                ),
            )
        }
    }

    /**
     * Success is a frame off the screen — the route navigates away — so it is only
     * pinned here to say the predicate has no fourth answer hiding in it.
     */
    @Test
    fun `success still reads as the address screen, and is navigated away from`() {
        val succeeded = ActivationPhase.Succeeded(
            sourceId = "source",
            itemCount = 1,
            status = ProviderStatus(usable = true),
        )
        assertTrue(
            isFixedViewport(ActivationUiState(phase = succeeded), ActivationStep.Mac, isTv = true),
        )
    }
}
