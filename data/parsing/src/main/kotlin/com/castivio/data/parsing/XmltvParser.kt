package com.castivio.data.parsing

import java.io.Reader

/**
 * Streaming XMLTV parser for the subset Castivio needs.
 *
 * XMLTV guides reach 100 MB with millions of `<programme>` elements, so a DOM
 * parse is out of the question. The platform pull parsers also differ between
 * the JVM (StAX) and Android (`XmlPullParser`), which would put the hottest
 * loop in the app on two different code paths.
 *
 * The approach here is deliberately boring: scan the stream for one element at
 * a time and parse that element's own text. Memory is bounded by the largest
 * single element — a kilobyte or two — not by the document. Element-at-a-time
 * is far less state than a general pull parser, which means far less to get
 * wrong on a hot path.
 *
 * **Scope note:** this reads XMLTV, not arbitrary XML. Namespaced elements or
 * DTD-defined entities would need a real parser; the streaming shape and the
 * performance budgets would be unchanged.
 */
object XmltvParser {

    private const val CHUNK = 1 shl 16

    fun parse(
        reader: Reader,
        onChannel: (XmltvChannel) -> Unit = {},
        onProgramme: (XmltvProgramme) -> Unit,
    ): XmltvStats {
        var channels = 0
        var programmes = 0

        forEachElement(reader, setOf("channel", "programme")) { tag, element ->
            when (tag) {
                "channel" -> {
                    val id = attribute(element, "id")
                    if (id != null) {
                        onChannel(
                            XmltvChannel(
                                id = id,
                                displayName = childText(element, "display-name") ?: id,
                                iconUrl = childAttribute(element, "icon", "src"),
                            )
                        )
                        channels++
                    }
                }
                "programme" -> {
                    val channel = attribute(element, "channel")
                    val start = attribute(element, "start")
                    if (channel != null && start != null) {
                        onProgramme(
                            XmltvProgramme(
                                channelId = channel,
                                title = childText(element, "title").orEmpty(),
                                description = childText(element, "desc"),
                                startMs = parseXmltvTime(start),
                                stopMs = attribute(element, "stop")?.let(::parseXmltvTime) ?: 0L,
                            )
                        )
                        programmes++
                    }
                }
            }
        }
        return XmltvStats(channels = channels, programmes = programmes)
    }

