package com.castivio.benchmark

/**
 * Stops the JIT deleting the work being measured.
 *
 * This is not optional. A benchmark whose callback only increments a counter
 * lets the compiler prove the parsed strings are never used and remove the
 * parsing entirely — which is how the first version of this suite reported
 * 5.4 million M3U entries/sec, a figure that is arithmetically impossible for
 * ~400,000 lines of string scanning.
 *
 * Accumulate into a local, then write it here **once**. The volatile write
 * makes the accumulation observable, so it cannot be elided, while costing one
 * memory barrier per run rather than one per entry.
 */
object Sink {
    @Volatile
    @JvmField
    var consumed: Long = 0
}

/**
 * A deliberately small measurement harness.
 *
 * JMH would give better numbers but needs its own toolchain and takes minutes
 * per run — too slow for a per-commit gate. This does the three things that
 * matter for catching regressions: warm the JIT, take the *best* of several
 * runs, and (via [Sink]) make sure the work cannot be optimised away.
 *
 * Best-of rather than mean is intentional. On a shared runner the mean is
 * dominated by whatever else the machine was doing; the fastest run is the
 * closest we get to the code's real cost.
 */
object Harness {

    fun measure(
        name: String,
        warmups: Int = 2,
        runs: Int = 5,
        block: () -> Long,
    ): Measurement {
        repeat(warmups) { block() }
        var bestNanos = Long.MAX_VALUE
        var units = 0L
        repeat(runs) {
            val start = System.nanoTime()
            val processed = block()
            val elapsed = System.nanoTime() - start
            if (elapsed < bestNanos) {
                bestNanos = elapsed
                units = processed
            }
        }
        return Measurement(name, units, bestNanos)
    }

    /**
     * Retained heap after [block], measured by settling the collector first.
     *
     * `System.gc()` is only a hint, so this loops until the reading stabilises
     * and takes the smallest value. Not exact — but decisive about the thing it
     * is guarding: an accumulating parser shows up as tens of megabytes, far
     * outside the noise.
     */
    fun retainedHeapBytes(block: () -> Unit): Long {
        settle()
        val before = usedHeap()
        block()
        settle()
        return (usedHeap() - before).coerceAtLeast(0)
    }

    private fun settle() {
        var last = Long.MAX_VALUE
        repeat(6) {
            System.gc()
            Thread.sleep(40)
            val now = usedHeap()
            if (now >= last) return
            last = now
        }
    }

    private fun usedHeap(): Long =
        Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    /** Kilobytes, so a small-but-real retention isn't rounded away to "0 MB". */
    fun retainedKb(block: () -> Unit): Long = retainedHeapBytes(block) / 1024
}

data class Measurement(val name: String, val units: Long, val nanos: Long) {
    val perSecond: Long get() = if (nanos == 0L) 0 else units * 1_000_000_000L / nanos
    val millis: Double get() = nanos / 1_000_000.0

    override fun toString(): String =
        "%s: %,d units in %.1f ms (%,d/sec)".format(name, units, millis, perSecond)
}

/**
 * Generates realistic test data.
 *
 * Synthetic, but shaped like what providers actually send: `tvg-*` attributes,
 * group titles, commas inside logo URLs, occasional malformed lines. Benchmarks
 * against tidy input measure the wrong thing.
 */
object Fixtures {

    private val groups = listOf("Sports", "News", "Movies", "Kids", "Music", "Docs", "Arabic", "4K")

    fun m3uLines(count: Int): Sequence<String> = sequence {
        yield("#EXTM3U")
        for (i in 0 until count) {
            val group = groups[i % groups.size]
            // Roughly one in 500 entries is malformed, as in real playlists.
            if (i % 500 == 499) {
                yield("http://example.com/orphan/$i.ts")
                continue
            }
            yield(
                """#EXTINF:-1 tvg-id="ch$i" tvg-name="Channel $i" """ +
                    """tvg-logo="http://cdn.example.com/logo,$i.png" group-title="$group",Channel $i HD"""
            )
            yield("http://stream.example.com/live/user/pass/$i.ts")
        }
    }

    /**
     * An XMLTV document with [programmes] entries spread over [channels].
     *
     * Timestamps are real dates computed from [XMLTV_START_MS], not decimal
     * arithmetic on the `YYYYMMDDHHMMSS` digits. An earlier version incremented
     * the digits directly and drifted into month 19 and day 75 after a day's
     * worth of slots — invalid dates that a retention window then silently
     * dropped, quietly measuring 27% fewer programmes than the fixture claimed.
     *
     * Each channel gets consecutive [SLOT_MS] slots, which is the shape a real
     * guide has and keeps a large fixture inside a normal retention window.
     */
    fun xmltv(channels: Int, programmes: Int): String {
        val sb = StringBuilder(programmes * 180)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n').append("<tv>\n")
        for (c in 0 until channels) {
            sb.append("""  <channel id="ch$c"><display-name>Channel $c</display-name>""")
                .append("""<icon src="http://cdn.example.com/$c.png"/></channel>""").append('\n')
        }
        for (p in 0 until programmes) {
            val channel = p % channels
            val start = XMLTV_START_MS + (p / channels) * SLOT_MS
            sb.append("""  <programme start="${xmltvTime(start)}" """)
                .append("""stop="${xmltvTime(start + SLOT_MS)}" channel="ch$channel">""")
                .append("<title lang=\"en\">Programme $p &amp; more</title>")
                .append("<desc>Description for programme $p with &lt;markup&gt; inside.</desc>")
                .append("</programme>\n")
        }
        sb.append("</tv>\n")
        return sb.toString()
    }

    /** 2026-07-25 06:00:00 UTC — where every generated guide starts. */
    const val XMLTV_START_MS = 1_784_959_200_000L

    /** Half-hour slots, the most common programme length. */
    const val SLOT_MS = 30 * 60 * 1000L

    /** Epoch millis → `20260725060000 +0000`, without a date library. */
    fun xmltvTime(ms: Long): String {
        val totalSeconds = ms / 1000
        var remainingDays = totalSeconds / 86_400
        val secondOfDay = (totalSeconds % 86_400).toInt()
        var year = 1970
        while (true) {
            val length = if (isLeap(year)) 366 else 365
            if (remainingDays < length) break
            remainingDays -= length
            year++
        }
        val monthLengths = intArrayOf(31, if (isLeap(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var month = 0
        while (remainingDays >= monthLengths[month]) {
            remainingDays -= monthLengths[month]
            month++
        }
        return "%04d%02d%02d%02d%02d%02d +0000".format(
            year, month + 1, remainingDays.toInt() + 1,
            secondOfDay / 3600, (secondOfDay % 3600) / 60, secondOfDay % 60,
        )
    }

    private fun isLeap(year: Int) = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
}
