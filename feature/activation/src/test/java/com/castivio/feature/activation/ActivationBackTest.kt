package com.castivio.feature.activation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where back goes, from every step there is.
 *
 * ## The bug this file was written after
 *
 * A tester reported that pressing the system's back button in the video library returned
 * them to "what did your provider give you?" instead of "what would you like to play?" —
 * one screen too far, every time. The drawn Back button on the same screen went to the
 * right place, so the two disagreed and only one of them was visible in review.
 *
 * The cause was a hand-written ladder ending in `else -> Choose`. That was correct for the
 * two form steps it was written for, and it silently became the answer for the five steps
 * added afterwards — the media source and its four screens all fell into it.
 *
 * A hand-written ladder cannot be trusted to stay right as steps are added, so it was
 * replaced by [ActivationStep.parent], and the properties below are asserted over
 * `entries` rather than over a list somebody has to remember to extend. A new step that
 * nobody assigns a parent to does not compile; a new step assigned the *wrong* parent
 * fails here.
 */
class ActivationBackTest {

    /**
     * The reported bug, as the four assertions it actually was.
     *
     * Written out one by one rather than as a loop over the four, because the report was
     * about a specific journey and a test that reads like the report is a test somebody can
     * check against it.
     */
    @Test
    fun `back from a media screen returns to the media source, not to the source choice`() {
        assertEquals(
            "the video library went one screen too far",
            ActivationStep.MediaSource,
            ActivationStep.VideoLibrary.parent(),
        )
        assertEquals(
            "the audio library went one screen too far",
            ActivationStep.MediaSource,
            ActivationStep.AudioLibrary.parent(),
        )
        assertEquals(ActivationStep.MediaSource, ActivationStep.PickVideo.parent())
        assertEquals(ActivationStep.MediaSource, ActivationStep.PickAudio.parent())
    }

    /** And the step above them, which the same `else` was also answering by accident. */
    @Test
    fun `back from the media source returns to the source choice`() {
        assertEquals(ActivationStep.Choose, ActivationStep.MediaSource.parent())
    }

    @Test
    fun `back from a form returns to the source choice`() {
        assertEquals(ActivationStep.Choose, ActivationStep.Xtream.parent())
        assertEquals(ActivationStep.Choose, ActivationStep.Playlist.parent())
        assertEquals(ActivationStep.Choose, ActivationStep.SavedSources.parent())
    }

    @Test
    fun `back from the source choice returns to the address, which is the root`() {
        assertEquals(ActivationStep.Mac, ActivationStep.Choose.parent())
        assertNull("the root has nothing behind it, so back leaves", ActivationStep.Mac.parent())
    }

    /**
     * Every step reaches the root, and none of them loops.
     *
     * The property that makes the ladder a ladder. A parent assignment that pointed two
     * steps at each other would strand a user with a back button that never leaves, which
     * is a far worse failure than the one that started this file and one that no
     * individual assertion above would catch.
     */
    @Test
    fun `every step walks back to the root in a finite number of presses`() {
        for (start in ActivationStep.entries) {
            var step: ActivationStep? = start
            var presses = 0
            while (step != null) {
                step = step.parent()
                presses++
                assertTrue(
                    "back from $start does not terminate — it looped after $presses presses",
                    presses <= ActivationStep.entries.size,
                )
            }
            assertTrue("$start reached the root", presses >= 1)
        }
    }

    /**
     * Nothing is its own parent.
     *
     * The one-line version of the loop above, and worth its own assertion because it is the
     * likeliest typo: a step added by copying its neighbour and having the copy's name left
     * on both sides.
     */
    @Test
    fun `no step is its own parent`() {
        for (step in ActivationStep.entries) {
            assertTrue("$step is its own parent", step.parent() != step)
        }
    }
}
