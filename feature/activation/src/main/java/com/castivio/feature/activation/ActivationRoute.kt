package com.castivio.feature.activation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.common.locale.CastivioLanguage
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.MotionLevel
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.activation.ActivationForm
import com.castivio.domain.activation.ActivationPhase
import com.castivio.domain.activation.ActivationUiState

/**
 * Where the user is in the activation flow.
 *
 * A local sealed type rather than a navigation graph. The whole flow is four steps deep,
 * every one of them belongs to this feature, and the app-level `Route.Activation` is a
 * single destination as far as the rest of Castivio is concerned — putting a second
 * NavHost inside it would add a library and a back stack to model three transitions.
 *
 * `internal` rather than private, and only so [isFixedViewport] can take it. That
 * predicate used to be handed a boolean the caller derived -- which is how the
 * source choice came to be in the scrolling frame: nothing about the type stopped
 * a second step from being forgotten, because the set of fixed steps was written
 * at the call site and the function could not see it. It is written once, here,
 * and the compiler checks the `when` is exhaustive.
 */
internal enum class ActivationStep {
    /** The address, and the route most people take. */
    Mac,

    /** "What did your provider give you?" */
    Choose,

    Xtream,
    Playlist,
}

/**
 * The activation flow, root to finish.
 *
 * Two things are worth knowing about how this is put together.
 *
 * **The importing screen is not a step.** It is a state of the attempt, so it replaces
 * whichever form started it rather than sitting after it in a stack. Pressing back from
 * a running import therefore cancels the import and returns to the form with the text
 * still in it — which is what a user means by back, and what a fifth step in a stack
 * would have got wrong.
 *
 * **Back is handled here, not by the system.** On a television back is the most-pressed
 * key on the remote, and the default behaviour — leave the app — is the one thing it
 * must not do from the middle of a form.
 */
@Composable
fun ActivationRoute(
    onActivated: () -> Unit,
    onExit: () -> Unit,
    /**
     * Which of the 37 Castivio is in, and what to do when the user picks another.
     *
     * Passed in rather than reached for. Applying a language means wrapping the
     * `Context` an activity is built on, which is `:app`'s business; this module
     * draws the picker and reports the choice. A feature that reached into the
     * application to change a locale would be a feature that cannot be rendered
     * in isolation, and this one is tested exactly that way.
     */
    language: CastivioLanguage,
    onLanguage: (CastivioLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activation: ActivationViewModel = hiltViewModel()
    val identityModel: ActivationIdentityViewModel = hiltViewModel()

    val state by activation.state.collectAsStateWithLifecycle()
    val identity by identityModel.state.collectAsStateWithLifecycle()

    // The picker is an overlay over this screen, so it is a piece of state here
    // rather than a step: nothing behind it moves, and back closes it without
    // touching where the user was in the flow.
    var pickingLanguage by rememberSaveable { mutableStateOf(false) }

    // Survives rotation and process death; the form text does too, because it lives in
    // the view model. Coming back to a half-typed server URL and finding it gone is the
    // kind of small betrayal people remember.
    var step by rememberSaveable { mutableStateOf(ActivationStep.Mac) }

    val phase = state.phase

    LaunchedEffect(phase) {
        if (phase is ActivationPhase.Succeeded) onActivated()
    }

    BackHandler {
        when {
            // The overlay is the innermost thing on screen, so it is the first
            // thing back closes. On a television this is the only way out of it.
            pickingLanguage -> pickingLanguage = false
            // A running attempt is the thing back cancels, whatever step started it.
            state.busy -> activation.cancel()
            phase is ActivationPhase.Failed -> activation.dismissFailure()
            step == ActivationStep.Mac -> onExit()
            step == ActivationStep.Choose -> step = ActivationStep.Mac
            else -> step = ActivationStep.Choose
        }
    }

    ImmersiveWhileVisible()

    val fixedViewport = isFixedViewport(state, step)

    ActivationSurface(modifier, fixedViewport = fixedViewport) {
        when {
            state.busy -> ImportingScreen(phase = phase, onCancel = activation::cancel)

            phase is ActivationPhase.Failed -> ActivationFailureScreen(
                failed = phase,
                onRetry = activation::retry,
                onEdit = activation::dismissFailure,
            )

            else -> Steps(
                // Explicit rather than inherited. AnimatedContent sizes itself to
                // its content by default, and the whole of this bug was a
                // constraint that was reasoned about instead of stated.
                modifier = if (fixedViewport) Modifier.fillMaxSize() else Modifier,
                step = step,
                state = state,
                identity = identity,
                activation = activation,
                onStep = { step = it },
                onRefresh = identityModel::refresh,
                onCopied = identityModel::copied,
                onOpenLanguage = { pickingLanguage = true },
            )
        }
    }

    if (pickingLanguage) {
        LanguagePicker(
            selected = language,
            onPick = {
                pickingLanguage = false
                onLanguage(it)
            },
            onDismiss = { pickingLanguage = false },
        )
    }
}

/**
 * Nothing on screen but Castivio, for as long as this screen is on it.
 *
 * Activation is the first thing a user sees and it is a full-frame composition:
 * a status bar's clock and battery sitting on top of the title, and a navigation
 * bar cutting into the QR, are somebody else's interface drawn over ours. The
 * device review photographed both.
 *
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` rather than a sticky hide: the bars
 * come back on a swipe and go away again on their own. Hiding system UI that a
 * user cannot retrieve is a different thing from hiding it, and the wrong one.
 *
 * **Restored on the way out.** `onDispose` puts the bars back, so leaving
 * activation for the shell does not leave the rest of the app immersive. A
 * one-way call in `onCreate` would have done half the job and been invisible
 * until somebody wondered where the clock went.
 *
 * A television has no bars to hide and the controller call is a no-op there.
 */
@Composable
private fun ImmersiveWhileVisible() {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context.findWindow()) ?: return

    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** The activity's window, through however many `ContextWrapper`s are in the way. */
private tailrec fun Context.findWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findWindow()
    else -> null
}

