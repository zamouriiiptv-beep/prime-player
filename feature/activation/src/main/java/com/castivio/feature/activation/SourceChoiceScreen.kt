package com.castivio.feature.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.castivio.core.design.components.InteractiveGlassCard
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing

/**
 * "What did your provider give you?"
 *
 * The question is asked in the user's terms rather than ours. Nobody arrives here
 * knowing they want "Xtream Codes"; they arrive holding an e-mail, and the two options
 * are described by what that e-mail looks like — a server and a password, or one long
 * link. The protocol names stay as titles because that is what the e-mail calls them.
 *
 * Two cards, no third. Local files are deliberately absent: they were cut from this
 * screen because a route almost nobody takes still costs everybody a decision.
 */
@Composable
internal fun SourceChoiceScreen(
    onXtream: () -> Unit,
    onPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                text = stringResource(R.string.source_choice_title),
                style = CastivioType.headlineMedium,
                color = colors.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.source_choice_subtitle),
                style = CastivioType.bodyLarge,
                color = colors.onBackgroundVariant,
            )
        }

        SourceCard(
            title = stringResource(R.string.source_xtream_title),
            detail = stringResource(R.string.source_xtream_detail),
            onClick = onXtream,
        )
        SourceCard(
            title = stringResource(R.string.source_m3u_title),
            detail = stringResource(R.string.source_m3u_detail),
            onClick = onPlaylist,
        )
    }
}

@Composable
private fun SourceCard(title: String, detail: String, onClick: () -> Unit) {
    val colors = CastivioTheme.colors

    InteractiveGlassCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(text = title, style = CastivioType.titleMedium, color = colors.onBackground)
            Text(text = detail, style = CastivioType.bodyMedium, color = colors.onBackgroundVariant)
        }
    }
}
