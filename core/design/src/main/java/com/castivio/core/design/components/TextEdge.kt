package com.castivio.core.design.components

/**
 * Which edge of its box a text is set against when its own words may run the other way from the page.
 *
 * A page that follows the language's direction puts everything against the start edge -- the right, in Arabic -- and the
 * provider's words are not always in the page's language: an English name and an English plot on an Arabic page. Compose's
 * `TextAlign.Start` is the start of the *paragraph*, so an English paragraph on an Arabic page would sit against the
 * left, one edge for the page's own words and another for the provider's. This decides, from the text, which alignment
 * puts it on the page's start edge without turning the words round: an English paragraph keeps its order and its
 * punctuation and is set against the right.
 *
 * Pure -- the text and the page's direction are given -- so what is decided is asserted without a screen.
 */
internal object TextEdge {

    /**
     * Whether a text reads right to left, from its first letter that has a direction, or null when it has none --
     * figures, symbols and spaces only -- and so reads as the page does.
     *
     * Leading symbols, digits and the invisible marks that isolate a figure are not letters and are passed over, as the
     * platform's own "first strong" rule passes them.
     */
    fun readsRtl(text: String): Boolean? {
        var at = 0
        while (at < text.length) {
            val point = text.codePointAt(at)
            when (Character.getDirectionality(point)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                else -> Unit
            }
            at += Character.charCount(point)
        }
        return null
    }

    /**
     * Whether [text] has to be set against the END of its paragraph to stand on the page's start edge: its words run the
     * other way from the page. A text with no direction of its own reads as the page does, which is the start.
     */
    fun againstEnd(pageIsRtl: Boolean, text: String): Boolean {
        val rtl = readsRtl(text) ?: return false
        return rtl != pageIsRtl
    }
}
