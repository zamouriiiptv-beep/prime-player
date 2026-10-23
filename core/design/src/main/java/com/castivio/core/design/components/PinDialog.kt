package com.castivio.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing

/**
 * A password asked for in digits: the words, how many have been typed, and a keypad.
 *
 * ## What it asks and what it does not know
 *
 * It collects [length] digits and hands them to [onComplete] the moment the last one is typed --
 * there is no OK to press -- and it has no opinion on whether they are right. The caller checks them
 * and answers by raising [attempt] with an [error], which clears the dots and says so, or by taking
 * the dialog away. A dialog that decided for itself would need the password, and the password is
 * not a design-system thing.
 *
 * ## Three ways in, one result
 *
 * A thumb taps the keypad. A remote walks it with the arrows and presses OK, or types on its number
 * keys without looking at it, which is how most people type a PIN on a television. A keyboard types.
 * The three end in the same [PinEntry], and none of them is shown the digits: the dots count and
 * never say.
 *
 * ## The keypad is the same in every language
 *
 * 1-2-3 left to right, as on every phone and cash machine, and the dots fill from the left. The
 * words beside it follow the language, so in Arabic the sentence is at the right and the keypad at
 * the left -- the dialog mirrors, the digits do not.
 *
 * ## Where it sits
 *
 * It is the app's own modal ([DialogPanel]) and not a second one. Beside the words when the panel is
 * wide enough to read them next to the keys, which is every landscape surface, because stacked the
 * four rows of keys would not leave a 360dp-tall handset its buttons. Under them when it is not.
 *
 * @param error what was wrong with the last attempt, or null. Raise [attempt] with it: the same
 *   sentence twice in a row is two refusals and the second must clear the dots too.
 * @param attempt changes whenever the caller refuses what was typed, and clears it.
 * @param progressLabel what a screen reader says for [entered] digits of [length] -- never which.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CastivioPinDialog(
    title: String,
    message: String,
    cancelLabel: String,
    deleteLabel: String,
    progressLabel: @Composable (entered: Int) -> String,
    onComplete: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 4,
    error: String? = null,
    attempt: Int = 0,
) {
    val colors = CastivioTheme.colors
    val tv = CastivioTheme.device.isTv

    // A new step -- a new title -- and a refused attempt both start from nothing.
    val entryState = remember(title, attempt) { mutableStateOf(PinEntry(length)) }
    val entry = entryState.value

    // **Remembered against the entry they change, and told the caller's latest answer.**
    //
    // These were local functions, passed to the keypad as references, and after the first refusal the
    // keypad went on calling the first entry's: a reference to a local function compares equal to the one
    // it replaced, so the keys were never given the new entry and every digit after a wrong password was
    // typed into one nobody was looking at. A lambda remembered against the entry itself is a new lambda
    // exactly when the entry is a new one -- and `rememberUpdatedState` is how it still reaches whatever
    // `onComplete` the caller has now.
    val latestComplete by rememberUpdatedState(onComplete)
    val typeDigit = remember(entryState) {
        { digit: Char ->
            val before = entryState.value
            val after = before.push(digit)
            entryState.value = after
            if (after.complete && !before.complete) latestComplete(after.digits)
        }
    }
    val deleteDigit = remember(entryState) { { entryState.value = entryState.value.pop() } }

    DialogPanel(
        title = title,
        onScrim = onDismiss,
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (val action = pinKeyAction(event.key)) {
                is PinKeyAction.Digit -> { typeDigit(action.value); true }
                PinKeyAction.Delete -> { deleteDigit(); true }
                null -> false
            }
        },
        maxFraction = PIN_PANEL_FRACTION,
        minPanel = PIN_PANEL_MIN,
    ) { _ ->
        val key = Sizing.minTarget(tv)
        val keyWidth = key * KEY_ASPECT
        val padWidth = keyWidth * 3 + KEY_GAP * 2

        // **The remote cannot leave it.** What is behind the scrim is still on screen and still focusable,
        // and an arrow past the keypad's edge would otherwise land on a channel row or a button of the
        // board -- one press from doing something to it. A group whose exits are cancelled keeps the
        // arrows inside the dialog; Back and Cancel are the ways out.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
        ) {
            val beside = pinBeside(available = maxWidth, keypad = padWidth)

            @Composable
            fun words() {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(
                        text = message,
                        style = CastivioType.bodyMedium,
                        color = colors.onBackgroundVariant,
                        // A sentence is cut before the Cancel under it is: the longest translation of
                        // these is three lines beside the keypad, and a fourth is room to spare.
                        maxLines = MESSAGE_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Dots(entry = entry, label = progressLabel(entry.count))
                    Text(
                        text = error.orEmpty(),
                        style = CastivioType.bodySmall,
                        color = colors.danger,
                        minLines = 1,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }

            @Composable
            fun cancel() = CastivioButton(
                text = cancelLabel,
                weight = ButtonWeight.Secondary,
                onClick = onDismiss,
            )

            @Composable
            fun pad() = Keypad(
                keyWidth = keyWidth,
                keyHeight = key,
                deleteLabel = deleteLabel,
                onDigit = typeDigit,
                onDelete = deleteDigit,
            )

            if (beside) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        words()
                        cancel()
                    }
                    pad()
                }
            } else {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    words()
                    pad()
                    cancel()
                }
            }
        }
    }
}

/** The dots: one for each digit asked for, filled for each one typed. They never show which. */
@Composable
private fun Dots(entry: PinEntry, label: String) {
    val colors = CastivioTheme.colors
    // Left to right in every language, because the digits are typed and read that way.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier
                .height(DOT_ROW)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    liveRegion = LiveRegionMode.Polite
                },
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(entry.length) { index ->
                val filled = index < entry.count
                Box(
                    Modifier
                        .size(DOT)
                        .clip(CircleShape)
                        .background(if (filled) colors.onBackground else Color.Transparent)
                        .border(1.5.dp, if (filled) colors.onBackground else colors.glassBorder, CircleShape),
                )
            }
        }
    }
}

