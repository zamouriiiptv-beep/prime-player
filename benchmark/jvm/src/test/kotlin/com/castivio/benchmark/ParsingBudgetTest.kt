package com.castivio.benchmark

import com.castivio.data.parsing.M3uParser
import com.castivio.data.parsing.XmltvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * Performance gates. These fail the build, which is the only thing that keeps a
 * budget honest over a project's lifetime.
 *
 * Every failure message states the measurement, the budget and the likely
 * cause — a red build at 2am should tell you what to look at, not just that a
 * number moved.
 */
class ParsingBudgetTest {

    @Test
    fun `m3u parse throughput stays within budget`() {
        val entries = 200_000
        val lines = Fixtures.m3uLines(entries).toList()   // exclude generation from timing

        val measurement = Harness.measure("m3u-parse") {
            var seen = 0L
            var acc = 0L
            M3uParser.parse(lines.asSequence()) { entry ->
                seen++
                // Touch every parsed field. Without this the JIT proves the
                // strings are unused and deletes the parsing outright.
                acc += entry.name.length + entry.url.length +
                    (entry.groupTitle?.length ?: 0) + (entry.tvgId?.length ?: 0) +
                    (entry.logoUrl?.length ?: 0) + entry.durationSeconds
            }
            Sink.consumed = acc
            seen
        }
        report(measurement)

        assertTrue(
            """
            M3U parsing regressed.
              measured : ${"%,d".format(measurement.perSecond)} entries/sec
              budget   : ${"%,d".format(PerformanceBudgets.M3U_ENTRIES_PER_SECOND_MIN)} entries/sec minimum
            A drop this large is structural, not drift. Usual causes: a Regex or
            String.split introduced per line, or building an intermediate list.
            """.trimIndent(),
            measurement.perSecond >= PerformanceBudgets.M3U_ENTRIES_PER_SECOND_MIN,
        )
    }

    /**
     * The most important test in the suite: proof that the parser streams.
     *
     * If someone changes `parse` to return a `List<M3uEntry>`, retained heap
     * grows with the playlist and this fails immediately — before it reaches a
     * device with a 128 MB heap and becomes an OOM crash report.
     */
    @Test
    fun `m3u parsing does not retain the catalogue`() {
        val count = PerformanceBudgets.MEMORY_PROBE_ENTRIES
        var parsed = 0L

        var acc = 0L
        val retainedKb = Harness.retainedKb {
            // Generated lazily so the *fixture* is not what occupies the heap.
            M3uParser.parse(Fixtures.m3uLines(count)) { entry ->
                parsed++
                acc += entry.name.length + entry.url.length
            }
            Sink.consumed = acc
        }
        val retainedMb = retainedKb / 1024
        println("[budget] m3u-retained-heap: $retainedKb KB after $parsed entries")

        assertTrue("expected to parse $count entries, got $parsed", parsed > count * 0.9)
        assertTrue(
            """
            M3U parsing is retaining the catalogue.
              measured : $retainedMb MB retained after parsing ${"%,d".format(parsed)} entries
              budget   : ${PerformanceBudgets.M3U_RETAINED_HEAP_MB_MAX} MB maximum
            Memory must not grow with library size. Something is now accumulating
            entries — a list, a map, a cache, or a captured closure. On a 128 MB
            heap this is an OOM, not a slowdown.
            """.trimIndent(),
            retainedMb <= PerformanceBudgets.M3U_RETAINED_HEAP_MB_MAX,
        )
    }

    @Test
    fun `xmltv parse throughput stays within budget`() {
        val programmes = 60_000
        val document = Fixtures.xmltv(channels = 400, programmes = programmes)

        val measurement = Harness.measure("xmltv-parse") {
            var seen = 0L
            var acc = 0L
            XmltvParser.parse(StringReader(document)) { programme ->
                seen++
                acc += programme.title.length + programme.channelId.length +
                    (programme.description?.length ?: 0) + programme.startMs
            }
            Sink.consumed = acc
            seen
        }
        report(measurement)

        assertTrue(
            """
            XMLTV parsing regressed.
              measured : ${"%,d".format(measurement.perSecond)} programmes/sec
              budget   : ${"%,d".format(PerformanceBudgets.XMLTV_PROGRAMMES_PER_SECOND_MIN)} programmes/sec minimum
            Usual causes: a DOM or pull parser replacing the streaming scan, or
            SimpleDateFormat being used for timestamps.
            """.trimIndent(),
            measurement.perSecond >= PerformanceBudgets.XMLTV_PROGRAMMES_PER_SECOND_MIN,
        )
    }

