package com.castivio.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing
import com.castivio.playback.api.EngineId
import kotlinx.coroutines.delay

/**
 * **Temporary, for development.** What a channel's opening cost, over the big player: the press to the first frame,
 * stage by stage, and the last few openings in a table -- see [OpeningReading] for what each figure is.
 *
 * Draws nothing unless the panel is on, which is a debug build and nothing else, and nothing until there has been an
 * opening. Its lines take no focus and no touches: they are text over the picture, not controls, so the remote and the
 * controls underneath are as they were. Always left to right and in the code face, for the reason `LoadStrip` is: an
 * instrument written in Latin figures, which a photograph carries intact.
 *
 * ## The two buttons
 *
 * The only touchable things on it, and tap-only: they take no focus, so a remote never lands on them and cannot press
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
    val canRetry = reading?.timing?.engine == EngineId.BACKUP
    val hasReport = reading?.failureReport != null

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier
                .widthIn(max = PANEL_MAX_WIDTH)
                .clip(shape)
                // Twice: one scrim dims a picture, and this has to be read over a bright one.
                .background(colors.scrim)
                .background(colors.scrim)
                .padding(Spacing.sm),
        ) {
            if (canRetry || hasReport) {
                Row(
                    modifier = Modifier.padding(bottom = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (canRetry) PanelButton(RETRY_LABEL, onRetryOnMedia3)
                    if (hasReport) {
                        PanelButton(if (copied) COPIED_LABEL else COPY_LABEL) {
                            // Made now, at the press: the reading in hand is the one on the panel, and its report is
                            // redacted as a whole by [OpeningReading.copyText].
                            OpeningDiagnostics.current.value?.copyText()?.let {
                                clipboard.setText(AnnotatedString(it))
                                copied = true
                            }
                        }
                    }
                }
            }
            lines.forEach { line ->
                Text(
                    text = line.text,
                    style = CastivioType.codeSmall,
                    color = when (line.tone) {
                        HudTone.RUNNING -> colors.warning
                        HudTone.DONE -> colors.success
                        HudTone.FAILED -> colors.danger
                        HudTone.MUTED -> colors.onBackground
                    },
                )
            }
        }
    }
}

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

/** How long the copy button says it has copied. */
private const val COPIED_MS = 1_500L

private const val RETRY_LABEL = "إعادة تجربة Media3 لهذه القناة"
private const val COPY_LABEL = "نسخ التقرير"
private const val COPIED_LABEL = "تم النسخ"
