package com.castivio.data.parsing

import com.castivio.domain.MediaKind
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a portal actually answers, and what has to be survived.
 *
 * The replies here are the shapes portals send rather than the shapes a specification
 * promises: `js` as an object, as a list, as `false`, and as a page from a web server
 * that is not a portal at all. Each of those reached a user as a different bug the
 * first time this was written against one well-behaved panel.
 */
class StalkerParserTest {

    /* -------------------------------------------------------------- the handshake */

    @Test
    fun `a handshake yields its token`() {
        val token = StalkerParser.parseHandshake(
            StringReader("""{"js":{"token":"A1B2C3D4E5","random":"9f"}}"""),
        )
        assertEquals("A1B2C3D4E5", token)
    }

    /**
     * Not a portal, and that is an answer rather than a crash.
     *
     * A bare host answers `/server/load.php` with its index page or a JSON error, and
     * the caller's next move — ask the next endpoint — depends on telling "this is not
     * a portal" apart from "this portal said no".
     */
    @Test
    fun `a reply with no token is no token, not an error`() {
        assertNull(StalkerParser.parseHandshake(StringReader("""{"js":false}""")))
        assertNull(StalkerParser.parseHandshake(StringReader("""{"error":"nope"}""")))
        assertNull(StalkerParser.parseHandshake(StringReader("""{"js":{"token":""}}""")))
    }

    /* ---------------------------------------------------------------- the profile */

    @Test
    fun `a healthy profile is usable`() {
        val profile = StalkerParser.parseProfile(
            StringReader("""{"js":{"blocked":"0","status":0,"name":"Sam","phone":"2027-03-14"}}"""),
        )!!
        assertTrue(profile.isUsable)
        assertEquals("2027-03-14", profile.expiresText)
        assertEquals("Sam", profile.accountName)
    }

    /**
     * Blocked and non-zero status are both refusals, and both have to be read.
     *
     * Stalker's convention is the opposite of Xtream's — zero is healthy here, where
     * `"Active"` is healthy there — which is the kind of inversion that silently
     * passes if only one of the two is ever tested.
     */
    @Test
    fun `a blocked or non-zero account is not usable`() {
        val blocked = StalkerParser.parseProfile(StringReader("""{"js":{"blocked":"1","status":0}}"""))!!
        val refused = StalkerParser.parseProfile(StringReader("""{"js":{"blocked":"0","status":2}}"""))!!
        assertFalse(blocked.isUsable)
        assertFalse(refused.isUsable)
    }

    @Test
    fun `a reply with no profile in it is null`() {
        assertNull(StalkerParser.parseProfile(StringReader("""{"error":"token expired"}""")))
    }

    /* --------------------------------------------------------------- the channels */

    @Test
    fun `channels are read one at a time, with the total beside them`() {
        val json = """
            {"js":{"total_items":3,"max_page_items":2,"data":[
              {"id":"101","name":"Al Jazeera","cmd":"ffmpeg http://host/live/101.ts","logo":"aj.png","number":"1","tv_archive":"1"},
              {"id":"102","name":"BBC One","cmd":"http://host/live/102.ts","number":"2"}
            ]}}
        """.trimIndent()

        var total = 0
        val found = mutableListOf<StalkerChannel>()
        StalkerParser.channels(StringReader(json), onTotal = { total = it }) { found += it }

        assertEquals(3, total)
        assertEquals(listOf("Al Jazeera", "BBC One"), found.map { it.name })
        assertEquals("aj.png", found[0].logo)
        assertTrue(found[0].hasArchive)
        assertFalse(found[1].hasArchive)
    }

    /**
     * A channel with no command is not a channel.
     *
     * Portals send placeholder rows — a name and nothing to play — and a catalogue
     * that imported them would list channels that do nothing when pressed, which is
     * worse than a shorter list.
     */
    @Test
    fun `a row with nothing to play is dropped`() {
        val json = """{"js":{"data":[{"id":"1","name":"Nothing"},{"id":"2","name":"Real","cmd":"http://h/2.ts"}]}}"""
        val found = mutableListOf<StalkerChannel>()
        StalkerParser.channels(StringReader(json)) { found += it }
        assertEquals(listOf("Real"), found.map { it.name })
    }

    @Test
    fun `an empty portal reads as no channels`() {
        val found = mutableListOf<StalkerChannel>()
        StalkerParser.channels(StringReader("""{"js":{"total_items":0,"data":[]}}""")) { found += it }
        assertTrue(found.isEmpty())
    }

    /* ------------------------------------------------------------- what cmd means */

    /**
     * The two forms of command that carry an address, and the one that does not.
     *
     * The third is the reason this is a nullable property and not a `String`: a
     * `/media/…` command is resolved by the portal at play time, and a URL invented
     * for it would be a link that fails in the player rather than a fact the importer
     * can act on.
     */
    @Test
    fun `an address is taken from a command only when it carries one`() {
        assertEquals(
            "http://host/live/1.ts",
            StalkerChannel("1", "n", "ffmpeg http://host/live/1.ts", null, null, false).directUrl,
        )
        assertEquals(
            "http://host/live/1.ts",
            StalkerChannel("1", "n", "http://host/live/1.ts", null, null, false).directUrl,
        )
        assertNull(StalkerChannel("1", "n", "/media/file_1.mpg", null, null, false).directUrl)
    }

