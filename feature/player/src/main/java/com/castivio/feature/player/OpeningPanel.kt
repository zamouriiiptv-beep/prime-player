package com.castivio.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.icons.CastivioIcons
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.playback.api.EngineId
import kotlinx.coroutines.delay

/**
 * **Temporary, for development.** What a channel's opening cost, over the big player: the press to the first frame,
 * stage by stage, and the last few openings in a table -- see [OpeningReading] for what each figure is.
 *
 * Draws nothing unless the panel is on, which is a debug build and nothing else, and nothing until there has been an
 * opening. Always left to right and in the code face, for the reason `LoadStrip` is: an instrument written in Latin
 * figures, which a photograph carries intact.
 *
 * ## Two sizes, one report -- as Home's measurement card is
 *
 * It used to be twenty lines, 560dp wide, over the side of the picture the controls are on in Arabic, so what was being
 * changed in the player could not be seen under it. **Collapsed**, it is a card of two lines at the picture's left edge,
 * between the title and the playlist button: the opening's number, its engine and its time in the colour of how it stands,
 * and under that how it was opened and how it ended ([OpeningReading.glance]). A press opens it to every line there is
 * and the two buttons; a second press closes it again. Drawn first in `design/mockups/player-measurements-card-ar.html`.
 *
 * A new opening brings the card back, collapsed. It goes by itself [LINGER_MS] after the opening ends unless it is open,
 * and the mark at its corner hides it for the opening it shows: nothing is un-recorded, and the next opening brings it
 * back. It is tap-only, as the panel was -- its lines take no focus and no touches of their own, so the remote and the
 * controls underneath are as they were -- and so a remote cannot open it.
 *
 * ## The two buttons
 *
 * Inside the opened card, tap-only like the rest: they take no focus, so a remote never lands on them and cannot press
 * them. *Retry on Media3* is drawn only while the backup engine is the one playing, and asks the player to try the
 * primary again for this channel; *Copy report* is drawn only while the opening carries the primary's failure report,
 * and puts it on the clipboard in full, redacted. Neither is a feature of the product -- both leave with the panel.
 *
 * The text is made when a reading is published, ten times a second while a channel opens -- nothing is formatted here,
 * and the text to copy is made when the button is pressed, not while it is drawn.
 */
@Composable
internal fun OpeningPanel(
    modifier: Modifier = Modifier,
    onRetryOnMedia3: () -> Unit = {},
) {
    if (!OpeningDiagnostics.enabled) return
    val lines by OpeningDiagnostics.panel.collectAsStateWithLifecycle()
    val reading by OpeningDiagnostics.current.collectAsStateWithLifecycle()
    if (lines.isEmpty()) return
    val now = reading ?: return

    // An opening is one card: a new one begins again, collapsed and shown.
    var open by remember(now.id) { mutableStateOf(false) }
    var hidden by remember(now.id) { mutableStateOf(OpeningDiagnostics.cardHidden(now.id)) }
    var gone by remember(now.id, now.running, open) { mutableStateOf(false) }
    LaunchedEffect(now.id, now.running, open) {
        if (!now.running && !open) {
            delay(LINGER_MS)
            gone = true
        }
    }
    if (hidden || gone) return

    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.md)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MS)
            copied = false
        }
    }
    val canRetry = now.timing.engine == EngineId.BACKUP
    val hasReport = now.failureReport != null

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        // At the picture's left edge in every language: the controls are on the right of an Arabic screen, and this is what
        // must not stand over them.
        Box(modifier.padding(start = Spacing.xxxl), contentAlignment = Alignment.CenterStart) {
            Box(
                Modifier
                    .clip(shape)
                    // Twice: one scrim dims a picture, and this has to be read over a bright one.
                    .background(colors.scrim)
                    .background(colors.scrim)
                    .border(1.dp, colors.glassBorder, shape)
                    .testTag(OPENING_CARD_TAG)
                    .tapOnly { open = !open },
            ) {
                if (open) {
                    OpenedCard(
                        lines = lines,
                        canRetry = canRetry,
                        hasReport = hasReport,
                        copied = copied,
                        onRetry = onRetryOnMedia3,
                        onCopy = {
                            // Made now, at the press: the reading in hand is the one on the panel, and its report is
                            // redacted as a whole by [OpeningReading.copyText].
                            OpeningDiagnostics.current.value?.copyText()?.let {
                                clipboard.setText(AnnotatedString(it))
                                copied = true
                            }
                        },
                        onClose = { open = false },
                        onHide = {
                            OpeningDiagnostics.hideCard(now.id)
                            hidden = true
                        },
                    )
                } else {
                    CollapsedCard(
                        glance = now.glance(),
                        onOpen = { open = true },
                        onHide = {
                            OpeningDiagnostics.hideCard(now.id)
                            hidden = true
                        },
                    )
                }
            }
        }
    }
}

