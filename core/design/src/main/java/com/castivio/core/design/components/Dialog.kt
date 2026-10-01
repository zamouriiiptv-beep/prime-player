package com.castivio.core.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.graphics.Brush
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
    /**
     * Something to fill in between the message and the buttons, for the dialogs that
     * ask for a value rather than for a yes.
     *
     * Optional, and null for every caller that was here before this existed, so a
     * confirmation still draws exactly what it drew. A second dialog implementation
     * with a field in it would have been two scrims, two panels and two focus rules
     * agreeing only until one of them was edited.
     */
    field: (@Composable ColumnScope.() -> Unit)? = null,
    /**
     * How the confirming button is drawn.
     *
     * [ButtonWeight.Ghost] by default, and the note on the button below says why: on a
     * dialog guarding something irreversible, a filled affirmative is Castivio saying
     * "this is the thing to do" while the focus order says the opposite.
     *
     * A dialog that asks for a value rather than for a yes is not guarding anything —
     * saving a new name is as reversible as typing it again — and there the ghost reads
     * as a label beside an outlined Cancel, which inverts the hierarchy: the action the
     * dialog was opened for looks weaker than the way out of it. Such a caller passes
     * [ButtonWeight.Primary]. Every existing one passes nothing and is unchanged.
     *
     * A caller guarding something irreversible may also pass [ButtonWeight.Primary],
     * with [confirmFill] — see that parameter for why the argument above does not hold
     * once the fill is red.
     */
    confirmWeight: ButtonWeight = ButtonWeight.Ghost,
    /**
     * What the confirming button is painted with, when it is filled at all.
     *
     * Null is the default brush, which for [ButtonWeight.Primary] is the azure every
     * affirmative action in the app is drawn with, and which a ghost never reaches.
     *
     * The reason this exists is the one case the note above argues against: a red fill.
     * The objection to filling a destructive confirmation is that a fill is Castivio
     * saying *this is the thing to do* while the focus order says the opposite — and
     * that is an objection to the **azure**, which is the colour of approval. Red is
     * not. A red fill says what the button will cost, which is the same thing the
     * dialog's sentence says and the same thing the bin in the row behind it says, so
     * the emphasis and the warning point the same way for once.
     *
     * The focus rule is untouched by it: the safe button still takes focus, and a
     * remote's first press still cancels.
     */
    confirmFill: Brush? = null,
) {
    val colors = CastivioTheme.colors
    val safe = remember { FocusRequester() }

    // The safe action takes focus once, when the dialog appears. Not on every
    // recomposition: a user who has moved to Exit and is reading it should not
    // have the remote pulled back under them.
    LaunchedEffect(Unit) { runCatching { safe.requestFocus() } }

    DialogPanel(title = title, onScrim = onDismiss, modifier = modifier) { tight ->
        // **The sentence is what gives way, and it gives way entirely.**
        //
        // Measured, on the frame this ships to: a landscape handset is 393dp tall and
        // the keyboard takes 212 of them, so a rename dialog has 181dp to live in. What
        // it must show measures 178 — 24 of padding, 22 of title, 16 of rhythm, 68 for a
        // labelled field and 48 for the buttons. There is no arrangement of 181 that is
        // also 210, which is what the same panel measures with the sentence in it, so
        // the sentence goes. Not shrunk, not scrolled: gone, and back the moment the
        // keyboard is.
        //
        // `weight(1f, fill = false)` was already letting it compress to nothing, and
        // that was never enough — a child measured at zero still costs its share of the
        // column's rhythm, and the spacer above the field costs another. Removing it
        // outright is what buys the 24dp those two were holding.
        //
        // **Only when there is a field.** A confirmation's message is the question it
        // exists to ask, and a dialog with no field never has a keyboard in front of it
        // anyway. So every dialog that is not this one is drawn exactly as it was, in
        // every case, which is also what keeps the existing tests honest.
        val hideMessage = tight && field != null

        if (!hideMessage) {
            Text(
                text = message,
                style = CastivioType.bodyMedium,
                color = colors.onBackgroundVariant,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            )
        }

        if (field != null) {
            if (!hideMessage) Spacer(Modifier.height(Spacing.sm))
            field()
        }

        Row(
            // The column's own rhythm already separates this row from the field. The
            // extra step above it is breathing room, and it is the third thing the
            // keyboard takes — 8dp that the buttons would otherwise be clipped by.
            Modifier.padding(top = if (tight) 0.dp else Spacing.sm),
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
                // Ghost by default. The azure fill is Castivio saying "this is
                // the thing to do", and on a dialog guarding an irreversible
                // action it is saying the opposite of what the focus order
                // says. Ghost keeps it plainly available and plainly second.
                // See [confirmWeight] and [confirmFill] for the callers that differ.
                weight = confirmWeight,
                fill = confirmFill,
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

    // The body is the whole dialog, so it never gives way — the parameter is read and
    // deliberately ignored. A notice has no field and therefore no keyboard in front of
    // it; the only surface short enough to make it tight is one it already scrolls on.
    DialogPanel(title = title, onScrim = onClose, modifier = modifier) { _ ->
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
    /**
     * @param tight whether the surface left over is short enough that the panel has to
     *   drop something to fit on it. It is the measured height, not a question about the
     *   keyboard: what the content owes an answer to is how much room there is, and the
     *   keyboard is only the commonest reason for there being little.
     */
    content: @Composable ColumnScope.(tight: Boolean) -> Unit,
) {
    val colors = CastivioTheme.colors
    val tv = CastivioTheme.device.isTv

    // **The scrim is the window; the keyboard only moves the panel.**
    //
    // These were one box, with `imePadding()` on it, and that was wrong in a way a
    // photograph showed at once: padding the scrim *shrinks* the scrim, so it stopped
    // short of the keys and a band of the application's own backdrop stood lit between
    // the dialog and the keyboard. A modal that dims part of what is behind it is not
    // dimming anything.
    //
    // So the dim covers the whole window, and the inset is spent inside it, on the box
    // the panel is measured and centred in. Nothing new is drawn: it is the same one
    // scrim, no longer cut short.
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
                onClick = onScrim,
            )
            // Announced as a dialog, so a screen reader says so rather than
            // reading a heading that happens to be on top.
            .semantics(mergeDescendants = false) { dialog() },
    ) {
        BoxWithConstraints(
            // `enableEdgeToEdge` means the IME is drawn over the window rather than
            // shrinking it, so without this the panel centres itself in a height that is no
            // longer there and the field a dialog exists to offer sits behind the keys.
            // Here rather than outside, so `maxHeight` describes the space that is actually
            // free — which is what the panel's own cap is a fraction of — while the dim
            // above stays the size of the window.
            Modifier.fillMaxSize().imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            val m = dialogMetricsFor(maxWidth, maxHeight)
            val shape = RoundedCornerShape(m.radius)
            val tight = maxHeight < TIGHT

            Column(
                Modifier
                    .widthIn(max = m.width)
                    // Never taller than most of the screen. On the 360dp frame that
                    // is 288dp, which is the number that makes the notice scroll
                    // rather than push its own close button off the bottom.
                    //
                    // **Unless most of the screen is already gone.** The fifth of the height
                    // this gives back is breathing room, and breathing room is the first
                    // thing a keyboard takes: with the IME up the box above it is a couple
                    // of hundred dp, and spending a fifth of that on margin is spending it
                    // on nothing a user can see.
                    //
                    // Below [TIGHT] it reserves nothing at all. It kept a fixed 24dp back,
                    // which is 24dp the panel then overflowed by and had clipped off its
                    // own bottom — the buttons, measured: 178dp of content, 181dp of room,
                    // and a cap of 157. A margin is what is left over after the content
                    // fits, not something taken before it is measured.
                    .heightIn(
                        max = if (tight) maxHeight else maxHeight * PANEL_MAX_FRACTION,
                    )
                    .clip(shape)
                    .background(colors.backgroundElevated)
                    .border(BorderStroke(1.dp, colors.glassBorderSoft), shape)
                    // Presses inside the panel belong to the panel, not the scrim.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(if (tight) Spacing.md else m.padding),
                // The rhythm tightens with the room, for the reason the cap does.
                verticalArrangement = Arrangement.spacedBy(
                    if (tight) Spacing.sm else Spacing.md,
                ),
            ) {
                Text(
                    text = title,
                    // **Weight is the device's; only the size is the surface's.**
                    //
                    // This picks the same two tokens it always picked, and overwrites the
                    // one thing it is allowed to: the step. See [TITLE] for why the weight
                    // stayed behind.
                    style = (if (tv) CastivioType.headlineSmall else CastivioType.titleMedium)
                        .copy(
                            fontSize = m.title.value.sp,
                            lineHeight = (m.title.value * TITLE_LEADING).sp,
                        ),
                    color = colors.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
                content(tight)
            }
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

/**
 * Below this much free height the panel stops giving a fifth of it away.
 *
 * Not a device rule: it is the height actually left over, which on a handset in
 * landscape with the keyboard up is a fraction of the display. 320dp is under every
 * frame this ships to with nothing covering them — the shortest is 360 — so a dialog
 * with no keyboard in front of it is laid out exactly as it always was.
 */
private val TIGHT: Dp = 320.dp

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
 * The title's step — and **only** its step.
 *
 * The title was `if (tv) headlineSmall else titleMedium`, and those two tokens differ in
 * exactly three properties: `fontWeight`, `fontSize` and `lineHeight`. Two of the three
 * are a size, and a size chosen by the device's name is what this migration removes, so
 * they come off this share instead — bounded so both drawn steps come back exactly, 18dp
 * at 960×540 and 15 on every handset frame.
 *
 * The third is not a size, and it stays where it was. Collapsing the pair to one token
 * would have decided a **weight** by side effect: whichever token was kept, one of the
 * two devices would have had its dialog title re-weighted — semibold in the hand, or
 * medium across a room — and neither is a decision this migration was asked to make.
 * Typography is not in its remit, so the branch that selects the weight is left intact
 * and the branch that selected a size is gone. That is the whole distinction the phase
 * is about, in one expression.
 *
 * If the two weights should become one, that is a typography decision, taken on its own
 * and in its own commit, against both devices rather than as the residue of a refactor.
 */
private const val TITLE = 24f / 720f
private val TITLE_MIN: Dp = 15.dp
private val TITLE_MAX: Dp = 20.dp
private const val TITLE_LEADING = 1.44f