    /* ------------------------------------------------------- films, shows, stations */

    /**
     * A film carries the things a library screen shows, and they are read.
     *
     * The year, the running time and the poster are the difference between a films
     * grid and a list of titles. `time` is minutes on every portal seen and the
     * catalogue counts seconds, so the conversion happens here rather than being left
     * for a screen to guess at.
     */
    @Test
    fun `a film is read with its poster, its year and its running time`() {
        val json = """
            {"js":{"total_items":1,"data":[
              {"id":"55","name":"Dune","o_name":"Dune (2021)","cmd":"/media/file_55.mpg",
               "screenshot_uri":"http://host/p/55.jpg","year":"2021","time":"155"}
            ]}}
        """.trimIndent()

        val found = mutableListOf<StalkerItem>()
        StalkerParser.items(StringReader(json), MediaKind.MOVIE) { found += it }

        val film = found.single()
        assertEquals("Dune", film.name)
        assertEquals("2021", film.year)
        assertEquals(155 * 60, film.durationSeconds)
        assertEquals("http://host/p/55.jpg", film.posterUrl)
        assertEquals(MediaKind.MOVIE, film.kind)
    }

    /**
     * The kind is what was asked for, not what the row looks like.
     *
     * Portals put one-off specials under `series` and film-length documentaries under
     * `vod`, and a parser that re-decided would be overruling the provider about its
     * own catalogue.
     */
    @Test
    fun `an item takes the kind the portal was asked for`() {
        val json = """{"js":{"data":[{"id":"1","name":"Station","cmd":"http://h/1"}]}}"""
        val radio = mutableListOf<StalkerItem>()
        StalkerParser.items(StringReader(json), MediaKind.RADIO) { radio += it }
        assertEquals(MediaKind.RADIO, radio.single().kind)
    }

    /**
     * A show with no command of its own is kept when it has episodes under it.
     *
     * This is where a film and a show part company: a portal hands out one command per
     * episode, so a series row legitimately has none. Dropping it for that reason — the
     * rule that is right for a channel — would empty the Series section on every portal
     * that behaves normally.
     */
    @Test
    fun `a series with episodes is kept even with no command of its own`() {
        val json = """{"js":{"data":[{"id":"9","name":"The Wire","series":[1,2,3]}]}}"""
        val found = mutableListOf<StalkerItem>()
        StalkerParser.items(StringReader(json), MediaKind.SERIES) { found += it }

        assertEquals(1, found.size)
        assertEquals(3, found.single().episodeCount)
        assertNull(found.single().command)
    }

    /** And a row with neither a command nor episodes is nothing at all. */
    @Test
    fun `a row with nothing to play and no episodes is dropped`() {
        val json = """{"js":{"data":[{"id":"9","name":"Placeholder"}]}}"""
        val found = mutableListOf<StalkerItem>()
        StalkerParser.items(StringReader(json), MediaKind.MOVIE) { found += it }
        assertTrue(found.isEmpty())
    }

    @Test
    fun `a row with no title is dropped, whatever else it carries`() {
        val json = """{"js":{"data":[{"id":"9","cmd":"http://h/9"},{"id":"10","name":"Real","cmd":"http://h/10"}]}}"""
        val found = mutableListOf<StalkerItem>()
        StalkerParser.items(StringReader(json), MediaKind.MOVIE) { found += it }
        assertEquals(listOf("Real"), found.map { it.name })
    }

    @Test
    fun `an empty section reads as nothing rather than as an error`() {
        val found = mutableListOf<StalkerItem>()
        StalkerParser.items(StringReader("""{"js":false}"""), MediaKind.MOVIE) { found += it }
        assertTrue(found.isEmpty())
    }

    /* ------------------------------------------------------------------ create_link */

    /**
     * What the portal resolved a `/media/…` command into.
     *
     * This is the call that makes those rows playable rather than guessed at: the
     * protocol has an answer, and it is asked rather than imitated.
     */
    @Test
    fun `a resolved link is the address the portal returned`() {
        assertEquals(
            "http://host/live/55.ts",
            StalkerParser.parseLink(StringReader("""{"js":{"cmd":"ffmpeg http://host/live/55.ts"}}""")),
        )
    }

    /** A refusal is a refusal, and is not turned into a link. */
    @Test
    fun `a link the portal would not resolve is null`() {
        assertNull(StalkerParser.parseLink(StringReader("""{"js":false}""")))
        assertNull(StalkerParser.parseLink(StringReader("""{"js":{"cmd":""}}""")))
        assertNull(StalkerParser.parseLink(StringReader("""{"js":{"cmd":"/media/file_55.mpg"}}""")))
    }
}
