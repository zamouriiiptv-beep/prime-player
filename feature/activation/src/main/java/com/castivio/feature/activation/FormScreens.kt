package com.castivio.feature.activation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.castivio.core.design.theme.castivioStage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.components.CastivioChip
import com.castivio.core.design.components.CastivioDisc
import com.castivio.core.design.components.CastivioPlaylistArt
import com.castivio.core.design.components.CastivioTextField
import com.castivio.core.design.components.GlassCard
import com.castivio.core.design.components.castivioDescriptionColor
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.core.design.theme.boundedFraction
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

        ConnectButton(enabled = canSubmit, onClick = onSubmit, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The playlist form, which is the one screen in this flow a stranger reaches first.
 *
 * ## Why this one is drawn and the other two are not
 *
 * Because of what it asks for. Xtream asks for four things a provider e-mails as four
 * labelled lines, and a user filling it in is copying; a portal asks for one address
 * from the same e-mail. This asks for a single long link, and the people who arrive
 * here most often have been sent one with no idea what it is. A column with two fields
 * and a button answers "where do I type" and nothing else — so the left half of this
 * screen exists to answer "what is this going to do", which is the question that was
 * actually being asked.
 *
 * It says it in words the panel beside it does not repeat. The two halves are a claim
 * and an instruction, and a screen that makes the same statement twice has wasted the
 * half a reader looked at first.
 *
 * ## One composition, two surfaces
 *
 * [M3uSpread] where [fitsSpread] says so and [M3uColumn] otherwise, and the second one
 * is the form exactly as it has always been under the header. Not a breakpoint in the
 * sizing system's sense — that system returns sizes and forbids a layout that
 * changes — but a choice this screen makes about itself, in the one place the
 * application has a reason for it: an illustration and a form side by side need about
 * 820dp of stage, a handset in landscape has 750, and a drawing squeezed to a third of
 * its size is worth less than the vertical room it costs.
 *
 * Xtream and the portal keep the column at every size, and that is deliberate too.
 * Neither has anything to say on a left-hand side.
 *
 * ## It owns its viewport, like the chooser and unlike the other two forms
 *
 * Which is what lets it wear [ChooserHeader] at the chooser's own place, and what gives
 * the band a bounded height to divide. The scrolling the other two forms need — a
 * keyboard takes most of a handset — is kept, and kept where it belongs: around the
 * band, under the header, so a keyboard moves the fields and never the way back.
 */
@Composable
internal fun M3uFormScreen(
    form: ActivationForm.Playlist,
    enabled: Boolean,
    canSubmit: Boolean,
    onName: (String) -> Unit,
    onUrl: (String) -> Unit,
    onSubmit: () -> Unit,
    onUseXtream: () -> Unit,
    /** The route's one answer to Back, raised by the header's chip. */
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = CastivioTheme.device.isTv

    BoxWithConstraints(modifier.fillMaxSize()) {
        val surface = maxWidth
        val m = sourceMetricsFor(tv = tv, width = surface, height = maxHeight)
        val band = m3uBand(m, maxHeight)

        Column(Modifier.fillMaxSize().castivioStage(m.frame)) {
            // The chooser's header, the same component at the same place with this
            // screen's own title -- which is what puts Back where a reader who has just
            // come from the chooser already knows it is.
            ChooserHeader(
                m = m,
                // The words on the card the user just pressed, from the gated chooser
                // bundle -- so the screen is named the same way in all thirty-seven
                // languages, and named the same thing it was reached by.
                title = stringResource(R.string.source_m3u_title),
                headingTag = ActivationTags.PLAYLIST_HEADING,
                backTag = ActivationTags.PLAYLIST_BACK,
                onBack = onBack,
            )
            Spacer(Modifier.height(m.bandTop))

            // **The band is a fixed height inside a box that scrolls.**
            //
            // Both halves matter. The height is fixed so the two columns can divide it
            // -- the illustration takes what the words leave, the facts sit at the
            // foot -- which a scrolling parent's unbounded height makes impossible.
            // The box scrolls so a keyboard cannot cut the band off: with the IME up
            // this box is shorter than its content and the content moves, which is
            // what the three forms were given a scrolling frame for in the first
            // place. With no keyboard the two are the same number to the dp and
            // nothing scrolls, which is what the drawing requires at 1280x720.
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                // The stage's own width, measured rather than subtracted: `castivioStage`
                // pays `max(edge, inset)` per side, so a display cutout wider than the
                // edge makes `surface - edge * 2` an overstatement -- and an
                // overstatement here is a `Modifier.width` larger than the box holding it.
                if (fitsSpread(surface, band)) {
                    val metrics = m3uSpread(maxWidth)
                    M3uSpread(
                        metrics = metrics,
                        modifier = Modifier.align(Alignment.TopCenter).width(metrics.measure).height(band),
                        form = form,
                        enabled = enabled,
                        canSubmit = canSubmit,
                        onName = onName,
                        onUrl = onUrl,
                        onSubmit = onSubmit,
                        onUseXtream = onUseXtream,
                    )
                } else {
                    M3uColumn(
                        form = form,
                        enabled = enabled,
                        canSubmit = canSubmit,
                        onName = onName,
                        onUrl = onUrl,
                        onSubmit = onSubmit,
                        onUseXtream = onUseXtream,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .widthIn(max = formMeasure(m.frame))
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * The height the band actually gets: the stage, less the header and the gap under it.
 *
 * Written out rather than taken from the layout because the layout cannot give it
 * back — the band is a weighted child, and its two columns have to know the number
 * before they are measured. It is the same arithmetic `Column` does, stated once so a
 * test can check it: at the 1280x720 reference it is 557.4dp, which is the band the
 * drawing was approved in.
 */
internal fun m3uBand(m: SourceMetrics, height: Dp): Dp =
    height - m.stageTop - m.stageBottom - m.header - m.bandTop

/** The illustration and the claim on one side, the form on the other. */
@Composable
private fun M3uSpread(
    metrics: M3uMetrics,
    form: ActivationForm.Playlist,
    enabled: Boolean,
    canSubmit: Boolean,
    onName: (String) -> Unit,
    onUrl: (String) -> Unit,
    onSubmit: () -> Unit,
    onUseXtream: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(metrics.gap),
    ) {
        PlaylistPitch(metrics, Modifier.width(metrics.left).fillMaxHeight())
        PlaylistPanel(
            metrics = metrics,
            form = form,
            enabled = enabled,
            canSubmit = canSubmit,
            onName = onName,
            onUrl = onUrl,
            onSubmit = onSubmit,
            onUseXtream = onUseXtream,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

/** The left half: what a playlist does, said once, with a picture of it. */
@Composable
private fun PlaylistPitch(metrics: M3uMetrics, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CastivioPlaylistArt(
            labels = listOf(
                stringResource(R.string.playlist_rail_live),
                stringResource(R.string.playlist_rail_movies),
                stringResource(R.string.playlist_rail_series),
                stringResource(R.string.playlist_rail_catchup),
            ),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        Spacer(Modifier.height(Spacing.md))
        Text(
            text = stringResource(R.string.playlist_pitch_title),
            style = CastivioType.headlineMedium,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.playlist_pitch_detail),
            style = CastivioType.bodyMedium,
            color = castivioDescriptionColor,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Spacing.xxl))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Fact(
                metrics = metrics,
                hue = colors.hueViolet,
                icon = Icons.Rounded.Devices,
                title = stringResource(R.string.playlist_fact_devices_title),
                detail = stringResource(R.string.playlist_fact_devices_detail),
                modifier = Modifier.weight(1f),
            )
            Fact(
                metrics = metrics,
                hue = colors.hueAzure,
                icon = Icons.Rounded.Bolt,
                title = stringResource(R.string.playlist_fact_fast_title),
                detail = stringResource(R.string.playlist_fact_fast_detail),
                modifier = Modifier.weight(1f),
            )
            Fact(
                metrics = metrics,
                hue = colors.hueGreen,
                icon = Icons.Rounded.Shield,
                title = stringResource(R.string.playlist_fact_private_title),
                detail = stringResource(R.string.playlist_fact_private_detail),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One of the three claims under the illustration.
 *
 * A card and not a control: nothing here is pressable, so it takes the card's own
 * edge and no focus behaviour. Three facts a reader can take or leave is the point —
 * a row of three things that *look* pressable on a television is three places a D-pad
 * goes and finds nothing.
 */
@Composable
private fun Fact(
    metrics: M3uMetrics,
    hue: Color,
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.md)
    Row(
        modifier
            .height(metrics.factHeight)
            .clip(shape)
            .background(colors.surfaceGlass)
            .border(BorderStroke(1.dp, colors.edgeCard), shape)
            .padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CastivioDisc(size = metrics.factDisc, hue = hue, icon = icon)
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = CastivioType.labelLarge,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = CastivioType.bodySmall,
                color = colors.onBackgroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The right half: the two fields, the action, and the one line that says what next. */
@Composable
private fun PlaylistPanel(
    metrics: M3uMetrics,
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
    val offered = form.detectedXtream != null && enabled

    GlassCard(modifier) {
        Column(Modifier.padding(Spacing.xl)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CastivioDisc(
                    size = metrics.panelDisc,
                    hue = colors.hueAzure,
                    icon = Icons.Rounded.Link,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.playlist_panel_title),
                        style = CastivioType.headlineMedium,
                        color = colors.onBackground,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = stringResource(R.string.playlist_panel_detail),
                        style = CastivioType.bodyMedium,
                        color = castivioDescriptionColor,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xl))
            CastivioTextField(
                value = form.name,
                onValueChange = onName,
                label = stringResource(R.string.field_playlist_name),
                hint = stringResource(R.string.field_optional),
                placeholder = stringResource(R.string.field_playlist_name_placeholder),
                error = checked.name.problem.message(),
                enabled = enabled,
                labelIcon = Icons.Rounded.Home,
                icon = Icons.Rounded.Home,
            )

            Spacer(Modifier.height(Spacing.lg))
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
                labelIcon = Icons.Rounded.Link,
                icon = Icons.Rounded.Link,
                trailing = { PasteChip(enabled = enabled, onPaste = onUrl) },
            )

            Spacer(Modifier.height(Spacing.xl))
            ConnectButton(
                enabled = canSubmit,
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                minHeight = metrics.button,
            )

            Spacer(Modifier.height(Spacing.md))

            // **One slot at the foot, and two things that can be in it.**
            //
            // The offer and the next step are both "the thing this screen has to say
            // right now", and stacking them would move the button they sit under every
            // time one appeared.
            //
            // Nothing about a *failure* is said here, and that is the point rather than
            // an omission: Connect on a playlist registers it, makes it active and
            // opens Home — see `ActivateProvider.hasAccount`. There is no attempt to
            // fail. Whether the link reads is Home's question, asked by the section
            // loader where the answer is needed and where the retry lives.
            //
            // What a *field* has wrong with it is still said by the field, under
            // itself, exactly as it is on the other two forms.
            if (offered) {
                CompactXtreamOffer(onUseXtream, Modifier.fillMaxWidth())
            } else {
                PanelNote(
                    detail = stringResource(
                        if (canSubmit) R.string.playlist_note_ready else R.string.playlist_note_empty,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(ActivationTags.PLAYLIST_NOTE),
                )
            }
        }
    }
}

/**
 * The Xtream offer, at the size a panel with eighty dp of slack can pay for.
 *
 * `DetectedXtreamOffer`'s card is three elements and a hundred and seventy dp, which
 * is the right shape in a column that scrolls and does not fit in a band that does
 * not. Same words, same destination, one line and a control.
 */
@Composable
private fun CompactXtreamOffer(onUseXtream: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.detected_xtream_title),
            style = CastivioType.bodySmall,
            color = castivioDescriptionColor,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        CastivioButton(
            text = stringResource(R.string.detected_xtream_accept),
            weight = ButtonWeight.Ghost,
            onClick = onUseXtream,
            labelStyle = CastivioType.labelMedium,
        )
    }
}

/**
 * What to do next, in one line, at the foot of the panel.
 *
 * Two sentences and nothing else: the button is not live yet, or it is. It stays in
 * the same place either way, because a message that appears and disappears moves the
 * button it sits under.
 *
 * It is never an error. A field says what is wrong with a field, under that field; and
 * a playlist's Connect cannot fail, because it asks nobody anything. The one ink is
 * therefore the quiet one, and there is no second branch for a state that does not
 * exist.
 */
@Composable
private fun PanelNote(detail: String, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null, // the sentence beside it is the message
            tint = colors.onBackgroundMuted,
            modifier = Modifier.size(Sizing.iconSm),
        )
        Text(
            text = detail,
            style = CastivioType.bodySmall,
            color = colors.onBackgroundMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Paste, inside the field it pastes into.
 *
 * ## Why it is here and not beside the field
 *
 * A playlist URL is eighty characters of query string, and nobody types one on a
 * remote. So the control that ends that problem has to be the first thing a D-pad
 * reaches after the field — and a button placed *after* the field is one the user
 * passes the whole field to get to, in a layout where the next stop is Connect.
 *
 * Its own target, its own focus ring, its own surface: it is a control inside a
 * surface rather than a glyph on one, and drawing it as a glyph is how a user learns
 * that the field has a decoration they cannot press.
 *
 * Nothing is logged here, by the clipboard's nature: what a user pastes into this
 * field is their subscription.
 */
@Composable
private fun PasteChip(enabled: Boolean, onPaste: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    CastivioChip(
        text = stringResource(R.string.action_paste),
        icon = Icons.Rounded.ContentPaste,
        onClick = {
            if (enabled) {
                val pasted = clipboard.getText()?.text?.trim()
                if (!pasted.isNullOrEmpty()) onPaste(pasted)
            }
        },
        labelStyle = CastivioType.labelMedium,
        padH = Spacing.md,
        padV = Spacing.xs,
    )
}

/**
 * The link providers actually e-mail. An offer, phrased as one — the field above still
 * says exactly what the user pasted, and pressing Connect still submits it as a
 * playlist.
 */
@Composable
private fun DetectedXtreamOffer(onUseXtream: () -> Unit) {
    val colors = CastivioTheme.colors
    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Spacing.lg),
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
                color = castivioDescriptionColor,
            )
            CastivioButton(
                text = stringResource(R.string.detected_xtream_accept),
                weight = ButtonWeight.Secondary,
                onClick = onUseXtream,
            )
        }
    }
}

/** The form as it has always been, for every surface too narrow to hold the spread. */
@Composable
private fun M3uColumn(
    form: ActivationForm.Playlist,
    enabled: Boolean,
    canSubmit: Boolean,
    onName: (String) -> Unit,
    onUrl: (String) -> Unit,
    onSubmit: () -> Unit,
    onUseXtream: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val checked = form.checked

    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        // No headline. The screen wears the chooser's header now, and the title in it
        // says the same words -- a second one here was the thing that made this form
        // look like a different screen from the one it is reached through.
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
                    labelIcon = Icons.Rounded.Home,
                    icon = Icons.Rounded.Home,
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
                    labelIcon = Icons.Rounded.Link,
                    icon = Icons.Rounded.Link,
                    trailing = { PasteChip(enabled = enabled, onPaste = onUrl) },
                )
            }
        }

        if (form.detectedXtream != null && enabled) {
            DetectedXtreamOffer(onUseXtream)
        }

        ConnectButton(
            enabled = canSubmit,
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
        )

        PanelNote(
            detail = stringResource(
                if (canSubmit) R.string.playlist_note_ready else R.string.playlist_note_empty,
            ),
            modifier = Modifier.fillMaxWidth().testTag(ActivationTags.PLAYLIST_NOTE),
        )
    }
}

