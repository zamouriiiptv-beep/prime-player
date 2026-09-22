package com.castivio.core.design.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import java.text.BreakIterator

/**
 * One line that loses its **middle** rather than its end when it will not fit.
 *
 * ## What this exists to prevent
 *
 * A provider names a bouquet, not a channel. Sixty-one of them arrive as
 *
 * ```
 * US: CINEMANIA HOLLYWOOD PREMIERE 1
 * US: CINEMANIA HOLLYWOOD PREMIERE 2
 * …
 * US: CINEMANIA HOLLYWOOD PREMIERE 5
 * ```
 *
 * and a tail ellipsis keeps the twenty-three characters they all share and throws
 * away the two that tell them apart. Measured on the owner's handset — 2340×1080 at
 * density 2.8125, so 833×385dp — the name is given **186.8dp** of the list column's
 * 337.2dp, the rest going to the row's two 10dp insets, three 10dp gaps, a 30dp
 * number plate, a 26dp logo and an 18.5dp quality tag. At roughly 7.9dp per
 * upper-case character that is about twenty-three characters, which is precisely
 * the length of the shared prefix. Every row rendered the same string.
 *
 * Cutting the middle instead keeps both ends: about eleven characters of the family
 * name, the ellipsis, and the whole of `PREMIERE 5`.
 *
 * ## Why not the alternatives
 *
 * Detecting the shared prefix across a category and stripping it needs every name
 * in that category, and there is no `getAll()` — a category here holds 653 channels
 * and the catalogue targets 400,000. Rewriting the provider's name so the
 * distinctive part comes first needs a definition of "distinctive", and breaks
 * search, which matches what the provider actually wrote. Both were proposed and
 * withdrawn. This needs nothing but the one row's own text and the width it was
 * handed, so it is correct while scrolling, correct in search results, and correct
 * for a provider nobody has seen yet.
 *
 * ## How
 *
 * [BoxWithConstraints] because the width has to be known *before* the glyphs are
 * chosen: a text that measured itself afterwards would draw wrong for one frame,
 * and in a list that is a flicker on every row that scrolls in. The cost is one
 * subcomposition per row — twelve rows on the deepest surface — paid when a row is
 * composed, not on every frame.
 *
 * The cut point is then a binary search over how many grapheme clusters to remove
 * from the middle: about six measurements for a sixty-character name, against the
 * measurer's own layout cache. [elisions] holds the finished strings so a row
 * scrolled back into view does not repeat even that.
 *
 * Compose 1.8 does this natively with `TextOverflow.MiddleEllipsis`. This project is
 * on 1.7, and moving the bill of materials is a change that touches every screen in
 * the application, so it is its own commit on its own day; this file is deleted then.
 *
 * @param text the whole name, always. It is what a screen reader is given and what
 *   the search index matches, whatever is drawn.
 */
@Composable
fun CastivioMiddleEllipsisText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val resolver = LocalFontFamilyResolver.current

        // Every input that can change the answer is in the key, so a cached string can
        // never be stale: the text, the width it has to fit, the style that shapes it,
        // the density and font scale that size it, the direction that lays it out, and
        // the resolver that supplies the face.
        val key = ElisionKey(
            text = text,
            widthPx = constraints.maxWidth,
            style = style,
            density = density.density,
            fontScale = density.fontScale,
            direction = direction,
            resolver = resolver,
        )
        val shown = remember(key) { elide(key, measurer) }

        Text(
            text = shown,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            // The name that was cut is still the name. A reader hears all of it.
            modifier = Modifier.semantics { contentDescription = text },
        )
    }
}

/**
 * The cut itself, with measurement handed in.
 *
 * Separated from the composable because this is the part with the arithmetic in it
 * and Compose is the part that needs a device. [fits] answers one question — does
 * this candidate draw inside the width — and a test can answer it without a font.
 *
 * Binary search over `removed`, the number of grapheme clusters taken out of the
 * middle: a wider cut is never wider on screen, so the smallest cut that fits is the
 * one to draw. If even a bare ellipsis does not fit, a bare ellipsis is what is
 * returned and the row clips it, which is the honest picture of a slot that is
 * broken in a way no string can answer.
 */
internal fun middleElide(text: String, fits: (String) -> Boolean): String {
    if (text.isEmpty() || fits(text)) return text

    val cuts = graphemes(text)
    val total = cuts.size - 1
    var low = 1
    var high = total
    var best = ELLIPSIS

    while (low <= high) {
        val removed = (low + high) / 2
        val candidate = withMiddleRemoved(text, cuts, removed)
        if (fits(candidate)) {
            best = candidate
            high = removed - 1
        } else {
            low = removed + 1
        }
    }
    return best
}

