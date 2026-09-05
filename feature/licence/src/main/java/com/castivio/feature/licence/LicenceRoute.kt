package com.castivio.feature.licence

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.domain.entitlement.EntitlementState

/**
 * The licence screen, wired.
 *
 * ## The handoff is the whole feature
 *
 * Castivio is portal-first on every platform: the app never processes a payment.
 * Pressing a plan opens the Castivio activation portal in the user's browser,
 * carrying which plan and which device — and the portal owns authentication,
 * payment, licence creation and MAC binding from there. When Play Billing
 * arrives for Play builds it arrives as a second producer of a
 * `RedemptionCredential`, which is the single integration point, and nothing on
 * this screen changes.
 *
 * The `Intent` lives here rather than in the view model. Launching one is the
 * platform's business, and a view model that held a `Context` would be a view
 * model this feature could not test on the JVM.
 */
@Composable
fun LicenceRoute(
    onLeave: () -> Unit,
    onOpenLanguage: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * An entitlement to render instead of the device's own. **Debug only.**
     *
     * The reason this exists is a real gap found on a device: a debug build
     * grants itself a local trial, so gate one always passes and the licence
     * screen is unreachable — every entitlement state that matters was
     * untestable by anyone holding a phone. This is the smallest thing that
     * closes that, and it deliberately overrides *only* the entitlement: the
     * view model, the address, the QR, the copy controls and the portal handoff
     * are all the real ones, so what is being tested is the screen and not a
     * preview of it.
     *
     * Ignored outside a debug build, checked here rather than trusted to the
     * caller — a debug affordance whose safety depends on who calls it is a
     * debug affordance that ships.
     */
    forcedState: EntitlementState? = null,
) {
    val model: LicenceViewModel = hiltViewModel()
    val real by model.state.collectAsStateWithLifecycle()
    val state = if (BuildConfig.DEBUG && forcedState != null) {
        real.copy(licence = forcedState)
    } else {
        real
    }
    val context = LocalContext.current

    ImmersiveWhileVisible()

    // ## Back, innermost thing first
    //
    // A handoff in flight is what back cancels; then a failure notice; then the
    // screen itself. [onLeave] is the caller's, and that is the whole of the
    // per-state rule in `design/licence-spec.md` §11: this screen is reached
    // from the gate only when the app may not be used, where there is nothing
    // behind it and leaving means leaving; and from Settings only when it may,
    // where leaving means going back to Settings. `StartGate` already decides
    // which, so restating it here would be a second answer to a settled
    // question.
    //
    // What back must never do is nothing at all. On a remote it is the
    // most-pressed key on the device, and a press that appears to be ignored is
    // a press repeated harder.
    BackHandler {
        when {
            state.opening != null -> model.cancelHandoff()
            state.failed -> model.dismissFailure()
            else -> onLeave()
        }
    }

    LicenceSurface(modifier) {
        LicenceScreen(
            state = state,
            onPlan = { offer ->
                val url = model.portalFor(offer)
                if (!context.openExternally(url)) model.handoffFailed()
            },
            onRetry = model::refresh,
            onSupport = {
                // The support address is the portal's own page: it is the one
                // place Castivio can answer from, it already knows how to
                // identify a device, and a `mailto:` would ask a television with
                // no mail client to do something it cannot.
                if (!context.openExternally(model.supportUrl())) model.handoffFailed()
            },
            onCopied = model::copied,
            onOpenLanguage = onOpenLanguage,
        )
    }
}

/**
 * The frame the screen sits in: the viewport, and nothing that scrolls.
 *
 * The composition is measured against a fixed 873×393, its hairlines run edge to
 * edge, and its middle band claims what the header and footer leave. All three
 * need a parent with a **bounded** height. Inside a scrolling column the height
 * constraint is infinite, a `weight` has no remaining space to divide, the band
 * measures zero, and the screen renders as a header above a legal line. That is
 * not hypothetical — it shipped on the sibling screen and was photographed on a
 * real device.
 */
@Composable
private fun LicenceSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            // The gradient runs under the system bars; the content does not.
            //
            // `safeDrawing` rather than a hand-picked side: it is the union of
            // the system bars and the display cutout, it is already mirrored for
            // RTL because it is resolved per edge rather than per direction, and
            // it is zero on a television, which has neither.
            //
            // The screen also runs immersive, so on a settled device these are
            // zero. Applying them anyway is the difference between a layout that
            // is correct and one that is correct while the system cooperates —
            // and the budget in `LicenceMetrics` is written against the bar
            // being there.
            // **Vertical only.** The horizontal half moved to `castivioStage`, where
            // it is combined with the frame's own `edge` by `max` rather than added to
            // it. Applied here too, the two stacked: a 37dp display cutout plus a 32dp
            // edge put the composition 69dp from one side of a real handset and 33 from
            // the other. The vertical half stays, because the frame is chosen from the
            // height this measures and every budget is written against it.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
    ) { content() }
}

/**
 * Nothing on screen but Castivio, for as long as this screen is on it.
 *
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` rather than a sticky hide: the bars
 * come back on a swipe and go away again on their own. Hiding system UI a user
 * cannot retrieve is a different thing from hiding it, and the wrong one.
 *
 * **Restored on the way out**, so leaving the licence screen does not leave the
 * rest of the app immersive. A one-way call would do half the job and stay
 * invisible until somebody wondered where the clock went.
 */
@Composable
private fun ImmersiveWhileVisible() {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = view.context.findWindow() ?: return

    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * Open a link, and say whether anything happened.
 *
 * A television without a browser throws [ActivityNotFoundException], and a
 * device policy can make the resolve fail in other ways. Returning false rather
 * than crashing lets the screen say "that didn't work" — which is the honest
 * answer and the only one the user can act on. Silently leaving a card spinning
 * would be the worst of the three.
 */
private fun Context.openExternally(url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        // The portal is a separate journey and belongs in its own task: coming
        // back from the browser must return to the licence screen as it was, not
        // to a back stack with a web page in the middle of it.
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { startActivity(intent) }.isSuccess
}

/** The activity's window, through however many `ContextWrapper`s are in the way. */
private tailrec fun Context.findWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findWindow()
    else -> null
}