/**
 * The Stalker portal form: a name the user may give it, and the address.
 *
 * ## Why there is no MAC on it
 *
 * A portal does bind a subscription to a set-top box, and the handshake does carry an
 * address — but the user has already given their provider that address, off the
 * activation screen, which is how they got a portal URL in the first place. Castivio
 * holds it, so `StalkerHttpApi` sends it. Asking for it here would be asking somebody
 * to copy a number out of one screen of this application and into another.
 *
 * ## Why it is this file and not a screen of its own
 *
 * It is the M3U form with a different label and a different destination: the same
 * glass card, the same field, the same Connect. A second file would be a second set of
 * decisions about what a form looks like, agreeing with this one until the first edit
 * to either.
 */
@Composable
internal fun PortalFormScreen(
    form: ActivationForm.Portal,
    enabled: Boolean,
    canSubmit: Boolean,
    onName: (String) -> Unit,
    onUrl: (String) -> Unit,
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
            text = stringResource(R.string.source_portal_title),
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
                    modifier = Modifier.testTag(ActivationTags.PORTAL_NAME),
                )
                CastivioTextField(
                    value = form.url,
                    onValueChange = onUrl,
                    label = stringResource(R.string.field_portal_url),
                    placeholder = stringResource(R.string.field_portal_url_placeholder),
                    error = form.url.problemOnceTyped(checked.url.problem),
                    enabled = enabled,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    onImeAction = onSubmit,
                    modifier = Modifier.testTag(ActivationTags.PORTAL_URL),
                )
                Text(
                    text = stringResource(R.string.field_portal_hint),
                    style = CastivioType.bodyMedium,
                    color = colors.onBackgroundVariant,
                )
            }
        }

        ConnectButton(enabled = canSubmit, onClick = onSubmit, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The one action all three forms end on.
 *
 * ## The two fills, and why disabled is glass rather than a dimmed gradient
 *
 * A primary dimmed to [DISABLED_ALPHA] is still unmistakably the brand ramp, so an
 * empty form shows a faded purple bar that reads as "loading" rather than as "not
 * yet". Unpressable is a different statement from busy, and it has to look different:
 * the control falls back to the panel's own glass with the quiet edge, which says it
 * is a place where a button will be.
 *
 * ## The ramp is `ctaBrush` and not `primaryBrush`
 *
 * Castivio has two primaries. The blue one is an action on something that already
 * exists — refresh, play, open. The brand ramp is for the screen whose whole purpose
 * is to add something, which is exactly these three forms, and it is the gradient the
 * wordmark is set in. It was drawn by nothing until now.
 *
 * ## There is no running state on it
 *
 * A playlist's Connect registers the source and opens Home, so there is nothing to
 * wait through. The other two forms do wait, and while they do, `ImportingScreen`
 * replaces the form — so this control is never on screen during an attempt on any of
 * the three, and a spinner inside it would be a state no user can reach.
 *
 * @param minHeight a floor above the frame's own, for a screen that sizes its
 *   controls. Null leaves the component's own D-pad floor.
 */
@Composable
private fun ConnectButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp? = null,
) {
    val colors = CastivioTheme.colors
    CastivioButton(
        text = stringResource(R.string.action_connect),
        weight = ButtonWeight.Primary,
        icon = Icons.Rounded.Link,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        fill = if (enabled) colors.ctaBrush else SolidColor(colors.surfaceGlass),
        ring = if (enabled) colors.ctaQuietRing else colors.edgeQuiet,
        minHeight = minHeight,
    )
}

