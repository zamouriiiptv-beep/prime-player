package com.castivio.data.parsing

import java.io.Reader

/**
 * A streaming JSON reader, written for the same reason the M3U and XMLTV parsers
 * are hand-rolled.
 *
 * An Xtream category can hold twenty thousand streams, and `get_vod_streams`
 * for a large provider is tens of megabytes. `JSONObject` and `JSONArray` parse
 * the whole document into memory first, which is exactly the allocation this app
 * exists to avoid; `android.util.JsonReader` would work but is Android-only,
 * which would move the hottest parsing in the app off the JVM and out of the
 * per-commit benchmarks.
 *
 * The shape is callback-driven rather than a general pull API, because that is
 * what makes it hard to misuse: a field whose value the caller does not read is
 * skipped automatically, so a provider adding a nested object to a response can
 * never desynchronise the scan.
 *
 * ```
 * scanner.readArray {
 *     scanner.readObject { name ->
 *         when (name) {
 *             "category_id" -> id = scanner.string()
 *             "category_name" -> title = scanner.string()
 *             // everything else is skipped for us
 *         }
 *     }
 * }
 * ```
 *
 * Lenient on purpose: Xtream servers return numbers as strings, strings as
 * numbers, `null` where a string is expected and `"0"` where a boolean belongs.
 * Being strict here would mean failing to import from real providers.
 */
class JsonScanner(private val source: Reader) {

    private val buffer = CharArray(BUFFER)
    private var length = 0
    private var position = 0

    /** True while the value for the current field has not been consumed. */
    private var valuePending = false

    private val text = StringBuilder(64)

    /**
     * The member names this scanner has already made into strings, so an array of fifty thousand objects with the
     * same fourteen keys makes fourteen strings and not seven hundred thousand. Small, open and forgetful: a name
     * that finds its place taken replaces what was there, and the cost of that is one string made again.
     */
    private val names = arrayOfNulls<String>(NAME_SLOTS)

    /**
     * Reads an object, invoking [onField] once per member with its name. The
     * scanner is positioned at the member's value; consume it with [string],
     * [long], [int], [boolean], [readObject] or [readArray], or ignore it and it
     * will be skipped.
     */
    fun readObject(onField: (name: String) -> Unit) {
        val asMember = valuePending
        valuePending = false
        skipWhitespace()
        // `null` in place of an object — providers do this — and, *inside* an object,
        // anything else that is not one.
        //
        // The distinction is the whole of it. A member's value is the provider's to
        // get wrong: a Stalker account with nothing to say answers `"js":false`, an
        // empty one `"js":[]`, and both mean "no object here" rather than "this
        // document is broken". A *document* that does not start with an object is a
        // different event — an HTML error page served with a 200, which has to reach
        // the caller as a parse failure and not as an empty result. So the tolerance
        // stops at the top level, where `XtreamParserTest` holds it.
        if (peek() == 'n' || (asMember && peek() != '{')) {
            skip()
            return
        }
        expect('{')
        skipWhitespace()
        if (peek() == '}') {
            advance()
            return
        }
        while (true) {
            skipWhitespace()
            val name = readName()
            skipWhitespace()
            expect(':')
            valuePending = true
            onField(name)
            if (valuePending) skip()
            skipWhitespace()
            when (val c = read()) {
                ',' -> Unit
                '}' -> return
                else -> error("expected , or } but found '$c'")
            }
        }
    }

    /** Reads an array, invoking [onElement] once per element. */
    fun readArray(onElement: () -> Unit) {
        val asMember = valuePending
        valuePending = false
        skipWhitespace()
        // The same rule [readObject] states, for the same reason: `"data":false` is how
        // more than one portal says it has no channels, while a document that is not an
        // array at all is a failure the caller has to hear about.
        if (peek() == 'n' || (asMember && peek() != '[')) {
            skip()
            return
        }
        expect('[')
        skipWhitespace()
        if (peek() == ']') {
            advance()
            return
        }
        while (true) {
            valuePending = true
            onElement()
            if (valuePending) skip()
            skipWhitespace()
            when (val c = read()) {
                ',' -> Unit
                ']' -> return
                else -> error("expected , or ] but found '$c'")
            }
        }
    }

