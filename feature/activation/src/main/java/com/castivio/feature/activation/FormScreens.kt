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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.CastivioTextField
import com.castivio.core.design.components.GlassCard
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing
import com.castivio.domain.activation.ActivationForm

/**
 * The two forms.
 *
 * Field order is the order the provider's e-mail lists them, not the order the protocol
 * needs — somebody is copying from one to the other, and a form that reshuffles them
 * makes that harder for no gain.
 *
 * Errors appear as the user types rather than on submit. The alternative is the pattern
 * this whole flow exists to be better than: a button that always looks live, then a
 * failure that names none of the four fields.
 */
@Composable
internal fun XtreamFormScreen(
    form: ActivationForm.Xtream,
    enabled: Boolean,
    canSubmit: Boolean,
    onName: (String) -> Unit,
    onServerUrl: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val checked = form.checked

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        Text(
            text = stringResource(R.string.source_xtream_title),
            style = CastivioType.headlineMedium,
            color = colors.onBackground,
            modifier = Modifier.semantics { heading() },
        )

        GlassCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                CastivioTextField(
                    value = form.name,
                    onValueChange = onName,
                    label = stringResource(R.string.field_playlist_name),
                    hint = stringResource(R.string.field_optional),
                    placeholder = stringResource(R.string.field_playlist_name_placeholder),
                    // Only ever shown once it is wrong; an empty optional field is not.
                    error = checked.name.problem.message(),
                    enabled = enabled,
                )
                CastivioTextField(
                    value = form.serverUrl,
                    onValueChange = onServerUrl,
                    label = stringResource(R.string.field_server_url),
                    placeholder = stringResource(R.string.field_server_url_placeholder),
                    error = form.serverUrl.problemOnceTyped(checked.serverUrl.problem),
                    enabled = enabled,
                    keyboardType = KeyboardType.Uri,
                )
                CastivioTextField(
                    value = form.username,
                    onValueChange = onUsername,
                    label = stringResource(R.string.field_username),
                    error = form.username.problemOnceTyped(checked.username.problem),
                    enabled = enabled,
                )
                CastivioTextField(
                    value = form.password,
                    onValueChange = onPassword,
                    label = stringResource(R.string.field_password),
                    error = form.password.problemOnceTyped(checked.password.problem),
                    enabled = enabled,
                    secret = true,
                    imeAction = ImeAction.Done,
                    onImeAction = onSubmit,
                )
            }
        }

        ConnectButton(enabled = canSubmit, onClick = onSubmit)
    }
}

@Composable
internal fun M3uFormScreen(
    form: ActivationForm.Playlist,
    enabled: Boolean,
    canSubmit: Boolean,
    onName: (String) -> Unit,
    onUrl: (String) -> Unit,
    onSubmit: () -> Unit,
    onUseXtream: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val checked = form.checked

    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        Text(
            text = stringResource(R.string.source_m3u_title),
            style = CastivioType.headlineMedium,
            color = colors.onBackground,
            modifier = Modifier.semantics { heading() },
        )

        GlassCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                CastivioTextField(
                    value = form.name,
                    onValueChange = onName,
                    label = stringResource(R.string.field_playlist_name),
                    hint = stringResource(R.string.field_optional),
                    placeholder = stringResource(R.string.field_playlist_name_placeholder),
                    error = checked.name.problem.message(),
                    enabled = enabled,
                )
                CastivioTextField(
                    value = form.url,
                    onValueChange = onUrl,
                    label = stringResource(R.string.field_playlist_url),
                    placeholder = stringResource(R.string.field_playlist_url_placeholder),
                    error = form.url.problemOnceTyped(checked.url.problem),
                    enabled = enabled,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    onImeAction = onSubmit,
                )
            }
        }

        // The link providers actually e-mail. An offer, phrased as one — the field above
        // still says exactly what the user pasted, and pressing Connect still submits it
        // as a playlist.
        if (form.detectedXtream != null && enabled) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Text(
                        text = stringResource(R.string.detected_xtream_title),
                        style = CastivioType.titleMedium,
                        color = colors.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.detected_xtream_detail),
                        style = CastivioType.bodyMedium,
                        color = colors.onBackgroundVariant,
                    )
                    CastivioButton(
                        text = stringResource(R.string.detected_xtream_accept),
                        weight = ButtonWeight.Secondary,
                        onClick = onUseXtream,
                    )
                }
            }
        }

        ConnectButton(enabled = canSubmit, onClick = onSubmit)
    }
}

@Composable
private fun ConnectButton(enabled: Boolean, onClick: () -> Unit) {
    CastivioButton(
        text = stringResource(R.string.action_connect),
        weight = ButtonWeight.Primary,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
    )
}

/**
 * The problem, but only once there is something to be wrong about.
 *
 * A required field is empty before it has been typed in, and marking every field red the
 * moment the screen opens tells the user they have already failed at a form they have
 * not started.
 */
@Composable
private fun String.problemOnceTyped(
    problem: com.castivio.domain.provider.FieldProblem?,
): String? = if (isEmpty()) null else problem.message()
