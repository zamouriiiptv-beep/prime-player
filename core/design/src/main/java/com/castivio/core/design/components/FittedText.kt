package com.castivio.core.design.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * One line, at the largest size that fits the width it was actually given.
 *
 * ## What this exists to prevent
 *
 * `maxLines = 1` does not shrink a string that will not fit and it does not wrap
 * it — it **clips** it, with nothing on screen to say that it did. Two strings on
 * the identity screen cannot survive that:
 *
 * - **The address.** A frame is chosen by its height and then assumes a width,
 *   and the assumption breaks: a landscape handset near 660dp wide takes the
 *   shortest frame's numbers, which were drawn for 800, and the value box comes
 *   out about 130dp short. `3E:26:C1:EF:9` is not a smaller address, it is a
 *   different one, and it is read out to a provider and believed.
 * - **The title.** Its translations are not the same length within a factor of
 *   two: "Add a playlist" is 169dp where "Adicionar uma lista de reprodução" is
 *   428, and a header that lets the longer one take what it needs pushes the
 *   language control off the end of the row. That is a control disappearing to
 *   make room for a caption, which is the priority backwards.
 *
 * ## How
 *
 * The text is laid out; while it overflows its width it is re-laid at 94% of the
 * size, until it fits or reaches [FIT_FLOOR]. Nothing is painted until the size
 * settles, so the shrink is never a visible reflow from oversized to correct —
 * three or four passes at the worst case, all before the first frame.
 *
 * The floor is there so a pathologically narrow slot yields something too small
 * rather than something invisible. Below it, clipping is the lesser evil, and the
 * budget gates are what stop any drawn frame ever reaching it.
 *
 * Compose 1.8 does this natively with `BasicText(autoSize = …)`. This project is
 * on 1.7, so it is done by hand; this file is one line when the bill of materials
 * moves.
 *
 * @param spoken what a screen reader says instead. A MAC read character by
 *   character is punctuation, so the address hands over a spaced spelling.
 */
@Composable
fun CastivioFittedText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    var size by remember(text, style) { mutableStateOf(style.fontSize) }
    var settled by remember(text, style) { mutableStateOf(false) }

    Text(
        text = text,
        style = style.copy(fontSize = size),
        color = color,
        maxLines = 1,
        softWrap = false,
        textAlign = textAlign,
        onTextLayout = { result ->
            if (result.didOverflowWidth && size > FIT_FLOOR) {
                size *= FIT_STEP
            } else {
                settled = true
            }
        },
        modifier = modifier.drawWithContent { if (settled) drawContent() },
    )
}

/** Six percent a pass: fine enough not to overshoot, coarse enough to converge. */
private const val FIT_STEP = 0.94f

/** Below this the slot is broken in a way no font size can answer. */
private val FIT_FLOOR: TextUnit = 11.sp