    /**
     * The current value as a string.
     *
     * Numbers and booleans are accepted and returned verbatim — a provider that
     * sends `"stream_id": 1234` and one that sends `"stream_id": "1234"` must
     * both import. `null` and `""` both come back as null, because an empty
     * string in an Xtream response means "absent".
     */
    fun string(): String? {
        valuePending = false
        skipWhitespace()
        return when (peek()) {
            '"' -> readQuoted().takeIf { it.isNotEmpty() }
            'n' -> { skipLiteral(); null }
            '{', '[' -> { skip(); null }
            else -> readRawToken().takeIf { it.isNotEmpty() && it != "null" }
        }
    }

    fun long(): Long {
        valuePending = false
        skipWhitespace()
        val digits = digitsInBuffer()
        if (digits != NOT_DIGITS) return digits
        return string()?.let { raw ->
            raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
        } ?: 0L
    }

    fun int(): Int = long().toInt()

    /** `true`, `1` and `"1"` are all true, which is what providers actually send. */
    fun boolean(): Boolean {
        valuePending = false
        skipWhitespace()
        // A whole number, written either way, is true unless it is zero -- which is what the lines below say of
        // "1" and of any other number, without making a string of it first.
        val digits = digitsInBuffer()
        if (digits != NOT_DIGITS) return digits != 0L
        return when (val raw = string()?.lowercase()) {
            null -> false
            "true", "1", "yes" -> true
            else -> raw.toDoubleOrNull()?.let { it != 0.0 } ?: false
        }
    }

    /** Skips the current value, however deeply nested. */
    fun skip() {
        valuePending = false
        skipWhitespace()
        when (peek()) {
            '{' -> skipContainer('{', '}')
            '[' -> skipContainer('[', ']')
            '"' -> skipQuoted()
            else -> skipLiteral()
        }
    }

    /** True when the whole document has been consumed. */
    fun atEnd(): Boolean {
        skipWhitespace()
        return length < 0
    }

    // ------------------------------------------------------------------ internals

    /**
     * Moves past a container, counting only its own kind of bracket and passing over strings whole.
     *
     * The run of characters that are none of the four that matter -- a quote, the two brackets, the end -- is
     * taken in one loop over the buffer instead of one call per character: this is where a field the caller does
     * not read, an array of ids or a nested object, is stepped over.
     */
    private fun skipContainer(open: Char, close: Char) {
        expect(open)
        var depth = 1
        while (depth > 0) {
            var i = position
            val end = length
            val buf = buffer
            while (i < end) {
                val c = buf[i]
                if (c == '"' || c == open || c == close || c == END) break
                i++
            }
            position = i
            if (i >= end) {
                if (!refill()) error("unterminated $open")
                continue
            }
            when (buffer[position]) {
                // Left at the quote, so the string skipper sees its opening one.
                '"' -> skipQuoted()
                open -> {
                    position++
                    depth++
                }
                close -> {
                    position++
                    depth--
                }
                else -> error("unterminated $open")
            }
        }
    }

    private fun skipLiteral() {
        while (true) {
            var i = position
            val end = length
            while (i < end && isLiteralChar(buffer[i])) i++
            position = i
            // Stopped on a character that is not part of a literal: done. Stopped at the end of the buffer: the
            // literal may go on after the next read, and if there is none, it ended.
            if (i < end || !refill()) return
        }
    }