/** 1-9, then a gap, 0 and delete -- the arrangement every phone has taught everybody. */
@Composable
private fun Keypad(
    keyWidth: Dp,
    keyHeight: Dp,
    deleteLabel: String,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(verticalArrangement = Arrangement.spacedBy(KEY_GAP)) {
            for (row in 0 until 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    for (column in 0 until 3) {
                        val digit = ('1' + row * 3 + column)
                        PinKey(
                            text = digit.toString(),
                            width = keyWidth,
                            height = keyHeight,
                            onClick = { onDigit(digit) },
                            // The middle of the keypad, so the remote is one arrow from any key and not in
                            // a corner of it.
                            startsFocused = digit == '5',
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                // Nothing under the 7: the keypad is a grid and the gap is part of it. A key there
                // that did nothing would be a target a remote can land on and a thumb can hit.
                Spacer(Modifier.size(keyWidth, keyHeight))
                PinKey(text = "0", width = keyWidth, height = keyHeight, onClick = { onDigit('0') })
                PinKey(
                    icon = true,
                    description = deleteLabel,
                    width = keyWidth,
                    height = keyHeight,
                    onClick = onDelete,
                )
            }
        }
    }
}

/**
 * One key: at the control floor in height, because a remote has to land on it, and a little wider
 * than that, because a digit is a thing a thumb aims at.
 *
 * Drawn as the board draws its own buttons -- glass at rest, the violet and the ring when the remote
 * is on it -- so the vocabulary is the one a viewer has already learned.
 */
@Composable
private fun PinKey(
    width: Dp,
    height: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: Boolean = false,
    description: String? = null,
    /**
     * Takes the remote when it appears.
     *
     * Asked of the key itself and not of the dialog: the keypad is laid out inside a measured box, which
     * composes its content a pass after the dialog does, so a request made from the dialog's own
     * composition reaches a key that is not there yet -- and focus is what carries a remote's number keys
     * to the dialog at all.
     */
    startsFocused: Boolean = false,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    if (startsFocused) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    val shape = RoundedCornerShape(Radius.sm)
    val ink = if (focused) colors.onSecondary else colors.onBackground

    Box(
        modifier
            .size(width, height)
            .focusRequester(requester)
            .castivioFocusScale(Motion.focusScaleButton, interaction)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clip(shape)
            .background(if (focused) colors.secondary else colors.glassFill)
            .border(1.dp, if (focused) colors.focusRing else colors.glassBorder, shape)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Backspace,
                contentDescription = description,
                tint = ink,
                modifier = Modifier.size(Sizing.iconMd),
            )
        } else {
            Text(text.orEmpty(), style = CastivioType.titleMedium, color = ink)
        }
    }
}