/**
 * The playlist spread's own dimensions, from the width it is given.
 *
 * ## Why a width and nothing else
 *
 * Because the width is the only axis this screen measures. It sits in the activation
 * surface's scrolling column, so the height it is handed is unbounded — and a layout
 * that asked for one would get `Infinity` and lay itself out in it. [M3uMetrics.band]
 * is therefore *derived* from the width rather than read off the viewport: on the 16:9
 * surfaces this spread is drawn on, the share below lands within a dp of the content
 * height the stage actually leaves, and on a surface that is not 16:9 the band is the
 * right size for its own columns rather than the wrong size for the window.
 *
 * Every figure is read off the approved 1280x720 drawing, whose stage is 1157.4 wide,
 * and every one of them is bounded — the rule `boundedFraction` exists for.
 */
internal fun m3uSpread(width: Dp): M3uMetrics {
    // A ceiling on the drawing itself, not only on its parts. A 1920dp set reports a
    // stage of 1776, and two columns stretched across it are a television's worth of
    // air between a picture and the field it explains -- `Sizing.maxContentWidth`'s
    // reasoning, at the width this particular composition stops improving at.
    val measure = width.coerceAtMost(SPREAD_MEASURE_MAX)
    val gap = measure.boundedFraction(GAP, 24.dp, 56.dp)
    return M3uMetrics(
        measure = measure,
        // The illustration's share, except where taking it would leave the panel too
        // narrow for a URL. The form is the half with a job; the drawing gives way.
        left = minOf(
            measure.boundedFraction(LEFT, 300.dp, 640.dp),
            measure - gap - PANEL_MIN,
        ),
        gap = gap,
        factHeight = measure.boundedFraction(FACT_H, 56.dp, 84.dp),
        factDisc = measure.boundedFraction(FACT_DISC, 34.dp, 52.dp),
        panelDisc = measure.boundedFraction(PANEL_DISC, 56.dp, 88.dp),
        button = measure.boundedFraction(BUTTON, 48.dp, 72.dp),
    )
}

