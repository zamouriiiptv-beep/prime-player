package com.castivio.tv.gate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.DelayedSpinner
import com.castivio.domain.entitlement.StartDestination
import com.castivio.feature.activation.ActivationRoute
import com.castivio.tv.locale.LocalLocaleController
import com.castivio.tv.licence.LicenceWithLanguage

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
            //
            // It used to become real by recreating the activity. It does not any
            // more: the controller writes the choice to storage and moves the
            // composition to it, so the screen changes language without the
            // window being destroyed. See `LocaleController`.
            val locale = LocalLocaleController.current
            ActivationRoute(
                onActivated = model::refresh,
                onExit = onExit,
                language = locale.current.language,
                onLanguage = locale::choose,
                modifier = modifier,
            )
        }

        // Castivio's own licence, which is not the provider's subscription. Back
        // leaves the app: there is no screen behind this one, because the gate
        // sent the user here precisely because the app may not be used.
        is StartDestination.Licence -> LicenceWithLanguage(
            onLeave = onExit,
            modifier = modifier,
        )
    }
}