/**
 * Which of the two frames this state belongs in.
 *
 * The approved activation screen is a fixed-viewport composition: three bands
 * filling the screen, full-bleed hairlines, and no scroll. The forms are the
 * opposite — they overflow, they want a comfortable measure, and they want
 * padding. One container cannot be both, and treating them as the same thing is
 * what put the address, the key, the actions and the QR inside a vertically
 * scrolling column, where the middle band's `weight(1f)` had no bounded height
 * to take a share of and measured 0dp.
 *
 * A named function rather than three clauses inline, for one reason: this
 * predicate is the whole bug. Inline it was a boolean nothing could assert; here
 * it is pure, takes a domain value, and is checked in `ActivationFrameTest`
 * without a device. Getting it wrong again should cost a red JVM test, not a
 * user's screen.
 *
 * ## The source choice is fixed on every device, and no longer asks which one
 *
 * It asked twice, and both answers shipped wrong. `isTv` first: a 873dp handset
 * in landscape is not a television, so the step scrolled. Then
 * `DeviceClass.Expanded`, which is `screenWidthDp >= 840` — but `screenWidthDp`
 * describes the window, and the screen is drawn inside `safeDrawing`, so a
 * display cutout of 41dp is spent before the layout sees a pixel. 873 becomes
 * 827, and which side of 840 the platform's own figure falls on varies by
 * handset for reasons that have nothing to do with the question.
 *
 * The question is gone rather than asked more precisely. `SourceChoiceScreen`
 * lays its two cards out with `weight(1f)`, which divides whatever width exists
 * and therefore cannot overflow one; and the activity is
 * `screenOrientation="sensorLandscape"`, so the narrow portrait frame the old
 * branch existed for never reaches a user. Fixed, always, like the address step.
 */
internal fun isFixedViewport(
    state: ActivationUiState,
    step: ActivationStep,
): Boolean =
    !state.busy && state.phase !is ActivationPhase.Failed && when (step) {
        ActivationStep.Mac, ActivationStep.Choose -> true
        ActivationStep.Xtream, ActivationStep.Playlist -> false
    }

@Composable
private fun Steps(
    modifier: Modifier,
    step: ActivationStep,
    state: ActivationUiState,
    identity: ActivationIdentityState,
    activation: ActivationViewModel,
    onStep: (ActivationStep) -> Unit,
    onRefresh: () -> Unit,
    onCopied: (Copied) -> Unit,
    onOpenLanguage: () -> Unit,
) {
    // Read outside the transition: transitionSpec is not a composable lambda, so a
    // token fetched inside it would not compile -- and reading it once is what makes
    // the level the user chose apply to the transition that is about to start.
    val animated = CastivioTheme.motionLevel != MotionLevel.DISABLED

    AnimatedContent(
        modifier = modifier,
        targetState = step,
        transitionSpec = {
            // Every animation is optional. At DISABLED the step simply changes, with no
            // crossfade to sit through on a box that cannot afford one.
            if (animated) {
                fadeIn(Motion.enterSpec()) togetherWith fadeOut(Motion.exitSpec())
            } else {
                EnterTransition.None togetherWith ExitTransition.None
            }
        },
        label = "activationStep",
    ) { current ->
        when (current) {
            ActivationStep.Mac -> MacActivationScreen(
                identity = identity,
                onAddPlaylist = { onStep(ActivationStep.Choose) },
                onRefresh = onRefresh,
                onCopied = onCopied,
                onOpenLanguage = onOpenLanguage,
            )

            ActivationStep.Choose -> SourceChoiceScreen(
                onXtream = {
                    activation.useXtream()
                    onStep(ActivationStep.Xtream)
                },
                onPlaylist = {
                    activation.usePlaylistUrl()
                    onStep(ActivationStep.Playlist)
                },
                onBack = { onStep(ActivationStep.Mac) },
            )

            ActivationStep.Xtream -> Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                XtreamFormScreen(
                    form = state.form as? ActivationForm.Xtream ?: ActivationForm.Xtream(),
                    enabled = !state.busy,
                    canSubmit = state.canSubmit,
                    onName = activation::name,
                    onServerUrl = activation::serverUrl,
                    onUsername = activation::username,
                    onPassword = activation::password,
                    onSubmit = activation::submit,
                )
                BackButton(onClick = { onStep(ActivationStep.Choose) })
            }

            ActivationStep.Playlist -> Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                M3uFormScreen(
                    form = state.form as? ActivationForm.Playlist ?: ActivationForm.Playlist(),
                    enabled = !state.busy,
                    canSubmit = state.canSubmit,
                    onName = activation::name,
                    onUrl = activation::playlistUrl,
                    onSubmit = activation::submit,
                    onUseXtream = {
                        activation.acceptDetectedXtream()
                        onStep(ActivationStep.Xtream)
                    },
                )
                BackButton(onClick = { onStep(ActivationStep.Choose) })
            }
        }
    }
}