/**
 * The digits typed so far, and what typing and deleting do to them.
 *
 * Pure, so the rules of the entry -- it stops at [length], it takes only digits, it is empty after
 * the last delete -- are asserted without a screen.
 */
@Immutable
internal data class PinEntry(val length: Int, val digits: String = "") {

    val count: Int get() = digits.length

    /** Every digit asked for has been typed. */
    val complete: Boolean get() = digits.length >= length

    /** [digit] on the end, unless the entry is full or it is not a digit. */
    fun push(digit: Char): PinEntry =
        if (complete || digit !in '0'..'9') this else copy(digits = digits + digit)

    /** The last digit off, or the same empty entry. */
    fun pop(): PinEntry = if (digits.isEmpty()) this else copy(digits = digits.dropLast(1))
}

/** What a key on a remote or a keyboard means to the keypad. */
internal sealed interface PinKeyAction {
    data class Digit(val value: Char) : PinKeyAction
    data object Delete : PinKeyAction
}

/**
 * The number keys of a remote and the number row and pad of a keyboard are digits; backspace, delete
 * and clear take one away. Everything else is not the keypad's and is left to the key it is on.
 */
internal fun pinKeyAction(key: Key): PinKeyAction? = when (key) {
    Key.Zero, Key.NumPad0 -> PinKeyAction.Digit('0')
    Key.One, Key.NumPad1 -> PinKeyAction.Digit('1')
    Key.Two, Key.NumPad2 -> PinKeyAction.Digit('2')
    Key.Three, Key.NumPad3 -> PinKeyAction.Digit('3')
    Key.Four, Key.NumPad4 -> PinKeyAction.Digit('4')
    Key.Five, Key.NumPad5 -> PinKeyAction.Digit('5')
    Key.Six, Key.NumPad6 -> PinKeyAction.Digit('6')
    Key.Seven, Key.NumPad7 -> PinKeyAction.Digit('7')
    Key.Eight, Key.NumPad8 -> PinKeyAction.Digit('8')
    Key.Nine, Key.NumPad9 -> PinKeyAction.Digit('9')
    Key.Backspace, Key.Delete, Key.Clear -> PinKeyAction.Delete
    else -> null
}

/**
 * Whether the words go beside the keypad, in the room [available] that the panel leaves.
 *
 * When there is room for the keypad, a gap, and a line the words can be read in -- [WORDS_MIN].
 * Not a device rule: a landscape handset, a television and a tablet held either way all answer it
 * from their own width, and a handset held upright answers it the other way.
 */
internal fun pinBeside(available: Dp, keypad: Dp): Boolean = available >= keypad + Spacing.lg + WORDS_MIN

/** Share of the free height the password panel may take; see [DialogPanel]'s `maxFraction`. */
internal const val PIN_PANEL_FRACTION = 0.94f

/**
 * The panel's width where the surface has it: the keypad, a gap, a line the words are read in, and the
 * padding on both sides -- 210 + 16 + 200 + 2 x 27. It is what keeps the words beside the keypad on a
 * 640dp handset, where stacked they would not fit its 360dp height.
 */
internal val PIN_PANEL_MIN: Dp = 480.dp

/** A key is a third wider than it is tall: a digit is aimed at, and three of them still fit a thumb's reach. */
internal const val KEY_ASPECT = 1.4f

/** Between keys. The grid's own rhythm, and the one place it is set. */
private val KEY_GAP: Dp = Spacing.xs

/** The line the words are read in, at the narrowest it is allowed to be beside the keypad. */
internal val WORDS_MIN: Dp = 160.dp

private const val MESSAGE_LINES = 4

private val DOT: Dp = 14.dp
private val DOT_ROW: Dp = 28.dp
