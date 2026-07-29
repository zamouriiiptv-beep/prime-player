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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
) {
    val colors = CastivioTheme.colors
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }

    val border by animateColorAsState(
        when {
            !enabled -> colors.glassBorder
            error != null -> colors.danger
            focused -> colors.focusRing
            else -> colors.glassBorder
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
                .background(if (enabled) colors.glassFill else colors.glassFillStrong)
                .border(HAIRLINE, border, RoundedCornerShape(Radius.sm))
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty() && placeholder != null) {
                // Cleared from the semantics tree: the label already names the field,
                // and a reader that says the placeholder as well says it twice.
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
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
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