@Immutable
internal data class M3uMetrics(
    /** How wide the whole spread is drawn, which is the stage up to a ceiling. */
    val measure: Dp,
    /** The illustration column. The panel takes the rest. */
    val left: Dp,
    /** Between the two columns. */
    val gap: Dp,
    val factHeight: Dp,
    val factDisc: Dp,
    val panelDisc: Dp,
    /** A floor under Connect, above the frame's own D-pad floor. */
    val button: Dp,
)

/**
 * Whether this surface is worth drawing the spread on.
 *
 * ## One threshold, measured on what the screen actually got
 *
 * An earlier draft asked this twice — once outside the stage, to decide whether the
 * activation surface capped the column, and once inside it, to decide the layout —
 * and the two could only be kept honest by a gap between them that a test had to
 * police. The playlist step owns its viewport now, so there is one measurement and
 * one answer: the surface's width, and the band the header leaves.
 *
 * The band rather than the height, because the band is what the two columns divide
 * and it is what a header and a stage have already been taken out of. A 1400x540
 * window is wide and has 418dp of band; a 1400x400 one has 300 and gets the column.
 *
 * @param width the surface, not the stage. The stage's own margins scale with it.
 * @param band [m3uBand].
 */
internal fun fitsSpread(width: Dp, band: Dp): Boolean =
    width >= SPREAD_MIN_WIDTH && band >= SPREAD_MIN_BAND

