package com.castivio.data.parsing

import com.castivio.domain.WorldCountry
import com.castivio.domain.WorldStation
import com.castivio.domain.WorldTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/** The directory's lists, read tolerantly: what is absent costs that field, what cannot be used is skipped. */
class RadioBrowserParserTest {

    private fun stations(json: String): Pair<Int, List<WorldStation>> {
        val out = ArrayList<WorldStation>()
        val sent = RadioBrowserParser.parseStations(StringReader(json)) { out += it }
        return sent to out
    }

    // -------------------------------------------------------------------------------- countries

    @Test
    fun `countries are read with their code, name and count`() {
        val out = ArrayList<WorldCountry>()
        val count = RadioBrowserParser.parseCountries(
            StringReader(
                """[{"name":"Morocco","iso_3166_1":"MA","stationcount":105},
                    {"name":"The Netherlands","iso_3166_1":"nl","stationcount":"412"}]""",
            ),
        ) { out += it }

        assertEquals(2, count)
        assertEquals(WorldCountry("MA", "Morocco", 105), out[0])
        assertEquals("a lower case code is upper case, a count sent as text is a count", WorldCountry("NL", "The Netherlands", 412), out[1])
    }

    @Test
    fun `a country with no code, a code that is not two letters, or no stations is left out`() {
        val out = ArrayList<WorldCountry>()
        val count = RadioBrowserParser.parseCountries(
            StringReader(
                """[{"name":"Nowhere","iso_3166_1":"","stationcount":5},
                    {"name":"Odd","iso_3166_1":"XYZ","stationcount":5},
                    {"name":"Digits","iso_3166_1":"1A","stationcount":5},
                    {"name":"Empty","iso_3166_1":"EM","stationcount":0},
                    {"iso_3166_1":"NA","stationcount":3},
                    {"name":"Chad","iso_3166_1":"TD","stationcount":1}]""",
            ),
        ) { out += it }

        assertEquals(1, count)
        assertEquals("TD", out.single().code)
    }

    // ------------------------------------------------------------------------------------- tags

    @Test
    fun `tags are read and the empty or the unused ones are left out`() {
        val out = ArrayList<WorldTag>()
        RadioBrowserParser.parseTags(
            StringReader("""[{"name":"pop","stationcount":900},{"name":"  ","stationcount":4},{"name":"quran","stationcount":0},{"name":"news","stationcount":310}]"""),
        ) { out += it }

        assertEquals(listOf(WorldTag("pop", 900), WorldTag("news", 310)), out)
    }

    // ---------------------------------------------------------------------------------- stations

    @Test
    fun `a station is read with everything the directory said of it`() {
        val (sent, out) = stations(
            """[{"changeuuid":"c1","stationuuid":"s-1","name":" RIM Radio ","url":"http://a.invalid/rim","url_resolved":"https://b.invalid/rim.mp3",
                "homepage":"https://rim.invalid","favicon":"https://rim.invalid/f.png","tags":"news, talk,news,,music","country":"Morocco",
                "countrycode":"ma","codec":"MP3","bitrate":128,"hls":0,"lastcheckok":1,"lastchecktime_iso8601":"2026-11-04T12:30:41Z",
                "votes":12,"clickcount":340,"ssl_error":0,"geo_lat":null}]""",
        )

        assertEquals(1, sent)
        val s = out.single()
        assertEquals("s-1", s.uuid)
        assertEquals("RIM Radio", s.name)
        assertEquals("http://a.invalid/rim", s.url)
        assertEquals("https://b.invalid/rim.mp3", s.urlResolved)
        assertEquals("https://b.invalid/rim.mp3", s.streamUrl)
        assertEquals("https://rim.invalid/f.png", s.favicon)
        assertEquals(listOf("news", "talk", "music"), s.tags)
        assertEquals("MA", s.countryCode)
        assertEquals("Morocco", s.country)
        assertEquals("MP3", s.codec)
        assertEquals(128, s.bitrate)
        assertEquals(false, s.hls)
        assertEquals(true, s.serverCheckedOk)
        assertEquals(1_793_795_441_000L, s.serverCheckedAtMs)
        assertEquals(12, s.votes)
        assertEquals(340, s.clickCount)
        assertEquals(false, s.sslError)
    }