/** Two lines: how the opening stands, then how it was opened and ended -- and the marks to open it and to hide it. */
@Composable
private fun CollapsedCard(glance: Glance, onOpen: () -> Unit, onHide: () -> Unit) {
    val colors = CastivioTheme.colors
    Row(
        Modifier
            .widthIn(max = COLLAPSED_MAX_WIDTH)
            .padding(start = Spacing.md, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                text = glance.head,
                style = CastivioType.codeSmall,
                color = toneInk(glance.tone),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = glance.detail,
                style = CastivioType.codeSmall,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Pointing at the trailing edge, turned to point down: there is more under it.
        PanelMark(CastivioIcons.Chevron, OPEN_ARROW_DEGREES, OPENING_OPEN_TAG, onOpen)
        PanelMark(CastivioIcons.Close, 0f, OPENING_HIDE_TAG, onHide)
    }
}

/** Every line of the opening, the two buttons where they apply, and the marks at the corner: close, and hide. */
@Composable
private fun OpenedCard(
    lines: List<HudLine>,
    canRetry: Boolean,
    hasReport: Boolean,
    copied: Boolean,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit,
    onHide: () -> Unit,
) {
    Box(Modifier.widthIn(max = PANEL_MAX_WIDTH)) {
        Column(
            Modifier
                // What the corner holds, kept clear of the text: two marks and the gap between them.
                .padding(start = Spacing.sm, end = PANEL_MARK * 2 + Spacing.md, top = Spacing.sm, bottom = Spacing.sm),
        ) {
            if (canRetry || hasReport) {
                Row(
                    modifier = Modifier.padding(bottom = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (canRetry) PanelButton(RETRY_LABEL, onRetry)
                    if (hasReport) PanelButton(if (copied) COPIED_LABEL else COPY_LABEL, onCopy)
                }
            }
            lines.forEach { line ->
                Text(
                    text = line.text,
                    style = CastivioType.codeSmall,
                    color = toneInk(line.tone),
                )
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            PanelMark(CastivioIcons.Chevron, CLOSE_ARROW_DEGREES, OPENING_CLOSE_TAG, onClose)
            PanelMark(CastivioIcons.Close, 0f, OPENING_HIDE_TAG, onHide)
        }
    }
}

/** The ink of a line: amber while it runs, green when it is done, red when it failed, plain for the rest. */
@Composable
private fun toneInk(tone: HudTone): Color {
    val colors = CastivioTheme.colors
    return when (tone) {
        HudTone.RUNNING -> colors.warning
        HudTone.DONE -> colors.success
        HudTone.FAILED -> colors.danger
        HudTone.MUTED -> colors.onBackground
    }
}

/** A small round mark that is only a tap, like the buttons: no focus, no role, found by tests through its tag. */
@Composable
private fun PanelMark(icon: ImageVector, degrees: Float, tag: String, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    Box(
        Modifier
            .size(PANEL_MARK)
            .clip(CircleShape)
            .background(colors.glassFillStrong)
            .testTag(tag)
            .tapOnly(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.onBackgroundMuted,
            modifier = Modifier.size(Sizing.iconSm).rotate(degrees),
        )
    }
}

/** A tap and nothing else: no focus, no semantics, so the remote and the screen reader pass over it. */
private fun Modifier.tapOnly(onClick: () -> Unit): Modifier = pointerInput(Unit) { detectTapGestures { onClick() } }

/**
 * A button that is only a tap: no focus, no semantics, so the remote and the screen reader pass over it as they pass
 * over the lines beside it. Small on purpose -- the panel has no room to spare, and this is a development tool.
 */
@Composable
private fun PanelButton(label: String, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    val latest by rememberUpdatedState(onClick)
    Text(
        text = label,
        style = CastivioType.codeSmall,
        color = colors.onPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(colors.primary)
            .pointerInput(Unit) { detectTapGestures { latest() } }
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
}

/** Wide enough for the longest line without taking the picture. */
private val PANEL_MAX_WIDTH = 560.dp

/** The collapsed card is as wide as its longest line needs and no wider. */
private val COLLAPSED_MAX_WIDTH = 280.dp

/** The marks' diameter: a finger's, on a development tool that has no room to spare. */
private val PANEL_MARK = 24.dp

/** The arrow of a chevron that points at the trailing edge: a quarter turn to point down, three to point up. */
private const val OPEN_ARROW_DEGREES = 90f
private const val CLOSE_ARROW_DEGREES = 270f

/** How long the card stays after its opening ends, unless it is open. */
internal const val LINGER_MS = 20_000L

internal const val OPENING_CARD_TAG = "opening.card"
internal const val OPENING_OPEN_TAG = "opening.open"
internal const val OPENING_CLOSE_TAG = "opening.close"
internal const val OPENING_HIDE_TAG = "opening.hide"

/** How long the copy button says it has copied. */
private const val COPIED_MS = 1_500L

private const val RETRY_LABEL = "إعادة تجربة Media3 لهذه القناة"
private const val COPY_LABEL = "نسخ التقرير"
private const val COPIED_LABEL = "تم النسخ"
