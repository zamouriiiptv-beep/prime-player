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
 *
 * ## What this file can and cannot be asked
 *
 * It used to take a `DeviceClass` and enumerate the four cases, and that was the
 * shape of a mistake rather than a safeguard: it made a screen-size question look
 * answerable from a bucket, and both answers it gave for the source choice —
 * `isTv`, then `Expanded` — shipped a stacked, scrolling screen to a landscape
 * handset. The predicate no longer knows what a device is, so this file cannot
 * assert anything about one. Where the cards land is a question for Compose, and
 * `SourceChoiceLayoutTest` asks Compose.
 */
class ActivationFrameTest {

    /** The state the user lands in on first launch. The one that broke. */
    @Test
    fun `the address screen at rest owns the viewport`() {
        assertTrue(isFixedViewport(ActivationUiState(), ActivationStep.Mac))
    }

    /**
     * The source choice owns it too, on every device there is.
     *
     * Its four cards sit in a grid of `weight(1f)` halves, which divide whatever width
     * the frame has and so cannot overflow it. Nothing about that needs a scroll, and
     * a scroll is what clipped the title on one end of the gesture and Back on the
     * other.
     */
    @Test
    fun `the source choice owns the viewport`() {
        assertTrue(isFixedViewport(ActivationUiState(), ActivationStep.Choose))
    }

    /**
     * And so does the saved-subscriptions step, which is the counter-intuitive one.
     *
     * It is the only step here holding a list, so the scrolling frame looks like the
     * obvious answer and is the wrong one: `ActivationSurface` implements that frame
     * with `verticalScroll`, and a `LazyColumn` measured against an unbounded height
     * does not scroll, it throws. The list needs a *bounded* parent to scroll inside,
     * which is what the fixed frame is.
     */
    @Test
    fun `the saved subscriptions step owns the viewport, so its list can scroll inside it`() {
        assertTrue(isFixedViewport(ActivationUiState(), ActivationStep.SavedSources))
    }

    /**
     * Every form scrolls, because every form can be taller than a landscape phone
     * once a keyboard is up.
     */
    @Test
    fun `the forms scroll`() {
        for (step in listOf(ActivationStep.Xtream, ActivationStep.Playlist)) {
            assertFalse(
                "$step was given the fixed frame",
                isFixedViewport(ActivationUiState(), step),
            )
        }
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
                isFixedViewport(ActivationUiState(phase = phase), ActivationStep.Mac),
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
                ),
            )
        }
    }

    /**
     * A failure takes the scrolling frame from whichever step raised it, including
     * the two that are fixed at rest.
     */
    @Test
    fun `a failure scrolls from the fixed steps as well`() {
        val failed = ActivationUiState(phase = ActivationPhase.Failed(ActivationFailure.entries.first()))
        for (step in listOf(
            ActivationStep.Mac,
            ActivationStep.Choose,
            ActivationStep.SavedSources,
        )) {
            assertFalse("$step kept the fixed frame while failed", isFixedViewport(failed, step))
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
        assertTrue(isFixedViewport(ActivationUiState(phase = succeeded), ActivationStep.Mac))
    }
}