/**
 * The string with `removed` clusters taken from the middle.
 *
 * The survivors are split evenly and the odd one goes to the head, which is what
 * the platform's own middle ellipsis does and what reads naturally: a line is
 * entered from its start. The tail is the half that carries the discriminator, and
 * an even split already guarantees it room.
 *
 * Whitespace either side of the join is dropped. `US: CINEMAN` + `…` + ` PREMIERE 5`
 * would otherwise draw a space that reads as part of the gap rather than part of a
 * word, and buys nothing back.
 */
private fun withMiddleRemoved(text: String, cuts: IntArray, removed: Int): String {
    val total = cuts.size - 1
    val kept = total - removed
    if (kept <= 0) return ELLIPSIS

    val head = (kept + 1) / 2
    val tail = kept - head
    val start = text.substring(0, cuts[head]).trimEnd()
    val end = if (tail == 0) "" else text.substring(cuts[total - tail]).trimStart()
    return start + ELLIPSIS + end
}

/**
 * Every position this string may be cut at, ends included.
 *
 * By cluster and not by `Char`, because a `Char` is half of an emoji and one third
 * of a flag, and a name cut through the middle of a surrogate pair renders as the
 * replacement glyph — a defect that would only ever appear on the providers that
 * decorate their names, which is most of them.
 *
 * The returned array is `clusters + 1` long: `[0, …, text.length]`.
 */
private fun graphemes(text: String): IntArray {
    val breaks = BreakIterator.getCharacterInstance()
    breaks.setText(text)
    val cuts = ArrayList<Int>(text.length + 1)
    cuts.add(breaks.first())
    var next = breaks.next()
    while (next != BreakIterator.DONE) {
        cuts.add(next)
        next = breaks.next()
    }
    return cuts.toIntArray()
}

/** One measurement, against the width this text was actually handed. */
private fun elide(key: ElisionKey, measurer: TextMeasurer): String {
    // An unbounded slot cannot overflow, so there is nothing to decide. This is a
    // horizontally scrolling parent, not a mistake.
    if (key.widthPx == Constraints.Infinity || key.widthPx <= 0) return key.text

    cached(key)?.let { return it }

    val shown = middleElide(key.text) { candidate ->
        measurer.measure(
            text = AnnotatedString(candidate),
            style = key.style,
            softWrap = false,
            maxLines = 1,
        ).size.width <= key.widthPx
    }
    store(key, shown)
    return shown
}

/**
 * What was drawn last time, for exactly these inputs.
 *
 * `remember` already keeps a row's answer while that row is composed; this keeps it
 * while the row is *not*. A list being flung recomposes the same twelve names over
 * and over as they leave and re-enter the viewport, and on a stick that has to hold
 * 60fps over a 400,000-item catalogue the cheapest measurement is the one that does
 * not happen.
 *
 * Access-ordered and bounded: the names in front of the viewer stay, the rest fall
 * out. Synchronised because a prefetching list may compose off the frame thread —
 * the cost of a duplicate computation is nothing, the cost of a corrupted map is a
 * crash.
 */
private val elisions = object : LinkedHashMap<ElisionKey, String>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ElisionKey, String>): Boolean =
        size > ELISION_CACHE
}

private fun cached(key: ElisionKey): String? = synchronized(elisions) { elisions[key] }

private fun store(key: ElisionKey, shown: String) {
    synchronized(elisions) { elisions[key] = shown }
}

/**
 * Everything that decides what a name looks like when it is cut.
 *
 * [resolver] has no `equals`, so it keys by identity, which is what is wanted: a new
 * resolver is a new set of faces and every answer taken against the old one is void.
 */
private data class ElisionKey(
    val text: String,
    val widthPx: Int,
    val style: TextStyle,
    val density: Float,
    val fontScale: Float,
    val direction: LayoutDirection,
    val resolver: FontFamily.Resolver,
)

/**
 * Twelve rows on the deepest surface, and a viewer flings through a few screens
 * before settling. Two hundred and fifty-six names is about twenty of those screens
 * and a few tens of kilobytes; the budget that matters here is the stick's gigabyte.
 */
private const val ELISION_CACHE = 256

/** The character, not three dots: one glyph, and the one a reader recognises. */
private const val ELLIPSIS = "…"