    /**
     * The literal at the position as a string -- or the empty string when there is none.
     *
     * A literal wholly inside the buffer is taken from it at once. One that reaches the end of the buffer may go
     * on in the next, and is built a character at a time as it always was.
     */
    private fun readRawToken(): String {
        var i = position
        val end = length
        while (i < end && isLiteralChar(buffer[i])) i++
        if (i < end) {
            if (i == position) return ""
            val token = buffer.concatToString(position, i)
            position = i
            return token
        }
        text.setLength(0)
        while (isLiteralChar(peek())) {
            text.append(peek())
            advance()
        }
        return text.toString()
    }

    /**
     * A whole number written in the buffer -- `123`, `-5`, `"1600000000"` -- as its value, without making a string of
     * it; [NOT_DIGITS] for anything else, which the caller reads the way it always did.
     *
     * "Anything else" is every doubt: a token that reaches the end of the buffer, a quote that is not closed
     * straight after the digits, a sign or a point or a letter after them, no digit at all, or more digits than a
     * `Long` is sure to hold. Only what `toLongOrNull` would read as exactly this number is answered here, and the
     * position is moved only when it is.
     */
    private fun digitsInBuffer(): Long {
        val end = length
        var i = position
        if (i >= end) return NOT_DIGITS
        val quoted = buffer[i] == '"'
        if (quoted) i++
        var negative = false
        if (i < end && buffer[i] == '-') {
            negative = true
            i++
        }
        var value = 0L
        var count = 0
        while (i < end) {
            val c = buffer[i]
            if (c < '0' || c > '9') break
            if (count == MAX_DIGITS) return NOT_DIGITS
            value = value * DECIMAL + (c - '0')
            count++
            i++
        }
        if (count == 0 || i >= end) return NOT_DIGITS
        if (quoted) {
            if (buffer[i] != '"') return NOT_DIGITS
            i++
        } else if (isLiteralChar(buffer[i])) {
            return NOT_DIGITS
        }
        position = i
        return if (negative) -value else value
    }

