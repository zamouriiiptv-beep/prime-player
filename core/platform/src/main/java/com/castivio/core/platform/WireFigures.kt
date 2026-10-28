package com.castivio.core.platform

import java.util.Locale

/**
 * How one action's answers travelled, summed over its calls: the protocol, whether the provider compressed, what the
 * connection carried against what the parser was given, and where the time between asking and having went.
 *
 * **Temporary, for development**, read by the load strip and the log. Names and numbers only -- a protocol, an
 * encoding, a count -- never an address: it is printed with the rest of the run. Everything but the decoded figures
 * comes from OkHttp's own events; those come from a meter on the body the parser reads, which is why they are
 * zero when [metered] is.
 */
data class WireFigures(
    /** `h2`, `http/1.1`: the protocol each call was answered over. */
    val protocols: List<String>,
    /** The `Accept-Encoding` the client sent, as sent. */
    val asked: List<String>,
    /** The `Content-Encoding` the provider answered with, as received; empty when it never named one. */
    val encodings: List<String>,
    /** How many calls were answered with a `Content-Encoding` other than `identity`. */
    val encodedCalls: Int,
    /** How many calls declared their length; the rest came chunked. */
    val declaredCalls: Int,
    /** The `Content-Length`s added up: the compressed size when the provider compressed. */
    val declaredBytes: Long,
    /** How many calls had their body metered; [decodedBytes] and [blockedMs] cover those and no others. */
    val metered: Int,
    /** What the parser's reader was handed after every decoding there was. */
    val decodedBytes: Long,
    /** How many bodies were gzip with no `Content-Encoding` to say so: the app's own sniffing unpacked them. */
    val sniffedGzipCalls: Int,
    /** Name lookup, connecting and TLS, in milliseconds. */
    val setupMs: Long,
    /** From the request being sent to the first byte of the answer: the provider thinking. */
    val firstByteMs: Long,
    /** From the answer's headers to its last byte read: the body arriving and being consumed. */
    val transferMs: Long,
    /** The part of [transferMs] spent inside reads, waiting for the connection to deliver. */
    val blockedMs: Long,
) {

    /**
     * This and [other] as one: the sums added, the names joined. For a run that asked several actions.
     */
    operator fun plus(other: WireFigures): WireFigures = WireFigures(
        protocols = (protocols + other.protocols).distinct().sorted(),
        asked = (asked + other.asked).distinct().sorted(),
        encodings = (encodings + other.encodings).distinct().sorted(),
        encodedCalls = encodedCalls + other.encodedCalls,
        declaredCalls = declaredCalls + other.declaredCalls,
        declaredBytes = declaredBytes + other.declaredBytes,
        metered = metered + other.metered,
        decodedBytes = decodedBytes + other.decodedBytes,
        sniffedGzipCalls = sniffedGzipCalls + other.sniffedGzipCalls,
        setupMs = setupMs + other.setupMs,
        firstByteMs = firstByteMs + other.firstByteMs,
        transferMs = transferMs + other.transferMs,
        blockedMs = blockedMs + other.blockedMs,
    )

    /**
     * The figures as two lines, for the strip and for the log: what crossed the wire, then the time it took.
     *
     * [wireBytes] is what the connection carried, which the call tally counts and this does not; [calls] is how many
     * calls that covers. Digits are Western whatever the locale, as the rest of the strip's are. Two lines because
     * the strip clips a line at three rows of a phone's width, and one that said all of this would not fit.
     *
     * Read the first as the answer to *is the provider compressing?* -- what was asked, what was answered, and the
     * size on the wire against the size handed to the parser. Read the second as the answer to *is the wait the
     * connection's?* -- the body's time against the part of it spent blocked inside reads: close together, the
     * connection (or the provider behind it) is the limit; far apart, the reader is, and a faster link would not
     * help -- and to *how fast?*, the wire's size over the body's time.
     */
    fun describe(wireBytes: Long, calls: Int): List<String> {
        val answered = when {
            encodedCalls > 0 -> "answered ${encodings.joinToString("+")} ($encodedCalls/$calls)"
            encodings.isNotEmpty() -> "answered ${encodings.joinToString("+")}"
            else -> "answered with no Content-Encoding"
        }
        val sniffed = if (sniffedGzipCalls > 0) " · gzip in the body with no header ($sniffedGzipCalls)" else ""
        val decoded = when {
            metered == 0 -> "decoded not metered"
            wireBytes > 0 -> "${megabytes(decodedBytes)} decoded ×${ratio(decodedBytes, wireBytes)}"
            else -> "${megabytes(decodedBytes)} decoded"
        }
        val declared = if (declaredCalls > 0) {
            "declared ${megabytes(declaredBytes)} on $declaredCalls/$calls"
        } else {
            "length not declared (chunked)"
        }
        val blocked = if (metered > 0 && transferMs > 0) {
            " (reads blocked ${(blockedMs * PERCENT / transferMs).coerceIn(0, PERCENT)}%)"
        } else {
            ""
        }
        val rate = if (transferMs > 0 && wireBytes > 0) {
            " · ${String.format(Locale.ROOT, "%.2f", wireBytes * MS_PER_S / transferMs / BYTES_PER_MB)} MB/s"
        } else {
            ""
        }
        return listOf(
            "wire: ${protocols.joinToString("/").ifEmpty { DASH }}" +
                " · asked ${asked.joinToString("+").ifEmpty { "nothing" }} · $answered$sniffed" +
                " · ${megabytes(wireBytes)} on the wire → $decoded · $declared",
            "wire time (s): setup ${seconds(setupMs)} · first byte ${seconds(firstByteMs)}" +
                " · body ${seconds(transferMs)}$blocked$rate",
        )
    }

    private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / BYTES_PER_MB)

    private fun ratio(decoded: Long, wire: Long): String = String.format(Locale.ROOT, "%.1f", decoded.toDouble() / wire)

    private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.2f", ms / MS_PER_S)

    private companion object {
        const val BYTES_PER_MB = 1_048_576.0
        const val MS_PER_S = 1_000.0
        const val PERCENT = 100L
        const val DASH = "-"
    }
}
