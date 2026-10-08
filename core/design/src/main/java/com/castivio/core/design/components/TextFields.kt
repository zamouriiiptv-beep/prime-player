package com.castivio.core.design.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Motion
import com.castivio.core.design.theme.Radius
import com.castivio.core.design.theme.Sizing
import com.castivio.core.design.theme.Spacing
import androidx.compose.ui.unit.dp

/** One device pixel at every density that matters; thinner reads as a rendering fault. */
private val HAIRLINE = 1.dp

/**
 * The one text field.
 *
 * Written once here rather than per form, because the four Xtream fields and the two
 * playlist fields must agree on height, focus ring, error placement and how the label
 * reads to a screen reader — and six copies of a field is six chances for one of them
 * to lose its error text.
 *
 * Three things it does that a stock field does not:
 *
 *  - **Focus is visible from across a room.** The border takes the focus ring colour
 *    rather than a thin underline, because this control has to be findable with a
 *    D-pad on a television at three metres.
 *  - **The error has a fixed place.** Text below the field, always in the same spot,
 *    so a form that grows an error does not shuffle the fields under the user's thumb.
 *  - **The label is announced with the value.** A screen reader reads "Server URL,
 *    http://…", and the error as an error rather than as more prose.
 */
@Composable
fun CastivioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    /** Shown under the field, and announced as an error. Null when the field is fine. */
    error: String? = null,
    /** Rendered beside the label. For "Optional", which is a fact about the field. */
    hint: String? = null,
    enabled: Boolean = true,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    /**
     * A glyph beside the label, saying what kind of thing the field holds.
     *
     * A glyph rather than a slot, because every one of these is the same drawing at
     * the same step in the same ink and a caller that could pass a composable could
     * pass a second control — which is what [trailing] is for.
     */
    labelIcon: ImageVector? = null,
    /**
     * The same glyph at the field's own leading edge, inside the box.
     *
     * Usually [labelIcon] again: the label is above the field and the glyph inside it
     * is what identifies the row once a user's eye is on the line they are typing in.
     * Separate parameters because a field may want one without the other — a password
     * says "password" above and draws nothing inside, where a URL wants both.
     */
    icon: ImageVector? = null,
    /**
     * A control at the field's trailing edge, inside its box.
     *
     * The playlist form needs a Paste beside a URL that a remote cannot realistically
     * be typed, and a control placed *after* the field is a second stop in the focus
     * order that a user has to pass the whole field to reach. Inside it, the two are
     * one row: the field, then the thing you do to it.
     *
     * It is a slot rather than a `Paste` parameter because the next field that needs
     * one will not need Paste — a Clear, a reveal, a unit. The caller brings its own
     * control and its own focus behaviour; the field only makes room.
     *
     * Null on every existing caller, which draws exactly what it drew.
     */
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }

    val border by animateColorAsState(
        when {
            // A dead field keeps the quiet edge a chip wears: it is not a surface
            // to type into any more, so it stops being outlined as one.
            !enabled -> colors.edgeQuiet
            error != null -> colors.danger
            focused -> colors.focusRing
            // The card family, because a field is a surface rather than a control.
            // It was `glassBorder` -- the edge a chip and a button also draw -- so a
            // field and the button under it were outlined identically and nothing in
            // the drawing said which one you type in.
            else -> colors.edgeCard
        },
        Motion.focusSpec(),
        label = "fieldBorder",
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (labelIcon != null) {
                Icon(
                    imageVector = labelIcon,
                    contentDescription = null, // the label beside it says it
                    tint = colors.onBackgroundMuted,
                    modifier = Modifier.size(Sizing.iconSm),
                )
            }
            Text(
                text = label,
                style = CastivioType.labelMedium,
                color = if (enabled) colors.onBackgroundVariant else colors.onBackgroundMuted,
            )
            if (hint != null) {
                Text(text = hint, style = CastivioType.labelSmall, color = colors.onBackgroundMuted)
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Sizing.minTouchTarget)
                .clip(RoundedCornerShape(Radius.sm))
                // **A well, not glass.** A field is the one surface in Castivio a
                // reader puts something *into*, and the surface ladder has a rung for
                // exactly that: `surfaceWell`, darker than the page, found by its lit
                // edge. Drawn as `surfaceGlass` it was the same material as the card
                // around it, so a form read as a panel with lines on it rather than as
                // a panel with places to type in it.
                //
                // Disabled drops back to the card's own glass: a field that cannot be
                // typed into is not a place, so it stops being drawn as one. That is
                // also the way round it belongs — it used to go the other way, to the
                // *brighter* 12% rung, which made a dead field the loudest thing in a
                // form.
                .background(if (enabled) colors.surfaceWell else colors.surfaceGlass)
                .border(HAIRLINE, border, RoundedCornerShape(Radius.sm))
                .padding(
                    start = Spacing.lg,
                    // A trailing control brings its own breathing room, so the box's
                    // own inset would be paid twice on that side.
                    end = if (trailing == null) Spacing.lg else Spacing.sm,
                    // Same again down the field. A trailing control is floored at the
                    // frame's own target -- 56dp on a remote -- so the full inset above
                    // and below it would make a field with a Paste in it 80dp tall
                    // against its neighbour's 72.
                    top = if (trailing == null) Spacing.md else Spacing.xs,
                    bottom = if (trailing == null) Spacing.md else Spacing.xs,
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null, // the label above it says it
                        tint = colors.onBackgroundMuted,
                        modifier = Modifier.size(Sizing.iconMd),
                    )
                }

                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) {
                        // Cleared from the semantics tree: the label already names the
                        // field, and a reader that says the placeholder as well says it
                        // twice.
                        Text(
                            text = placeholder,
                            style = CastivioType.bodyLarge,
                            color = colors.onBackgroundMuted,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }

                    CompositionLocalProvider(LocalTextStyle provides CastivioType.bodyLarge) {
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            enabled = enabled,
                            singleLine = true,
                            textStyle = CastivioType.bodyLarge.copy(
                                color = if (enabled) colors.onBackground else colors.onBackgroundMuted,
                            ),
                            cursorBrush = SolidColor(colors.focusRing),
                            visualTransformation =
                                if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = keyboardType,
                                imeAction = imeAction,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { onImeAction?.invoke() },
                                onGo = { onImeAction?.invoke() },
                                onNext = { onImeAction?.invoke() },
                            ),
                            interactionSource = interaction,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focused = it.isFocused }
                                .semantics {
                                    contentDescription = label
                                    if (error != null) this.error(error)
                                },
                        )
                    }
                }

                if (trailing != null) trailing()
            }
        }

        if (error != null) {
            Text(
                text = error,
                style = CastivioType.bodySmall,
                color = colors.danger,
                // Announced by the field itself; repeating it here would say it twice.
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}
