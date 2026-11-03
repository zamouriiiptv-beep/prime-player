package com.castivio.playback.api

import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Temporary, for development.** The tries an engine made at the address it was asked to open, in the order it made
 * them, for the panel that says where an opening's time went.
 *
 * A source that answers in nine seconds can have been slow, or can have been silent for the eight seconds the reader
 * waits for an answer and then asked again; the single figure the opening reports -- when the server answered --
 * cannot tell the two apart, because it is the answer of the last try. This keeps every try, so the two are
 * different pictures.
 *
 * Every figure is milliseconds after the engine was asked to open, like [OpeningMarks]'s own, and null for what had
 * not happened. The panel moves them to be from the press, as it does the rest.
 */
data class OpenAttempt(
    /** 1 for the first try at the address; 2 and up are the ones the engine made again after a failure. */
    val number: Int,
    /** The engine began to open the address: the connection, any redirect and the request all come after this. */
    val startedMs: Long,
    /** The response began: the server answered, with the status accepted. Null for a try that never got one. */
    val answeredMs: Long? = null,
    /** The first bytes of the body reached the engine on this try. */
    val firstBytesMs: Long? = null,
    /** The try ended: it failed, or its connection was closed. Null while it is still going. */
    val endedMs: Long? = null,
    val outcome: AttemptOutcome = AttemptOutcome.WAITING,
    /**
     * What the engine said went wrong, by the name of the exception that caused it -- or the status the server
     * answered with. Never a message: an exception's message can carry an address, and this is shown to others.
     */
    val failure: String? = null,
) {

    /** A try the engine made again, after another had failed. */
    val retry: Boolean get() = number > 1

    /** This try's own wait for the answer, which is not the opening's: a retry's starts when the last one ended. */
    val answerTookMs: Long? get() = answeredMs?.let { (it - startedMs).coerceAtLeast(0) }

    /** How long this try lasted, once it has ended. */
    val lastedMs: Long? get() = endedMs?.let { (it - startedMs).coerceAtLeast(0) }

    /** The same try with every instant moved by [byMs], to be measured from another start. */
    fun shifted(byMs: Long): OpenAttempt = copy(
        startedMs = startedMs + byMs,
        answeredMs = answeredMs?.plus(byMs),
        firstBytesMs = firstBytesMs?.plus(byMs),
        endedMs = endedMs?.plus(byMs),
    )
}

/** Where a try is. */
enum class AttemptOutcome {
    /** Begun, and the server has not answered: the connection is being made, or the answer is being waited for. */
    WAITING,

    /** The server answered and the try's connection is open. */
    ANSWERED,

    /** The engine reported the try failed -- before an answer or after it -- and says how in [OpenAttempt.failure]. */
    FAILED,

    /** The connection was closed without a failure: the source was replaced or released. */
    CLOSED,
}

/**
 * How many addresses the stream's host has in each family, as a lookup made *after* the open said anything.
 *
 * It is what the resolver offers now, which is not the address the connection used: the HTTP source does not say
 * which one it connected to. A host with an IPv6 address on a network that cannot reach it is the kind of thing that
 * makes a connection wait for its timeout before it tries the next, and this is what says whether that is possible.
 * The addresses themselves are never kept.
 */
data class HostLookup(
    val ipv4: Int = 0,
    val ipv6: Int = 0,
    /** The exception's name when the lookup failed, which is itself the answer to what it was asked. */
    val failure: String? = null,
)

/** What [OpenAttemptLog] knew when it was read: the tries, how many more there were than it kept, and the lookup. */
data class OpenAttempts(
    val tries: List<OpenAttempt> = emptyList(),
    /** Tries beyond the number the log keeps, counted and not described. */
    val more: Int = 0,
    val lookup: HostLookup? = null,
) {

    val isEmpty: Boolean get() = tries.isEmpty() && more == 0

    /** How many times the address was tried, those that were not kept included. */
    val count: Int get() = tries.size + more

    /** The same account with every instant moved by [byMs]: the engine's clock begins when it is asked, not at the press. */
    fun shifted(byMs: Long): OpenAttempts =
        if (tries.isEmpty()) this else copy(tries = tries.map { it.shifted(byMs) })

    companion object {
        val NONE = OpenAttempts()
    }
}

/**
 * The record of one open's tries, written as the engine's HTTP source and player report them.
 *
 * One per open, and read while the open is still going: the panel asks ten times a second. The writers are two
 * threads -- the loader's, which reports a try beginning, the answer, the bytes and the connection closing, and the
 * playback thread's, which reports a try failing -- and the reader is a third. Everything that changes a structure
 * takes [lock]; what is called for every read of the body, [awaitingBytes], is a read of one volatile field, so
 * measuring the first bytes of a try costs the reads after them nothing.
 *
 * Nothing here decides anything about an open, and nothing in the engine reads it back: it only remembers.
 */
