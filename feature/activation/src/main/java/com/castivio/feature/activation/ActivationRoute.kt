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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
    modifier: Modifier = Modifier,
) {
    val activation: ActivationViewModel = hiltViewModel()
    val identityModel: MacIdentityViewModel = hiltViewModel()

    val state by activation.state.collectAsStateWithLifecycle()
    val identity by identityModel.state.collectAsStateWithLifecycle()

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
            // A running attempt is the thing back cancels, whatever step started it.
            state.busy -> activation.cancel()
            phase is ActivationPhase.Failed -> activation.dismissFailure()
            step == ActivationStep.Mac -> onExit()
            step == ActivationStep.Choose -> step = ActivationStep.Mac
            else -> step = ActivationStep.Choose
        }
    }

    ActivationSurface(modifier) {
        when {
            state.busy -> ImportingScreen(phase = phase, onCancel = activation::cancel)

            phase is ActivationPhase.Failed -> ActivationFailureScreen(
                failed = phase,
                onRetry = activation::retry,
                onEdit = activation::dismissFailure,
            )

            else -> Steps(
                step = step,
                state = state,
                identity = identity,
                activation = activation,
                onStep = { step = it },
            )
        }
    }
}

@Composable
private fun Steps(
    step: ActivationStep,
    state: ActivationUiState,
    identity: MacIdentityState,
    activation: ActivationViewModel,
    onStep: (ActivationStep) -> Unit,
) {
    // Read outside the transition: transitionSpec is not a composable lambda, so a
    // token fetched inside it would not compile -- and reading it once is what makes
    // the level the user chose apply to the transition that is about to start.
    val animated = CastivioTheme.motionLevel != MotionLevel.DISABLED

    AnimatedContent(
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
                onAddManually = { onStep(ActivationStep.Choose) },
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
private fun ActivationSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val device = CastivioTheme.device
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        modifier
            .fillMaxSize()
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
