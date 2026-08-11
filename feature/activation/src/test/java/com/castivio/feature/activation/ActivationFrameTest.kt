package com.castivio.feature.activation

import com.castivio.core.design.theme.DeviceClass
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
    fun `the address screen at rest owns the viewport, on every device`() {
        for (device in DeviceClass.entries) {
            assertTrue("$device", isFixedViewport(ActivationUiState(), ActivationStep.Mac, device))
        }
    }

    /**
     * Every form scrolls, because every form can be taller than a landscape phone
     * once a keyboard is up.
     */
    @Test
    fun `the forms scroll`() {
        for (step in listOf(ActivationStep.Xtream, ActivationStep.Playlist)) {
            for (device in DeviceClass.entries) {
                assertFalse(
                    "$step on $device was given the fixed frame",
                    isFixedViewport(ActivationUiState(), step, device),
                )
            }
        }
    }

    /**
     * The source choice is fixed wherever two cards fit, and scrolls where they
     * do not.
     *
     * ## The version of this that was wrong, and shipped
     *
     * It asserted `isTv = true` against `isTv = false` — and passed, while the
     * screen came up stacked and scrolling on a 873dp phone in landscape. That
     * device is [DeviceClass.Expanded]: not a television, and 420dp per card,
     * which is exactly what a television gives them. The predicate was answering
     * the question it was asked; the question was about the wrong thing.
     *
     * Enumerated over every case now rather than over a boolean, so a fifth
     * device class cannot be added without this test making someone decide which
     * side of the line it falls on.
     */
    @Test
    fun `the source choice owns the viewport wherever two cards fit`() {
        for (device in DeviceClass.entries) {
            val fixed = isFixedViewport(ActivationUiState(), ActivationStep.Choose, device)
            when (device) {
                DeviceClass.Television, DeviceClass.Expanded ->
                    assertTrue("$device is wide enough but scrolls", fixed)
                DeviceClass.Compact, DeviceClass.Medium ->
                    assertFalse("$device is too narrow for a row but was given one", fixed)
            }
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
                isFixedViewport(ActivationUiState(phase = phase), ActivationStep.Mac, DeviceClass.Television),
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
                    DeviceClass.Television,
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
            isFixedViewport(ActivationUiState(phase = succeeded), ActivationStep.Mac, DeviceClass.Television),
        )
    }
}
