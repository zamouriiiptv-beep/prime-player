package com.castivio.data.database

/**
 * Turns what a user typed into an FTS4 MATCH expression.
 *
 * This is a sanitiser before it is a query builder. FTS4 MATCH has its own
 * syntax — `"`, `*`, `^`, `-`, `:`, `(`, `)`, `NEAR`, `OR`, `AND` — and users
 * type film titles, not query expressions. Passing raw input through means
 * `Mission: Impossible` throws a syntax error and the results list goes empty on
 * a perfectly reasonable search.
 *
 * Search runs on every keystroke, so this is also on a hot path: it does one
 * pass over the input with no regex and no intermediate lists.
 */
object FtsQuery {

    /** Below this, prefix matching returns most of the catalogue — not useful. */
    const val MIN_TERM_LENGTH = 1

    /**
     * @return a MATCH expression, or null when there is nothing searchable.
     *   Null means "show the idle state", not "show zero results" — an important
     *   difference while a user is still typing.
     */
    fun build(input: String): String? {
        if (input.isEmpty()) return null

        // Folded here to match `media.search_text`, which is folded at import.
        // SQLite's own `lower()` is ASCII-only and the FTS tokenizer does not
        // case-fold non-Latin scripts, so both sides have to be folded in Kotlin
        // or "новости" would never find "Новости".
        val raw = input.lowercase()
        val out = StringBuilder(raw.length + 8)
        var termStart = -1
        var terms = 0

        // Each token becomes a prefix term: "nov spo" matches "Nova Sports".
        // Prefix matching is what makes search feel instant while typing — the
        // user should not have to finish a word.
        fun flush(end: Int) {
            if (termStart < 0) return
            val length = end - termStart
            if (length >= MIN_TERM_LENGTH) {
                if (terms > 0) out.append(' ')
                out.append(raw, termStart, end).append('*')
                terms++
            }
            termStart = -1
        }

        for (i in raw.indices) {
            if (raw[i].isSearchable()) {
                if (termStart < 0) termStart = i
            } else {
                flush(i)
            }
        }
        flush(raw.length)

        return if (terms == 0) null else out.toString()
    }

    /**
     * Letters and digits only.
     *
     * Everything else is a separator rather than something to escape: an
     * apostrophe, colon or hyphen inside a title is not a search operator the
     * user meant, and treating it as a word break is what they expect
     * ("Mission: Impossible" → `mission* impossible*`).
     *
     * `isLetterOrDigit` is Unicode-aware, so Arabic, Cyrillic and CJK titles
     * tokenise the same way Latin ones do.
     */
    private fun Char.isSearchable(): Boolean = isLetterOrDigit()
}
