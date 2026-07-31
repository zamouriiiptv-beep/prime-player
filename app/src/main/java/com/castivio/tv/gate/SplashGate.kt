package com.castivio.tv.gate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.DelayedSpinner
import com.castivio.core.design.components.ErrorState
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.entitlement.StartDestination
import com.castivio.feature.activation.ActivationRoute
import com.castivio.tv.locale.AppLocale
import com.castivio.tv.locale.findActivity
import com.castivio.feature.activation.R as ActivationStrings

/**
 * The first decision the app makes, and the only screen that is allowed to make it.
 *
 * Two gates in a fixed order — is Castivio licensed on this device, and is there a
 * catalogue to show — answered in `:domain` and merely rendered here. The rule that a
 * lapsed provider still opens Home is not restated in this file; it is held by the fact
 * that `startDestination` is never given a provider's health to consider.
 *
 * Nothing is drawn until the answer arrives, beyond a spinner that waits a beat first. A
 * splash that flashes the activation screen and then replaces it with Home is a worse
 * first impression than a blank half-second.
 */
@Composable
internal fun SplashGate(
    onExit: () -> Unit,
    home: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val model: SplashViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()

    when (val destination = state.destination) {
        null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DelayedSpinner()
        }

        is StartDestination.Home -> home()

        is StartDestination.Activation -> {
            // The application owns the locale, because applying one means wrapping
            // the Context the activity was built on. The feature draws the picker
            // and reports the choice; this is where that choice becomes real.
            val context = LocalContext.current
            val activity = remember(context) { context.findActivity() }
            ActivationRoute(
                onActivated = model::refresh,
                onExit = onExit,
                language = remember(context) { AppLocale.current(context).language },
                onLanguage = { language ->
                    AppLocale.choose(context, language)
                    // Every resource read before now was read in the old language,
                    // so the activity is rebuilt rather than recomposed.
                    activity?.recreate()
                },
                modifier = modifier,
            )
        }

        // The licence screen is a later slice. Until it exists this states the fact and
        // offers the only honest action, rather than inventing a purchase flow — and it
        // is unreachable in a debug build, where the local trial is granted.
        is StartDestination.Licence -> Box(
            modifier.fillMaxSize().padding(Spacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            ErrorState(
                title = stringResource(ActivationStrings.string.licence_blocked_title),
                detail = stringResource(ActivationStrings.string.licence_blocked_detail),
                actionLabel = stringResource(ActivationStrings.string.licence_blocked_action),
                onAction = onExit,
            )
        }
    }
}
