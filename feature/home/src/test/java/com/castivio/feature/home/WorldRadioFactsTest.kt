package com.castivio.feature.home

import com.castivio.domain.DirectoryFailure
import com.castivio.domain.DirectoryResult
import com.castivio.domain.WorldCountry
import com.castivio.domain.WorldTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** What the world radio says, from what the directory said and nothing else. */
class WorldRadioFactsTest {

    private val english = Locale.forLanguageTag("en-US")

    // ---------------------------------------------------------------------------------- country names

    @Test
    fun `a country is named in the reader's language`() {
        assertEquals("Morocco", countryNameOf("MA", "Maroc", english))
        assertEquals("المغرب", countryNameOf("MA", "Morocco", Locale.forLanguageTag("ar")))
    }

    @Test
    fun `a code the platform cannot name falls back to the directory's own name`() {
        assertEquals("Nowhere", countryNameOf("", "Nowhere", english))
        assertEquals("Nowhere", countryNameOf("1", "Nowhere", english))
        assertEquals("Nowhere", countryNameOf("ABC", "Nowhere", english))
    }

    // ---------------------------------------------------------------------------------- the rail

    private val countries = listOf(
        WorldCountry("MA", "Morocco", 105),
        WorldCountry("FR", "France", 640),
        WorldCountry("ES", "Spain", 410),
        WorldCountry("DE", "Germany", 900),
    )

    @Test
    fun `the countries are in the reader's alphabetical order, each with its count`() {
        val entries = countryEntries(countries, english)

        assertEquals(listOf("France", "Germany", "Morocco", "Spain"), entries.map { it.label })
        assertEquals(listOf(640, 900, 105, 410), entries.map { it.count })
        assertEquals(WorldChoice.Country("MA"), entries.first { it.label == "Morocco" }.choice)
    }

    @Test
    fun `the viewer's own country comes first`() {
        val entries = countryEntries(countries, Locale.forLanguageTag("en-MA"))

        assertEquals(listOf("Morocco", "France", "Germany", "Spain"), entries.map { it.label })
    }

    @Test
    fun `a country is its own entry once, whatever the order`() {
        val entries = countryEntries(countries, english)

        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }

    @Test
    fun `the tags keep the directory's order and read as words`() {
        val entries = tagEntries(listOf(WorldTag("pop", 900), WorldTag("news", 310)), english)

        assertEquals(listOf("Pop", "News"), entries.map { it.label })
        assertEquals(WorldChoice.Tag("news"), entries[1].choice)
        assertEquals(listOf("t:pop", "t:news"), entries.map { it.key })
    }

    @Test
    fun `the field narrows by what the name holds in any case, or by the country's code`() {
        val entries = countryEntries(countries, english)

        assertEquals(listOf("Germany"), entries.matching("erma").map { it.label })
        assertEquals(listOf("Morocco"), entries.matching("MOROC").map { it.label })
        assertEquals("a code is a way to a country", listOf("Spain"), entries.matching("es").map { it.label })
        assertEquals(entries, entries.matching("  "))
        assertTrue(entries.matching("zzz").isEmpty())
    }

    // ---------------------------------------------------------------------------------- directory answers

    @Test
    fun `a fresh answer is a list, a stale one is a list from the saved copy, no answer is a failure, and none yet is a wait`() {
        val all = listOf("a", "b")
        val named = { names: List<String> -> names.map { WorldRailEntry("k:$it", it.uppercase(), 1, WorldChoice.Tag(it)) } }
        val entries = named(all)

        assertEquals(RailLoad.Ready(entries, fromSavedList = false), DirectoryResult.Fresh(all).asRail(named))
        assertEquals(RailLoad.Ready(entries, fromSavedList = true), DirectoryResult.Stale(all, ageMs = 5).asRail(named))
        assertEquals(
            RailLoad.Unavailable(DirectoryFailure.OFFLINE),
            DirectoryResult.Unavailable(DirectoryFailure.OFFLINE).asRail(named),
        )
        assertEquals(RailLoad.Loading, (null as DirectoryResult<List<String>>?).asRail(named))
    }

    // ---------------------------------------------------------------------------------- the line under a name

    @Test
    fun `a station says its country where the list mixes them, and what it plays at`() {
        val station = worldStation("s1", codec = "mp3", bitrate = 128)

        assertEquals("Morocco · 128 kbps · MP3", worldStationCaption(station, "Morocco", "128 kbps"))
        assertEquals("128 kbps · MP3", worldStationCaption(station, null, "128 kbps"))
    }

    @Test
    fun `only what the directory said is said`() {
        assertEquals("AAC", worldStationCaption(worldStation("s1", codec = "AAC", bitrate = 0), null, null))
        assertEquals("Morocco", worldStationCaption(worldStation("s1", codec = null, bitrate = 0), "Morocco", null))
        assertNull(worldStationCaption(worldStation("s1", codec = "", bitrate = 0), "", null))
    }
}
