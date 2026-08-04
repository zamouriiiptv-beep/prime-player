package com.castivio.tv.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.BackHandler
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.Plan
import com.castivio.domain.entitlement.ServiceFault
import com.castivio.tv.BuildConfig
import com.castivio.tv.licence.LicenceWithLanguage

/**
 * A way to reach the licence screen on a device, in any entitlement state.
 *
 * ## Why this had to exist
 *
 * Found by testing on real hardware: **the licence screen was unreachable in a
 * debug build.** Not a navigation bug — the gate working exactly as designed.
 * `establish()` grants a local trial in a debug build, so gate one always passes;
 * gate two then sends a device with no playlist to Add Subscription, and the
 * Settings entry that would otherwise reach the licence screen lives in the
 * shell, which needs a catalogue to get to. A tester installing the APK saw Add
 * Subscription and had no route to the thing under test.
 *
 * Making the gate reachable by *removing* the debug trial would be worse: it
 * would put every developer behind a licence wall to test anything else. So the
 * screen gets a debug door instead of the gate getting a debug hole.
 *
 * ## What it is not
 *
 * Not a mock screen and not a preview. Each row hands the real `LicenceRoute` an
 * entitlement to render; everything else — the view model, the device address,
 * the QR, the copy controls, the portal handoff, the language overlay — is the
 * production path. What is being checked on the device is the screen, not a
 * drawing of it.
 *
 * ## Why it cannot ship
 *
 * Three independent reasons, because one is a habit and three is a property:
 * [DebugEntry] composes nothing unless `BuildConfig.DEBUG`, `LicenceRoute`
 * ignores `forcedState` unless `BuildConfig.DEBUG`, and R8 removes the lot from a
 * release build because the constant is false at compile time.
 */
@Composable
internal fun DebugEntry(content: @Composable () -> Unit) {
    if (!BuildConfig.DEBUG) {
        content()
        return
    }

    var open by remember { mutableStateOf(false) }
    var forced by remember { mutableStateOf<EntitlementState?>(null) }

    when {
        forced != null -> {
            LicenceWithLanguage(onLeave = { forced = null }, forcedState = forced)
            return
        }
        open -> {
            StateBoard(onPick = { forced = it }, onDismiss = { open = false })
            return
        }
    }

    Box(Modifier.fillMaxSize()) {
        content()
        // Deliberately ugly and deliberately in the way. A debug affordance that
        // looks like part of the product is one somebody screenshots for a review.
        Text(
            text = "DEBUG",
            style = CastivioType.labelMedium,
            color = CastivioTheme.colors.onBackground,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.sm)
                .clip(RoundedCornerShape(Radius.xs))
                .background(CastivioTheme.colors.danger)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { open = true }
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                .testTag(DEBUG_TAG),
        )
    }
}

@Composable
private fun StateBoard(onPick: (EntitlementState) -> Unit, onDismiss: () -> Unit) {
    val colors = CastivioTheme.colors
    BackHandler(onBack = onDismiss)

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            "Licence states — debug only",
            style = CastivioType.titleMedium,
            color = colors.onBackground,
        )
        Text(
            "Each row opens the real licence screen with that entitlement. Back returns here.",
            style = CastivioType.bodySmall,
            color = colors.onBackgroundMuted,
        )
        val floor = Sizing.minTarget(CastivioTheme.device.isTv)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(everyEntitlement()) { (label, state) ->
                Text(
                    text = label,
                    style = CastivioType.bodyMedium,
                    color = colors.onBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        // A remote has to land on these too, debug tool or not.
                        .defaultMinSize(minHeight = floor)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(colors.glassFill)
                        .clickable { onPick(state) }
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                )
            }
        }
    }
}

/**
 * Every case of the sealed type, named as a tester would ask for it.
 *
 * Enumerated by hand rather than derived, because the two `ServiceUnavailable`
 * faults are different screens and a derivation over the sealed type would offer
 * one row for both.
 */
private fun everyEntitlement(): List<Pair<String, EntitlementState>> = listOf(
    "Trial · 3 days left" to EntitlementState.TrialActive(expiresAtMs = 0, daysRemaining = 3),
    "Trial · 1 day left" to EntitlementState.TrialActive(expiresAtMs = 0, daysRemaining = 1),
    "Trial ended" to EntitlementState.TrialExpired,
    "Annual · active" to EntitlementState.AnnualActive(expiresAtMs = 0, daysRemaining = 200),
    "Annual · expired" to EntitlementState.AnnualExpired,
    "Lifetime" to EntitlementState.Lifetime,
    "No licence yet" to EntitlementState.Unknown,
    "Verification needed" to EntitlementState.VerificationUnavailable(
        lastKnownPlan = Plan.ANNUAL,
        lastKnownExpiresAtMs = null,
        graceEndedAtMs = 0,
    ),
    "Unavailable · not configured" to
        EntitlementState.ServiceUnavailable(ServiceFault.NOT_CONFIGURED),
    "Unreadable · storage" to
        EntitlementState.ServiceUnavailable(ServiceFault.STORAGE_UNREADABLE),
    "Revoked" to EntitlementState.Revoked(revokedAtMs = 0),
)

internal const val DEBUG_TAG = "debug.entry"
