package com.castivio.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing

/**
 * **Temporary, for development.** What a channel's opening cost, over the big player: the press to the first frame,
 * stage by stage, and the last few openings in a table -- see [OpeningReading] for what each figure is.
 *
 * Draws nothing unless the panel is on, which is a debug build and nothing else, and nothing until there has been an
 * opening. It takes no focus and no touches: it is lines over the picture, not a control, so the remote and the
 * controls underneath are as they were. Always left to right and in the code face, for the reason `LoadStrip` is: an
 * instrument written in Latin figures, which a photograph carries intact.
 *
 * The text is made when a reading is published, ten times a second while a channel opens -- nothing is formatted here.
 */
@Composable
internal fun OpeningPanel(modifier: Modifier = Modifier) {
    if (!OpeningDiagnostics.enabled) return
    val lines by OpeningDiagnostics.panel.collectAsStateWithLifecycle()
    if (lines.isEmpty()) return

    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.md)
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

/** Wide enough for the longest line without taking the picture. */
private val PANEL_MAX_WIDTH = 560.dp
