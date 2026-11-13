package com.castivio.data.parsing

/**
 * The identifiers a provider gives a film or a show in the catalogues of others -- TMDB's and IMDb's -- as panels write
 * them, read into the one shape the application keeps.
 *
 * What the panel sends is not one shape. A TMDB id is `603` as a number, `"603"` as a string, `""` or `0` or `"0"` for a title the
 * panel could not match, and sometimes something that is not an id at all in a field of the same name. An id kept wrongly is worse
 * than no id: it names *another* film, and a page that went by it would show that film's story. So the rule here is strict -- a
 * value is an id only if it is exactly one -- and everything else is "none", which the rest of the application already knows how
 * to live with.
 *
 * Pure Kotlin on purpose: it runs in the parser, which streams, and is proved without a device.
 */
internal object ExternalIds {

    /**
     * A TMDB id: a whole number above zero, written in digits and nothing else.
     *
     * **No decimal point is allowed, not even `.0`.** A field called `tmdb` is read for shows, and a panel that put a *rating*
     * there (`"8.0"`) would otherwise name TMDB's film number eight. A number that is too long to be an id, or too large to hold,
     * is none; so is a negative, a number with a sign, text, and a URL.
     */
    fun tmdb(raw: String?): Long? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty() || text.length > MAX_TMDB_DIGITS) return null
        if (!text.all { it in '0'..'9' }) return null
        return text.toLongOrNull()?.takeIf { it > 0L }
    }

    /**
     * An IMDb id: `tt` and then five to ten digits -- `tt0133093` -- in lower case whatever the panel wrote.
     *
     * The `tt` is required. Digits alone are not taken for one: a field that held a bare number may as well be IMDb's *rating*
     * or its vote count, and IMDb's own ids always carry the prefix.
     */
    fun imdb(raw: String?): String? {
        val text = raw?.trim()?.lowercase() ?: return null
        if (!text.startsWith(IMDB_PREFIX)) return null
        val digits = text.substring(IMDB_PREFIX.length)
        if (digits.length !in MIN_IMDB_DIGITS..MAX_IMDB_DIGITS) return null
        if (!digits.all { it in '0'..'9' }) return null
        return text
    }

    private const val IMDB_PREFIX = "tt"
    private const val MIN_IMDB_DIGITS = 5
    private const val MAX_IMDB_DIGITS = 10

    /** TMDB's ids are well under ten digits; twelve is already a number that is not one. */
    private const val MAX_TMDB_DIGITS = 12
}
