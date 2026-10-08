package com.castivio.feature.activation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
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

            // **The band, and nothing may leave it.**
            //
            // No scroll and no `imePadding`. Both were here and both were wrong for
            // this screen: a scroll meant Connect could sit below the fold on the one
            // device the project is tested on, and an IME inset on a window that does
            // not resize -- `:app` is edge to edge, so the keyboard arrives as an
            // inset rather than a resize -- would have shrunk the band and clipped the
            // control instead.
            //
            // What replaces them is arithmetic. `m3uSpread` is handed this height and
            // chooses a composition that fits it: at the reference the full drawing,
            // on a short landscape handset the same drawing with its optional parts
            // dropped and its padding tightened. `M3uSpreadTest` asserts the fit on
            // every surface rather than trusting this comment.
            //
            // **It measures its own width rather than subtracting one.** Two reasons,
            // and the second is why this is not a `Box`. `castivioStage` pays
            // `max(edge, inset)` per side, so `surface - edge * 2` overstates the room
            // on a handset whose display cutout is wider than the margin. And a
            // `BoxScope` carries `@LayoutScopeMarker`, which hides the enclosing
            // `BoxWithConstraints` -- reading the outer `maxWidth` from inside a plain
            // `Box` does not compile, and that is what it failed on.
            BoxWithConstraints(Modifier.weight(1f)) {
                if (fitsSpread(surface, band)) {
                    val metrics = m3uSpread(maxWidth, band, tv)
                    M3uSpread(
                        metrics = metrics,
                        modifier = Modifier.align(Alignment.TopCenter).width(metrics.measure),
                        form = form,
                        enabled = enabled,
                        canSubmit = canSubmit,
                        onName = onName,
                        onUrl = onUrl,
                        onSubmit = onSubmit,
                    )
                } else {
                    M3uColumn(
                        form = form,
                        enabled = enabled,
                        canSubmit = canSubmit,
                        chrome = m3uChrome(full = band >= COLUMN_FIXED + Sizing.minTarget(tv)),
                        onName = onName,
                        onUrl = onUrl,
                        onSubmit = onSubmit,
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
    modifier: Modifier = Modifier,
) {
    // **The band is the whole budget.** Nothing here scrolls and nothing may exceed
    // it, so both columns are drawn at a density `m3uSpread` already proved fits:
    // the illustration is capped by what the words leave, and the panel's optional
    // parts are gone where the height could not hold them. Pinning the row to the
    // band is what makes that a guarantee rather than a hope -- an overflow is a
    // clipped Connect, and this is the one screen where that was shipped once.
    Row(
        modifier.height(metrics.band),
        horizontalArrangement = Arrangement.spacedBy(metrics.gap),
    ) {
        PlaylistPitch(metrics, Modifier.width(metrics.left))
        PlaylistPanel(
            metrics = metrics,
            form = form,
            enabled = enabled,
            canSubmit = canSubmit,
            onName = onName,
            onUrl = onUrl,
            onSubmit = onSubmit,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The left half: what a playlist does, said once, with a picture of it. */
@Composable
private fun PlaylistPitch(metrics: M3uMetrics, modifier: Modifier = Modifier) {
    val colors = CastivioTheme.colors
    val chrome = metrics.chrome

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CastivioPlaylistArt(
            labels = listOf(
                stringResource(R.string.playlist_rail_live),
                stringResource(R.string.playlist_rail_movies),
                stringResource(R.string.playlist_rail_series),
                stringResource(R.string.playlist_rail_catchup),
            ),
            modifier = Modifier.fillMaxWidth().height(metrics.art),
        )

        // **The line counts are a budget, not a preference.** The screen does not
        // scroll, so what the words may cost has to be known before they are measured
        // -- see `pitchNeeds`. A translation longer than its allowance is clipped to
        // it rather than allowed to push the drawing off the bottom.
        Spacer(Modifier.height(PITCH_GAP))
        Text(
            text = stringResource(R.string.playlist_pitch_title),
            style = CastivioType.headlineMedium,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
            maxLines = chrome.pitchTitleLines,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(TITLE_GAP))
        Text(
            text = stringResource(R.string.playlist_pitch_detail),
            style = CastivioType.bodyMedium,
            color = castivioDescriptionColor,
            textAlign = TextAlign.Center,
            maxLines = chrome.pitchBodyLines,
            overflow = TextOverflow.Ellipsis,
        )

        if (metrics.facts) {
            Spacer(Modifier.height(FACTS_GAP))
            Facts(metrics)
        }
    }
}

/**
 * The three claims, in a row.
 *
 * ## Why they are sometimes absent and never stacked
 *
 * A card is a disc, a name and a line under it, and the name is the binding figure:
 * "يعمل على كل جهاز" is about 110dp of bold label, and the disc and the padding take
 * another 70. Below [FACTS_ROW_MIN] the column cannot give three of those the 180dp
 * each they need.
 *
 * Stacking them was the first answer and it was wrong: three cards down a column is
 * 184dp where a row is 56, on exactly the surfaces that have no height to spare, and
 * the screen does not scroll. So where they do not fit across, they are not drawn --
 * they are the reassurance the left half offers, not anything a user has to reach,
 * and the illustration and its claim say the same thing in less room.
 */
@Composable
private fun Facts(metrics: M3uMetrics) {
    val colors = CastivioTheme.colors
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
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val chrome = metrics.chrome
    val checked = form.checked

    GlassCard(modifier) {
        Column(Modifier.padding(chrome.pad)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CastivioDisc(
                    size = if (chrome.full) metrics.panelDisc else chrome.disc,
                    hue = colors.hueAzure,
                    icon = Icons.Rounded.Link,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.playlist_panel_title),
                        style = CastivioType.headlineMedium,
                        color = colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    // The sentence goes first where the band is short. It is the one
                    // thing on the panel that repeats: the header says the screen, the
                    // title says the panel, and this says both again one line later.
                    if (chrome.blurb) {
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = stringResource(R.string.playlist_panel_detail),
                            style = CastivioType.bodyMedium,
                            color = castivioDescriptionColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(chrome.headGap))

            // The optional field, and the only control this screen will give up. Its
            // own label says "optional", a subscription takes a name without it, and
            // the saved-subscriptions screen renames one afterwards. Connect does not
            // move for it.
            if (chrome.name) {
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
                Spacer(Modifier.height(chrome.fieldGap))
            }

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

            Spacer(Modifier.height(chrome.actionGap))
            ConnectButton(
                enabled = canSubmit,
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                minHeight = metrics.button,
            )

            Spacer(Modifier.height(chrome.noteGap))

            // **One line at the foot, and only ever this one.**
            //
            // It used to share the slot with an offer to read the link as Xtream
            // instead, and that offer is gone from this screen entirely — see the note
            // on `M3uFormScreen`. What is left is a single sentence of a known height,
            // which is also what makes the panel's budget exact: the offer was a
            // two-line label beside a button, about 48dp where the budget had counted
            // 20, and on a short band it would have pushed Connect out of the viewport
            // the moment a user pasted a `get.php` link.
            //
            // Nothing about a *failure* is said here either, and that is the point
            // rather than an omission: Connect on a playlist registers it, makes it
            // active and opens Home — see `ActivateProvider.hasAccount`. Whether the
            // link reads is Home's question.
            //
            // What a *field* has wrong with it is still said by the field, under
            // itself, exactly as it is on the other two forms.
            PanelNote(
                detail = stringResource(
                    if (canSubmit) R.string.playlist_note_ready else R.string.playlist_note_empty,
                ),
                modifier = Modifier.fillMaxWidth().testTag(ActivationTags.PLAYLIST_NOTE),
            )
        }
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

/** The form as it has always been, for every surface too narrow to hold the spread. */
@Composable
private fun M3uColumn(
    form: ActivationForm.Playlist,
    enabled: Boolean,
    canSubmit: Boolean,
    chrome: M3uChrome,
    onName: (String) -> Unit,
    onUrl: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val checked = form.checked

    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(chrome.actionGap),
    ) {
        // No headline. The screen wears the chooser's header now, and the title in it
        // says the same words -- a second one here was the thing that made this form
        // look like a different screen from the one it is reached through.
        GlassCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(chrome.pad),
                verticalArrangement = Arrangement.spacedBy(chrome.fieldGap),
            ) {
                if (chrome.name) {
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
                }
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
internal fun m3uSpread(width: Dp, band: Dp, tv: Boolean): M3uMetrics {
    // A ceiling on the drawing itself, not only on its parts. A 1920dp set reports a
    // stage of 1776, and two columns stretched across it are a television's worth of
    // air between a picture and the field it explains -- `Sizing.maxContentWidth`'s
    // reasoning, at the width this particular composition stops improving at.
    val measure = width.coerceAtMost(SPREAD_MEASURE_MAX)
    val gap = measure.boundedFraction(GAP, 24.dp, 56.dp)
    val left = minOf(
        measure.boundedFraction(LEFT, 300.dp, 640.dp),
        measure - gap - PANEL_MIN,
    )
    // Floored at the frame's own target, because that is what the control actually
    // measures: `CastivioButton` takes `max(minTarget, minHeight)`, so on a television
    // a 45dp share becomes 56 and a budget that believed the share would be 11dp short
    // of the thing it was budgeting for.
    val button = maxOf(measure.boundedFraction(BUTTON, 48.dp, 72.dp), Sizing.minTarget(tv))
    val factHeight = measure.boundedFraction(FACT_H, 56.dp, 84.dp)

    // **The band decides what is on the screen, because the screen does not scroll.**
    //
    // The panel at full dress is 368dp of fixed parts plus its button. Where the band
    // cannot hold that, drawing it anyway would put Connect below the bottom of the
    // screen with no way to reach it -- which is the defect this replaced.
    val chrome = m3uChrome(full = band >= PANEL_FIXED + button)

    // The three cards only where the column can draw them properly. Three names at
    // 94dp are three names cut off; see [Facts].
    val facts = chrome.full && left >= FACTS_ROW_MIN

    return M3uMetrics(
        measure = measure,
        band = band,
        left = left,
        // **The drawing's own aspect, capped by what the words leave.**
        //
        // The aspect is what makes a set a set: 330dp at the reference, to the dp,
        // which is what was approved. The cap is what makes the column *fit* -- the
        // screen has no scroll, so a drawing that asks for more than the band has
        // left is a drawing with its stand cut off. It is the only dimension on this
        // screen that yields, and it is the right one: a smaller television is still
        // a television, where half a Connect button is not a button.
        art = minOf(
            left.boundedFraction(ART_OF_COLUMN, ART_MIN, ART_MAX),
            band - pitchNeeds(chrome) - if (facts) FACTS_GAP + factHeight else 0.dp,
        ).coerceAtLeast(ART_MIN),
        gap = gap,
        facts = facts,
        chrome = chrome,
        factHeight = factHeight,
        factDisc = measure.boundedFraction(FACT_DISC, 34.dp, 52.dp),
        panelDisc = measure.boundedFraction(PANEL_DISC, 56.dp, 88.dp),
        button = button,
    )
}

/** What the words under the illustration cost, at the lines they are allowed. */
internal fun pitchNeeds(chrome: M3uChrome): Dp =
    PITCH_GAP + LINE_TITLE * chrome.pitchTitleLines + TITLE_GAP + LINE_BODY * chrome.pitchBodyLines

/** What the panel costs: its fixed parts at this density, plus the button it ends on. */
internal fun panelNeeds(chrome: M3uChrome, button: Dp): Dp =
    (if (chrome.full) PANEL_FIXED else PANEL_FIXED_COMPACT) + button

/** The single column's, for the surfaces too narrow to hold two. */
internal fun columnNeeds(chrome: M3uChrome, button: Dp): Dp =
    (if (chrome.full) COLUMN_FIXED else COLUMN_FIXED_COMPACT) + button

@Immutable
internal data class M3uMetrics(
    /** How wide the whole spread is drawn, which is the stage up to a ceiling. */
    val measure: Dp,
    /** The height the stage leaves under the header. Nothing may exceed it. */
    val band: Dp,
    /** The illustration column. The panel takes the rest. */
    val left: Dp,
    /** The drawing inside that column: its own aspect, capped by the band. */
    val art: Dp,
    /** Between the two columns. */
    val gap: Dp,
    /** Whether the three cards are drawn at all. */
    val facts: Boolean,
    /** What this band can afford to show, and how tightly. */
    val chrome: M3uChrome,
    val factHeight: Dp,
    val factDisc: Dp,
    val panelDisc: Dp,
    /** A floor under Connect, above the frame's own D-pad floor. */
    val button: Dp,
)

/**
 * What a band can afford: the two densities this screen is drawn at.
 *
 * ## Why a composition and not a scale
 *
 * A landscape handset leaves 303dp under the header and the panel at full dress wants
 * 416. The difference cannot be scaled away — the two fields are at the D-pad floor,
 * the button is at the touch floor, and a layout that shrank them would be a drawing
 * of a form nobody can press. So the *composition* changes instead, in the order the
 * screen's own priorities give: the header, the form, the URL field and Paste, and
 * Connect are never touched; the padding tightens, the panel's description and its
 * optional name field go, and the three cards go.
 *
 * ## What is lost, and where it went
 *
 * Only two things, both of them named as optional by the screen itself. The playlist
 * *name* is marked "optional" in its own label and is renameable afterwards from the
 * saved subscriptions; the panel's description repeats, one line later, what the
 * header and the title above it already say.
 *
 * Nothing is hidden that a user has to reach. Connect is drawn last and is inside the
 * viewport on every surface, which `M3uSpreadTest` asserts rather than hopes.
 */
@Immutable
internal data class M3uChrome(
    val full: Boolean,
    /** The panel's and the card's inner inset. */
    val pad: Dp,
    /** Under the panel's head. */
    val headGap: Dp,
    /** Between the two fields, where there are two. */
    val fieldGap: Dp,
    /** Above Connect. */
    val actionGap: Dp,
    /** Above the line under it. */
    val noteGap: Dp,
    /** The panel's own disc. Smaller when the band is short; it is a mark, not a control. */
    val disc: Dp,
    /** The optional playlist name. */
    val name: Boolean,
    /** The sentence under the panel's title. */
    val blurb: Boolean,
    val pitchTitleLines: Int,
    val pitchBodyLines: Int,
)

internal fun m3uChrome(full: Boolean): M3uChrome =
    if (full) {
        M3uChrome(
            full = true,
            pad = Spacing.lg,
            headGap = Spacing.xl,
            fieldGap = Spacing.lg,
            actionGap = Spacing.xl,
            noteGap = Spacing.md,
            disc = Dp.Unspecified,
            name = true,
            blurb = true,
            pitchTitleLines = 2,
            pitchBodyLines = 2,
        )
    } else {
        M3uChrome(
            full = false,
            pad = Spacing.md,
            headGap = Spacing.md,
            fieldGap = Spacing.sm,
            actionGap = Spacing.md,
            noteGap = Spacing.xs,
            disc = COMPACT_DISC,
            name = false,
            blurb = false,
            pitchTitleLines = 1,
            pitchBodyLines = 1,
        )
    }

/**
 * Whether this surface is wide enough for two columns.
 *
 * ## Width, and only width
 *
 * This asked about the height too, and the height was the wrong question twice over.
 * It kept the drawing off the device the project is actually tested on — a landscape
 * handset leaves about 300dp under the header, the threshold wanted 400, and the
 * screen fell back to a form with two fields in it and nothing else. And it was
 * answering a question the layout no longer asks: the columns wrap and the box
 * scrolls, so a short surface costs a scroll rather than a clipped panel.
 *
 * What a short surface cannot buy back is *width*. Below [SPREAD_MIN_WIDTH] the stage
 * cannot give the panel the 420dp a URL field with a Paste in it needs and still leave
 * the illustration a column worth drawing in, so there the screen is one column.
 *
 * ## The band is asked again, at the floor that is actually load-bearing
 *
 * Not the 400dp it used to ask for — that figure was the *full* panel's and it kept
 * the drawing off a handset that could have carried the compact one. This is
 * [SPREAD_BAND_MIN], what the compact panel costs, and it excludes only the windows
 * where even that would be cut: a 840x300 one has 222dp of band and the panel wants
 * 244. There the screen is a single column, which fits in 200.
 *
 * @param width the surface, not the stage. The stage's own margins scale with it.
 * @param band [m3uBand].
 */
internal fun fitsSpread(width: Dp, band: Dp): Boolean =
    width >= SPREAD_MIN_WIDTH && band >= SPREAD_BAND_MIN

/**
 * The narrowest surface that holds two columns.
 *
 * Derived rather than chosen: the panel's floor is [PANEL_MIN] and the illustration
 * column's is 300dp, so the stage must leave 720dp plus the gap between them, and the
 * stage is the surface less `edge` on each side. 840 clears that with room; 820 does
 * not, and `M3uSpreadTest` holds the arithmetic to it.
 *
 * It was 960 -- the width of the smallest television -- which reads like a decision
 * and was really an assumption that nothing smaller would want this drawing. The
 * handset the project is tested on is 851dp wide.
 */
internal val SPREAD_MIN_WIDTH: Dp = 840.dp

/**
 * The shortest band two columns fit in: the compact panel's fixed parts plus the
 * smallest control a thumb can land on.
 *
 * Derived, like the width. Nothing the project ships to comes near it — the shortest
 * frame in the sizing system is 800x360, which leaves 274.7 — so this is the guard on
 * a window shape rather than a device, and below it the single column fits in 200.
 */
internal val SPREAD_BAND_MIN: Dp = 244.dp

/**
 * The narrowest illustration column that can hold three fact cards side by side.
 *
 * A card's name is the binding figure at about 110dp, and the disc and padding take
 * 70 more. Three of those plus two gaps is 572, so a column under this does not draw
 * them at all -- see [Facts] for why that is better than stacking them.
 */
internal val FACTS_ROW_MIN: Dp = 480.dp

/* ------------------------------------------------- the spread, as shares of its stage
 *
 * 1157.4dp is the content width of the 1280x720 reference once the stage's 61.3dp
 * margins are paid, and it is what the drawing was approved at.
 */

private const val LEFT = 575f / 1157.4f

/** 330 over 575: the drawing's own rectangle, as a share of the column it sits in. */
private const val ART_OF_COLUMN = 330f / 575f

/**
 * The drawing's bounds. The floor is where a television stops being recognisable as
 * one — below it the set, its menu and the sheet in front of it are a smudge — and
 * the ceiling is the size it was approved at plus the room a 4K stage would add.
 */
private val ART_MIN: Dp = 100.dp
private val ART_MAX: Dp = 360.dp

/**
 * What the panel costs apart from its button, at each density. Counted rather than
 * measured, because the screen has to know before it lays out whether the band can
 * hold it — see [m3uSpread].
 *
 * Counted to the dp against what the components actually measure, which is where an
 * earlier pass of this was wrong twice: the name field's label row carries the word
 * "optional" on `labelSmall`, whose leading is 18 and not 16; and `CastivioButton`
 * floors itself at the frame's own D-pad target, so a television's is 56 whatever the
 * share says. Both are in [button] and in the figures below now.
 *
 * Full: 16 pad + 80 head + 24 + 70 name + 16 + 76 url + 24 + [button] + 12 + 20 note
 * + 16 pad. Compact: 12 + 40 head + 12 + 76 url + 12 + [button] + 4 + 20 + 12.
 */
private val PANEL_FIXED: Dp = 354.dp
private val PANEL_FIXED_COMPACT: Dp = 188.dp

/**
 * The same for the single column, which carries the fields in a card of their own.
 *
 * Full: 194 card + 24 + [button] + 24 + 20 note. Compact: 100 card + 12 + 12 + 20.
 */
internal val COLUMN_FIXED: Dp = 262.dp
private val COLUMN_FIXED_COMPACT: Dp = 144.dp

/** The panel's mark where the band is short: a glyph, not a control, so it may shrink. */
private val COMPACT_DISC: Dp = 40.dp

/** `Spacing.md` above the pitch, `Spacing.xs` inside it, `Spacing.xxl` above the cards. */
private val PITCH_GAP: Dp = Spacing.md
private val TITLE_GAP: Dp = Spacing.xs
private val FACTS_GAP: Dp = Spacing.xxl

/** `CastivioType.headlineMedium` and `bodyMedium`, which the pitch is set in. */
private val LINE_TITLE: Dp = 32.dp
private val LINE_BODY: Dp = 22.dp
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
