package com.castivio.tv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.castivio.feature.activation.LocalMediaSelection
import com.castivio.feature.player.PlayerRequest
import com.castivio.feature.player.PlayerRoute
import com.castivio.playback.api.MediaKind

/**
 * Where the player is opened from, and the only place that knows both the activation flow
 * and the player exist.
 *
 * ## Why the four browse screens were not redrawn to connect them
 *
 * They already hoisted the press. What changed is what the press *carries*: a lambda with
 * no argument was enough while the lists were fixtures and is not enough now that they are
 * the device's real media, because a press is a press on a particular file. So there is one
 * seam instead of four and it carries a [LocalMediaSelection] — three fields the screen
 * already had in hand.
 *
 * Nothing about the layout of those screens changed to make this work, which is the point.
 *
 * ## The title, and why there is no lookup behind it
 *
 * It comes from the `MediaStore` row the list was drawn from. The player's contract says
 * the title must arrive with the request and never from a fetch, and this is where that is
 * honoured: by the time a press happens the name has been on screen for some time already.
 */
@Composable
internal fun PlayerHost(
    modifier: Modifier = Modifier,
    /**
     * What the front branch draws. Defaulted to the real player, and a parameter only so
     * that the swap itself can be tested: the real one reaches for a Hilt graph and an
     * ExoPlayer, and the claim worth testing here — that the browse flow comes back where
     * it was left — is about the swap and not about decoding anything.
     */
    player: @Composable (request: PlayerRequest, onLeave: () -> Unit) -> Unit =
        { request, onLeave -> PlayerRoute(request = request, onLeave = onLeave, modifier = modifier) },
    content: @Composable (onPlay: (LocalMediaSelection) -> Unit) -> Unit,
) {
    var request by remember { mutableStateOf<PlayerRequest?>(null) }

    // Where the browse flow's state is kept while the player is over it.
    //
    // This swap composes one branch or the other, never both, so opening a film removes
    // the whole activation subtree from the composition — and with it every
    // `rememberSaveable` inside, including the step the user was on. Coming back rebuilt
    // the flow from its root, which is why leaving a video landed on the MAC screen
    // instead of the library the film was opened from. It looked like broken back
    // navigation and was actually state that had been thrown away.
    //
    // `SaveableStateProvider` is what that swap was missing: the browse side's saved
    // state is put aside under a key when it leaves and handed back when it returns.
    // Only the browse side needs it. The player is rebuilt from its request, and a new
    // film should start clean.
    val retained = rememberSaveableStateHolder()

    val open = request
    if (open != null) {
        player(open) { request = null }
    } else {
        retained.SaveableStateProvider(BROWSE) {
            content { selection -> request = selection.asPlayerRequest() }
        }
    }
}

/** One flow, one key. Named so the reason for it survives a later edit. */
private const val BROWSE = "castivio.browse"

/**
 * A chosen file, as something the player can open.
 *
 * Both kinds are [MediaKind.VOD] and that is not an oversight: a file on the device has a
 * known length and a seekable timeline, which is what `VOD` means to the engine. It selects
 * the deeper buffer, and a track that stutters on its first seek is a worse outcome than a
 * hundred milliseconds of start-up on a file that is already local.
 */
private fun LocalMediaSelection.asPlayerRequest() = PlayerRequest(
    url = uri,
    title = title,
    kind = MediaKind.VOD,
)
