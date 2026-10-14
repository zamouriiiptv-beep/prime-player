package com.castivio.feature.activation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.castivio.core.design.components.castivioFocusScale
import com.castivio.core.design.components.castivioGlyphGlow
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Elevation
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.core.design.theme.boundedFraction
import com.castivio.domain.activation.ActivationForm

/**
 * The Xtream form: a name the user may give it, the server, the username and the
 * password.
 *
 * Field order is the order the provider's e-mail lists them, not the order the protocol
 * needs — somebody is copying from one to the other, and a form that reshuffles them
 * makes that harder for no gain.
 *
 * Errors appear as the user types rather than on submit. The alternative is the pattern
 * this whole flow exists to be better than: a button that always looks live, then a
 * failure that names none of the four fields.
 *
 * ## The playlist and portal screens' header and frame, with four fields in it
 *
 * The same [ChooserHeader] with this screen's own title and Back, and the same frame
 * under it, so the three forms are one screen with different words. What four fields
 * change is the height: they do not fit a handset's band in landscape at any spacing,
 * and [xtreamFrame] decides between two arrangements from the band itself -- see it
 * for why the band and not the device.
 *
 *  - **Where the band holds the form** -- a television, a tablet -- the frame reaches
 *    the bottom of the band, so the margin under it is the stage's own and matches the
 *    gap above it, and the spacing shares whatever the controls leave. Nothing scrolls,
 *    so nothing may grow: a field with something wrong turns red, and what is wrong is
 *    said in the line under Connect, which is one line whatever it says.
 *  - **Where it does not** -- a handset -- the frame is the one the other two screens
 *    draw there, the spacing is the sizing system's at full size, and the form scrolls
 *    inside the frame under a header that stays where it is. A field's error is written
 *    under the field, as on the other two.
 *
 * ## The keyboard
 *
 * The window does not resize for it -- `:app` is edge to edge -- so the frame does:
 * `imePadding` takes the keys' height off the room the frame is placed in, the frame
 * keeps the part above them, and the form scrolls inside that part, which is what keeps
 * the field being typed in above the keys. Where the form fits, the scroll is only
 * switched on while a keyboard is up; at rest there is nothing on this screen to scroll.
 *
 * ## The remote
 *
 * Down goes name, server, username, password, Connect -- the order on the screen,
 * because the order on the screen is the order 2D focus search finds. Paste in the
 * server, the username and the password sits inside its field at the far end, and the
 * password's show and hide beside the lock at the near one: across from the field
 * either way rather than a stop on the way down. The keyboard's Next goes down the same
 * way, and Done on the password is Connect.
 *
 * ## Paste in three fields, at the price of the same height in three fields
 *
 * The username and the password carry the server's Paste, the same chip at the same
 * inset, so all three boxes are the playlist link's box: taller than a plain field by
 * the chip's floor, which a remote needs, and the same as one another. The smallest
 * television pays for it in spacing -- see [xtreamFrame] -- and nothing else does.
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
    /** The route's one answer to Back, raised by the header's chip. */
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = CastivioTheme.device.isTv

    SourceFormScreen(
        title = stringResource(R.string.source_xtream_title),
        glyph = Icons.Rounded.Storage,
        hue = CastivioTheme.colors.hueViolet,
        headingTag = ActivationTags.XTREAM_HEADING,
        backTag = ActivationTags.XTREAM_BACK,
        onBack = onBack,
        modifier = modifier,
    ) { surface, surfaceHeight, m, band ->
        val frame = xtreamFrame(surface, surfaceHeight, band, m.bandTop, Sizing.minTarget(tv))

        // On the room the frame is placed in rather than on the band, which the playlist
        // and portal screens share and which does not answer the keyboard. The frame's
        // own `height` is a preferred size, so with the keys up it is whatever is left.
        Box(Modifier.fillMaxSize().imePadding()) {
            XtreamFrame(
                frame = frame,
                fieldMeasure = formMeasure(m.frame),
                form = form,
                enabled = enabled,
                canSubmit = canSubmit,
                onName = onName,
                onServerUrl = onServerUrl,
                onUsername = onUsername,
                onPassword = onPassword,
                onSubmit = onSubmit,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(frame.height),
            )
        }
    }
}