/** The smallest television Castivio is designed against is 960x540. */
internal val SPREAD_MIN_WIDTH: Dp = 960.dp

/**
 * What the band has to leave once the words below the illustration are paid for.
 *
 * The pitch and the facts cost about 190dp at the type they are set in, and below
 * roughly 400 the drawing is smaller than the three cards under it — at which point
 * the left column is a caption with a thumbnail and the column layout says more.
 */
internal val SPREAD_MIN_BAND: Dp = 400.dp

/* ------------------------------------------------- the spread, as shares of its stage
 *
 * 1157.4dp is the content width of the 1280x720 reference once the stage's 61.3dp
 * margins are paid, and it is what the drawing was approved at.
 */

private const val LEFT = 575f / 1157.4f
private const val GAP = 40f / 1157.4f
private const val FACT_H = 68f / 1157.4f
private const val FACT_DISC = 44f / 1157.4f
private const val PANEL_DISC = 72f / 1157.4f
private const val BUTTON = 60f / 1157.4f

/**
 * The narrowest the panel may be squeezed to before the illustration starts giving
 * way: a URL field, a Paste beside it and a button under both.
 */
private val PANEL_MIN: Dp = 420.dp

/** Where the spread stops growing. A hair over the reference's own stage. */
private val SPREAD_MEASURE_MAX: Dp = 1280.dp

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