    @Test
    fun `xmltv parsing does not retain the guide`() {
        val programmes = PerformanceBudgets.XMLTV_PROBE_PROGRAMMES
        val document = Fixtures.xmltv(channels = 800, programmes = programmes)
        var seen = 0L

        var acc = 0L
        val retainedKb = Harness.retainedKb {
            XmltvParser.parse(StringReader(document)) { programme ->
                seen++
                acc += programme.title.length
            }
            Sink.consumed = acc
        }
        val retainedMb = retainedKb / 1024
        println("[budget] xmltv-retained-heap: $retainedKb KB after $seen programmes")

        assertTrue("expected $programmes programmes, got $seen", seen > programmes * 0.9)
        assertTrue(
            """
            XMLTV parsing is retaining the guide.
              measured : $retainedMb MB retained after ${"%,d".format(seen)} programmes
              budget   : ${PerformanceBudgets.XMLTV_RETAINED_HEAP_MB_MAX} MB maximum
            A real guide is millions of programmes; retention here does not scale.
            """.trimIndent(),
            retainedMb <= PerformanceBudgets.XMLTV_RETAINED_HEAP_MB_MAX,
        )
    }

    @Test
    fun `xmltv timestamp conversion stays within budget`() {
        val samples = List(1_000) { "2026072506${"%02d".format(it % 60)}00 +0200" }

        val measurement = Harness.measure("xmltv-timestamp", runs = 7) {
            var acc = 0L
            repeat(200) {
                for (s in samples) acc += XmltvParser.parseXmltvTime(s)
            }
            Sink.consumed = acc
            (samples.size * 200).toLong()
        }
        report(measurement)

        assertTrue(
            """
            XMLTV timestamp parsing regressed.
              measured : ${"%,d".format(measurement.perSecond)} conversions/sec
              budget   : ${"%,d".format(PerformanceBudgets.XMLTV_TIMESTAMPS_PER_SECOND_MIN)} conversions/sec minimum
            This runs once per programme. SimpleDateFormat or Instant.parse here
            allocates per call and shows up across an entire guide load.
            """.trimIndent(),
            measurement.perSecond >= PerformanceBudgets.XMLTV_TIMESTAMPS_PER_SECOND_MIN,
        )
    }

    // ------------------------------------------------------------ correctness
    // A fast parser that returns wrong data is not a fast parser.

    @Test
    fun `m3u parser reads real-world attributes`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="nova.1" tvg-name="Nova" tvg-logo="http://cdn/l,1.png" group-title="Sports",Nova Sports 1
            http://host/live/u/p/1.ts
            #EXTGRP:News
            #EXTINF:-1,Atlas News
            http://host/live/u/p/2.ts
            #EXTINF:7200 tvg-id="m.9" group-title="Movies",The Long Return
            http://host/movie/u/p/9.mp4
        """.trimIndent().lineSequence()

        val parsed = mutableListOf<com.castivio.data.parsing.M3uEntry>()
        val stats = M3uParser.parse(playlist) { parsed.add(it) }

        assertEquals(3, stats.parsed)
        assertEquals("Nova Sports 1", parsed[0].name)
        assertEquals("nova.1", parsed[0].tvgId)
        // The logo URL contains a comma — the display name must survive it.
        assertEquals("http://cdn/l,1.png", parsed[0].logoUrl)
        assertEquals("Sports", parsed[0].groupTitle)
        assertTrue(parsed[0].isLive)

        // #EXTGRP applies to the following entry.
        assertEquals("Atlas News", parsed[1].name)
        assertEquals("News", parsed[1].groupTitle)

        // A positive duration means VOD, not live.
        assertEquals(7200, parsed[2].durationSeconds)
        assertTrue(!parsed[2].isLive)
    }

    @Test
    fun `xmltv parser reads channels programmes and entities`() {
        val guide = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="nova.1"><display-name>Nova Sports</display-name><icon src="http://cdn/n.png"/></channel>
              <programme start="20260725210000 +0200" stop="20260725230000 +0200" channel="nova.1">
                <title lang="en">Cup Final &amp; Analysis</title>
                <desc><![CDATA[Live from the stadium]]></desc>
              </programme>
            </tv>
        """.trimIndent()

        val channels = mutableListOf<com.castivio.data.parsing.XmltvChannel>()
        val programmes = mutableListOf<com.castivio.data.parsing.XmltvProgramme>()
        val stats = XmltvParser.parse(StringReader(guide), { channels.add(it) }) { programmes.add(it) }

        assertEquals(1, stats.channels)
        assertEquals(1, stats.programmes)
        assertEquals("Nova Sports", channels[0].displayName)
        assertEquals("http://cdn/n.png", channels[0].iconUrl)
        assertEquals("Cup Final & Analysis", programmes[0].title)      // entity resolved
        assertEquals("Live from the stadium", programmes[0].description) // CDATA unwrapped
        assertTrue("start must parse", programmes[0].startMs > 0)
        assertTrue("stop must follow start", programmes[0].stopMs > programmes[0].startMs)
    }

    @Test
    fun `xmltv timestamps honour the utc offset`() {
        // Same instant expressed in two zones must agree.
        val utc = XmltvParser.parseXmltvTime("20260725120000 +0000")
        val plusTwo = XmltvParser.parseXmltvTime("20260725140000 +0200")
        assertEquals(utc, plusTwo)
    }

    private fun report(m: Measurement) = println("[budget] $m")
}