/** The frame, and the four fields, Connect and the note inside it. */
@Composable
private fun XtreamFrame(
    frame: XtreamFrameMetrics,
    /** See [PlaylistPanel]'s own parameter of the same name. */
    fieldMeasure: Dp,
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
    val checked = form.checked
    val colors = CastivioTheme.colors
    val focus = LocalFocusManager.current
    // Down, not Next: Next is the order of composition, which visits Paste between the
    // server and the username; Down is the order on the screen.
    val next: () -> Unit = { focus.moveFocus(FocusDirection.Down) }

    // Hidden each time the screen is opened. Saveable, so a configuration change in the
    // middle of typing does not hide it again under the user's thumb.
    var revealed by rememberSaveable { mutableStateOf(false) }

    // A flag rather than the inset itself, so the frame recomposes when the keyboard
    // comes and goes and not on every frame of the keys sliding in.
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val keyboard by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }

    val nameLabel = stringResource(R.string.field_playlist_name)
    val serverLabel = stringResource(R.string.field_server_url)
    val usernameLabel = stringResource(R.string.field_username)
    val passwordLabel = stringResource(R.string.field_password)

    // Only ever shown once they are wrong; an empty field is not, optional or not.
    val nameError = checked.name.problem.message()
    val serverError = form.serverUrl.problemOnceTyped(checked.serverUrl.problem)
    val usernameError = form.username.problemOnceTyped(checked.username.problem)
    val passwordError = form.password.problemOnceTyped(checked.password.problem)

    // **Where the form does not scroll, the first thing wrong is said under Connect.**
    //
    // Under its field it would need a line the frame has not got: the frame is the
    // band, and every dp of the band is already spent. The note's line is spent too,
    // on something less urgent, so the error takes it -- named by its field, because
    // "this one is needed" means nothing a button's height away from the field it is
    // about. The field itself still turns red and still announces it.
    val problem = when {
        frame.scroll -> null
        nameError != null -> nameLabel to nameError
        serverError != null -> serverLabel to serverError
        usernameError != null -> usernameLabel to usernameError
        passwordError != null -> passwordLabel to passwordError
        else -> null
    }
    val note = if (problem != null) {
        stringResource(R.string.xtream_note_problem, problem.first, problem.second)
    } else {
        stringResource(if (canSubmit) R.string.xtream_note_ready else R.string.xtream_note_empty)
    }

    SourceFrameBox(padH = frame.padH, modifier = modifier.testTag(ActivationTags.XTREAM_FRAME)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState(), enabled = frame.scroll || keyboard)
                .padding(vertical = frame.pad)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = fieldMeasure),
        ) {
            CastivioTextField(
                value = form.name,
                onValueChange = onName,
                label = nameLabel,
                hint = stringResource(R.string.field_optional),
                placeholder = stringResource(R.string.field_playlist_name_placeholder),
                error = nameError,
                enabled = enabled,
                onImeAction = next,
                labelIconHue = colors.hueViolet,
                labelIcon = Icons.Rounded.Home,
                icon = Icons.Rounded.Home,
                errorBelow = frame.scroll,
                modifier = Modifier.fillMaxWidth().testTag(ActivationTags.XTREAM_NAME),
            )

            Spacer(Modifier.height(frame.fieldGap))
            CastivioTextField(
                value = form.serverUrl,
                onValueChange = onServerUrl,
                label = serverLabel,
                placeholder = stringResource(R.string.field_server_url_placeholder),
                error = serverError,
                enabled = enabled,
                keyboardType = KeyboardType.Uri,
                onImeAction = next,
                labelIconHue = colors.hueAzure,
                labelIcon = Icons.Rounded.Link,
                icon = Icons.Rounded.Link,
                trailing = { PasteChip(enabled = enabled, onPaste = onServerUrl) },
                errorBelow = frame.scroll,
                modifier = Modifier.fillMaxWidth().testTag(ActivationTags.XTREAM_SERVER),
            )

            Spacer(Modifier.height(frame.fieldGap))
            CastivioTextField(
                value = form.username,
                onValueChange = onUsername,
                label = usernameLabel,
                error = usernameError,
                enabled = enabled,
                onImeAction = next,
                labelIconHue = colors.hueGreen,
                labelIcon = Icons.Rounded.Person,
                icon = Icons.Rounded.Person,
                trailing = { PasteChip(enabled = enabled, onPaste = onUsername) },
                errorBelow = frame.scroll,
                modifier = Modifier.fillMaxWidth().testTag(ActivationTags.XTREAM_USERNAME),
            )

            Spacer(Modifier.height(frame.fieldGap))
            CastivioTextField(
                value = form.password,
                onValueChange = onPassword,
                label = passwordLabel,
                error = passwordError,
                enabled = enabled,
                secret = !revealed,
                // A password keyboard whether it is shown or not: it is what tells the
                // keyboard not to learn it or offer it back as a suggestion.
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                onImeAction = onSubmit,
                labelIconHue = colors.hueAmber,
                labelIcon = Icons.Rounded.Lock,
                icon = Icons.Rounded.Lock,
                // The show and hide beside the lock, and Paste alone at the far end where
                // the server's and the username's are. The box is built exactly as
                // theirs -- the same Paste at the same inset -- so the three are one
                // height, and the show and hide is a target as tall as that Paste.
                leading = {
                    RevealToggle(revealed = revealed, enabled = enabled, onToggle = { revealed = !revealed })
                },
                trailing = { PasteChip(enabled = enabled, onPaste = onPassword) },
                errorBelow = frame.scroll,
                modifier = Modifier.fillMaxWidth().testTag(ActivationTags.XTREAM_PASSWORD),
            )

            Spacer(Modifier.height(frame.actionGap))
            ConnectButton(
                enabled = canSubmit,
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(frame.noteGap))
            PanelNote(
                detail = note,
                error = problem != null,
                // One line where the frame cannot grow, so the frame's arithmetic holds.
                maxLines = if (frame.scroll) Int.MAX_VALUE else 1,
                modifier = Modifier.fillMaxWidth().testTag(ActivationTags.XTREAM_NOTE),
            )
        }
    }
}

