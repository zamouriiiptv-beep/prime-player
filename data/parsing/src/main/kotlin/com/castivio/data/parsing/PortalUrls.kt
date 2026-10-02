package com.castivio.data.parsing

/**
 * Where a Stalker or Ministra portal's API lives, from the address a user pasted.
 *
 * ## Why this is not [XtreamUrls.normaliseBase]
 *
 * An Xtream client is handed an origin and builds every path itself, so dropping
 * whatever the user typed after the host is right there. A portal is the opposite: the
 * path *is* the portal. `http://host/c/` and `http://host/stalker_portal/c/` are two
 * different installations on one machine, and an API URL built from the origin alone
 * reaches the first and never the second.
 *
 * So the path is kept, and only the parts that are the *player's* end of it are taken
 * off: the `/c/` the provider puts in a link because that is the web client's entry
 * point, and any API file the user happened to copy along with it.
 *
 * ## The endpoints are a list, in order
 *
 * There is no single answer. Stalker serves `server/load.php` under the portal root;
 * Ministra and most resellers also answer `portal.php` beside it; and a provider who
 * sent the bare origin of a `stalker_portal` installation is reachable only one level
 * down. Which of them a given box answers on cannot be known without asking, so the
 * caller asks in this order and stops at the first that replies like a portal.
 *
 * Ordered by what answers most often first, so the common case costs one request.
 */
object PortalUrls {

    /**
     * The portal's root: scheme, authority and the path it is installed at.
     *
     * Everything a client adds is removed — the trailing `/c`, an API file, a query
     * string — and nothing the provider chose is. A URL with no scheme gets `http`,
     * because that is what portals are handed out as and a user who types `host:8080`
     * means the same thing as the provider who wrote it down for them.
     */
    fun root(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return value
        if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) {
            value = "http://$value"
        }
        value = value.substringBefore('?').substringBefore('#').trimEnd('/')

        // The authority is never trimmed, whatever the segments below decide: the
        // shortest legitimate portal address is an origin, and a rule that could eat
        // into it would turn `http://host:8080` into `http://host`.
        val schemeEnd = value.indexOf("://") + 3
        val authorityEnd = value.indexOf('/', schemeEnd).takeIf { it > 0 } ?: value.length
        val authority = value.substring(0, authorityEnd)

        var path = value.substring(authorityEnd).trimEnd('/')
        // Peel one client-side segment at a time, and only from the end, so a portal
        // genuinely installed in a directory called `c` keeps it when something else
        // follows.
        var peeled = true
        while (peeled && path.isNotEmpty()) {
            peeled = false
            val last = path.substringAfterLast('/')
            if (last.lowercase() in CLIENT_SEGMENTS) {
                path = path.substring(0, path.length - last.length - 1)
                peeled = true
            }
        }
        return authority + path
    }

    /**
     * The API addresses to try, most likely first.
     *
     * A list rather than one string because a portal's API file is a fact about the
     * installation, not about the protocol, and the only way to learn it is to ask.
     * Callers try them in order and remember the one that answered — see the handshake,
     * which carries the endpoint forward so the rest of a session costs no searching.
     */
    fun endpoints(raw: String): List<String> {
        val root = root(raw)
        if (root.isEmpty()) return emptyList()
        val candidates = mutableListOf(
            "$root/server/load.php",
            "$root/portal.php",
        )
        // A provider who sent the bare origin of a Ministra installation: the portal is
        // one directory down and nothing in the address says so.
        if (!root.endsWith("/stalker_portal")) {
            candidates += "$root/stalker_portal/server/load.php"
        }
        return candidates
    }

    /**
     * One API call, as a URL.
     *
     * `JsHttpRequest=1-xml` is not optional and is not XML: it is the flag the portal's
     * own JavaScript client sends, and a portal that receives a request without it
     * replies with a rendered page instead of the JSON envelope every response here is
     * read as.
     */
    fun call(
        endpoint: String,
        type: String,
        action: String,
        parameters: Map<String, String> = emptyMap(),
    ): String = buildString {
        append(endpoint)
        append("?type=").append(encode(type))
        append("&action=").append(encode(action))
        for ((key, value) in parameters) {
            append('&').append(encode(key)).append('=').append(encode(value))
        }
        append("&JsHttpRequest=1-xml")
    }

    /**
     * The identity two portal subscriptions are told apart by.
     *
     * The root rather than what was typed, so `http://host/c/`, `host/c` and
     * `http://host/` are one subscription however the provider wrote it down — which is
     * the same rule, and the same reason, as the one `SourceIds` applies to an Xtream
     * account's host. Lower-cased because a host is case-insensitive and a user
     * retyping one should not acquire a second subscription for it.
     */
    fun identity(raw: String): String = root(raw).lowercase()

    /** What a portal's *client* adds to an address, and what a user copies with it. */
    private val CLIENT_SEGMENTS = setOf(
        "c",
        "portal.php",
        "load.php",
        "server",
        "index.html",
        "stalker_portal/c",
    )

    private fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (char.isLetterOrDigit() || char in "-_.~") append(char) else append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
        }
    }

    private const val HEX = "0123456789ABCDEF"
}
