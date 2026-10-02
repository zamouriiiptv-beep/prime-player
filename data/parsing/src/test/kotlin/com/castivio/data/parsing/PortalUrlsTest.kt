package com.castivio.data.parsing

import com.castivio.domain.PlaylistSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a pasted portal address means, and what two of them have to agree about.
 *
 * The cases here are the shapes providers actually send, collected rather than
 * invented: a link to the web client, a Ministra installation in a directory, an
 * origin with a port and nothing else, and the API file a user copied out of a
 * browser's address bar because that is what was on screen.
 */
class PortalUrlsTest {

    /* ------------------------------------------------------------------- the root */

    @Test
    fun `the client's own segment is not part of the portal`() {
        assertEquals("http://host.example:8080", PortalUrls.root("http://host.example:8080/c/"))
        assertEquals("http://host.example:8080", PortalUrls.root("http://host.example:8080/c"))
    }

    /**
     * A Ministra installation keeps the directory it is installed in.
     *
     * This is the case that forbids treating a portal like an Xtream host: drop the
     * path and this address becomes `http://host.example`, which is a different portal
     * or no portal at all.
     */
    @Test
    fun `a portal installed in a directory keeps it`() {
        assertEquals(
            "http://host.example/stalker_portal",
            PortalUrls.root("http://host.example/stalker_portal/c/"),
        )
    }

    @Test
    fun `an address with no scheme is http, and an origin is left whole`() {
        assertEquals("http://host.example:8080", PortalUrls.root("host.example:8080"))
        assertEquals("http://host.example:8080", PortalUrls.root("http://host.example:8080/"))
    }

    /** The authority is never eaten, however much there is to peel after it. */
    @Test
    fun `peeling never reaches into the host`() {
        assertEquals("http://c", PortalUrls.root("http://c/c/"))
    }

    @Test
    fun `a copied API file is not part of the address`() {
        assertEquals(
            "http://host.example:8080",
            PortalUrls.root("http://host.example:8080/server/load.php?type=stb&action=handshake"),
        )
        assertEquals("http://host.example", PortalUrls.root("http://host.example/portal.php"))
    }

    /* -------------------------------------------------------------- the endpoints */

    /**
     * Three addresses, in the order they are worth asking.
     *
     * `server/load.php` first because it is what Stalker itself serves; `portal.php`
     * second because most resellers answer there too; the directory last, for the
     * provider who sent an origin and left out where their portal lives.
     */
    @Test
    fun `an origin offers all three endpoints, most likely first`() {
        assertEquals(
            listOf(
                "http://host.example:8080/server/load.php",
                "http://host.example:8080/portal.php",
                "http://host.example:8080/stalker_portal/server/load.php",
            ),
            PortalUrls.endpoints("http://host.example:8080/c/"),
        )
    }

    /** And an address that already names the directory does not offer it twice. */
    @Test
    fun `a portal already in its directory is not asked for it again`() {
        val endpoints = PortalUrls.endpoints("http://host.example/stalker_portal/c/")
        assertEquals(
            listOf(
                "http://host.example/stalker_portal/server/load.php",
                "http://host.example/stalker_portal/portal.php",
            ),
            endpoints,
        )
    }

    @Test
    fun `nothing is offered for an empty address`() {
        assertEquals(emptyList<String>(), PortalUrls.endpoints("   "))
    }

    /* ------------------------------------------------------------------- the call */

    /**
     * The flag the portal's own client sends.
     *
     * Without it a portal answers with a page rather than the JSON envelope, which
     * reads as "the server is up and talking nonsense" at every level above this one.
     */
    @Test
    fun `every call carries the JsHttpRequest flag`() {
        val url = PortalUrls.call("http://h/server/load.php", type = "stb", action = "handshake")
        assertTrue(url, url.endsWith("&JsHttpRequest=1-xml"))
        assertTrue(url, url.startsWith("http://h/server/load.php?type=stb&action=handshake"))
    }

    @Test
    fun `parameters are encoded, because a token is not safe in a query`() {
        val url = PortalUrls.call(
            endpoint = "http://h/portal.php",
            type = "itv",
            action = "get_ordered_list",
            parameters = mapOf("genre" to "films & series", "p" to "2"),
        )
        assertTrue(url, url.contains("genre=films%20%26%20series"))
        assertTrue(url, url.contains("p=2"))
    }

    /* ---------------------------------------------------------------- the identity */

    /**
     * One subscription, however the provider wrote the address down.
     *
     * The same claim `SourceIdsTest` makes about an Xtream account: a user who re-adds
     * the subscription they already have must not acquire a second row for it, because
     * the row is what their name, their catalogue and their favourites hang off.
     */
    @Test
    fun `one portal written four ways is one subscription`() {
        val ids = listOf(
            "http://host.example:8080/c/",
            "http://HOST.example:8080/c",
            "host.example:8080",
            "http://host.example:8080/",
        ).map { SourceIds.of(PlaylistSource.Portal(it)) }.toSet()

        assertEquals("the same portal produced ${ids.size} subscriptions", 1, ids.size)
    }

    /** And two portals on one machine stay two, which is what keeping the path buys. */
    @Test
    fun `two portals on one host are two subscriptions`() {
        assertNotEquals(
            SourceIds.of(PlaylistSource.Portal("http://host.example/c/")),
            SourceIds.of(PlaylistSource.Portal("http://host.example/stalker_portal/c/")),
        )
    }

    /** A portal and a playlist at the same address are not the same subscription. */
    @Test
    fun `a portal is never confused with a playlist`() {
        assertNotEquals(
            SourceIds.of(PlaylistSource.Portal("http://host.example:8080/c/")),
            SourceIds.of(PlaylistSource.M3u("http://host.example:8080/c/")),
        )
    }
}