/**
 * Show or hide the password, inside the field it shows.
 *
 * Inside for the reason Paste is (see [PasteChip]): beside the field it would be a stop
 * on the remote's way down the form. It sits by the lock that names the field, with the
 * field's Paste at the other end, so the two are never side by side to be pressed for
 * each other. A square at the target floor, which is the Paste chip's own floor, so it
 * adds nothing to the box's height.
 *
 * It looks like part of the field until it has focus, and then like every other
 * focused control in Castivio: the lift, and the ring, inset so that it does not draw
 * over the field's own edge.
 */
@Composable
private fun RevealToggle(
    revealed: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.xs)
    val ring by animateColorAsState(
        if (focused) colors.focusRing else colors.focusRing.copy(alpha = 0f),
        Motion.focusSpec(),
        label = "revealRing",
    )

    Box(
        modifier
            .size(Sizing.minTarget(CastivioTheme.device.isTv))
            .castivioFocusScale(Motion.focusScaleIcon, interaction)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onToggle)
            .testTag(ActivationTags.XTREAM_REVEAL)
            .padding(Spacing.xs)
            .border(BorderStroke(1.dp, ring), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            // Named by what pressing it does, as the theme control is.
            contentDescription = stringResource(
                if (revealed) R.string.action_hide_password else R.string.action_show_password,
            ),
            tint = if (focused) colors.onBackground else colors.onBackgroundMuted,
            modifier = Modifier.size(Sizing.iconMd),
        )
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
 * [M3uSpread] where [fitsSpread] says so and [SourceFormFrame] otherwise, the framed
 * form under the header. Not a breakpoint in the
 * sizing system's sense — that system returns sizes and forbids a layout that
 * changes — but a choice this screen makes about itself, in the one place the
 * application has a reason for it: an illustration and a form side by side need about
 * 820dp of stage, a handset in landscape has 750, and a drawing squeezed to a third of
 * its size is worth less than the vertical room it costs.
 *
 * Where the spread does not fit, the screen draws [SourceFormFrame] -- the same frame
 * the portal draws at every size, so the two forms cannot drift apart. Xtream draws
 * the same frame around its four fields; see [XtreamFormScreen].
 *
 * ## It owns its viewport, like the chooser and the other two forms
 *
 * Which is what lets it wear [ChooserHeader] at the chooser's own place, and what gives
 * the band a bounded height to divide. See [SourceFormScreen].
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
    val checked = form.checked

    SourceFormScreen(
        title = stringResource(R.string.source_m3u_title),
        glyph = Icons.Rounded.Description,
        hue = CastivioTheme.colors.hueAzure,
        badge = M3U,
        headingTag = ActivationTags.PLAYLIST_HEADING,
        backTag = ActivationTags.PLAYLIST_BACK,
        onBack = onBack,
        modifier = modifier,
    ) { surface, surfaceHeight, m, band ->
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
                fieldMeasure = formMeasure(m.frame),
            )
        } else {
            val frame = formFrame(surface, surfaceHeight, band, m.bandTop, Sizing.minTarget(tv))
            SourceFormFrame(
                frame = frame,
                fieldMeasure = formMeasure(m.frame),
                name = form.name,
                nameError = checked.name.problem.message(),
                url = form.url,
                urlError = form.url.problemOnceTyped(checked.url.problem),
                urlLabel = stringResource(R.string.field_playlist_url),
                urlPlaceholder = stringResource(R.string.field_playlist_url_placeholder),
                note = stringResource(if (canSubmit) R.string.playlist_note_ready else R.string.playlist_note_empty),
                enabled = enabled,
                canSubmit = canSubmit,
                onName = onName,
                onUrl = onUrl,
                onSubmit = onSubmit,
                nameTag = ActivationTags.PLAYLIST_NAME,
                urlTag = ActivationTags.PLAYLIST_URL,
                noteTag = ActivationTags.PLAYLIST_NOTE,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(frame.height),
            )
        }
    }
}

