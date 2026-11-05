package com.castivio.data.parsing

import com.castivio.domain.WorldListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the directory is asked for: pages of a list, always filtered once, never the whole of it. */
class RadioBrowserQueryTest {

    private fun path(listing: WorldListing, offset: Int = 0, limit: Int = 60) = RadioBrowserQuery.stations(listing, offset, limit)

    @Test
    fun `the top of the world is asked for by listens, most first, in a page`() {
        assertEquals(
            "/json/stations/search?hidebroken=true&order=clickcount&reverse=true&offset=0&limit=60",
            path(WorldListing.Top),
        )
    }

    @Test
    fun `a country is asked for by its code in upper case`() {
        assertTrue(path(WorldListing.OfCountry("ma"), offset = 120).contains("&countrycode=MA&"))
        assertTrue(path(WorldListing.OfCountry("ma"), offset = 120).endsWith("&offset=120&limit=60"))
    }

    @Test
    fun `a tag is asked for exactly, and encoded`() {
        assertTrue(path(WorldListing.OfTag("r&b soul")).contains("&tag=r%26b%20soul&tagExact=true&"))
    }

    @Test
    fun `a search is by name, trimmed and encoded, a space never a plus`() {
        val p = path(WorldListing.Search("  rim radio é  "))

        assertTrue(p, p.contains("&name=rim%20radio%20%C3%A9&"))
        assertFalse(p.contains("+"))
    }

    @Test
    fun `every list is filtered once and paged, and there is no way to ask for all of it`() {
        for (listing in listOf(WorldListing.Top, WorldListing.OfCountry("MA"), WorldListing.OfTag("news"), WorldListing.Search("x"))) {
            val p = path(listing)
            assertTrue(p, p.contains("hidebroken=true"))
            assertTrue(p, p.contains("&offset=0&limit=60"))
        }
        assertThrows(IllegalArgumentException::class.java) { path(WorldListing.Top, limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { path(WorldListing.Top, limit = 100_000) }
        assertThrows(IllegalArgumentException::class.java) { path(WorldListing.Top, offset = -1) }
    }

    @Test
    fun `the countries and the tags are asked for with a filter, and the tags with a limit`() {
        assertEquals("/json/countries?hidebroken=true&order=name", RadioBrowserQuery.countries())
        assertEquals("/json/tags?hidebroken=true&order=stationcount&reverse=true&limit=200", RadioBrowserQuery.tags())
    }

    @Test
    fun `stations by uuid go in one request, joined`() {
        assertEquals("/json/stations/byuuid?uuids=a%2Cb%2Cc", RadioBrowserQuery.byUuids(listOf("a", "b", "c")))
    }

    @Test
    fun `a listen is registered at its station's path`() {
        assertEquals("/json/url/9617a958-0601-11e8-ae97-52543be04c81", RadioBrowserQuery.listen("9617a958-0601-11e8-ae97-52543be04c81"))
        assertEquals("/json/url/a%2Fb", RadioBrowserQuery.listen("a/b"))
    }
}
