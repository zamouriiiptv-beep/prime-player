package com.castivio.domain.provider

import com.castivio.domain.PlaylistSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a provider actually sends people, and what has to happen to it.
 *
 * Most of these inputs are real shapes: a bare host with no scheme, a `player_api.php`
 * URL where an origin was asked for, a trailing slash, a password with a space on the
 * end from a copy. None of them are the user's mistake in any sense they could act on,
 * so none of them are errors — they are work this file does silently.
 */
class FieldValidationTest {

    // ------------------------------------------------------------------ the name

    @Test
    fun `the name is optional`() {
        val empty = FieldValidation.playlistName("")

        assertTrue(empty.isValid)
        assertEquals("", empty.value)
    }

    @Test
    fun `the name is trimmed`() {
        assertEquals("Home", FieldValidation.playlistName("  Home  ").value)
    }

    @Test
    fun `a name longer than the field allows is refused and truncated`() {
        val long = FieldValidation.playlistName("x".repeat(FieldValidation.MAX_NAME + 1))

        assertEquals(FieldProblem.TOO_LONG, long.problem)
        assertEquals(FieldValidation.MAX_NAME, long.value.length)
    }

    // ------------------------------------------------------------ the server url

    @Test
    fun `a bare host gets the scheme providers assume`() {
        assertEquals("http://line.example.com:8080", FieldValidation.serverUrl("line.example.com:8080").value)
    }

    @Test
    fun `https is kept when it is given`() {
        assertEquals("https://line.example.com", FieldValidation.serverUrl("https://line.example.com").value)
    }

    /**
     * The single most useful thing in this file. Providers send `player_api.php` links,
     * `get.php` links and panel URLs when they mean "your server is here", and an Xtream
     * client that keeps the path builds `…/c/player_api.php` and fails unreadably.
     */
    @Test
    fun `a server url is reduced to its origin`() {
        val pasted = listOf(
            "http://line.example.com:8080/player_api.php?username=bob&password=hunter2",
            "http://line.example.com:8080/c/",
            "http://line.example.com:8080/",
            "http://line.example.com:8080/panel_api.php",
            "  http://line.example.com:8080  ",
        )

        for (text in pasted) {
            assertEquals(text, "http://line.example.com:8080", FieldValidation.serverUrl(text).value)
        }
    }

    @Test
    fun `credentials in the authority are dropped rather than stored`() {
        assertEquals(
            "http://line.example.com:8080",
            FieldValidation.serverUrl("http://bob:hunter2@line.example.com:8080").value,
        )
    }

    @Test
    fun `an empty server url is required`() {
        assertEquals(FieldProblem.REQUIRED, FieldValidation.serverUrl("   ").problem)
    }

    @Test
    fun `a scheme we cannot fetch is refused`() {
        assertEquals(FieldProblem.UNSUPPORTED_SCHEME, FieldValidation.serverUrl("rtmp://line.example.com").problem)
        assertEquals(FieldProblem.UNSUPPORTED_SCHEME, FieldValidation.serverUrl("ftp://line.example.com").problem)
        assertEquals(FieldProblem.UNSUPPORTED_SCHEME, FieldValidation.serverUrl("htp://line.example.com").problem)
    }

    @Test
    fun `half a url is an incomplete host`() {
        val halves = listOf(
            "http://",
            "https://",
            // A port that arrived without its host. Never typed on purpose.
            "8080",
            "http://:8080",
            // Empty labels.
            "http://.com",
            "http://example.",
            "http://a..b",
            // A label cannot start or end with a hyphen.
            "http://-example.com",
            "http://example-.com",
        )

        for (text in halves) {
            assertEquals(text, FieldProblem.INCOMPLETE_HOST, FieldValidation.serverUrl(text).problem)
        }
    }

    /**
     * A dot is a convention of public domains, not a rule of hostnames. Someone running
     * a panel on their own network reaches it by a single-label name, and refusing that
     * would break a setup that works to catch a typo that mostly does not happen.
     */
    @Test
    fun `a hostname without a dot is a hostname`() {
        val internal = listOf("myserver", "localhost", "iptv-box", "nas_01", "MyServer")

        for (host in internal) {
            val checked = FieldValidation.serverUrl(host)

            assertTrue("$host -> ${checked.problem}", checked.isValid)
            assertEquals("http://$host", checked.value)
        }
    }