    @Test
    fun `the plain spelling of the check time is read as UTC`() {
        assertEquals(1_793_795_441_000L, RadioBrowserParser.checkTimeOf(null, "2026-11-04 12:30:41"))
        assertEquals(1_793_795_441_000L, RadioBrowserParser.checkTimeOf("2026-11-04T14:30:41+02:00", null))
        assertEquals("the ISO spelling wins when both are sent", 1_793_795_441_000L, RadioBrowserParser.checkTimeOf("2026-11-04T12:30:41Z", "garbage"))
        assertNull(RadioBrowserParser.checkTimeOf("not a time", "also not"))
        assertNull(RadioBrowserParser.checkTimeOf(null, null))
    }

    @Test
    fun `a station with no uuid, no name or no address is skipped, and still counted as sent`() {
        val (sent, out) = stations(
            """[{"name":"No uuid","url":"http://a.invalid/1"},
                {"stationuuid":"s2","url":"http://a.invalid/2"},
                {"stationuuid":"s3","name":"No address"},
                {"stationuuid":"s4","name":"Fine","url":"http://a.invalid/4"}]""",
        )

        assertEquals("the page's end is read from what was sent", 4, sent)
        assertEquals(listOf("s4"), out.map { it.uuid })
    }

    @Test
    fun `fields that are missing, empty or of another type cost that field only`() {
        val (_, out) = stations(
            """[{"stationuuid":"s1","name":"Bare","url":"http://a.invalid/1","url_resolved":"","favicon":null,"tags":"","countrycode":"",
                 "codec":"","bitrate":"96","hls":"1","lastcheckok":"0","votes":"x","clickcount":null,"ssl_error":true}]""",
        )

        val s = out.single()
        assertNull(s.urlResolved)
        assertNull(s.favicon)
        assertTrue(s.tags.isEmpty())
        assertNull(s.countryCode)
        assertNull(s.codec)
        assertEquals(96, s.bitrate)
        assertEquals(true, s.hls)
        assertEquals(false, s.serverCheckedOk)
        assertNull(s.serverCheckedAtMs)
        assertEquals(0, s.votes)
        assertEquals(0, s.clickCount)
        assertEquals(true, s.sslError)
    }

    @Test
    fun `a station the directory never checked has no verdict`() {
        val (_, out) = stations("""[{"stationuuid":"s1","name":"New","url":"http://a.invalid/1"}]""")

        assertNull(out.single().serverCheckedOk)
        assertNull(out.single().serverCheckedAtMs)
    }

    @Test
    fun `an empty list is an empty list`() {
        val (sent, out) = stations("[]")

        assertEquals(0, sent)
        assertTrue(out.isEmpty())
    }

    // --------------------------------------------------------------------------- servers, listens

    @Test
    fun `servers are read by name, in lower case`() {
        val out = ArrayList<String>()
        RadioBrowserParser.parseServers(
            StringReader("""[{"ip":"1.2.3.4","name":"DE1.api.radio-browser.info"},{"ip":"5.6.7.8","name":"fr1.api.radio-browser.info"},{"ip":"9.9.9.9"}]"""),
        ) { out += it }

        assertEquals(listOf("de1.api.radio-browser.info", "fr1.api.radio-browser.info"), out)
    }

    @Test
    fun `a listen's answer carries whether it counted and the address now`() {
        val answer = RadioBrowserParser.parseListen(
            StringReader("""{"ok":true,"message":"retrieved station url","stationuuid":"s1","name":"RIM","url":"https://b.invalid/now.mp3"}"""),
        )

        assertEquals(RadioBrowserParser.ListenAnswer(ok = true, url = "https://b.invalid/now.mp3"), answer)
    }

    @Test
    fun `an answer that is not an object is no answer`() {
        assertNull(RadioBrowserParser.parseListen(StringReader("{}")))
    }
}
