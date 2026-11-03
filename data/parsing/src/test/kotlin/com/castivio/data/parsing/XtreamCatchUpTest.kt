package com.castivio.data.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * What a channel's archive listing says, and the address an archived programme is asked for by.
 *
 * Fixtures are strings: no server, no clock. The base64 titles are real ones (`Sport` and `News at ten`).
 */
class XtreamCatchUpTest {

    private fun listings(body: String): List<XtreamArchiveEntry> {
        val out = ArrayList<XtreamArchiveEntry>()
        XtreamParser.parseArchiveListings(StringReader(body)) { out.add(it) }
        return out
    }

    @Test
    fun `a listing reads the instant, the panel's own start, the archive mark and the decoded text`() {
        val entries = listings(
            """{"epg_listings":[{"id":"1","epg_id":"7","title":"U3BvcnQ=","lang":"","start":"2025-11-02 21:30:00",
            "end":"2025-11-02 22:30:00","description":"TmV3cyBhdCB0ZW4=","channel_id":"bein","start_timestamp":"1762119000",
            "stop_timestamp":"1762122600","now_playing":0,"has_archive":1}]}""",
        )

        val entry = entries.single()
        assertEquals("Sport", entry.title)
        assertEquals("News at ten", entry.description)
        assertEquals(1_762_119_000_000L, entry.startMs)
        assertEquals(1_762_122_600_000L, entry.stopMs)
        assertEquals("2025-11-02 21:30:00", entry.serverStart)
        assertTrue(entry.archived)
    }

    @Test
    fun `a programme the panel did not record is listed and not marked`() {
        val entry = listings(
            """{"epg_listings":[{"title":"Plain title","start":"2025-11-02 21:30:00","start_timestamp":1762119000,
            "stop_timestamp":1762122600,"has_archive":0}]}""",
        ).single()

        assertFalse(entry.archived)
        assertEquals("Plain title", entry.title)
    }

    @Test
    fun `the archive mark is read however the panel writes it`() {
        val body = { mark: String ->
            """{"epg_listings":[{"title":"T","start":"2025-11-02 21:30:00","start_timestamp":1762119000,"has_archive":$mark}]}"""
        }

        assertTrue(listings(body("1")).single().archived)
        assertTrue(listings(body("\"1\"")).single().archived)
        assertTrue(listings(body("true")).single().archived)
        assertFalse(listings(body("0")).single().archived)
        assertFalse(listings(body("\"0\"")).single().archived)
        assertFalse(listings(body("null")).single().archived)
    }

    @Test
    fun `an entry with no instant or no start string cannot be addressed and is not reported`() {
        val entries = listings(
            """{"epg_listings":[
            {"title":"no instant","start":"2025-11-02 21:30:00","has_archive":1},
            {"title":"no start","start_timestamp":1762119000,"has_archive":1},
            {"title":"fine","start":"2025-11-02 22:30:00","start_timestamp":1762122600,"has_archive":1}]}""",
        )

        assertEquals(listOf("fine"), entries.map { it.title })
    }

    @Test
    fun `a listing without a stop gets the default length`() {
        val entry = listings(
            """{"epg_listings":[{"title":"T","start":"2025-11-02 21:30:00","start_timestamp":1762119000}]}""",
        ).single()

        assertEquals(1_762_119_000_000L + 30 * 60_000L, entry.stopMs)
    }

    @Test
    fun `an empty or absent listing is no entries`() {
        assertTrue(listings("""{"epg_listings":[]}""").isEmpty())
        assertTrue(listings("""{"epg_listings":null}""").isEmpty())
        assertTrue(listings("""{}""").isEmpty())
    }

    @Test
    fun `other fields and other members are stepped over`() {
        val entries = listings(
            """{"server":{"x":[1,2,3]},"epg_listings":[{"junk":{"a":[1]},"title":"T","start":"2025-11-02 21:30:00",
            "start_timestamp":1762119000,"has_archive":1,"more":[{}]}]}""",
        )

        assertEquals(1, entries.size)
    }

    // ----------------------------------------------------------------------------------- the start

    @Test
    fun `the start is written the way the archive path wants it, digits unchanged`() {
        assertEquals("2025-11-02:21-30", XtreamUrls.timeshiftStart("2025-11-02 21:30:00"))
        assertEquals("2025-11-02:21-30", XtreamUrls.timeshiftStart("2025-11-02T21:30:00"))
        assertEquals("2025-01-09:00-05", XtreamUrls.timeshiftStart("  2025-01-09 00:05:59  "))
    }

    @Test
    fun `a start that is not a date and a time is refused`() {
        assertNull(XtreamUrls.timeshiftStart(""))
        assertNull(XtreamUrls.timeshiftStart("2025-11-02"))
        assertNull(XtreamUrls.timeshiftStart("02/11/2025 21:30"))
        assertNull(XtreamUrls.timeshiftStart("2025-11-02 2x:30:00"))
        assertNull(XtreamUrls.timeshiftStart("2025-11-02 21-30-00"))
    }

    // ---------------------------------------------------------------------------------- the address

    @Test
    fun `an archive address has the duration in minutes, the start and the stream id, and the base is normalised`() {
        val url = XtreamUrls.timeshift(
            base = "host.example:8080/player_api.php",
            username = "user",
            password = "pw",
            streamId = "1234",
            durationMinutes = 60,
            start = "2025-11-02:21-30",
        )

        assertEquals("http://host.example:8080/timeshift/user/pw/60/2025-11-02:21-30/1234.ts", url)
    }

    @Test
    fun `credentials are encoded in the path and a duration is never less than one minute`() {
        val url = XtreamUrls.timeshift("http://h", "a b", "p@ss+&", "9", durationMinutes = 0, start = "2025-11-02:21-30")

        assertTrue(url, url.startsWith("http://h/timeshift/a%20b/p%40ss%2B%26/1/"))
        assertTrue(url, url.endsWith("/9.ts"))
    }

    @Test
    fun `the container can be named and an empty one falls back to ts`() {
        assertTrue(XtreamUrls.timeshift("http://h", "u", "p", "9", 5, "2025-11-02:21-30", extension = ".m3u8").endsWith("/9.m3u8"))
        assertTrue(XtreamUrls.timeshift("http://h", "u", "p", "9", 5, "2025-11-02:21-30", extension = " ").endsWith("/9.ts"))
    }
}
