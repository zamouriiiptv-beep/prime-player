package com.castivio.data.parsing

import com.castivio.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * Fixtures here are shaped like real Xtream panel responses, including the parts
 * that are technically wrong: numbers as strings, `"0"` for false, base64 titles,
 * empty strings for absent values.
 */
class XtreamParserTest {

    @Test
    fun `categories parse`() {
        val json = """
            [{"category_id":"1","category_name":"Sports","parent_id":0},
             {"category_id":"2","category_name":"| AR | RADIO","parent_id":0}]
        """.trimIndent()
        val categories = mutableListOf<XtreamCategory>()

        val count = XtreamParser.parseCategories(StringReader(json), MediaKind.LIVE) { categories.add(it) }

        assertEquals(2, count)
        assertEquals(listOf("Sports", "| AR | RADIO"), categories.map { it.name })
        assertTrue(categories.all { it.kind == MediaKind.LIVE })
    }

    @Test
    fun `live streams parse, with catch-up only when the provider really offers it`() {
        val json = """
            [{"num":1,"name":"Nova Sports 1","stream_type":"live","stream_id":1234,
              "stream_icon":"http://cdn/nova.png","epg_channel_id":"nova.1","added":"1700000000",
              "category_id":"1","tv_archive":1,"direct_source":"","tv_archive_duration":7},
             {"num":2,"name":"Atlas News","stream_type":"live","stream_id":"1235",
              "stream_icon":"","epg_channel_id":null,"added":"0",
              "category_id":"1","tv_archive":0,"tv_archive_duration":"7"}]
        """.trimIndent()
        val streams = mutableListOf<XtreamStream>()

        val count = XtreamParser.parseStreams(StringReader(json)) { streams.add(it) }

        assertEquals(2, count)
        val nova = streams[0]
        assertEquals("1234", nova.streamId)
        assertEquals("Nova Sports 1", nova.name)
        assertEquals("nova.1", nova.epgChannelId)
        // Seven days of archive, reported in hours.
        assertEquals(168, nova.catchUpHours)
        assertEquals(1, nova.number)
        assertEquals(1_700_000_000L, nova.addedEpochSeconds)

        val atlas = streams[1]
        assertEquals("1235", atlas.streamId)
        assertNull("an empty icon is absent, not a URL", atlas.iconUrl)
        assertNull(atlas.epgChannelId)
        // A duration with tv_archive = 0 is not catch-up: showing a rewind control
        // that fails is worse than showing none.
        assertNull(atlas.catchUpHours)
        assertNull(atlas.addedEpochSeconds)
    }

    @Test
    fun `vod streams keep their container extension`() {
        val json = """
            [{"num":1,"name":"Dune","stream_type":"movie","stream_id":"98","stream_icon":"http://cdn/d.jpg",
              "rating":"8.1","category_id":"5","container_extension":"mkv","custom_sid":null}]
        """.trimIndent()
        val streams = mutableListOf<XtreamStream>()

        XtreamParser.parseStreams(StringReader(json)) { streams.add(it) }

        assertEquals("mkv", streams.single().containerExtension)
    }

    @Test
    fun `series parse`() {
        val json = """
            [{"num":1,"name":"Chernobyl","series_id":551,"cover":"http://cdn/c.jpg",
              "plot":"1986","category_id":"9","last_modified":"1700000001",
              "backdrop_path":["http://cdn/b1.jpg","http://cdn/b2.jpg"]}]
        """.trimIndent()
        val series = mutableListOf<XtreamSeries>()

        XtreamParser.parseSeries(StringReader(json)) { series.add(it) }

        val show = series.single()
        assertEquals("551", show.seriesId)
        assertEquals("Chernobyl", show.name)
        assertEquals("http://cdn/c.jpg", show.coverUrl)
        assertEquals("1986", show.plot)
        assertEquals(1_700_000_001L, show.lastModifiedEpochSeconds)
    }

    @Test
    fun `series info yields episodes with seasons from the keys`() {
        val json = """
            {"seasons":[{"season_number":1,"name":"Season 1"}],
             "info":{"name":"Chernobyl","cover":"http://cdn/c.jpg"},
             "episodes":{
               "1":[{"id":"9001","episode_num":1,"title":"1:23:45","container_extension":"mkv",
                     "info":{"movie_image":"http://cdn/e1.jpg","duration_secs":3660}},
                    {"id":"9002","episode_num":2,"title":"Please Remain Calm","container_extension":"mkv",
                     "info":{"duration_secs":"3300"}}],
               "2":[{"id":"9101","episode_num":1,"title":"Next Season","container_extension":"mp4","info":{}}]
             }}
        """.trimIndent()
        val episodes = mutableListOf<XtreamEpisode>()

        val count = XtreamParser.parseSeriesInfo(StringReader(json)) { episodes.add(it) }

        assertEquals(3, count)
        assertEquals(listOf("1:23:45", "Please Remain Calm", "Next Season"), episodes.map { it.title })
        assertEquals(listOf(1, 1, 2), episodes.map { it.seasonNumber })
        assertEquals(listOf(1, 2, 1), episodes.map { it.episodeNumber })
        assertEquals("http://cdn/e1.jpg", episodes[0].coverUrl)
        assertEquals(3_660, episodes[0].durationSeconds)
        assertEquals(3_300, episodes[1].durationSeconds)
        assertNull(episodes[2].durationSeconds)
    }

