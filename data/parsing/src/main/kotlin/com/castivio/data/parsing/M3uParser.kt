package com.castivio.data.parsing

/**
 * Streaming M3U parser.
 *
 * The signature is the design: entries are handed to [onEntry] one at a time
 * and **never collected**. A 100 MB playlist with 400,000 entries passes
 * through this parser in constant memory, which is the property the whole
 * catalogue architecture depends on. Returning `List<M3uEntry>` would be a
 * 200 MB allocation and an OOM on a low-end box — so the parser cannot do it.
 *
 * Attributes are scanned by hand rather than with a `Regex`. A regex per line
 * across 400,000 lines is roughly an order of magnitude slower and allocates a
 * `MatchResult` per entry; the benchmark suite gates against exactly that
 * regression.
 */
object M3uParser {

    private const val EXTINF = "#EXTINF:"
    private const val EXTGRP = "#EXTGRP:"

    /**
     * @param lines the playlist, read lazily. Callers must supply a streaming
     *   sequence (e.g. `BufferedReader.lineSequence()`), never a pre-read list.
     * @param onEntry invoked once per complete entry, in file order.
     * @return counts for logging and diagnostics.
     */
    fun parse(lines: Sequence<String>, onEntry: (M3uEntry) -> Unit): M3uStats {
        var parsed = 0
        var skipped = 0

        // Current entry being assembled. Held as locals, not objects, so an
        // incomplete entry costs nothing.
        var name: String? = null
        var tvgId: String? = null
        var tvgName: String? = null
        var logo: String? = null
        var group: String? = null
        var duration = -1
        var pendingGroup: String? = null

        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue

            when {
                line.startsWith(EXTINF) -> {
                    val body = line.substring(EXTINF.length)
                    duration = readDuration(body)
                    tvgId = attribute(body, "tvg-id")
                    tvgName = attribute(body, "tvg-name")
                    logo = attribute(body, "tvg-logo")
                    group = attribute(body, "group-title") ?: pendingGroup
                    name = displayName(body)
                }

                // #EXTGRP applies to following entries until the next one.
                line.startsWith(EXTGRP) -> pendingGroup = line.substring(EXTGRP.length).trim()

                // Any other directive is metadata we don't model yet.
                line.startsWith('#') -> Unit

                else -> {
                    val title = name
                    if (title == null) {
                        // A URL with no preceding #EXTINF — malformed but common.
                        skipped++
                    } else {
                        onEntry(
                            M3uEntry(
                                name = title,
                                url = line,
                                tvgId = tvgId,
                                tvgName = tvgName,
                                logoUrl = logo,
                                groupTitle = group,
                                durationSeconds = duration,
                            )
                        )
                        parsed++
                    }
                    name = null; tvgId = null; tvgName = null; logo = null
                    group = null; duration = -1
                }
            }
        }
        return M3uStats(parsed = parsed, skipped = skipped)
    }

    /** `-1 tvg-id="x" …,Display Name` → `-1`. */
    private fun readDuration(body: String): Int {
        var i = 0
        val negative = body.startsWith('-')
        if (negative) i = 1
        var value = 0
        var seen = false
        while (i < body.length) {
            val c = body[i]
            if (c in '0'..'9') {
                value = value * 10 + (c - '0'); seen = true; i++
            } else break
        }
        if (!seen) return -1
        return if (negative) -value else value
    }

    /**
     * Finds `key="value"` (or `key='value'`) without allocating a matcher.
     * Returns null when absent.
     */
    private fun attribute(body: String, key: String): String? {
        var from = 0
        while (true) {
            val at = body.indexOf(key, from)
            if (at < 0) return null
            // Must be preceded by whitespace so `tvg-name` never matches inside
            // another key, and followed by '='.
            val okBefore = at == 0 || body[at - 1] == ' ' || body[at - 1] == '\t'
            var i = at + key.length
            while (i < body.length && body[i] == ' ') i++
            if (!okBefore || i >= body.length || body[i] != '=') { from = at + key.length; continue }
            i++
            while (i < body.length && body[i] == ' ') i++
            if (i >= body.length) return null
            val quote = body[i]
            return if (quote == '"' || quote == '\'') {
                val end = body.indexOf(quote, i + 1)
                if (end < 0) null else body.substring(i + 1, end)
            } else {
                // Unquoted value: read to the next space or comma.
                var end = i
                while (end < body.length && body[end] != ' ' && body[end] != ',') end++
                body.substring(i, end).takeIf { it.isNotEmpty() }
            }
        }
    }

    /**
     * The display name is everything after the last comma that sits outside
     * quotes — logos and group titles routinely contain commas.
     */
    private fun displayName(body: String): String? {
        var quote = ' '
        var lastComma = -1
        for (i in body.indices) {
            val c = body[i]
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '"' || c == '\'' -> quote = c
                c == ',' -> lastComma = i
            }
        }
        if (lastComma < 0 || lastComma == body.length - 1) return null
        return body.substring(lastComma + 1).trim().takeIf { it.isNotEmpty() }
    }
}

/** One playlist entry, exactly as the file described it. */
data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val durationSeconds: Int = -1,
) {
    /** VOD entries carry a real duration; live is -1. */
    val isLive: Boolean get() = durationSeconds <= 0
}

data class M3uStats(val parsed: Int, val skipped: Int)
