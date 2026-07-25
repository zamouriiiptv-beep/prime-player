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
     * Reads an object, invoking [onField] once per member with its name. The
     * scanner is positioned at the member's value; consume it with [string],
     * [long], [int], [boolean], [readObject] or [readArray], or ignore it and it
     * will be skipped.
     */
    fun readObject(onField: (name: String) -> Unit) {
        valuePending = false
        skipWhitespace()
        if (peek() == 'n') { // null in place of an object — providers do this
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
            val name = readQuoted()
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
        valuePending = false
        skipWhitespace()
        if (peek() == 'n') {
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

    fun long(): Long = string()?.let { raw ->
        raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
    } ?: 0L

    fun int(): Int = long().toInt()

    /** `true`, `1` and `"1"` are all true, which is what providers actually send. */
    fun boolean(): Boolean = when (val raw = string()?.lowercase()) {
        null -> false
        "true", "1", "yes" -> true
        else -> raw.toDoubleOrNull()?.let { it != 0.0 } ?: false
    }

    /** Skips the current value, however deeply nested. */
    fun skip() {
        valuePending = false
        skipWhitespace()
        when (peek()) {
            '{' -> skipContainer('{', '}')
            '[' -> skipContainer('[', ']')
            '"' -> readQuoted()
            else -> skipLiteral()
        }
    }

    /** True when the whole document has been consumed. */
    fun atEnd(): Boolean {
        skipWhitespace()
        return length < 0
    }

    // ------------------------------------------------------------------ internals

    private fun skipContainer(open: Char, close: Char) {
        expect(open)
        var depth = 1
        while (depth > 0) {
            when (val c = read()) {
                '"' -> {
                    // Rewind one so the string reader sees its opening quote.
                    position--
                    readQuoted()
                }
                open -> depth++
                close -> depth--
                else -> if (c == END) error("unterminated $open")
            }
        }
    }

    private fun skipLiteral() {
        while (isLiteralChar(peek())) advance()
    }

    private fun readRawToken(): String {
        text.setLength(0)
        while (isLiteralChar(peek())) {
            text.append(peek())
            advance()
        }
        return text.toString()
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

    private fun readQuoted(): String {
        expect('"')
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
            val c = peek()
            if (c == END || !c.isWhitespace()) return
            advance()
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
    }
}

class JsonFormatException(message: String) : RuntimeException(message)
