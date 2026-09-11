package com.castivio.core.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Spacing
import com.castivio.core.design.theme.boundedFraction

/**
 * A question the user has to answer before anything else happens.
 *
 * ## Where the design comes from
 *
 * The language picker, which was Castivio's only modal until this existed and is
 * therefore the design: a full-bleed scrim that absorbs presses, a raised panel
 * on `backgroundElevated` with a soft hairline, and a corner that grows with the
 * surface — a panel that reads as generous across a room reads as bloated in the
 * hand, and the share it is cut with says that once instead of a table saying it
 * per device.
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
    val safe = remember { FocusRequester() }

    // The safe action takes focus once, when the dialog appears. Not on every
    // recomposition: a user who has moved to Exit and is reading it should not
    // have the remote pulled back under them.
    LaunchedEffect(Unit) { runCatching { safe.requestFocus() } }

    DialogPanel(title = title, onScrim = onDismiss, modifier = modifier) {
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

/**
 * A statement the user reads and closes, rather than a question they answer.
 *
 * The legal notice is the case this was built for, and it is the case that
 * decides the shape: a paragraph too long to draw permanently on a 360dp-tall
 * landscape phone, which still has to be readable in full, in every language,
 * with a remote.
 *
 * So the body **scrolls** and the panel is bounded to a fraction of the screen
 * rather than to a line count. A notice that fits is not scrolled and does not
 * look scrollable; the same notice in German on the shortest frame scrolls, and
 * on a television the D-pad reaches the body from the close button because the
 * body is focusable. A fixed line clamp would have been the same design with a
 * language-shaped hole in it.
 *
 * Everything else — the scrim, the panel, the corner radius, the hairline, the
 * focus rule — is [CastivioDialog]'s, shared through [DialogPanel] rather than
 * copied. Two modals that agree because one file draws them both.
 */
@Composable
fun CastivioNoticeDialog(
    title: String,
    body: String,
    closeLabel: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    val close = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { close.requestFocus() } }

    DialogPanel(title = title, onScrim = onClose, modifier = modifier) {
        Text(
            text = body,
            style = CastivioType.bodySmall,
            color = colors.onBackgroundVariant,
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                // Focusable so a remote can scroll it. Not focus-requested: the
                // close button is still what the dialog opens on, and a user who
                // only wants out should not have to travel to find the way.
                .focusable(),
        )

        Row(Modifier.padding(top = Spacing.sm)) {
            CastivioButton(
                text = closeLabel,
                weight = ButtonWeight.Secondary,
                onClick = onClose,
                modifier = Modifier.focusRequester(close).focusable(),
            )
        }
    }
}

/**
 * The scrim, the panel and the title — the parts every Castivio modal shares.
 *
 * Private, and the only place any of it is written. A second modal that drew its
 * own scrim would drift from this one the first time either was touched, and the
 * invariant script's "a shared component is declared once" exists because that
 * has already happened here.
 */
@Composable
private fun DialogPanel(
    title: String,
    onScrim: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CastivioTheme.colors

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(colors.scrim)
            // The scrim absorbs presses rather than letting them through to a
            // screen that is no longer answering. That is what makes this modal
            // instead of a decoration drawn over something still live.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onScrim,
            )
            // Announced as a dialog, so a screen reader says so rather than
            // reading a heading that happens to be on top.
            .semantics(mergeDescendants = false) { dialog() },
        contentAlignment = Alignment.Center,
    ) {
        val m = dialogMetricsFor(maxWidth, maxHeight)
        val shape = RoundedCornerShape(m.radius)

        Column(
            Modifier
                .widthIn(max = m.width)
                // Never taller than most of the screen. On the 360dp frame that
                // is 288dp, which is the number that makes the notice scroll
                // rather than push its own close button off the bottom.
                .heightIn(max = maxHeight * PANEL_MAX_FRACTION)
                .clip(shape)
                .background(colors.backgroundElevated)
                .border(BorderStroke(1.dp, colors.glassBorderSoft), shape)
                // Presses inside the panel belong to the panel, not the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(m.padding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = title,
                style = CastivioType.headlineSmall.copy(
                    fontSize = m.title.value.sp,
                    lineHeight = (m.title.value * TITLE_LEADING).sp,
                ),
                color = colors.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            content()
        }
    }
}

/**
 * What a modal is drawn from, for the surface it was handed.
 *
 * The four numbers that used to be `if (CastivioTheme.device.isTv)` — a panel width, its
 * padding, its corner and its title's step. They are a type of their own rather than four
 * locals so the claims worth gating can be asserted without an emulator: a panel that
 * still fits the shortest surface, a corner that stops growing, a title that never drops
 * under the step a dialog has to be read at across a room.
 */
@Immutable
internal data class DialogMetrics(
    val width: Dp,
    val padding: Dp,
    val radius: Dp,
    val title: Dp,
)

/** [DialogMetrics] for a measured surface. */
internal fun dialogMetricsFor(width: Dp, height: Dp): DialogMetrics = DialogMetrics(
    width = width.boundedFraction(PANEL, PANEL_MIN, PANEL_MAX),
    padding = width.boundedFraction(PANEL_PAD, PAD_MIN, PAD_MAX),
    radius = height.boundedFraction(PANEL_RADIUS, R_MIN, R_MAX),
    title = height.boundedFraction(TITLE, TITLE_MIN, TITLE_MAX),
)

/** Most of the screen, never all of it — a modal has to read as one. */
private const val PANEL_MAX_FRACTION = 0.8f

/* ------------------------------------------------------------------ the shares
 *
 * Read off the 1280×720 reference, which is the 960×540 television drawing at 4/3, so
 * a television reproduces the panel it was approved at exactly: 560 wide, 32 of
 * padding, a 30dp corner. Every other surface is interpolated between the bounds
 * instead of being handed the phone's copy of the table.
 */

/** Wide enough for two lines of a question, narrow enough to read as a dialog. */
private const val PANEL = 746.7f / 1280f
private val PANEL_MIN: Dp = 400.dp
private val PANEL_MAX: Dp = 560.dp

private const val PANEL_PAD = 42.7f / 1280f
private val PAD_MIN: Dp = 20.dp
private val PAD_MAX: Dp = 36.dp

/**
 * The panel's own corner, not the stage's.
 *
 * `CastivioMetrics.radius` is what a *card* on the stage is cut with — 14 to 28 — and a
 * modal is the largest surface in the product, so it takes the step above it. One share
 * with its own bounds rather than a second reading of somebody else's.
 */
private const val PANEL_RADIUS = 40f / 720f
private val R_MIN: Dp = 20.dp
private val R_MAX: Dp = 32.dp

/**
 * The title's step, and the one place this file changes a **typeface** decision.
 *
 * It was `if (tv) headlineSmall else titleMedium` — two tokens for one role, which is
 * a device table with a second thing wrong with it: the two differ in weight as well as
 * in size, so a dialog title was semibold across a room and medium in the hand for no
 * reason either token could state. One token now, at a bounded step, and the bounds are
 * chosen so both drawn sizes come back exactly: 18dp at 960×540 and 15 on every handset
 * frame. What moves is the phone's weight, medium to semibold — one dialog, one title.
 */
private const val TITLE = 24f / 720f
private val TITLE_MIN: Dp = 15.dp
private val TITLE_MAX: Dp = 20.dp
private const val TITLE_LEADING = 1.44f
