package com.castivio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.castivioBodyStyle
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Radius
import com.castivio.core.platform.DevDiagnostics
import com.castivio.core.platform.LoadTrace
import com.castivio.core.platform.PerfSection
import com.castivio.core.platform.StripTone

/**
 * **Temporary, for development.** The measurement of a section's load, as lines under the board on Home or over
 * the rows at the top of the section -- see [LoadTrace] for what each figure is and what it is counted from.
 *
 * Draws nothing unless there is a session to show, and there is none unless the instruments are on: a debuggable
 * build, and nothing else. [section] null is Home's, the session pressed last, shown after it ends as well, so its
 * reading stays to be read; a section's own shows only while its session runs ([runningOnly]).
 *
 * Always left to right: it is an instrument, written in Latin figures a photograph carries intact, and in a
 * right-to-left layout a line of figures reads in an order nobody wrote. Text comes ready from [LoadTrace], which
 * ticks while a session runs -- nothing is formatted here.
 */
@Composable
internal fun LoadStrip(
    section: PerfSection?,
    runningOnly: Boolean,
    fontSize: Dp,
    modifier: Modifier = Modifier,
) {
    // Off in every build that is not debuggable: nothing is collected, and nothing is drawn.
    if (!DevDiagnostics.enabled) return
    val lines by remember(section, runningOnly) { LoadTrace.lines(section, runningOnly) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    if (lines.isEmpty()) return

    val colors = CastivioTheme.colors
    val shape = RoundedCornerShape(Radius.md)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.glassFill)
                .border(1.dp, colors.glassBorderSoft, shape)
                .padding(horizontal = fontSize, vertical = fontSize / 2),
        ) {
            lines.forEach { line ->
                Text(
                    text = line.text,
                    style = castivioBodyStyle(fontSize),
                    color = when (line.tone) {
                        StripTone.RUNNING -> colors.warning
                        StripTone.DONE -> colors.success
                        StripTone.FAILED -> colors.danger
                        StripTone.MUTED -> colors.onBackgroundMuted
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
