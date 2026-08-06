package com.castivio.core.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.CastivioType
import com.castivio.core.design.theme.Radius
import java.util.Locale

/**
 * A dot and a sentence: the two ways Castivio states a condition without a box
 * around it.
 *
 * ## Why a dot and not a badge
 *
 * A filled pill with a word in it reads as a control, and both places this
 * appears sit next to real controls. A 6dp dot beside plain type says "this is
 * information" without any of the affordances of a button — nothing to press,
 * nothing to dismiss, and nothing that changes size when the state changes.
 *
 * ## Why these are shared
 *
 * The activation screen's trial chip and the licence screen's status chip are
 * the same object in the same corner of the same header, and a user moving
 * between the two screens has to find the same information in the same place.
 * Two declarations is how one of them acquires a border six months from now.
 */

/**
 * The header's condition, at the weight of body text.
 *
 * There was a second `value` slot here, for a count appended after the label,
 * and it is gone because the one caller that used it stopped: a trailing count
 * fixes the word order at "words then number", which is English's and not
 * Japanese's, Turkish's or Finnish's. The count now lives inside the sentence
 * and is emphasised where the language actually puts it, which is what
 * [emphasiseNumber] is for and why [label] is an [AnnotatedString].
 *
 * @param label the condition, as an [AnnotatedString] because one of the two
 *   callers emphasises a number inside its sentence rather than appending one.
 *   Plain text passes `AnnotatedString("…")`; there is deliberately no `String`
 *   overload, because a defaulted pair of them is the kind of ambiguity that
 *   resolves differently under a compiler upgrade.
 * @param dot the mark's fill. A [Brush] rather than a [Color] because the trial
 *   dot is the primary gradient — the same fill as the primary button, which is
 *   the whole reason it is a gradient and not a flat token near it. Solid tones
 *   pass [SolidColor].
 */
@Composable
fun StatusChip(
    label: AnnotatedString,
    dot: Brush,
    modifier: Modifier = Modifier,
) {
    val colors = CastivioTheme.colors
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(MARK_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(MARK)
                .clip(RoundedCornerShape(Radius.pill))
                .background(dot),
        )
        // Silver, not the tone. The dot carries the colour and the *sentence*
        // carries the meaning -- "Licence expired" and "Licence active" are
        // different words, which is the signal that survives colour blindness
        // and a monochrome screenshot. Colouring the label as well would add
        // nothing a reader could not already read.
        //
        // The one span that is not silver is the trial's numeral, which
        // `emphasiseNumber` tints -- a count, not a condition.
        Text(text = label, style = CastivioType.bodyMedium, color = colors.onBackgroundVariant)
    }
}

/**
 * The reserved line under a screen's controls.
 *
 * **Reserved, not inserted.** A line that appears pushes whatever is above it
 * upward at the instant the user presses something — and a control that moves
 * under the thumb that pressed it is a control that gets pressed twice. On a
 * television it moves out from under the focus ring, which is worse.
 *
 * @param height the frame's reserved height, kept whether or not there is
 *   anything to say.
 * @param text null when the state has nothing to add. The box stays.
 */
@Composable
fun StatusLine(
    height: Dp,
    text: String?,
    tone: Color,
    modifier: Modifier = Modifier,
    textColor: Color = tone,
) {
    Box(
        modifier
            .heightIn(min = height)
            // Announced when it changes rather than when focus reaches it: the
            // sentence is the answer to something the user just did.
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MARK_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(MARK)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(tone),
                )
                Text(
                    text = text,
                    // A step down from body text. This is a caption under a row
                    // of controls, not a paragraph.
                    style = CastivioType.labelMedium,
                    color = textColor,
                )
            }
        }
    }
}

/**
 * The count inside a localised sentence, one weight heavier than the words
 * around it.
 *
 * "**7**-day trial", "**3** days left". The number is the only part
 * anybody reads twice, so it carries the emphasis and the sentence stays quiet.
 * One `Text` with one span — not two composables with a gap, which breaks the
 * moment a language puts the numeral in the middle or at the end, and several
 * of the 37 do.
 *
 * **Found rather than assumed.** The plural has already been formatted by the
 * resource system in the interface's locale, and that locale may not use Western
 * digits — Arabic renders 7 as ٧. So the same number is formatted the same way
 * and looked up in the result, with the ASCII spelling as a fallback. If neither
 * is present the sentence is drawn unemphasised, which is the right failure: a
 * missing bold is invisible, a bold at the wrong offset is a typo.
 *
 * @param color an optional tint for the numeral alone. The trial badge passes
 *   the primary, which is where that colour used to live when the count was a
 *   separate [StatusChip] slot: the badge became one sentence and the accent had
 *   to come with it, or the chip would have lost its only colour to a rewording.
 */
fun emphasiseNumber(text: String, count: Int, color: Color? = null): AnnotatedString {
    val spellings = listOf(
        String.format(Locale.getDefault(), "%d", count),
        count.toString(),
    )
    val style = SpanStyle(
        fontWeight = FontWeight.SemiBold,
        color = color ?: Color.Unspecified,
    )
    return buildAnnotatedString {
        append(text)
        for (spelling in spellings) {
            val at = text.indexOf(spelling)
            if (spelling.isNotEmpty() && at >= 0) {
                addStyle(style, at, at + spelling.length)
                return@buildAnnotatedString
            }
        }
    }
}

/** Small enough to read as punctuation, large enough to carry a colour. */
private val MARK = 6.dp

/**
 * Between the mark and the sentence, and the same number in both of the two
 * places a mark appears.
 *
 * It was `Spacing.xs`, four dp, which is close enough to the 6dp mark's own
 * radius that the dot read as attached to the first letter rather than as a
 * separate piece of punctuation — most visibly in Arabic, where the sentence
 * starts on the other side and the dot has no ascender to lean against.
 *
 * Its own constant rather than `Spacing.sm`, which is also eight. The token
 * would carry this number away the next time somebody retunes the spacing
 * scale, and this gap is answerable to the mark's diameter — a fixed 6dp — and
 * not to the layout rhythm. Named here so the chip in the header and the line
 * under the controls cannot drift apart: that pairing is why they live in the
 * same file, and the drift is invisible until the two are seen at once.
 */
internal val MARK_GAP = 8.dp
