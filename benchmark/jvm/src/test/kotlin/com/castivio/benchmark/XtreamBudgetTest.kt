package com.castivio.benchmark

import com.castivio.data.parsing.XtreamParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * Gates on the Xtream JSON path.
 *
 * `get_vod_streams` for a large provider is tens of megabytes and a single
 * category can hold twenty thousand rows, so this is the response most likely to
 * be "simplified" into a `JSONArray` some day. These two numbers make that
 * change fail the build instead of shipping.
 */
class XtreamBudgetTest {

    @Test
    fun `stream parsing throughput stays within budget`() {
        val rows = 200_000
        val json = Fixtures.xtreamStreams(rows)

        val measurement = Harness.measure("xtream-streams") {
            var seen = 0L
            var acc = 0L
            XtreamParser.parseStreams(StringReader(json)) { stream ->
                seen++
                // Touch every parsed field, or the JIT proves the strings unused
                // and deletes the parsing.
                acc += stream.name.length + stream.streamId.length +
                    (stream.iconUrl?.length ?: 0) + (stream.epgChannelId?.length ?: 0) +
                    (stream.catchUpHours ?: 0) + (stream.number ?: 0)
            }
            Sink.consumed += acc
            seen
        }
        println("[budget] $measurement")

        assertTrue(
            """
            Xtream stream parsing regressed.
              measured : ${"%,d".format(measurement.perSecond)} streams/sec
              budget   : ${"%,d".format(PerformanceBudgets.XTREAM_STREAMS_PER_SECOND_MIN)} streams/sec minimum
            Usual cause: JSONArray/JSONObject or a reflective JSON library replacing
            the streaming scan — both parse the whole response into memory first.
            """.trimIndent(),
            measurement.perSecond >= PerformanceBudgets.XTREAM_STREAMS_PER_SECOND_MIN,
        )
    }

    @Test
    fun `parsing a large category does not retain it`() {
        val rows = 200_000
        val json = Fixtures.xtreamStreams(rows)
        var parsed = 0L
        var acc = 0L

        val retainedKb = Harness.retainedKb {
            XtreamParser.parseStreams(StringReader(json)) { stream ->
                parsed++
                acc += stream.name.length + stream.streamId.length
            }
            Sink.consumed += acc
        }
        val retainedMb = retainedKb / 1024
        println("[budget] xtream-retained-heap: $retainedKb KB after $parsed streams")

        assertTrue("expected $rows streams, got $parsed", parsed == rows.toLong())
        assertTrue(
            """
            Xtream stream parsing is retaining the category.
              measured : $retainedMb MB retained after ${"%,d".format(parsed)} streams
              budget   : ${PerformanceBudgets.XTREAM_RETAINED_HEAP_MB_MAX} MB maximum
            Memory must not grow with category size; a provider's biggest category
            is not a size the app gets to choose.
            """.trimIndent(),
            retainedMb <= PerformanceBudgets.XTREAM_RETAINED_HEAP_MB_MAX,
        )
    }
}