    /**
     * What may appear in an unquoted value: digits, `true`/`false`/`null`, and the
     * punctuation numbers use.
     *
     * Deliberately an allow-list. Scanning "until the next comma or brace" instead
     * would swallow a stray `;` or `:` and let genuinely broken JSON — an HTML
     * error page, a truncated response — parse as though it were fine. Leniency
     * about *types* is what talking to real Xtream panels requires; leniency about
     * structure just hides failures.
     */
    private fun isLiteralChar(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c == '+' || c == '-' || c == '.'

    /**
     * A quoted string, unescaped.
     *
     * A string that closes inside the buffer with no escape in it -- nearly all of them -- is cut out of the buffer
     * in one piece. One with an escape, or one that runs past the end of the buffer, is read a character at a time
     * from where it began, as every string once was.
     */
    private fun readQuoted(): String {
        expect('"')
        val start = position
        var i = start
        val end = length
        while (i < end) {
            val c = buffer[i]
            if (c == '"') {
                position = i + 1
                return if (i == start) "" else buffer.concatToString(start, i)
            }
            if (c == '\\' || c == END) break
            i++
        }
        return readQuotedSlowly()
    }

    /** A member's name. The same as [readQuoted], except that a name seen before is not made again: see [names]. */
    private fun readName(): String {
        expect('"')
        val start = position
        var i = start
        val end = length
        var hash = 0
        while (i < end) {
            val c = buffer[i]
            if (c == '"') {
                val name = nameAt(start, i - start, hash)
                position = i + 1
                return name
            }
            if (c == '\\' || c == END) break
            hash = hash * HASH_PRIME + c.code
            i++
        }
        return readQuotedSlowly()
    }

    /** The name written at [start] in the buffer, from [names] when it is there and made and put there when it is not. */
    private fun nameAt(start: Int, size: Int, hash: Int): String {
        val first = (hash xor (hash ushr NAME_SHIFT)) and (NAME_SLOTS - 1)
        for (probe in 0 until NAME_PROBES) {
            val slot = (first + probe) and (NAME_SLOTS - 1)
            val known = names[slot] ?: break
            if (known.length == size && sameChars(known, start)) return known
        }
        val made = if (size == 0) "" else buffer.concatToString(start, start + size)
        // The first free slot of the probe, or the first slot of it when all are taken.
        var place = first
        for (probe in 0 until NAME_PROBES) {
            val slot = (first + probe) and (NAME_SLOTS - 1)
            if (names[slot] == null) {
                place = slot
                break
            }
        }
        names[place] = made
        return made
    }

    private fun sameChars(known: String, start: Int): Boolean {
        for (k in known.indices) if (known[k] != buffer[start + k]) return false
        return true
    }

    /**
     * Moves past a quoted string without making it.
     *
     * Where [readQuoted] cuts the string out of the buffer, this only finds where it ends. One with an escape, or one
     * that is not closed inside the buffer, is passed over by the character-at-a-time reader, which is also what says
     * so when the string is never closed or an escape is not one.
     */
    private fun skipQuoted() {
        expect('"')
        var i = position
        val end = length
        while (i < end) {
            val c = buffer[i]
            if (c == '"') {
                position = i + 1
                return
            }
            if (c == '\\' || c == END) break
            i++
        }
        readQuotedSlowly()
    }

    /** The rest of a quoted string whose opening quote has been read, a character at a time. */
    private fun readQuotedSlowly(): String {
        text.setLength(0)
        while (true) {
            val c = read()
            when (c) {
                '"' -> return text.toString()
                '\\' -> text.append(readEscape())
                END -> error("unterminated string")
                else -> text.append(c)
            }
        }
    }

    private fun readEscape(): Char = when (val c = read()) {
        '"', '\\', '/' -> c
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
            var value = 0
            repeat(4) {
                val digit = read()
                val nibble = when (digit) {
                    in '0'..'9' -> digit - '0'
                    in 'a'..'f' -> digit - 'a' + 10
                    in 'A'..'F' -> digit - 'A' + 10
                    else -> error("bad unicode escape")
                }
                value = value * 16 + nibble
            }
            value.toChar()
        }
        else -> c // Not valid JSON, but dropping the escape loses less than failing.
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        val c = read()
        if (c != expected) error("expected '$expected' but found '$c'")
    }

    private fun skipWhitespace() {
        while (true) {
            if (position >= length && !refill()) return
            val c = buffer[position]
            // A printable ASCII character is not white space: the answer for nearly every character this is asked
            // about, without asking the general question of it.
            if (c > ' ' && c < DEL_END) return
            if (c == END || !c.isWhitespace()) return
            position++
        }
    }

    private fun peek(): Char {
        if (position >= length && !refill()) return END
        return buffer[position]
    }

    private fun read(): Char {
        if (position >= length && !refill()) return END
        return buffer[position++]
    }

    private fun advance() {
        if (position < length) position++
    }

    private fun refill(): Boolean {
        if (length < 0) return false
        val read = source.read(buffer)
        if (read <= 0) {
            length = -1
            position = 0
            return false
        }
        length = read
        position = 0
        return true
    }

    private fun error(message: String): Nothing = throw JsonFormatException(message)

    private companion object {
        const val BUFFER = 1 shl 13
        const val END = '\u0000'

        /** The first character past ASCII: everything below it, above the space, is a plain printable one. */
        const val DEL_END = '\u0080'

        /** A `Long` is sure to hold eighteen decimal digits. */
        const val MAX_DIGITS = 18
        const val DECIMAL = 10L

        /** What [digitsInBuffer] answers for a value it will not read; no number of eighteen digits is it. */
        const val NOT_DIGITS = Long.MIN_VALUE

        /** The member-name table: a power of two, and how many neighbouring slots a name may take. */
        const val NAME_SLOTS = 256
        const val NAME_PROBES = 4
        const val NAME_SHIFT = 16
        const val HASH_PRIME = 31
    }
}

class JsonFormatException(message: String) : RuntimeException(message)