class OpenAttemptLog(
    /** The engine's clock when it was asked to open: every figure the log gives is after this. */
    private val epochMs: Long,
    /** The engine's own monotonic clock, in milliseconds. */
    private val clock: () -> Long,
) {

    private class Entry(val number: Int, val startedAt: Long) {
        @Volatile var answeredAt: Long = NOT_YET
        @Volatile var firstBytesAt: Long = NOT_YET
        @Volatile var endedAt: Long = NOT_YET
        @Volatile var outcome: AttemptOutcome = AttemptOutcome.WAITING
        @Volatile var failure: String? = null
    }

    private val lock = Any()
    private val entries = ArrayList<Entry>(INITIAL_CAPACITY)
    private var more = 0
    private val lookupClaimed = AtomicBoolean(false)

    /** The try the engine is on, or null before the first and after the last the log keeps. */
    @Volatile
    private var latest: Entry? = null

    @Volatile
    private var lookup: HostLookup? = null

    /** The engine began a try at the address. */
    fun started() {
        synchronized(lock) {
            if (entries.size >= MAX_KEPT) {
                // Counted, and the events that follow have no try to land on: they would be put on the last one kept.
                more++
                latest = null
                return
            }
            val entry = Entry(number = entries.size + more + 1, startedAt = clock())
            entries += entry
            latest = entry
        }
    }

    /** The server answered the try the engine is on. */
    fun answered() {
        val entry = latest ?: return
        synchronized(lock) {
            if (entry.answeredAt == NOT_YET) {
                entry.answeredAt = clock()
                if (entry.outcome == AttemptOutcome.WAITING) entry.outcome = AttemptOutcome.ANSWERED
            }
        }
    }

    /**
     * Whether the try the engine is on is still waiting for its first bytes: the one question asked on every read of
     * the body, so it is one volatile read of one field and nothing else.
     */
    fun awaitingBytes(): Boolean = latest?.let { it.firstBytesAt == NOT_YET } ?: false

    /** Bytes arrived on the try the engine is on. Only the first of them is kept. */
    fun bytes() {
        val entry = latest ?: return
        if (entry.firstBytesAt != NOT_YET) return
        synchronized(lock) {
            if (entry.firstBytesAt == NOT_YET) entry.firstBytesAt = clock()
        }
    }

    /** The connection of the try the engine is on was closed. A try already said to have failed stays failed. */
    fun closed() {
        val entry = latest ?: return
        synchronized(lock) {
            if (entry.endedAt == NOT_YET) entry.endedAt = clock()
            if (entry.outcome == AttemptOutcome.WAITING || entry.outcome == AttemptOutcome.ANSWERED) {
                entry.outcome = AttemptOutcome.CLOSED
            }
        }
    }

    /**
     * The engine reported a try failed, at [atMs] on the clock [clock] reads, for [reason].
     *
     * It lands on the latest try that began by then and was not already said to have failed. The player reports a
     * failure on its own thread after the loader's has closed the connection, so a try that was closed a moment ago
     * is one that failed: it becomes failed, and keeps the instant it closed.
     */
    fun failed(atMs: Long, reason: String) {
        synchronized(lock) {
            val entry = entries.lastOrNull { it.startedAt <= atMs && it.outcome != AttemptOutcome.FAILED } ?: return
            entry.outcome = AttemptOutcome.FAILED
            entry.failure = reason
            if (entry.endedAt == NOT_YET) entry.endedAt = atMs
        }
    }

    /** Whether this is the first to ask: one lookup is made for an open, and the one that gets true makes it. */
    fun claimLookup(): Boolean = lookupClaimed.compareAndSet(false, true)

    /** What the lookup found. */
    fun lookedUp(found: HostLookup) {
        lookup = found
    }

    /** The tries as they stand, in the order they began. */
    fun snapshot(): OpenAttempts = synchronized(lock) {
        OpenAttempts(
            tries = entries.map { it.asAttempt() },
            more = more,
            lookup = lookup,
        )
    }

    private fun Entry.asAttempt(): OpenAttempt = OpenAttempt(
        number = number,
        startedMs = (startedAt - epochMs).coerceAtLeast(0),
        answeredMs = after(answeredAt),
        firstBytesMs = after(firstBytesAt),
        endedMs = after(endedAt),
        outcome = outcome,
        failure = failure,
    )

    private fun after(at: Long): Long? = if (at == NOT_YET) null else (at - epochMs).coerceAtLeast(0)

    private companion object {
        /** "Not yet": a clock never reads it, and a fake one in a test can start at zero. */
        const val NOT_YET = -1L

        /** More than a handful of tries inside one opening is a source that is not going to open; the rest are counted. */
        const val MAX_KEPT = 8

        const val INITIAL_CAPACITY = 2
    }
}
