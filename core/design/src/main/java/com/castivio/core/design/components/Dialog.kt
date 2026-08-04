package com.castivio.core.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Spacing

/**
 * A question the user has to answer before anything else happens.
 *
 * ## Where the design comes from
 *
 * The language picker, which was Castivio's only modal until this existed and is
 * therefore the design: a full-bleed scrim that absorbs presses, a raised panel
 * on `backgroundElevated` with a soft hairline, and the same corner radius the
 * picker uses — one step larger on a television, because a panel that reads as
 * generous across a room reads as bloated in the hand.
 *
 * This is the general form of that, so the next modal is a call rather than a
 * second opinion about scrims.
 *
 * ## The safe action is the default
 *
 * [confirmLabel] is the consequential one and [dismissLabel] is the safe one, and
 * **focus starts on the safe one**. On a television that matters more than
 * anywhere else: the remote's centre key is under the user's thumb when the
 * dialog appears, and a confirm button under it turns one stray press into the
 * action the dialog exists to guard against.
 *
 * The two are ordered dismiss-then-confirm for the same reason — reading order
 * and focus order agree, in both text directions, because the row is laid out
 * with `start`/`end` and mirrors.
 *
 * ## It is not a platform `Dialog`
 *
 * No `androidx.compose.ui.window.Dialog`. That gets its own window, which on this
 * app means a window without the edge-to-edge flags and without the immersive
 * behaviour the screens underneath set up — the system bars come back for the
 * lifetime of the dialog and the composition visibly jumps. Drawn in the same
 * window, it inherits all of that and costs nothing.
 *
 * @param onDismiss the safe way out. Called by the dismiss button and by a press
 *   on the scrim. **Back is the caller's**, because only the caller knows what
 *   else back might mean on that screen — see the exit dialog for the shape.
 */
@Composable
fun CastivioDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val tv = CastivioTheme.device.isTv
    val shape = RoundedCornerShape(if (tv) Radius.xxl else Radius.xl)
    val safe = remember { FocusRequester() }

    // The safe action takes focus once, when the dialog appears. Not on every
    // recomposition: a user who has moved to Exit and is reading it should not
    // have the remote pulled back under them.
    LaunchedEffect(Unit) { runCatching { safe.requestFocus() } }

    Box(
        modifier
            .fillMaxSize()
            .background(colors.scrim)
            // The scrim absorbs presses rather than letting them through to a
            // screen that is no longer answering. That is what makes this modal
            // instead of a decoration drawn over something still live.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            // Announced as a dialog, so a screen reader says so rather than
            // reading a heading that happens to be on top.
            .semantics(mergeDescendants = false) { dialog() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = if (tv) TV_WIDTH else PHONE_WIDTH)
                .clip(shape)
                .background(colors.backgroundElevated)
                .border(BorderStroke(1.dp, colors.glassBorderSoft), shape)
                // Presses inside the panel belong to the panel, not the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(if (tv) TV_PADDING else PHONE_PADDING),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = title,
                style = if (tv) CastivioType.headlineSmall else CastivioType.titleMedium,
                color = colors.onBackground,
            )
            Text(
                text = message,
                style = CastivioType.bodyMedium,
                color = colors.onBackgroundVariant,
            )

            Row(
                Modifier.padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                CastivioButton(
                    text = dismissLabel,
                    weight = ButtonWeight.Secondary,
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(safe).focusable(),
                )
                CastivioButton(
                    text = confirmLabel,
                    // Not Primary. The primary fill is Castivio saying "this is
                    // the thing to do", and on a dialog guarding an irreversible
                    // action it is saying the opposite of what the focus order
                    // says. Ghost keeps it plainly available and plainly second.
                    weight = ButtonWeight.Ghost,
                    onClick = onConfirm,
                )
            }
        }
    }
}

/** Wide enough for two lines of a question, narrow enough to read as a dialog. */
private val PHONE_WIDTH: Dp = 420.dp
private val TV_WIDTH: Dp = 560.dp

private val PHONE_PADDING: Dp = Spacing.xl
private val TV_PADDING: Dp = Spacing.xxl
