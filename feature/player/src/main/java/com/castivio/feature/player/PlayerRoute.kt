package com.castivio.feature.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.input.KeyRelay
import com.castivio.core.design.input.LocalKeyRelay
import com.castivio.domain.PlayerPulse
import kotlinx.coroutines.delay

/**
 * The player, wired.
 *
 * Thin on purpose: it binds the view model, translates presses into calls, and owns the
 * things that are the activity's business rather than the screen's — the application going
 * to the background and coming back, and keeping the display awake. Everything else is
 * [PlayerScreen], which takes a value and a
 * bundle of lambdas and can therefore be composed by a test with no Hilt graph, no decoder
 * and no surface.
 *
 * ## Opening happens in a `LaunchedEffect` keyed on the request
 *
 * Not in composition. A screen that called `open` while composing would reopen the stream
 * on every recomposition — which, on a screen whose position updates four times a second,
 * is four channel changes a second.
 */
@Composable
fun PlayerRoute(
    request: PlayerRequest,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Which shape to draw. See [PlayerMode].
     *
     * The same route either way, and that is what makes the pair work: `hiltViewModel()`
     * here resolves against the activity — there is no navigation back stack under this
     * screen — so the compact composition and the expanded one bind the *same*
     * `PlayerViewModel`, holding the same engine on the same stream. Swapping one for the
     * other releases a `SurfaceView` and creates another; the decoder never stops.
     *
     * The two must never be composed at once. Two surfaces would both call `setOutput`
     * and the last one to compose would take the picture from the other. The caller
     * enforces that by composing one or the other — see the shell.
     */
    mode: PlayerMode = PlayerMode.Expanded,
    /**
     * Compact only: what a press on the picture does.
     *
     * Null in the expanded shape, where a press means what it has always meant.
     */
    onExpand: (() -> Unit)? = null,
    /**
     * Expanded only: where back goes instead of out.
     *
     * When this is given, the last rung of the back ladder collapses to the compact shape
     * rather than releasing the engine — which is the whole of "Back → Mini, still
     * playing". [onLeave] is then never reached from here, and the engine is released by
     * whoever takes the compact shape away.
     */
    onCollapse: (() -> Unit)? = null,
    /**
     * Compact only: asked as this composition leaves, and answers whether to keep playing.
     *
     * A composable that is simply removed runs no `leave`, so without this the compact
     * player would go on decoding behind whatever screen replaced it — which is the exact
     * defect the note on [PlayerViewModel.leave] records from the last time a player was
     * swapped out rather than popped.
     *
     * It cannot be a `Boolean`, and that is the whole reason for the lambda: the value has
     * to be read *at disposal*, after the caller's state has changed. A parameter captured
     * at composition time would always carry the answer from before the thing that removed
     * this screen happened, and the two cases it has to separate — handing the stream to
     * the expanded shape, and leaving the board altogether — differ only in that state.
     */
    retainOnDispose: () -> Boolean = { false },
    /**
     * Told what the player is doing -- see [PlayerState.pulse] -- and told again when that
     * changes: the stream it was asked for, whether it has a picture, and what the picture
     * measures. Null when there is no player.
     *
     * A callback and not a value because the board that marks the playing channel is in a
     * module that must not know the player exists -- the shell, which knows both, carries it
     * across. Called with the current answer as soon as this route is composed, so the compact
     * shape and the expanded one each report what the other left.
     */
    onPulseChange: (PlayerPulse?) -> Unit = {},
    /**
     * Expanded only: change the channel by this many places -- 1 for the next, -1 for the one
     * before -- when the remote's channel keys ask.
     *
     * Null when there is no list to step through, which is every film and every channel that was
     * not pressed in one: the keys are then the focus's, exactly as they were. Told rather than
     * done here, because which channel is next is the channel list's to say -- the player holds a
     * stream, and must not learn about a catalogue.
     */
    onChannelStep: ((Int) -> Unit)? = null,
) {
    val compact = mode == PlayerMode.Compact
    val model: PlayerViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()

    // Temporary, for development: the opening panel reads each opening from the press to its first frame -- see
    // `OpeningDiagnostics`. On in a debug build and nowhere else, and told before the open below is asked for.
    remember { OpeningDiagnostics.enable(BuildConfig.DEBUG) }

    LaunchedEffect(request) { model.open(request) }

    val report by rememberUpdatedState(onPulseChange)
    LaunchedEffect(model) { model.pulse.collect { report(it) } }

    // The screen must not sleep during a film. Cleared on the way out rather than left
    // set, because a flag that outlives its screen is a battery complaint nobody traces.
    DisposableEffect(activity) {
        activity?.window?.addFlags(KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(KEEP_SCREEN_ON) }
    }

    val chooser = stringResource(R.string.player_share)

    val current = state ?: return

    // **The channel keys, taken from the activity and not from the focus.**
    //
    // A full-screen player may have nothing focused -- the controls are hidden, and the picture is
    // not something a remote walks to -- and a key that goes to the focus then goes nowhere. A
    // channel key means the same wherever the focus is, so the player claims them from the relay
    // the activity offers every key to, for as long as it is on screen with a list to step through.
    // The decision is `channelStepOf`'s: which keys, and the three facts of the screen it reads.
    // A press steps once; the platform's repeats of it are taken and ignored, so a held key is not
    // a stream opened ten times a second. The key coming up is taken too, so the focus never sees
    // the half of a press it did not see the start of.
    val stepBy by rememberUpdatedState(onChannelStep)
    val scene by rememberUpdatedState(
        KeyScene(
            locked = current.locked,
            panelOpen = current.sheet != null || current.statistics,
            controlsShown = current.controls,
            failureShown = current.picture is Picture.Failed,
        ),
    )
    val relay = LocalKeyRelay.current
    val stepsChannels = !compact && onChannelStep != null
    DisposableEffect(relay, stepsChannels) {
        val release = if (relay != null && stepsChannels) {
            relay.claim(
                KeyRelay.KeyTaker { keyCode, down, repeats ->
                    val step = channelStepOf(keyCode, scene) ?: return@KeyTaker false
                    if (down && repeats == 0) stepBy?.invoke(step.by)
                    true
                },
            )
        } else {
            null
        }
        onDispose { release?.invoke() }
    }

    // The sound stops when the application does.
    //
    // `ON_STOP` rather than `ON_PAUSE`, and the difference is a split screen: a player beside
    // another application is paused by the system and still on screen, so pausing on
    // ON_PAUSE would stop a film somebody is watching. Nothing resumes on the way back
    // — a film that restarts itself when a phone is unlocked is how a player gets muted.
    //
    // And it comes back when the application does: a channel is opened again and a film is
    // given its surface back -- see `PlayerViewModel.returnFromBackground`.
    //
    // A stop that is the activity being rebuilt is not the viewer leaving and is not handled
    // here; the disposal below handles it.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val watcher = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP ->
                    if (activity?.isChangingConfigurations != true) model.pauseForBackground()
                Lifecycle.Event.ON_START -> model.returnFromBackground()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(watcher)
        onDispose { owner.lifecycle.removeObserver(watcher) }
    }

    // **An activity rebuilt is a player nobody can see.**
    //
    // The view model belongs to the activity and survives it being rebuilt; the shell's state
    // does not, so the new activity opens on Home with no player in it. Rotation does not
    // rebuild the activity -- the manifest takes those changes itself -- so any rebuild that
    // reaches here is one after which nothing will show this stream again. It went on
    // playing anyway: sound with no picture, and on the Channels board the channel's tile
    // where the video had been, until the player was opened again. It is released instead.
    DisposableEffect(Unit) {
        onDispose { if (activity?.isChangingConfigurations == true) model.leave() }
    }

    // Controls hide themselves, because a player whose chrome stays up is a player you
    // cannot watch. Restarted whenever they are shown again, and not running while a
    // sheet or the statistics panel is open — hiding the controls out from under a user
    // who is reading a panel is the most irritating thing a player can do.
    //
    // `interactions` is on the key list because the clock used to run from the moment the
    // chrome *appeared* and nothing restarted it. Four seconds is long enough to find a
    // control and not long enough to find it, change your mind and press another, so the
    // row could vanish under a thumb already travelling toward it — and the press then
    // landed on the picture, bringing the chrome back, which reads exactly like a button
    // that does nothing.
    LaunchedEffect(
        compact,
        current.controls,
        current.sheet,
        current.statistics,
        current.picture,
        current.interactions,
    ) {
        // Nothing to hide in the compact shape: its one band is drawn whether or not
        // `controls` is set, and hiding the flag there would only decide what the
        // expanded shape looked like on the way back.
        if (compact) return@LaunchedEffect
        if (!current.controls || current.sheet != null || current.statistics) return@LaunchedEffect
        if (current.picture !is Picture.Playing) return@LaunchedEffect
        delay(CONTROLS_LINGER_MS)
        model.showControls(false)
    }

    // Leaving is two things and used to be one. The screen is swapped out by the host
    // rather than popped off a back stack, so the view model is the activity's and
    // survives — clearing the request hid the player and left the engine decoding, which
    // is why the sound carried on over the library screen. The engine is released here,
    // explicitly, and only on the way out.
    val leave = {
        model.leave()
        // Nothing is playing once the engine is let go, and the collector above may already
        // be on its way out -- so it is said here rather than waited for.
        report(null)
        onLeave()
    }

    // **No back handler in the compact shape.** It is one element inside somebody else's
    // screen, and back there belongs to that screen: a preview that swallowed the key
    // would strand a viewer on the Channels board.
    // See [retainOnDispose]. Only the compact shape needs it: the expanded one is removed
    // by a press that has already decided what happens to the engine.
    if (compact) {
        DisposableEffect(Unit) {
            onDispose {
                if (!retainOnDispose()) {
                    model.leave()
                    report(null)
                }
            }
        }
    }

    BackHandler(enabled = !compact) {
        when {
            // Innermost first, which is the same ladder every other screen in Castivio
            // uses. A lock is deliberately *not* on it: back must not unlock, or the lock
            // protects nothing.
            current.locked -> Unit
            current.statistics -> model.setStatistics(false)
            current.sheet != null -> model.openSheet(null)
            current.controls -> model.showControls(false)
            // The last rung, and the only one this pair changes: collapse where there is
            // somewhere to collapse to, and otherwise leave as before. Collapsing does
            // not call `model.leave()`, which is exactly why the channel goes on playing.
            onCollapse != null -> onCollapse()
            else -> leave()
        }
    }

    // `fillMaxSize` in the expanded shape, which owns the viewport. In the compact one the
    // caller has already decided how big this is, and filling would take the well's whole
    // column rather than the plate inside it.
    Box(if (compact) modifier else modifier.fillMaxSize()) {
        PlayerScreen(
            state = current,
            mode = mode,
            actions = PlayerActions(
                // Collapse rather than release, for the same reason back does — the title
                // bar's arrow and the back key are the same gesture by a different route.
                onBack = onCollapse ?: leave,
                onPlayPause = model::playPause,
                onSeekBy = model::seekBy,
                onSeekTo = model::seekTo,
                onToggleControls = { model.showControls(!current.controls) },
                onAspect = model::setAspect,
                onSubtitleStyle = model::setSubtitleStyle,
                onFindSubtitles = model::findSubtitles,
                onSubtitleQuery = model::setSubtitleQuery,
                onUseSubtitle = model::useSubtitle,
                onClearSubtitle = model::clearDownloadedSubtitle,
                onNudgeSubtitles = model::nudgeSubtitles,
                onLock = model::setLocked,
                onSheet = model::openSheet,
                onStatistics = model::setStatistics,
                onSpeed = model::setSpeed,
                onSelectTrack = model::selectTrack,
                onReturnToLive = model::returnToLive,
                onRetry = model::retry,
                onTryBackup = model::tryBackup,
                onShare = { context.share(shareOffer(current.request), chooser) },
                onExpand = { onExpand?.invoke() },
                setOutput = model::setOutput,
                releaseOutput = model::releaseOutput,
            ),
        )
        // The big player only: the compact one is a plate in somebody else's screen, with no room for lines.
        if (!compact) OpeningPanel(Modifier.align(Alignment.CenterStart))
    }
}

/**
 * Hand what is playing to whatever the user picks.
 *
 * The read permission is granted on the intent rather than assumed: a `MediaStore` URI
 * belongs to this process's grant, and a receiving application that is simply handed the
 * string gets a `SecurityException` instead of a file.
 *
 * Wrapped, because the share sheet is not worth a crash. A device with no application able
 * to receive the intent throws, and the correct outcome there is a button that did nothing
 * this once — not a player that disappears mid-film.
 */
private fun android.content.Context.share(offer: ShareOffer, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TITLE, offerTitle(offer))
        putExtra(Intent.EXTRA_SUBJECT, offerTitle(offer))
        when (offer) {
            is ShareOffer.File -> {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, Uri.parse(offer.uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            is ShareOffer.Words -> {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, offer.title)
            }
        }
    }
    runCatching { startActivity(Intent.createChooser(intent, chooserTitle)) }
}

private fun offerTitle(offer: ShareOffer): String = when (offer) {
    is ShareOffer.File -> offer.title
    is ShareOffer.Words -> offer.title
}

internal tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`, named rather than imported wholesale. */
private const val KEEP_SCREEN_ON = 128

/** Long enough to find a control, short enough not to sit over the film. */
private const val CONTROLS_LINGER_MS = 4_000L
