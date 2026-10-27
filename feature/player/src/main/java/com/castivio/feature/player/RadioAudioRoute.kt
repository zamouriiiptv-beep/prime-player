package com.castivio.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.castivio.domain.RadioPhase
import com.castivio.domain.RadioStatus
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The radio's sound: the one player, with no picture and nothing drawn.
 *
 * Composed by the shell inside the radio's own destination, which is the whole of its lifetime: leaving the
 * radio removes it, and the sound is let go with it, so a station never plays on behind Settings or Home. The
 * application going to the background lets it go too, and nothing starts it again on the way back -- the
 * viewer asked for a station and not for a sound that follows them out of the application.
 *
 * It binds the same [PlayerViewModel] as the large player and the board's plate, and so the same engines, the
 * same fallback to the backup engine and the same opening deadline: a stream that one engine refuses is tried
 * on the other before the viewer is told it failed. What it does not do is draw -- there is no surface, no
 * controls and no screen kept awake, because there is nothing to look at.
 *
 * Told rather than telling: the viewer's choices come in as values ([request], [paused], [retries]) and what the
 * sound is doing goes out as [RadioStatus], so the radio's screen does not know the player exists.
 */
@Composable
fun RadioAudioRoute(
    /** The stream to play, or null for none. A new one replaces the last on the same engine when it can. */
    request: PlayerRequest?,
    /** The viewer holds the sound -- or the sleep timer did. */
    paused: Boolean,
    /** How many times a station that failed has been asked for again; a new number is a new try. */
    retries: Int,
    /** What the sound is doing, as it changes: see [radioStatusOf]. */
    onStatus: (RadioStatus) -> Unit,
    /** The sound was let go by the application going to the background, so the screen can forget the station. */
    onReleased: () -> Unit,
) {
    val model: PlayerViewModel = hiltViewModel()
    val report by rememberUpdatedState(onStatus)
    val released by rememberUpdatedState(onReleased)
    val activity = LocalContext.current.findActivity()
    val owner = LocalLifecycleOwner.current

    LaunchedEffect(model) { model.state.map(::radioStatusOf).distinctUntilChanged().collect { report(it) } }

    // A station is opened as it is asked for, and let go of when it is asked for no more. Never let go of what
    // was not opened: the player may be holding something else, and this route has not asked it for anything.
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(request) {
        if (request != null) {
            opened = true
            model.open(request)
        } else if (opened) {
            opened = false
            model.leave()
        }
    }

    // After the open above, which replaces the state it reads: a station asked for while another was held is
    // played, and not held because the last one was.
    //
    // **A station that was held comes back at the live edge.** A paused stream is a connection left open, and
    // played again from where it was paused it is minutes behind the broadcast -- a news bulletin heard late.
    // So letting go of a hold on the same station asks for it again, as a viewer tuning back in would.
    var heldUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(paused) {
        val current = model.state.value ?: return@LaunchedEffect
        val url = current.request.url
        if (paused) {
            heldUrl = url
            if (current.playRequested ?: true) model.playPause()
        } else {
            val resumed = heldUrl == url
            heldUrl = null
            if (resumed) model.retry()
        }
    }

    LaunchedEffect(retries) { if (retries > 0) model.retry() }

    // Stopped with the application: `ON_STOP`, as the player's own is, and not a rebuild of the activity, which
    // the disposal below handles.
    DisposableEffect(owner) {
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) {
                model.leave()
                released()
            }
        }
        owner.lifecycle.addObserver(watcher)
        onDispose { owner.lifecycle.removeObserver(watcher) }
    }

    // And with the screen: the player is the activity's and outlives a composition that is merely removed.
    DisposableEffect(Unit) {
        onDispose {
            model.leave()
            report(RadioStatus())
        }
    }
}

/**
 * The player's state as the radio's screen reads it.
 *
 * A buffering stall is still sounding -- the viewer has not stopped it -- and a stream being asked for again is
 * still on its way; a stream that ended is the same to a radio as one that failed, because both are silence
 * the viewer did not choose. Pure, so which phase a state is is asserted without a decoder.
 */
internal fun radioStatusOf(state: PlayerState?): RadioStatus {
    if (state == null) return RadioStatus()
    val phase = when (state.picture) {
        is Picture.Opening, is Picture.Reconnecting -> RadioPhase.OPENING
        is Picture.Playing, is Picture.Buffering ->
            if (state.playRequested == false) RadioPhase.PAUSED else RadioPhase.PLAYING
        is Picture.Paused -> RadioPhase.PAUSED
        is Picture.Failed, is Picture.Ended -> RadioPhase.FAILED
    }
    return RadioStatus(url = state.request.url, phase = phase)
}