/** One declaration, used by the three steps that have somewhere to go back to. */
@Composable
internal fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    CastivioButton(
        text = stringResource(R.string.action_back),
        weight = ButtonWeight.Ghost,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * The frame every step sits in.
 *
 * One column, capped and centred, scrolling when it has to. A television gets overscan
 * padding and a narrower measure — text that runs the full width of a 55-inch screen is
 * unreadable, and the same layout on a phone would waste the only width it has.
 *
 * The first control takes focus on arrival so a remote has somewhere to start; without
 * it the first press of a D-pad does nothing and the user presses it again harder.
 */
@Composable
internal fun ActivationSurface(
    modifier: Modifier = Modifier,
    /**
     * True for a screen that owns the viewport and must not scroll.
     *
     * The activation screen is measured against a fixed 873x393, its hairlines
     * run edge to edge, and its middle band claims the height the header and
     * footer leave. All three of those need a parent with a bounded height and
     * no padding of its own, and none of them survive a scrolling column: an
     * infinite height constraint leaves a `weight` nothing to divide, so the
     * band measures zero and the screen renders as a header above a legal line.
     *
     * The forms want precisely the opposite -- they overflow, so they scroll,
     * and a text field is unreadable at 873dp so they are capped to a measure.
     * Two requirements, two layouts, chosen here rather than compromised into
     * one that serves neither.
     */
    fixedViewport: Boolean = false,
    content: @Composable () -> Unit,
) {
    val device = CastivioTheme.device
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    if (fixedViewport) {
        Box(
            modifier
                .fillMaxSize()
                // The gradient runs under the system bars; the content does not.
                //
                // `:app` goes edge to edge, which is what lets the aurora reach
                // the corners instead of stopping at a grey rectangle. It also
                // means the navigation bar sits on top of whatever is drawn
                // there, and in landscape that bar is at the side -- so a screen
                // that ignores insets loses its edge padding on one side only,
                // and which side depends on the handset and the text direction.
                //
                // `safeDrawing` rather than a hand-picked side: it is the union
                // of the system bars and the display cutout, it is already
                // mirrored for RTL because it is resolved per edge rather than
                // per direction, and it is 0 on a television, which has neither.
                // An RTL-specific padding would be the same bug with a second
                // way to be wrong.
                //
                // Every side now, where it used to be horizontal only.
                //
                // The restriction was real: the shortest frame had 9dp of margin,
                // and a 24dp gesture bar would have pushed the identity column
                // past its band -- which does not clip or scroll, it hands zero
                // height to Add playlist and Refresh. The capsules bought 26dp
                // back, the budget is 35dp with the bars hidden and 11dp with one
                // swiped back, and `ActivationBudgetTest` asserts both.
                //
                // The screen also runs immersive (see [ImmersiveWhileVisible]), so
                // on a settled device these are zero. Applying them anyway is the
                // difference between a layout that is correct and one that is
                // correct while the system cooperates.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .focusRequester(focus),
        ) { content() }
        return
    }

    Box(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(
                horizontal = if (device.isTv) Spacing.tvOverscan else device.screenPadding,
                vertical = if (device.isTv) Spacing.tvOverscan else Spacing.xl,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = if (device.isTv) TV_MEASURE else Sizing.maxContentWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .focusRequester(focus),
        ) {
            content()
        }
    }
}

/**
 * Wide enough for an address at hero size, narrow enough that a paragraph beside it is
 * still one comfortable measure rather than a line the eye has to track across a room.
 */
private val TV_MEASURE = Sizing.maxContentWidth / 2
