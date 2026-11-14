package com.castivio.tv.probe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castivio.core.design.components.ButtonWeight
import com.castivio.core.design.components.CastivioButton
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing
import com.castivio.core.design.theme.castivioBackdrop

/**
 * The probe's one screen: what is about to happen, four buttons, where the session is, and its report.
 *
 * A developer's instrument and drawn as one -- plain text in the design system's own inks, no decoration -- on the
 * text drawing the owner approved. It is English and left to right whatever the device's language, like the load
 * strip, and it is not translated.
 *
 * ## Its ground
 *
 * The design system's own backdrop, and **still**. The activity gives the theme `withBackground = false`, so there
 * is no clock to move it, and this asks for the backdrop by name rather than painting a flat colour: the same ground
 * as every other screen, drawn once and replayed. A measurement is not taken beside an animation that spends the
 * device's own processor and graphics on every frame.
 */
@Composable
internal fun ProbeScreen(model: ProbeViewModel) {
    val ui by model.ui.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val colors = CastivioTheme.colors

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .castivioBackdrop()
                .systemBarsPadding()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(text = TITLE, style = CastivioType.titleMedium, color = colors.onBackground)
            for (line in ui.headline) {
                Text(text = line, style = CastivioType.bodySmall, color = colors.onBackgroundVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CastivioButton(
                    text = START,
                    onClick = model::start,
                    modifier = Modifier.weight(1f),
                    weight = ButtonWeight.Primary,
                    enabled = ui.canStart,
                )
                CastivioButton(
                    text = CONTINUE,
                    onClick = model::resume,
                    modifier = Modifier.weight(1f),
                    weight = ButtonWeight.Primary,
                    enabled = ui.canContinue,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CastivioButton(
                    text = STOP,
                    onClick = model::stop,
                    modifier = Modifier.weight(1f),
                    enabled = ui.canStop,
                )
                CastivioButton(
                    text = COPY,
                    onClick = { clipboard.setText(AnnotatedString(ui.report)) },
                    modifier = Modifier.weight(1f),
                    enabled = ui.canCopy,
                )
            }
            Text(text = ui.progress, style = CastivioType.labelMedium, color = colors.onBackground)
            SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Text(
                    text = ui.report,
                    style = CastivioType.codeSmall,
                    color = colors.onBackgroundVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

/*
 * Not translated, and that is correct rather than lazy: this surface exists only in a debug build, is read by the
 * person who built it, and its content is a table of figures. See `CrashReportSheet` for the same reasoning.
 */
private const val TITLE = "Castivio Probe (debug)"
private const val START = "Start"
private const val CONTINUE = "Continue"
private const val STOP = "Stop"
private const val COPY = "Copy report"
