package com.castivio.core.design.components

import android.view.WindowManager
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
 * ## It is a platform `Dialog`, and it took three rounds to admit it
 *
 * It was not, for the reason the deleted note gave: a separate window does not
 * inherit the immersive behaviour `MainActivity` sets up, so the system bars come
 * back for the lifetime of the dialog. True, and answered in [DialogPanel] in
 * four lines — while the thing drawing it in the main window was answering the
 * keyboard by arithmetic, and getting it wrong three times running. See
 * [DialogPanel] for what the window buys and what it costs.
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
     */
    confirmWeight: ButtonWeight = ButtonWeight.Ghost,
) {
    val colors = CastivioTheme.colors
    val safe = remember { FocusRequester() }

    // The safe action takes focus once, when the dialog appears. Not on every
    // recomposition: a user who has moved to Exit and is reading it should not
    // have the remote pulled back under them.
    LaunchedEffect(Unit) { runCatching { safe.requestFocus() } }

    DialogPanel(title = title, onScrim = onDismiss, modifier = modifier) { tight ->
        // **The message is the part that gives way, and only when it has to.**
        //
        // A question fits, and `fill = false` means it is laid out exactly as it always
        // was: the sentence yields before the control the dialog was opened for and
        // before the buttons that answer it, and only once there is nothing left to
        // yield from.
        //
        // On a tight surface it yields nothing, because the panel around it is
        // scrolling and a weight inside a scrolling column is a height measured
        // against infinity — which is not a layout, it is a crash. There the whole
        // panel gives way together, which is the point of it scrolling.
        Text(
            text = message,
            style = CastivioType.bodyMedium,
            color = colors.onBackgroundVariant,
            modifier = if (tight) {
                Modifier
            } else {
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            },
        )

        if (field != null) {
            Spacer(Modifier.height(Spacing.sm))
            field()
        }

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
                // Ghost by default. The primary fill is Castivio saying "this is
                // the thing to do", and on a dialog guarding an irreversible
                // action it is saying the opposite of what the focus order
                // says. Ghost keeps it plainly available and plainly second.
                // See [confirmWeight] for the one kind of caller that differs.
                weight = confirmWeight,
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

    DialogPanel(title = title, onScrim = onClose, modifier = modifier) { tight ->
        Text(
            text = body,
            style = CastivioType.bodySmall,
            color = colors.onBackgroundVariant,
            // Focusable so a remote can scroll it, whichever of the two is doing the
            // scrolling. Not focus-requested: the close button is still what the
            // dialog opens on, and a user who only wants out should not have to
            // travel to find the way.
            modifier = if (tight) {
                Modifier.focusable()
            } else {
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .focusable()
            },
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
 *
 * ## Why it is a window of its own
 *
 * Because the keyboard is the system's problem and the system already solves it.
 *
 * Drawn inside the application's window, a modal with a field in it has to work out
 * for itself how much of the screen the IME has taken and lift the panel by exactly
 * that much. That arithmetic was attempted three times. Each attempt was defensible
 * and each was wrong somewhere a photograph could see: the scrim padded and so cut
 * short of the keys; the scrim whole but the panel centred in a height the IME had
 * already claimed; the panel lifted correctly but the *screen* underneath shrunk too,
 * because `WindowInsets.safeDrawing` includes the keyboard and a screen was padding
 * with it.
 *
 * A dialog in its own window is told where the keyboard is by being resized around it.
 * There is no arithmetic left to get wrong — which is what the browser on the same
 * handset is doing, and why its dialog sat where ours did not.
 *
 * ## What the window costs, and the four lines that pay it
 *
 * A new window does not inherit `MainActivity`'s decor: not the hidden system bars,
 * not the transparent dim, not the resize behaviour. So this sets all three on it,
 * once, where they are visible together:
 *
 * - **the bars stay hidden**, with the same transient-by-swipe behaviour, or a clock
 *   and a battery meter appear across a television for the life of a confirmation;
 * - **the platform dim is switched off**, because Castivio draws its own scrim and two
 *   dims stacked is a darker modal than the one that was approved;
 * - **the window resizes for the keyboard** rather than being panned, which is the
 *   whole reason for the window. Panning would drag the scrim up with the panel and
 *   leave the application's own backdrop lit under it — the first bug, returned by a
 *   different door.
 *
 * The other cost is the locale. A popup window re-provides `LocalContext` and
 * `LocalConfiguration` from its own context, and this application has already shipped
 * a menu that rendered English inside an Arabic interface for exactly that reason. So
 * the three that decide what a string resolves to are captured outside the window and
 * put back inside it.
 */
@Composable
private fun DialogPanel(
    title: String,
    onScrim: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(tight: Boolean) -> Unit,
) {
    // Captured on this side of the window boundary. See the note above.
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val direction = LocalLayoutDirection.current

    Dialog(
        // Back is answered by the window that has focus, which is now this one. Every
        // caller's `BackHandler` sat in the window underneath and could not have been
        // reached anyway; each of them did what dismissing does, so this is the same
        // behaviour arriving by the shorter route.
        onDismissRequest = onScrim,
        properties = DialogProperties(
            // The content is the scrim, so it has to fill the window rather than be
            // centred in a platform-sized box inside it.
            usePlatformDefaultWidth = false,
            // And because it fills the window there is no "outside": the scrim below
            // owns that press, as it always has.
            dismissOnClickOutside = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let {
                it.setDimAmount(0f)
                // **The insets have to reach the composition.**
                //
                // A new window's decor fits system windows by default, which consumes
                // every inset before Compose sees it — including the keyboard's. The
                // panel below is lifted by `imePadding()`, and a consumed inset hands
                // it a zero: the photograph of that is a dialog centred in the whole
                // screen with its field and its buttons behind the keys, which is
                // exactly what the first build of this window did.
                //
                // Edge to edge instead, as `MainActivity` runs the window underneath,
                // and the lift is Compose's. The soft input mode is set as well for
                // the devices that still honour it; on anything modern it is ignored
                // and the inset is what does the work.
                WindowCompat.setDecorFitsSystemWindows(it, false)
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                // The insets controller needs a window that is attached to a display.
                // It is, on a device; it is not under Robolectric, where a dialog has
                // no view root, asking for one throws, and there are no system bars to
                // hide in the first place. Hiding the bars is decor, and decor that
                // cannot be applied must not take the dialog down with it.
                runCatching {
                    WindowCompat.getInsetsController(it, it.decorView).apply {
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
        }

        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalLayoutDirection provides direction,
        ) {
            DialogScrim(
                title = title,
                onScrim = onScrim,
                modifier = modifier,
                content = content,
            )
        }
    }
}

/**
 * The dim and the panel, once the window around them exists.
 *
 * The theme is read here rather than handed in: a dialog is a subcomposition of the
 * composition that opened it, so `CastivioTheme` crosses the window boundary on its
 * own. Only the three locals the platform *replaces* have to be carried across, and
 * they are carried above.
 */
@Composable
private fun DialogScrim(
    title: String,
    onScrim: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(tight: Boolean) -> Unit,
) {
    val colors = CastivioTheme.colors
    val tv = CastivioTheme.device.isTv

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
            // Belt and braces, and it costs nothing: the window is resized around the
            // keyboard, so this inset is zero and the modifier is a no-op. If a device
            // ever pans instead of resizing, it is the panel that moves and the scrim
            // that stays — which is the arrangement three rounds of photographs argued
            // for, kept for the day something honours the soft-input mode differently.
            Modifier.fillMaxSize().imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            val m = dialogMetricsFor(maxWidth, maxHeight)
            val shape = RoundedCornerShape(m.radius)

            // **Below this, the panel scrolls as one and stops doing arithmetic.**
            //
            // A rename dialog on a landscape handset with the keyboard up has about
            // 180dp to live in, and what it has to show measures 210: a title, an
            // explanation, a labelled field and two buttons, before a single token is
            // spent on margin. There is no share of 180 that is 210. Every version of
            // this that tried to find one gave way somewhere a photograph could see —
            // the buttons off the bottom, or the title off the top.
            //
            // So on a surface this short the panel is a scrolling sheet: it takes what
            // height there is, everything it was asked to show is still in it, and
            // reaching the last of it is a swipe rather than a redesign. Above the
            // threshold nothing changes at all — the panel is laid out exactly as it
            // always was, and [TIGHT] is under every frame this ships to with nothing
            // covering them.
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
                    .heightIn(
                        max = if (tight) {
                            (maxHeight - Spacing.sm * 2).coerceAtLeast(0.dp)
                        } else {
                            maxHeight * PANEL_MAX_FRACTION
                        },
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
                    // After the border and the ground, so those stay put and the
                    // contents move; before the padding, so the padding travels with
                    // them and the last line does not end flush against the edge.
                    .then(
                        if (tight) Modifier.verticalScroll(rememberScrollState()) else Modifier,
                    )
                    .padding(if (tight) Spacing.sm else m.padding),
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
 * Below this much free height the panel stops giving a fifth of it away, and scrolls.
 *
 * Not a device rule: it is the height actually left over, which on a handset in
 * landscape with the keyboard up is a fraction of the display. 320dp is under every
 * frame this ships to with nothing covering them — the shortest is 360 — so a dialog
 * with no keyboard in front of it is laid out exactly as it always was, and the
 * scrolling sheet is reached only by the case that needs it.
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
