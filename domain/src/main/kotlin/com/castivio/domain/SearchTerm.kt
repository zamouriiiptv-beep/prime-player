package com.castivio.domain

/**
 * What a typed search asks for, once case, spacing and punctuation are not part of it.
 *
 * The words, lower-cased, joined by one space; empty when nothing searchable was typed.
 * A word is a run of letters and digits in any script -- the very rule the index's query
 * builder splits on, which is why `"  BeIN   Sports "`, `"bein sports"` and `"bein, sports!"`
 * all come out as `bein sports`.
 *
 * Folded here and not only inside the query builder so that the search is the same value
 * everywhere it is compared: a viewer who types a trailing space has not asked a new
 * question, and a new question is a new database stream.
 */
fun searchTermOf(typed: String): String {
    val raw = typed.lowercase()
    val out = StringBuilder(raw.length)
    var start = -1
    for (i in 0..raw.length) {
        val inWord = i < raw.length && raw[i].isLetterOrDigit()
        if (inWord) {
            if (start < 0) start = i
        } else if (start >= 0) {
            if (out.isNotEmpty()) out.append(' ')
            out.append(raw, start, i)
            start = -1
        }
    }
    return out.toString()
}
