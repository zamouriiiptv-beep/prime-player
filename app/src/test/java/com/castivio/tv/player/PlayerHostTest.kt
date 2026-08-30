package com.castivio.tv.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.castivio.feature.activation.LocalMediaSelection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the browse flow is while a film is playing over it.
 *
 * ## The fault this file exists for
 *
 * Playing a video and pressing back — the arrow inside the player or the phone's button,
 * either one — returned to the MAC screen rather than to the library the film had been
 * opened from. It read as broken back navigation and was not: the back ladder was doing
 * exactly what it should. [PlayerHost] composes one branch or the other and never both,
 * so opening a film removed the whole activation subtree from the composition, and with
 * it every `rememberSaveable` inside — including which step the user was on. Coming back
 * rebuilt the flow from its root, because there was nothing left of it to come back to.
 *
 * A `SaveableStateHolder` is what the swap was missing. What follows is that swap in
 * miniature: a step that lives only in the browse branch, a film, and a return.
 *
 * The player branch is a stub. The real one wants a Hilt graph and an ExoPlayer, and the
 * claim here has nothing to do with decoding — it is about what survives the swap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PlayerHostTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Leave a film and the flow is where it was left.
     *
     * The step is deliberately moved off the root first: a test that opened the player
     * from the initial step would pass whether the state survived or was rebuilt, which
     * is the entire failure it is meant to catch.
     */
    @Test
    fun `the browse flow comes back on the step the film was opened from`() {
        compose.setContent { Harness() }

        compose.onNodeWithTag(DEEPER).performClick()
        compose.onNodeWithTag(LIBRARY).assertExists()

        compose.onNodeWithTag(PLAY).performClick()
        compose.onNodeWithTag(PLAYER).assertExists()
        compose.onAllNodesWithTag(LIBRARY).assertCountEquals(0)

        compose.onNodeWithTag(LEAVE).performClick()

        compose.onNodeWithTag(LIBRARY).assertExists()
        compose.onAllNodesWithTag(ROOT).assertCountEquals(0)
    }

    /** And the player is gone afterwards, not merely covered by the flow. */
    @Test
    fun `leaving takes the player out of the composition`() {
        compose.setContent { Harness() }

        compose.onNodeWithTag(PLAY).performClick()
        compose.onNodeWithTag(PLAYER).assertExists()

        compose.onNodeWithTag(LEAVE).performClick()

        compose.onAllNodesWithTag(PLAYER).assertCountEquals(0)
    }

    /**
     * A second film opens.
     *
     * Worth its own test because the retention is deliberately one-sided: the browse
     * branch is held and the player is not, and a holder wrapped round both would leave a
     * new film inheriting the last one's state.
     */
    @Test
    fun `a second film opens after the first is left`() {
        compose.setContent { Harness() }

        compose.onNodeWithTag(PLAY).performClick()
        compose.onNodeWithTag(LEAVE).performClick()

        compose.onNodeWithTag(PLAY).performClick()

        compose.onNodeWithTag(PLAYER).assertExists()
    }

    /**
     * The browse flow in miniature: a step it can be moved off, and a way to open a film.
     *
     * `rememberSaveable`, because that is what `ActivationRoute` holds its step in and
     * what a `SaveableStateHolder` is able to give back. A plain `remember` here would be
     * testing something the fix does not claim.
     */
    @Composable
    private fun Harness() {
        PlayerHost(
            player = { _, onLeave ->
                Column {
                    Text("playing", Modifier.testTag(PLAYER))
                    Text("leave", Modifier.testTag(LEAVE).clickable(onClick = onLeave))
                }
            },
        ) { onPlay ->
            var deeper by rememberSaveable { mutableStateOf(false) }
            Column {
                if (deeper) {
                    Text("library", Modifier.testTag(LIBRARY))
                } else {
                    Text("root", Modifier.testTag(ROOT))
                }
                Text("deeper", Modifier.testTag(DEEPER).clickable { deeper = true })
                Text("play", Modifier.testTag(PLAY).clickable { onPlay(SELECTION) })
            }
        }
    }

    private companion object {
        const val PLAYER = "test.player"
        const val LEAVE = "test.leave"
        const val PLAY = "test.play"
        const val DEEPER = "test.deeper"
        const val ROOT = "test.step.root"
        const val LIBRARY = "test.step.library"

        val SELECTION = LocalMediaSelection(
            uri = "content://media/external/video/media/1",
            title = "The Road to Chefchaouen",
            isVideo = true,
        )
    }
}
