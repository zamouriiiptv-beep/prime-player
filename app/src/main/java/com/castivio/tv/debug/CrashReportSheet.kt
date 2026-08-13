package com.castivio.tv.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import com.castivio.tv.BuildConfig

/**
 * The last crash, shown once, on the launch after it happened.
 *
 * ## What this is for and what it is not
 *
 * It is a diagnostic surface for a debug build, so it is deliberately plain: the whole
 * value is the frames, and dressing them up would cost space that the frames need. It is
 * not a product screen and it does not appear in a release build — the composable returns
 * immediately when `BuildConfig.DEBUG` is false, and the file it reads is never written
 * there either.
 *
 * ## Copy, because reading a stack trace aloud is not a plan
 *
 * The one affordance beyond dismissing it. A tester with a phone and no cable can copy the
 * text and paste it into a message, which is the entire distance between "the app closed"
 * and a fix.
 */
@Composable
internal fun CrashReportSheet() {
    if (!BuildConfig.DEBUG) return

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var report by remember { mutableStateOf(CrashReport.last(context)) }
    val text = report ?: return

    val colors = CastivioTheme.colors

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .padding(CastivioTheme.device.screenPadding),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(Radius.lg))
                .background(colors.backgroundElevated)
                .border(1.dp, colors.glassBorder, RoundedCornerShape(Radius.lg))
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = TITLE,
                style = CastivioType.titleMedium,
                color = colors.onBackground,
            )
            Text(
                text = text,
                style = CastivioType.codeSmall,
                color = colors.onBackgroundVariant,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PlainButton(COPY) { clipboard.setText(AnnotatedString(text)) }
                PlainButton(DISMISS) {
                    CrashReport.clear(context)
                    report = null
                }
            }
        }
    }
}

@Composable
private fun PlainButton(label: String, onClick: () -> Unit) {
    val colors = CastivioTheme.colors
    Box(
        Modifier
            .defaultMinSize(
                minWidth = Sizing.minTarget(CastivioTheme.device.isTv),
                minHeight = Sizing.minTarget(CastivioTheme.device.isTv),
            )
            .clip(RoundedCornerShape(Radius.pill))
            .background(colors.glassFillStrong)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = CastivioType.labelMedium, color = colors.onBackground)
    }
}

/*
 * Not translated, and that is correct rather than lazy.
 *
 * This surface exists only in a debug build and its content is a Java stack trace. Putting
 * three strings into 39 bundles so that a developer diagnostic reads well in Vietnamese
 * would be work spent on something no user will ever see, and `check-invariants.sh` counts
 * translations of *user-visible* strings for exactly that reason.
 */
private const val TITLE = "Castivio stopped last time"
private const val COPY = "Copy"
private const val DISMISS = "Dismiss"