    /**
     * Streams [reader], invoking [onElement] with the full source text of each
     * element whose name is in [tags]. The window only ever holds the element
     * currently being assembled, so a 100 MB guide costs kilobytes.
     */
    private inline fun forEachElement(
        reader: Reader,
        tags: Set<String>,
        onElement: (String, String) -> Unit,
    ) {
        val chunk = CharArray(CHUNK)
        val window = StringBuilder(4096)
        var capturing: String? = null
        var pending = StringBuilder(0)

        while (true) {
            val read = reader.read(chunk)
            if (read <= 0) break
            window.append(chunk, 0, read)

            var progress = true
            while (progress) {
                progress = false
                val current = capturing
                if (current == null) {
                    // Look for the start of any element we care about.
                    var bestAt = -1
                    var bestTag: String? = null
                    for (tag in tags) {
                        val at = indexOfOpenTag(window, tag)
                        if (at >= 0 && (bestAt < 0 || at < bestAt)) { bestAt = at; bestTag = tag }
                    }
                    if (bestTag != null) {
                        window.delete(0, bestAt)          // drop everything before it
                        capturing = bestTag
                        pending = StringBuilder(512)
                        progress = true
                    } else {
                        // Keep only a small tail — an open tag may straddle chunks.
                        if (window.length > 64) window.delete(0, window.length - 64)
                    }
                } else {
                    val close = "</$current>"
                    val end = window.indexOf(close)
                    if (end >= 0) {
                        pending.append(window, 0, end + close.length)
                        window.delete(0, end + close.length)
                        onElement(current, pending.toString())
                        capturing = null
                        progress = true
                    } else {
                        // Self-closing element, e.g. <channel id="x"/>
                        val selfEnd = indexOfSelfClose(window)
                        if (selfEnd >= 0) {
                            pending.append(window, 0, selfEnd + 2)
                            window.delete(0, selfEnd + 2)
                            onElement(current, pending.toString())
                            capturing = null
                            progress = true
                        } else {
                            // Carry the partial element forward, minus a safe tail.
                            val keep = minOf(window.length, close.length + 2)
                            if (window.length > keep) {
                                pending.append(window, 0, window.length - keep)
                                window.delete(0, window.length - keep)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Index of `<tag` followed by whitespace, `>` or `/`, else -1. */
    private fun indexOfOpenTag(sb: StringBuilder, tag: String): Int {
        val needle = "<$tag"
        var from = 0
        while (true) {
            val at = sb.indexOf(needle, from)
            if (at < 0) return -1
            val after = at + needle.length
            if (after >= sb.length) return at   // may complete in the next chunk
            val c = sb[after]
            if (c == '>' || c == '/' || c == ' ' || c == '\t' || c == '\n' || c == '\r') return at
            from = at + 1
        }
    }

    /** Index of `/>` that closes the opening tag, ignoring quoted values. */
    private fun indexOfSelfClose(sb: StringBuilder): Int {
        var quote = ' '
        var i = 0
        while (i < sb.length - 1) {
            val c = sb[i]
            when {
                quote != ' ' -> if (c == quote) quote = ' '
                c == '"' || c == '\'' -> quote = c
                c == '/' && sb[i + 1] == '>' -> return i
                c == '>' -> return -1     // opening tag ended without self-closing
            }
            i++
        }
        return -1
    }

    /** Reads `key="value"` from an element's opening tag. */
    internal fun attribute(element: String, key: String): String? {
        val tagEnd = element.indexOf('>').let { if (it < 0) element.length else it }
        var from = 0
        while (true) {
            val at = element.indexOf(key, from)
            if (at < 0 || at > tagEnd) return null
            val before = at == 0 || element[at - 1].isWhitespace()
            var i = at + key.length
            while (i < element.length && element[i] == ' ') i++
            if (!before || i >= element.length || element[i] != '=') { from = at + key.length; continue }
            i++
            while (i < element.length && element[i] == ' ') i++
            if (i >= element.length) return null
            val q = element[i]
            if (q != '"' && q != '\'') return null
            val end = element.indexOf(q, i + 1)
            return if (end < 0) null else unescape(element.substring(i + 1, end))
        }
    }

    /** Text of the first `<child>…</child>`, CDATA and entities handled. */
    internal fun childText(element: String, child: String): String? {
        val open = indexOfChildOpen(element, child) ?: return null
        val contentStart = element.indexOf('>', open)
        if (contentStart < 0) return null
        if (element.getOrNull(contentStart - 1) == '/') return ""    // <title/>
        val end = element.indexOf("</$child>", contentStart)
        if (end < 0) return null
        var body = element.substring(contentStart + 1, end)
        val cdata = body.indexOf("<![CDATA[")
        if (cdata >= 0) {
            val close = body.indexOf("]]>", cdata)
            if (close >= 0) body = body.substring(cdata + 9, close)
        }
        return unescape(body).trim()
    }

    private fun childAttribute(element: String, child: String, key: String): String? {
        val open = indexOfChildOpen(element, child) ?: return null
        val end = element.indexOf('>', open)
        if (end < 0) return null
        return attribute(element.substring(open, end + 1), key)
    }

    private fun indexOfChildOpen(element: String, child: String): Int? {
        val needle = "<$child"
        var from = 0
        while (true) {
            val at = element.indexOf(needle, from)
            if (at < 0) return null
            val after = at + needle.length
            if (after >= element.length) return null
            val c = element[after]
            if (c == '>' || c == '/' || c.isWhitespace()) return at
            from = at + 1
        }
    }

    /**
     * XMLTV timestamps: `20260725083000 +0200` (offset optional).
     * Parsed arithmetically — a `SimpleDateFormat` here allocates a Calendar per
     * programme and would dominate the parse on a million-entry guide.
     */
    fun parseXmltvTime(value: String): Long {
        val s = value.trim()
        if (s.length < 14) return 0L
        fun num(from: Int, len: Int): Int {
            var v = 0
            for (i in from until from + len) {
                if (i >= s.length) return -1
                val c = s[i]
                if (c !in '0'..'9') return -1
                v = v * 10 + (c - '0')
            }
            return v
        }
        val year = num(0, 4)
        val month = num(4, 2)
        val day = num(6, 2)
        val hour = num(8, 2)
        val minute = num(10, 2)
        val second = num(12, 2)
        if (year < 0 || month < 1 || day < 1 || hour < 0 || minute < 0 || second < 0) return 0L

        var epochSeconds = daysFromCivil(year, month, day) * 86_400L +
            hour * 3600L + minute * 60L + second

        // Optional trailing " +0200" / "-0500".
        var sign = -1
        for (i in 14 until s.length) {
            if (s[i] == '+' || s[i] == '-') { sign = i; break }
        }
        if (sign > 0 && sign + 5 <= s.length) {
            val oh = num(sign + 1, 2)
            val om = num(sign + 3, 2)
            if (oh >= 0 && om >= 0) {
                val offset = oh * 3600L + om * 60L
                epochSeconds += if (s[sign] == '+') -offset else offset
            }
        }
        return epochSeconds * 1000L
    }

    /** Days since the Unix epoch — Howard Hinnant's algorithm, no Calendar. */
    private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
        var year = y.toLong()
        if (m <= 2) year -= 1
        val era = (if (year >= 0) year else year - 399) / 400
        val yoe = year - era * 400
        val mp = if (m > 2) m - 3 else m + 9
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** Resolves the five XML entities plus numeric references. */
    internal fun unescape(s: String): String {
        if (s.indexOf('&') < 0) return s        // the overwhelmingly common case
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                sb.append(c)
                i++
                continue
            }
            val name = s.substring(i + 1, semi)
            val replacement: String? = when (name) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> when {
                    name.startsWith("#x") || name.startsWith("#X") ->
                        name.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                    name.startsWith("#") ->
                        name.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                    else -> null
                }
            }
            if (replacement != null) {
                sb.append(replacement)
            } else {
                sb.append(s, i, semi + 1)
            }
            i = semi + 1
        }
        return sb.toString()
    }
}

data class XmltvChannel(val id: String, val displayName: String, val iconUrl: String?)

data class XmltvProgramme(
    val channelId: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val stopMs: Long,
)

data class XmltvStats(val channels: Int, val programmes: Int)