    @Test
    fun `a single label host keeps its port and its scheme`() {
        assertEquals("http://myserver:8080", FieldValidation.serverUrl("myserver:8080").value)
        assertEquals("https://localhost:443", FieldValidation.serverUrl("https://localhost:443").value)
    }

    @Test
    fun `a single label host works as a playlist url too`() {
        assertEquals(
            "http://myserver:8080/playlist.m3u",
            FieldValidation.playlistUrl("myserver:8080/playlist.m3u").value,
        )
    }

    @Test
    fun `a label longer than dns allows is refused`() {
        val tooLong = "a".repeat(64)

        assertEquals(FieldProblem.INCOMPLETE_HOST, FieldValidation.serverUrl("http://$tooLong.com").problem)
        assertTrue(FieldValidation.serverUrl("http://${"a".repeat(63)}.com").isValid)
    }

    @Test
    fun `an ipv6 literal is a host`() {
        assertTrue(FieldValidation.serverUrl("http://[2001:db8::1]:8080").isValid)
        assertEquals("http://[2001:db8::1]:8080", FieldValidation.serverUrl("http://[2001:db8::1]:8080").value)
    }

    @Test
    fun `a port that is not a port is refused`() {
        assertEquals(FieldProblem.INVALID_PORT, FieldValidation.serverUrl("http://line.example.com:abc").problem)
        assertEquals(FieldProblem.INVALID_PORT, FieldValidation.serverUrl("http://line.example.com:99999").problem)
        assertEquals(FieldProblem.INVALID_PORT, FieldValidation.serverUrl("http://line.example.com:0").problem)
    }

    @Test
    fun `a url with a space in it is a paste that broke`() {
        assertEquals(
            FieldProblem.CONTAINS_SPACES,
            FieldValidation.serverUrl("http://line.example.com:8080/get. php").problem,
        )
    }

    @Test
    fun `an ip address is a host`() {
        assertEquals("http://203.0.113.9:8080", FieldValidation.serverUrl("203.0.113.9:8080").value)
    }

    // ---------------------------------------------------------- the playlist url

    /**
     * The opposite rule from [serverUrl]: here the path and query are the link, and
     * nothing may be trimmed off them.
     */
    @Test
    fun `a playlist url keeps everything after the host`() {
        val url = "http://line.example.com:8080/get.php?username=bob&password=hunter2&type=m3u_plus"

        assertEquals(url, FieldValidation.playlistUrl(url).value)
    }

    @Test
    fun `a playlist url with no scheme gets one`() {
        assertEquals(
            "http://line.example.com/playlist.m3u8",
            FieldValidation.playlistUrl("line.example.com/playlist.m3u8").value,
        )
    }

    /** Plenty of working M3U links end in nothing recognisable. Guessing would lose them. */
    @Test
    fun `a playlist url is not required to look like a playlist`() {
        val opaque = listOf(
            "http://line.example.com/9f2c81ab",
            "http://line.example.com/api/v2/list?token=abc",
            "https://cdn.example.com/u/1234/tv",
        )

        for (url in opaque) {
            assertTrue(url, FieldValidation.playlistUrl(url).isValid)
        }
    }

    @Test
    fun `an empty playlist url is required`() {
        assertEquals(FieldProblem.REQUIRED, FieldValidation.playlistUrl("").problem)
    }

    // -------------------------------------------------------------- the detection

    /**
     * The link a provider actually e-mails, pasted where the user found a box for it.
     * It works as an M3U, but as Xtream it also gets categories, series, catch-up and a
     * subscription status the app can read — so it is worth offering.
     */
    @Test
    fun `an xtream link pasted as a playlist is recognised`() {
        val detected = FieldValidation.detectXtream(
            "http://line.example.com:8080/get.php?username=bob&password=hunter2&type=m3u_plus",
        )

        assertEquals(PlaylistSource.Xtream("http://line.example.com:8080", "bob", "hunter2"), detected)
    }

    @Test
    fun `the order of the parameters does not matter`() {
        val detected = FieldValidation.detectXtream(
            "http://line.example.com/get.php?type=m3u_plus&password=hunter2&output=ts&username=bob",
        )

        assertEquals(PlaylistSource.Xtream("http://line.example.com", "bob", "hunter2"), detected)
    }

