package com.castivio.benchmark

/**
 * A deliberately small measurement harness.
 *
 * JMH would give better numbers but needs its own toolchain and takes minutes
 * per run — too slow for a per-commit gate. This does the two things that
 * matter for catching regressions: warm the JIT, then take the *best* of
 * several runs.
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

    /** An XMLTV document with [programmes] entries spread over [channels]. */
    fun xmltv(channels: Int, programmes: Int): String {
        val sb = StringBuilder(programmes * 180)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n').append("<tv>\n")
        for (c in 0 until channels) {
            sb.append("""  <channel id="ch$c"><display-name>Channel $c</display-name>""")
                .append("""<icon src="http://cdn.example.com/$c.png"/></channel>""").append('\n')
        }
        var start = 20_260_725_060_000L
        for (p in 0 until programmes) {
            val channel = p % channels
            sb.append("""  <programme start="${start} +0000" stop="${start + 3000} +0000" channel="ch$channel">""")
                .append("<title lang=\"en\">Programme $p &amp; more</title>")
                .append("<desc>Description for programme $p with &lt;markup&gt; inside.</desc>")
                .append("</programme>\n")
            if (p % 48 == 47) start += 1_000_000L
        }
        sb.append("</tv>\n")
        return sb.toString()
    }
}
