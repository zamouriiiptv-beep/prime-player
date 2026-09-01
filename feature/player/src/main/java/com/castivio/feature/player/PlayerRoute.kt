package com.castivio.feature.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * The player, wired.
 *
 * Thin on purpose: it binds the view model, translates presses into calls, and owns the two
 * things that are the activity's business rather than the screen's — picture-in-picture and
 * keeping the display awake. Everything else is [PlayerScreen], which takes a value and a
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
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
) {
    val model: PlayerViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()

    LaunchedEffect(request) { model.open(request) }

    // The screen must not sleep during a film. Cleared on the way out rather than left
    // set, because a flag that outlives its screen is a battery complaint nobody traces.
    DisposableEffect(activity) {
        activity?.window?.addFlags(KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(KEEP_SCREEN_ON) }
    }

    val chooser = stringResource(R.string.player_share)

    val current = state ?: return

    // The sound stops when the application does.
    //
    // `ON_STOP` rather than `ON_PAUSE`, and the difference is picture in picture: a player
    // in a PiP window is paused by the system and still on screen, so pausing on ON_PAUSE
    // would stop the one thing PiP exists to keep running. Nothing resumes on the way back
    // — a film that restarts itself when a phone is unlocked is how a player gets muted.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.pauseForBackground()
        }
        owner.lifecycle.addObserver(watcher)
        onDispose { owner.lifecycle.removeObserver(watcher) }
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
        current.controls,
        current.sheet,
        current.statistics,
        current.picture,
        current.interactions,
    ) {
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
        onLeave()
    }

    BackHandler {
        when {
            // Innermost first, which is the same ladder every other screen in Castivio
            // uses. A lock is deliberately *not* on it: back must not unlock, or the lock
            // protects nothing.
            current.locked -> Unit
            current.statistics -> model.setStatistics(false)
            current.sheet != null -> model.openSheet(null)
            current.controls -> model.showControls(false)
            else -> leave()
        }
    }

    Box(modifier.fillMaxSize()) {
        PlayerScreen(
            state = current,
            actions = PlayerActions(
                onBack = leave,
                onPlayPause = model::playPause,
                onSeekBy = model::seekBy,
                onSeekTo = model::seekTo,
                onPrevious = { onPrevious?.invoke() },
                onNext = { onNext?.invoke() },
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
                onFullscreen = { model.showControls(false) },
                onPictureInPicture = { activity?.enterPip() },
                onShare = { context.share(shareOffer(current.request), chooser) },
                setOutput = model::setOutput,
            ),
        )
    }
}

/**
 * Hand the window to the system.
 *
 * The aspect is 16:9 rather than the stream's, because the stream's is not known until
 * there is a frame and a PiP request that has to wait for one is a PiP request that misses
 * the gesture. The system letterboxes inside whatever it gives us.
 *
 * Nothing validates that the video fills the window afterwards, and that is correct: in PiP
 * the window belongs to Android, not to Castivio, so a check that the picture is edge to
 * edge would be checking somebody else's layout.
 */
private fun Activity.enterPip() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(PIP_WIDTH, PIP_HEIGHT))
        .build()
    runCatching { enterPictureInPictureMode(params) }
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

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`, named rather than imported wholesale. */
private const val KEEP_SCREEN_ON = 128

/** Long enough to find a control, short enough not to sit over the film. */
private const val CONTROLS_LINGER_MS = 4_000L

private const val PIP_WIDTH = 16
private const val PIP_HEIGHT = 9
