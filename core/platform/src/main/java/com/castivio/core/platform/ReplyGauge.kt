package com.castivio.core.platform

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What the provider's replies are while they arrive: how many bytes have come, how they are encoded, whether the reply says how
 * long it is, and how fast it is coming in.
 *
 * The strip used to say "measuring" for the whole of a load, because its size is the completed calls' and the one call that
 * matters -- a section's list, tens of megabytes -- completes last. A phone on mobile data spends a minute there with no figure
 * on the screen to say whether the reply is arriving, at what speed, and whether it is compressed. This is the figure.
 *
 * One entry for each action, because a section asks for several replies and two of them can arrive together: a small list of
 * groups beside the big list of rows. [reading] is the **largest** so far, which is the one a reader means, and does not
 * jump to a small reply that began later.
 *
 * Pure Kotlin on purpose -- the clock is given -- so that what it says can be proved without a device. It is told by two
 * parties and keeps nothing else: the listener on the client, which sees the raw headers (the bridge that unpacks a gzip
 * body takes `Content-Encoding` and `Content-Length` off the response it hands on, so nothing after it can say how the reply
 * travelled), and the meter on the connection's stream, which sees each read.
 */
class ReplyGauge(private val clock: () -> Long = System::nanoTime) {

    private class Reply(val encoding: String?, val declaredBytes: Long?) {
        val bytes = AtomicLong()

        @Volatile
        var firstAt = 0L

        @Volatile
        var lastAt = 0L
    }

    private val replies = ConcurrentHashMap<String, Reply>()

    /**
     * The headers of [action]'s reply have come: [encoding] is its `Content-Encoding` as the provider sent it (null when it
     * sent none) and [declaredBytes] its `Content-Length` (null when it did not say, which is a reply sent in chunks).
     *
     * A reply asked for again -- a retry -- starts from nothing: what is read is the one in hand.
     */
    fun started(action: String, encoding: String?, declaredBytes: Long?) {
        replies[action] = Reply(encoding?.trim()?.takeIf { it.isNotEmpty() }, declaredBytes?.takeIf { it >= 0 })
    }

    /** [count] more bytes of [action]'s reply have passed. */
    fun bytes(action: String, count: Long) {
        if (count <= 0) return
        val reply = replies.getOrPut(action) { Reply(encoding = null, declaredBytes = null) }
        val now = clock()
        if (reply.firstAt == 0L) reply.firstAt = now
        reply.lastAt = now
        reply.bytes.addAndGet(count)
    }

    /** The largest reply so far as it stands, or null while no byte of any has come. */
    fun reading(): ReplyReading? {
        var largest: Reply? = null
        for (reply in replies.values) {
            if (reply.bytes.get() > (largest?.bytes?.get() ?: 0L)) largest = reply
        }
        val reply = largest ?: return null
        return ReplyReading(
            bytes = reply.bytes.get(),
            encoding = reply.encoding,
            declaredBytes = reply.declaredBytes,
            spanMs = ((reply.lastAt - reply.firstAt).coerceAtLeast(0L)) / NANOS_PER_MS,
        )
    }

    /** Forgets every reply, for a run that is beginning. */
    fun clear() = replies.clear()

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

/**
 * A reply in progress, from [ReplyGauge.reading].
 *
 * [spanMs] is from the first byte to the latest one, so a rate over it is the speed the reply came at and does not fall away
 * while the reader is busy elsewhere.
 */
data class ReplyReading(
    val bytes: Long,
    val encoding: String?,
    val declaredBytes: Long?,
    val spanMs: Long,
) {
    /**
     * The reading as the strip says it: `31.2 MB so far (no Content-Encoding · chunked · 0.94 MB/s)`.
     *
     * Said in the strip's own words ([WireFigures.describe] says "no Content-Encoding" and "chunked" the same way), with a
     * declared length when the provider gave one -- `of 78.0 MB` -- and a rate only once the reply has been arriving long
     * enough for one to mean something. Digits are Western whatever the locale, as the rest of the strip's are.
     *
     * **A reply whose header says it is compressed** is unpacked by the client before the meter sees a byte of it, so what
     * has been counted is the unpacked size, and the line says so -- `unpacked so far`, against `packed 9.0 MB` -- because
     * the length the provider declared is the packed one, and the two figures side by side would read as a reply that had
     * outgrown its own length. The rate is then the unpacked one too.
     */
    fun describe(): String {
        val packed = encoding != null && !encoding.equals(IDENTITY, ignoreCase = true)
        val encoded = encoding ?: "no Content-Encoding"
        val so = if (packed) "unpacked so far" else "so far"
        val length = when {
            declaredBytes == null -> "chunked"
            packed -> "packed ${megabytes(declaredBytes)}"
            else -> "of ${megabytes(declaredBytes)}"
        }
        val rate = if (spanMs >= MIN_SPAN_MS) {
            " · ${java.lang.String.format(java.util.Locale.ROOT, "%.2f", bytes * MS_PER_S / spanMs / BYTES_PER_MB)} MB/s"
        } else {
            ""
        }
        return "${megabytes(bytes)} $so ($encoded · $length$rate)"
    }

    private fun megabytes(count: Long): String =
        java.lang.String.format(java.util.Locale.ROOT, "%.1f MB", count / BYTES_PER_MB)

    private companion object {
        const val BYTES_PER_MB = 1_048_576.0
        const val MS_PER_S = 1_000.0

        /** Under half a second a rate is one read's burst, not a speed. */
        const val MIN_SPAN_MS = 500L

        /** The one `Content-Encoding` that says nothing was done to the body. */
        const val IDENTITY = "identity"
    }
}
