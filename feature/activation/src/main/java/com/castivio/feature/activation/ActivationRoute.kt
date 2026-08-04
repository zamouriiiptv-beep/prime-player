package com.castivio.feature.activation

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
import androidx.compose.ui.res.stringResource
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
 */
private enum class ActivationStep {
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

    val fixedViewport = isFixedViewport(state, atAddressStep = step == ActivationStep.Mac)

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
 */
internal fun isFixedViewport(state: ActivationUiState, atAddressStep: Boolean): Boolean =
    !state.busy && state.phase !is ActivationPhase.Failed && atAddressStep

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

            ActivationStep.Choose -> Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.xl),
            ) {
                SourceChoiceScreen(
                    onXtream = {
                        activation.useXtream()
                        onStep(ActivationStep.Xtream)
                    },
                    onPlaylist = {
                        activation.usePlaylistUrl()
                        onStep(ActivationStep.Playlist)
                    },
                )
                BackButton { onStep(ActivationStep.Mac) }
            }

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
                BackButton { onStep(ActivationStep.Choose) }
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
                BackButton { onStep(ActivationStep.Choose) }
            }
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    CastivioButton(
        text = stringResource(R.string.action_back),
        weight = ButtonWeight.Ghost,
        onClick = onClick,
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
                // **Horizontal only, and that is a limitation, not a preference.**
                // The side inset is the one the device review found and it costs
                // this composition nothing: the band's budget is vertical. A
                // bottom inset is a different matter -- a 24dp gesture bar takes
                // the shortest frame from 9dp of margin to -15, and a band that
                // does not fit is how Add playlist disappeared the first time.
                // The vertical inset lands with the capsule work that frees the
                // height for it; until then the top and bottom behave as they did
                // when the composition was reviewed. `ActivationBudgetTest`
                // records the arithmetic.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
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