    @Test
    fun `percent encoded credentials are decoded`() {
        val detected = FieldValidation.detectXtream(
            "http://line.example.com/get.php?username=bob%40example&password=hunter%232",
        )

        assertEquals(PlaylistSource.Xtream("http://line.example.com", "bob@example", "hunter#2"), detected)
    }

    @Test
    fun `an ordinary playlist link is not mistaken for xtream`() {
        val plain = listOf(
            "http://line.example.com/playlist.m3u8",
            "http://line.example.com/get.php?username=bob",
            "http://line.example.com/get.php?password=hunter2",
            "http://line.example.com/get.php?username=&password=hunter2",
            "not a url at all",
        )

        for (url in plain) {
            assertNull(url, FieldValidation.detectXtream(url))
        }
    }

    // -------------------------------------------------------------- credentials

    @Test
    fun `a credential is required`() {
        assertEquals(FieldProblem.REQUIRED, FieldValidation.username("").problem)
        assertEquals(FieldProblem.REQUIRED, FieldValidation.password("   ").problem)
    }

    /**
     * The trailing space from a copy is the single most common reason a correct password
     * is refused, and no user could ever see it. Trimming is not leniency, it is the
     * right answer.
     */
    @Test
    fun `a credential is trimmed rather than refused`() {
        assertEquals("hunter2", FieldValidation.password("  hunter2\n").value)
        assertTrue(FieldValidation.password("  hunter2\n").isValid)
    }

    /** Providers do issue awkward passwords. Rejecting them would be our bug, not theirs. */
    @Test
    fun `an awkward password is still a password`() {
        val awkward = listOf("p@ss w0rd", "üñî©ødé", "!#$%&*()", "hunter 2")

        for (text in awkward) {
            assertTrue(text, FieldValidation.password(text).isValid)
        }
    }

    // -------------------------------------------------------------- the xtream form

    @Test
    fun `a complete xtream form submits the normalised values`() {
        val check = XtreamFormCheck.of(
            name = "  Home  ",
            serverUrl = "line.example.com:8080/player_api.php",
            username = "bob ",
            password = " hunter2",
        )

        assertTrue(check.canSubmit)
        assertEquals("Home", check.label)
        assertEquals(
            PlaylistSource.Xtream("http://line.example.com:8080", "bob", "hunter2"),
            check.source,
        )
    }

    @Test
    fun `an xtream form with no name still submits`() {
        val check = XtreamFormCheck.of("", "line.example.com:8080", "bob", "hunter2")

        assertTrue(check.canSubmit)
        assertNull(check.label)
    }

    @Test
    fun `an xtream form withholds everything while any field is wrong`() {
        val check = XtreamFormCheck.of("Home", "line.example.com:8080", "bob", "")

        assertFalse(check.canSubmit)
        assertNull(check.source)
        // And the good fields still report as good, so only the offending one is marked.
        assertTrue(check.serverUrl.isValid)
        assertTrue(check.username.isValid)
        assertEquals(FieldProblem.REQUIRED, check.password.problem)
    }

    // ----------------------------------------------------------------- the m3u form

    @Test
    fun `a complete m3u form submits`() {
        val check = M3uFormCheck.of("Backup", "line.example.com/playlist.m3u8")

        assertTrue(check.canSubmit)
        assertEquals("Backup", check.label)
        assertEquals(PlaylistSource.M3u("http://line.example.com/playlist.m3u8"), check.source)
        assertNull(check.xtream)
    }

    @Test
    fun `an m3u form carries the xtream offer without acting on it`() {
        val check = M3uFormCheck.of("", "http://line.example.com:8080/get.php?username=bob&password=hunter2")

        // Still perfectly submittable as M3U: the offer is the screen's to make.
        assertTrue(check.canSubmit)
        assertEquals(
            PlaylistSource.M3u("http://line.example.com:8080/get.php?username=bob&password=hunter2"),
            check.source,
        )
        assertEquals(
            PlaylistSource.Xtream("http://line.example.com:8080", "bob", "hunter2"),
            check.xtream,
        )
    }

    @Test
    fun `an m3u form with a broken url withholds the source`() {
        val check = M3uFormCheck.of("Backup", "rtmp://line.example.com/live")

        assertFalse(check.canSubmit)
        assertNull(check.source)
        assertEquals(FieldProblem.UNSUPPORTED_SCHEME, check.url.problem)
    }
}
