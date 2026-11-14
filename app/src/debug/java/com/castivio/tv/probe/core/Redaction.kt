package com.castivio.tv.probe.core

import java.net.URLEncoder

/**
 * The last guard before a report leaves the tool.
 *
 * The report is built from numbers and words and takes no address, user or password as an input, so none can
 * get into it by construction. This is the second line: every secret the tool holds, as it is and as it would be
 * written into a URL, is replaced wherever it appears in the finished text.
 *
 * A secret shorter than [MIN_LENGTH] is left alone: a two-letter user name replaced everywhere would damage the
 * report far more often than it could protect it.
 */
object Redaction {

    private const val MASK = "[redacted]"
    private const val MIN_LENGTH = 4

    fun scrub(text: String, secrets: Collection<String>): String {
        val candidates = LinkedHashSet<String>()
        for (secret in secrets) {
            val clean = secret.trim()
            if (clean.length < MIN_LENGTH) continue
            candidates += clean
            candidates += URLEncoder.encode(clean, "UTF-8")
        }
        var result = text
        // Longest first: a host inside an address must not be replaced before the address is.
        for (secret in candidates.sortedByDescending { it.length }) {
            result = result.replace(secret, MASK, ignoreCase = true)
        }
        return result
    }
}
