package com.castivio.feature.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.DelayedSpinner
import com.castivio.core.design.components.ErrorState
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.activation.ActivationPhase

/**
 * One screen for both routes, because from here on Xtream and a playlist URL are the
 * same job: bytes arriving and a number climbing.
 *
 * The number says **items**, not channels. An M3U cannot always be classified while it
 * is still streaming, and a count that promises "channels" and later re-sorts them into
 * films is a count the user watched lie to them. It also never goes backwards — that is
 * guaranteed upstream in `ActivateProvider`, and this screen simply shows what it is
 * given.
 */
@Composable
internal fun ImportingScreen(
    phase: ActivationPhase,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        // Only after a beat: a check that answers in 200 ms should not make a spinner
        // flash, which reads as a glitch rather than as progress.
        DelayedSpinner()

        when (phase) {
            is ActivationPhase.Checking -> Text(
                text = stringResource(R.string.importing_checking),
                style = CastivioType.titleMedium,
                color = colors.onBackground,
                modifier = Modifier.semantics { heading() },
            )

            is ActivationPhase.Importing -> ImportingProgress(phase)

            else -> Unit
        }

        Text(
            text = stringResource(R.string.importing_takes_a_while),
            style = CastivioType.bodyMedium,
            color = colors.onBackgroundMuted,
            textAlign = TextAlign.Center,
        )

        CastivioButton(
            text = stringResource(R.string.importing_cancel),
            weight = ButtonWeight.Secondary,
            onClick = onCancel,
        )
    }
}

@Composable
private fun ImportingProgress(phase: ActivationPhase.Importing) {
    val colors = CastivioTheme.colors

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.importing_reading),
            style = CastivioType.titleMedium,
            color = colors.onBackground,
            modifier = Modifier.semantics { heading() },
        )

        if (!phase.checkingForChanges) {
            Text(
                text = stringResource(R.string.importing_items, formatCount(phase.itemsFound)),
                style = CastivioType.codeLarge,
                color = colors.onBackground,
                // Polite: announced when the reader is idle rather than interrupting
                // whatever it is saying. A count that climbs is worth hearing about,
                // and worth never talking over.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (phase.groupsReady > 0) {
                Text(
                    text = stringResource(R.string.importing_groups, phase.groupsReady),
                    style = CastivioType.bodyMedium,
                    color = colors.onBackgroundVariant,
                )
            }
        }
    }
}

/**
 * A failure, in Castivio's words rather than the provider's.
 *
 * Two actions, and which two depends on whether trying again could plausibly work.
 * Offering a retry for a rejected password is offering the user a way to waste their own
 * time, so a failure that cannot pass gets one route out: edit what you typed.
 */
@Composable
internal fun ActivationFailureScreen(
    failed: ActivationPhase.Failed,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wording = failed.reason.wording()
    val progress = if (failed.itemsFound > 0) {
        stringResource(R.string.failure_stopped_after, formatCount(failed.itemsFound))
    } else {
        null
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        ErrorState(
            title = wording.title,
            detail = listOfNotNull(wording.detail, progress).joinToString(" "),
            actionLabel = if (failed.retryable) {
                stringResource(R.string.failure_retry)
            } else {
                stringResource(R.string.failure_edit)
            },
            onAction = if (failed.retryable) onRetry else onEdit,
            secondaryActionLabel = if (failed.retryable) stringResource(R.string.failure_edit) else null,
            onSecondaryAction = if (failed.retryable) onEdit else null,
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
        )
    }
}