/**
 * The stage the three source forms share: the chooser's header with this screen's own
 * title, the chooser's gap under it, and the band that [content] is given -- with the
 * surface's own width and height, its metrics, and the band's height.
 */
@Composable
private fun SourceFormScreen(
    title: String,
    headingTag: String,
    backTag: String,
    onBack: () -> Unit,
    /** The mark of the card that opened this form, in its hue. See `GlyphTitle`. */
    glyph: ImageVector,
    hue: Color,
    modifier: Modifier = Modifier,
    badge: String? = null,
    content: @Composable BoxWithConstraintsScope.(surface: Dp, surfaceHeight: Dp, m: SourceMetrics, band: Dp) -> Unit,
) {
    val tv = CastivioTheme.device.isTv

    BoxWithConstraints(modifier.fillMaxSize()) {
        val surface = maxWidth
        val surfaceHeight = maxHeight
        val m = sourceMetricsFor(tv = tv, width = surface, height = surfaceHeight)
        val band = m3uBand(m, surfaceHeight)

        Column(Modifier.fillMaxSize().castivioStage(m.frame)) {
            // The chooser's header, the same component at the same place with this
            // screen's own title -- which is what puts Back where a reader who has just
            // come from the chooser already knows it is. The title is the words on the
            // card the user just pressed, from the gated chooser bundle.
            ChooserHeader(
                m = m,
                title = title,
                headingTag = headingTag,
                backTag = backTag,
                onBack = onBack,
                glyph = glyph,
                hue = hue,
                badge = badge,
            )
            Spacer(Modifier.height(m.bandTop))

            // **The band, and nothing may leave it.**
            //
            // No scroll and no `imePadding` on the band: a scroll meant Connect could
            // sit below the fold, and an IME inset on a window that does not resize --
            // `:app` is edge to edge -- would have shrunk the band and clipped the
            // control instead. What replaces them is arithmetic, asserted on every
            // surface by `M3uSpreadTest`. The Xtream form, whose four fields a handset
            // cannot hold, scrolls and answers the keyboard inside its own frame and
            // leaves the band to the other two as it is; see [XtreamFormScreen].
            //
            // A `BoxWithConstraints` rather than a `Box`, so [content] can read the
            // stage's own width: `castivioStage` pays `max(edge, inset)` per side, and
            // `surface - edge * 2` would overstate the room beside a display cutout.
            BoxWithConstraints(Modifier.weight(1f)) {
                content(surface, surfaceHeight, m, band)
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
    /** See [PlaylistPanel]'s own parameter of the same name. */
    fieldMeasure: Dp,
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
            fieldMeasure = fieldMeasure,
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
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.art)
                .testTag(ActivationTags.PLAYLIST_ART),
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
    /**
     * How wide a field's own box may grow, on this surface.
     *
     * The panel itself is `weight(1f)` of whatever the spread leaves it -- up to
     * about 900dp on a wide television, where `metrics.left` has settled near its
     * 300dp floor. A single-line field that wide is not a bigger field, it is a
     * line a reader's eye has to travel across to reach the one thing typed into
     * it, and a Paste chip stranded at the far edge of it.
     *
     * [formMeasure] rather than a number invented here: it is the same cap
     * [SourceFormFrame] and Xtream's form already read a field at, derived
     * from the surface's own type step rather than fixed to one device class. The
     * field is centred in whatever width is left over, so the card, the title row,
     * Connect and the note under it are untouched -- only the two inputs narrow.
     */
    fieldMeasure: Dp,
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
                    labelIconHue = colors.hueViolet,
                    labelIcon = Icons.Rounded.Home,
                    icon = Icons.Rounded.Home,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = fieldMeasure)
                        .testTag(ActivationTags.PLAYLIST_NAME),
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
                labelIconHue = colors.hueAzure,
                labelIcon = Icons.Rounded.Link,
                icon = Icons.Rounded.Link,
                trailing = { PasteChip(enabled = enabled, onPaste = onUrl) },
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = fieldMeasure),
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
 * On the playlist and portal screens it is never an error. A field says what is wrong
 * with a field, under that field; a playlist's Connect cannot fail, because it asks
 * nobody anything, and a portal's failure replaces the whole form with its own screen.
 *
 * The Xtream form is the one exception, and only where it does not scroll: there a
 * field's error has no line of its own to go to, so it takes this one -- in the
 * error's ink, on one line, in place of the sentence it displaces. See
 * [XtreamFormScreen].
 *
 * @param error whether [detail] is a field's error rather than the next step.
 * @param maxLines one where the frame's height is fixed and the line must not wrap.
 *   Unbounded otherwise, as it always was.
 */
@Composable
private fun PanelNote(
    detail: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    val colors = CastivioTheme.colors
    val ink = if (error) colors.danger else colors.onBackgroundMuted
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.Info,
            contentDescription = null, // the sentence beside it is the message
            // A mark that is a next step in the hue Home gives its own note, lit like
            // the glyphs above the fields; a mark that is an error keeps the error ink.
            tint = if (error) ink else colors.discGlyph(colors.hueViolet),
            modifier = Modifier
                .size(Sizing.iconSm)
                .then(if (error) Modifier else Modifier.castivioGlyphGlow(colors.hueViolet, colors.discTop)),
        )
        Text(
            text = detail,
            style = CastivioType.bodySmall,
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            // Clip is `Text`'s own default, kept where the line is unbounded.
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
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
 * The framed form both source screens draw: the name field, the URL field with Paste,
 * Connect and the note -- always all four. The frame takes [FormFrameMetrics.height]; the
 * form inside it keeps the field measure and sits centred, so spare height is shared
 * above and below. Only the words and the tags differ between the two screens.
 *
 * The frame itself is [SourceFrameBox], which the Xtream form draws around its own four.
 */
@Composable
private fun SourceFormFrame(
    frame: FormFrameMetrics,
    /** See [PlaylistPanel]'s own parameter of the same name. */
    fieldMeasure: Dp,
    name: String,
    nameError: String?,
    url: String,
    urlError: String?,
    urlLabel: String,
    urlPlaceholder: String,
    note: String,
    enabled: Boolean,
    canSubmit: Boolean,
    onName: (String) -> Unit,
    onUrl: (String) -> Unit,
    onSubmit: () -> Unit,
    nameTag: String,
    urlTag: String,
    noteTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    SourceFrameBox(padH = frame.padH, modifier = modifier) {
        Column(Modifier.widthIn(max = fieldMeasure).fillMaxWidth()) {
            CastivioTextField(
                value = name,
                onValueChange = onName,
                label = stringResource(R.string.field_playlist_name),
                hint = stringResource(R.string.field_optional),
                placeholder = stringResource(R.string.field_playlist_name_placeholder),
                error = nameError,
                enabled = enabled,
                labelIconHue = colors.hueViolet,
                labelIcon = Icons.Rounded.Home,
                icon = Icons.Rounded.Home,
                modifier = Modifier.fillMaxWidth().testTag(nameTag),
            )

            Spacer(Modifier.height(frame.fieldGap))
            CastivioTextField(
                value = url,
                onValueChange = onUrl,
                label = urlLabel,
                placeholder = urlPlaceholder,
                error = urlError,
                enabled = enabled,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onImeAction = onSubmit,
                labelIconHue = colors.hueAzure,
                labelIcon = Icons.Rounded.Link,
                icon = Icons.Rounded.Link,
                trailing = { PasteChip(enabled = enabled, onPaste = onUrl) },
                modifier = Modifier.fillMaxWidth().testTag(urlTag),
            )

            Spacer(Modifier.height(frame.actionGap))
            ConnectButton(
                enabled = canSubmit,
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(frame.noteGap))
            PanelNote(
                detail = note,
                modifier = Modifier.fillMaxWidth().testTag(noteTag),
            )
        }
    }
}

/**
 * The frame itself, which the three source forms draw: GlassCard's own shadow, fill and
 * clip, plus the one thing it has no parameter for -- a border in the brand's two hues.
 * [content] is centred in it, [padH] in from either side.
 */
@Composable
private fun SourceFrameBox(
    padH: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.xl)

    Box(
        modifier
            .shadow(Elevation.level2, shape, ambientColor = Elevation.ambient, spotColor = Elevation.spot)
            .clip(shape)
            .background(colors.glassFillBrush)
            .border(BorderStroke(1.dp, Brush.linearGradient(listOf(colors.hueViolet, colors.hueAzure))), shape)
            .padding(horizontal = padH),
        contentAlignment = Alignment.Center,
        content = content,
    )
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
 * It is the playlist screen with a different title, label, example and destination:
 * the same header, the same frame, the same field with Paste, the same Connect. It
 * draws the frame at every size -- the spread's illustration is about playlists and
 * has nothing to say about a portal -- and the sizing system scales it from there.
 */
@Composable
internal fun PortalFormScreen(
    form: ActivationForm.Portal,
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
    val checked = form.checked

    SourceFormScreen(
        title = stringResource(R.string.source_portal_title),
        glyph = Icons.Rounded.LiveTv,
        hue = CastivioTheme.colors.hueAmber,
        headingTag = ActivationTags.PORTAL_HEADING,
        backTag = ActivationTags.PORTAL_BACK,
        onBack = onBack,
        modifier = modifier,
    ) { surface, surfaceHeight, m, band ->
        val frame = formFrame(surface, surfaceHeight, band, m.bandTop, Sizing.minTarget(tv))
        SourceFormFrame(
            frame = frame,
            fieldMeasure = formMeasure(m.frame),
            name = form.name,
            nameError = checked.name.problem.message(),
            url = form.url,
            urlError = form.url.problemOnceTyped(checked.url.problem),
            urlLabel = stringResource(R.string.field_portal_url),
            urlPlaceholder = stringResource(R.string.field_portal_url_placeholder),
            note = stringResource(if (canSubmit) R.string.portal_note_ready else R.string.playlist_note_empty),
            enabled = enabled,
            canSubmit = canSubmit,
            onName = onName,
            onUrl = onUrl,
            onSubmit = onSubmit,
            nameTag = ActivationTags.PORTAL_NAME,
            urlTag = ActivationTags.PORTAL_URL,
            noteTag = ActivationTags.PORTAL_NOTE,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(frame.height),
        )
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
 * the control falls back to the panel's own glass, which says it is a place where a
 * button will be.
 *
 * ## And the glass is not faded on top of that
 *
 * It used to be. The glass got `CastivioButton`'s disabled dimming as well, and the
 * word on it the muted ink, so on an empty form -- the state every user meets first --
 * the screen's one action all but vanished, which was reported from a device. The
 * glass already says "not yet"; the word says what will happen, in the ink the field
 * labels above it use, and the rim is the one the live button wears, so it is the same
 * control waiting rather than a different one. It is still unfocusable and does
 * nothing when pressed, which is the part of disabled that is not a colour.
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
        ring = colors.ctaQuietRing,
        minHeight = minHeight,
        disabledAlpha = 1f,
        disabledInk = colors.onBackgroundVariant,
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

/**
 * The source form's frame, from the surface and the band it is given.
 *
 * The spacing inside it is the sizing system's: bounded shares of the surface's height
 * (and of its width, across), read off 851x393 where this frame was approved. Every
 * control is at its touch floor and cannot give height back, so spacing is also what
 * yields: the frame keeps a bottom margin equal to [bandTop] above it, and the gaps
 * shrink together where that frame is too short for them at full size.
 */
internal fun formFrame(width: Dp, height: Dp, band: Dp, bandTop: Dp, button: Dp): FormFrameMetrics {
    val floor = formFrameFloor(button)
    val frame = (band - bandTop).coerceAtLeast(floor).coerceAtMost(band)
    val pad = height.boundedFraction(FRAME_PAD, Spacing.sm, Spacing.xl)
    val fieldGap = height.boundedFraction(FRAME_FIELD_GAP, Spacing.sm, Spacing.xl)
    val actionGap = height.boundedFraction(FRAME_ACTION_GAP, Spacing.md, Spacing.xxl)
    val noteGap = height.boundedFraction(FRAME_NOTE_GAP, Spacing.xs, Spacing.lg)
    val share = ((frame - floor) / (pad * 2 + fieldGap + actionGap + noteGap)).coerceIn(0f, 1f)
    return FormFrameMetrics(
        height = frame,
        padH = width.boundedFraction(FRAME_PAD_H, Spacing.md, Spacing.xxl),
        fieldGap = fieldGap * share,
        actionGap = actionGap * share,
        noteGap = noteGap * share,
    )
}

/** What the frame's contents measure with no spacing at all: both fields, Connect, the note. */
internal fun formFrameFloor(button: Dp): Dp = FRAME_NAME_FIELD + FRAME_URL_CHROME + button + button + FRAME_NOTE

@Immutable
internal data class FormFrameMetrics(
    /** The frame: the band less a bottom margin equal to the gap above it, where that fits. */
    val height: Dp,
    /** Either side of the form, inside the frame. */
    val padH: Dp,
    /** Between the name field and the URL field. */
    val fieldGap: Dp,
    /** Above Connect. */
    val actionGap: Dp,
    /** Above the note. */
    val noteGap: Dp,
)

/**
 * The Xtream frame, from the surface and the band it is given.
 *
 * ## One question, asked of the band
 *
 * Whether the band holds the form's controls at all, at no spacing -- [xtreamFloor].
 * Not whether this is a handset: that question was asked of `DeviceClass` and of `isTv`
 * on the screen before this one and shipped wrong both times (see `isFixedViewport`).
 * The band is the thing that has or has not got room, and asking it gives the answer
 * the devices would -- a handset's landscape band is 275 to 333dp against a floor of
 * 366, the smallest television's is 418 against 398 -- and the right answer for the
 * windows no device list names, such as a tablet split into a short half.
 *
 * Where it holds, the frame is the whole band, so the margin under the frame is the
 * stage's own bottom margin -- which the sizing system draws equal to the gap above
 * the frame -- and the spacing is the sizing system's, shared out of what the controls
 * leave where the band cannot afford it at full size. Every control keeps its height.
 *
 * Where it does not, the frame is [formFrame]'s, the one the playlist and portal
 * screens draw on the same surface, and the spacing is the sizing system's at full
 * size: the form scrolls inside the frame, so it has no reason to be cramped.
 */
internal fun xtreamFrame(width: Dp, height: Dp, band: Dp, bandTop: Dp, button: Dp): XtreamFrameMetrics {
    val floor = xtreamFloor(button)
    val pad = height.boundedFraction(FRAME_PAD, Spacing.sm, Spacing.xl)
    val fieldGap = height.boundedFraction(FRAME_FIELD_GAP, Spacing.sm, Spacing.xl)
    val actionGap = height.boundedFraction(FRAME_ACTION_GAP, Spacing.md, Spacing.xxl)
    val noteGap = height.boundedFraction(FRAME_NOTE_GAP, Spacing.xs, Spacing.lg)
    val fits = band >= floor
    val frame = if (fits) band else formFrame(width, height, band, bandTop, button).height
    val share = if (fits) {
        ((frame - floor) / (pad * 2 + fieldGap * 3 + actionGap + noteGap)).coerceIn(0f, 1f)
    } else {
        1f
    }
    return XtreamFrameMetrics(
        height = frame,
        padH = width.boundedFraction(FRAME_PAD_H, Spacing.md, Spacing.xxl),
        pad = pad * share,
        fieldGap = fieldGap * share,
        actionGap = actionGap * share,
        noteGap = noteGap * share,
        scroll = !fits,
    )
}

/**
 * What the Xtream form measures with no spacing at all: the name field; the server, the
 * username and the password, each a box with Paste in it; Connect and the note -- with
 * no error under any field, because where this is the budget an error is said in the
 * note. The password's show and hide is no taller than its Paste and adds nothing.
 */
internal fun xtreamFloor(button: Dp): Dp =
    FRAME_NAME_FIELD + (FRAME_URL_CHROME + button) * 3 + button + FRAME_NOTE

@Immutable
internal data class XtreamFrameMetrics(
    /** The frame: the whole band where the form fits, [formFrame]'s where it scrolls. */
    val height: Dp,
    /** Either side of the form, inside the frame. */
    val padH: Dp,
    /** Above the name field and under the note, inside the frame. */
    val pad: Dp,
    /** Between one field and the next. */
    val fieldGap: Dp,
    /** Above Connect. */
    val actionGap: Dp,
    /** Above the note. */
    val noteGap: Dp,
    /**
     * Whether the form scrolls inside the frame -- and with it, where a field's error
     * goes: under the field where the form scrolls and the line has somewhere to push,
     * in the note where it does not and the frame cannot grow.
     */
    val scroll: Boolean,
)

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

/** Label row 18 (`labelSmall` carries "optional"), `Spacing.xs`, a box at the touch floor. */
private val FRAME_NAME_FIELD: Dp = 70.dp

/** Label row 16, `Spacing.xs`, and `Spacing.xs` above and below a Paste chip floored at the button's target. */
private val FRAME_URL_CHROME: Dp = 28.dp

/** One line of `bodySmall`. */
private val FRAME_NOTE: Dp = 20.dp

/**
 * The frame's spacing as shares of the 720dp reference height, so that the 393dp handset
 * the frame was approved on gets 12, 12, 16 and 8 -- and 1280 across for the 16 either side.
 */
private const val FRAME_PAD = 22f / 720f
private const val FRAME_FIELD_GAP = 22f / 720f
private const val FRAME_ACTION_GAP = 29.3f / 720f
private const val FRAME_NOTE_GAP = 14.7f / 720f
private const val FRAME_PAD_H = 24f / 1280f

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
