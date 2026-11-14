package com.castivio.tv.probe.run

import com.castivio.data.parsing.XtreamUrls
import java.net.URI

/**
 * The account the probe asks, held in memory for the session and never written, logged or printed.
 *
 * The addresses are built by [XtreamUrls.api], the importer's own function, from the same three strings the
 * importer is given -- so what is asked is character for character what the application asks. `toString` says
 * nothing, so that no template or log line can put it in a message by accident; [secrets] is for [com.castivio.tv.probe.core.Redaction],
 * which removes them from a finished report.
 */
class ProbeCredentials(
    private val base: String,
    private val username: String,
    private val password: String,
) {
    private val normalised: String = XtreamUrls.normaliseBase(base)

    /** `http` or `https`: the only thing about the address that is ever printed. */
    val scheme: String
        get() = if (normalised.startsWith("https://", ignoreCase = true)) "https" else "http"

    fun categoriesUrl(): String = XtreamUrls.api(base, username, password, action = CATEGORIES_ACTION)

    /** The whole section in one reply: the streams action with no category. */
    fun wholeReplyUrl(): String = XtreamUrls.api(base, username, password, action = STREAMS_ACTION)

    fun categoryUrl(categoryId: String): String = XtreamUrls.api(
        base,
        username,
        password,
        action = STREAMS_ACTION,
        parameters = mapOf("category_id" to categoryId),
    )

    /** Everything that identifies the account, to be removed from a report. */
    fun secrets(): List<String> {
        val found = LinkedHashSet<String>()
        found += base.trim()
        found += normalised
        found += username
        found += password
        // And as the importer's own function writes them into an address. A space, a tilde or a star is not written
        // the way the platform's URL encoder writes it, and an address is what a failure's text is likeliest to carry.
        val query = XtreamUrls.api(base, username, password).substringAfter('?', "")
        for (part in query.split('&')) {
            val value = part.substringAfter('=', "")
            if (value.isNotEmpty()) found += value
        }
        val uri = runCatching { URI(normalised) }.getOrNull()
        val host = uri?.host
        if (host != null) found += host
        val authority = uri?.authority
        if (authority != null) found += authority
        return found.filter { it.isNotBlank() }
    }

    override fun toString(): String = "ProbeCredentials(redacted)"

    private companion object {
        const val CATEGORIES_ACTION = "get_vod_categories"
        const val STREAMS_ACTION = "get_vod_streams"
    }
}