    @Test
    fun `the account response says whether the credentials will work`() {
        val json = """
            {"user_info":{"username":"u","password":"p","message":"","auth":1,"status":"Active",
              "exp_date":"1800000000","is_trial":"0","active_cons":"1","created_at":"1600000000",
              "max_connections":"2","allowed_output_formats":["m3u8","ts"]},
             "server_info":{"url":"host","port":"8080","https_port":"443","server_protocol":"http",
              "timezone":"Europe/Paris","timestamp_now":1784980800,"time_now":"2026-07-25 14:00:00"}}
        """.trimIndent()

        val account = XtreamParser.parseAccount(StringReader(json))!!

        assertTrue(account.authenticated)
        assertTrue(account.isUsable)
        assertEquals("Active", account.status)
        assertEquals(1_800_000_000_000L, account.expiresAtMs)
        assertFalse(account.isTrial)
        assertEquals(1, account.activeConnections)
        assertEquals(2, account.maxConnections)
        assertEquals("Europe/Paris", account.timezone)
        assertFalse(account.atConnectionLimit)
        assertFalse(account.isExpiredAt(1_784_980_800_000L))
    }

    @Test
    fun `an expired or banned account is reported as unusable`() {
        val expired = XtreamParser.parseAccount(
            StringReader("""{"user_info":{"auth":1,"status":"Expired","exp_date":"1600000000",
                "is_trial":"1","active_cons":"2","max_connections":"2"}}""".trimIndent()),
        )!!

        assertFalse("a non-Active status must not be treated as usable", expired.isUsable)
        assertTrue(expired.isExpiredAt(1_784_980_800_000L))
        assertTrue(expired.isTrial)
        // Every allowed connection already in use: the player would fail to start.
        assertTrue(expired.atConnectionLimit)
    }

    @Test
    fun `a failed login is distinguishable from a broken response`() {
        val denied = XtreamParser.parseAccount(StringReader("""{"user_info":{"auth":0}}"""))!!
        assertFalse(denied.authenticated)
        assertFalse(denied.isUsable)

        // No user_info at all: not an Xtream panel, or an HTML error page.
        assertNull(XtreamParser.parseAccount(StringReader("""{"unexpected":true}""")))
    }

    @Test
    fun `short epg decodes base64 titles and uses the timestamps`() {
        val json = """
            {"epg_listings":[
              {"id":"1","epg_id":"7","title":"Q3VwIEZpbmFs","lang":"","start":"2026-07-25 12:00:00",
               "end":"2026-07-25 14:00:00","description":"TGl2ZSBmb290YmFsbA==","channel_id":"nova.1",
               "start_timestamp":"1784980800","stop_timestamp":"1784988000","now_playing":1},
              {"id":"2","epg_id":"7","title":"Plain Title","description":"","channel_id":"nova.1",
               "start_timestamp":"1784988000","stop_timestamp":"0"}
            ]}
        """.trimIndent()
        val entries = mutableListOf<XtreamEpgEntry>()

        val count = XtreamParser.parseShortEpg(StringReader(json)) { entries.add(it) }

        assertEquals(2, count)
        assertEquals("Cup Final", entries[0].title)
        assertEquals("Live football", entries[0].description)
        assertEquals(1_784_980_800_000L, entries[0].startMs)
        assertEquals(1_784_988_000_000L, entries[0].stopMs)
        // Not base64: shown as typed rather than as gibberish.
        assertEquals("Plain Title", entries[1].title)
        // No usable stop: filled in rather than left as a zero-length programme.
        assertTrue(entries[1].stopMs > entries[1].startMs)
    }

    @Test
    fun `base64 decoding falls back to the input when it is not base64`() {
        assertEquals("Cup Final", XtreamParser.decodeBase64OrSelf("Q3VwIEZpbmFs"))
        assertEquals("Sport", XtreamParser.decodeBase64OrSelf("Sport"))
        assertEquals("Match: Live", XtreamParser.decodeBase64OrSelf("Match: Live"))
        assertEquals("مرحبا", XtreamParser.decodeBase64OrSelf("2YXYsdit2KjYpw=="))
        // Decodes to bytes that are not text — keep what the provider sent.
        assertEquals("////", XtreamParser.decodeBase64OrSelf("////"))
    }

    @Test
    fun `rows missing an id or name are skipped rather than stored broken`() {
        val json = """
            [{"name":"No id","stream_id":""},
             {"stream_id":"5","name":""},
             {"stream_id":"6","name":"Good"}]
        """.trimIndent()
        val streams = mutableListOf<XtreamStream>()

        val count = XtreamParser.parseStreams(StringReader(json)) { streams.add(it) }

        assertEquals(1, count)
        assertEquals("Good", streams.single().name)
    }

    @Test
    fun `an html error page is a parse failure, not silent success`() {
        val failure = runCatching {
            XtreamParser.parseStreams(StringReader("<html><body>403 Forbidden</body></html>")) { }
        }
        assertTrue(failure.exceptionOrNull() is JsonFormatException)
    }
}
