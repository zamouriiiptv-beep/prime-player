package com.castivio.feature.home

import androidx.compose.runtime.Immutable
import com.castivio.domain.DirectoryFailure
import com.castivio.domain.DirectoryResult
import com.castivio.domain.WorldCountry
import com.castivio.domain.WorldStation
import com.castivio.domain.WorldTag
import java.text.Collator
import java.util.Locale

/** Which list of the world's stations is open. The first three are the viewer's and the directory's own; a search is not one of them. */
sealed interface WorldChoice {
    /** The stations listened to most, in the whole world: what the radio opens on. */
    data object Top : WorldChoice

    /** The viewer's favourites among the world's stations -- the subscription's favourites are not these. */
    data object Favorites : WorldChoice

    /** The world's stations listened to last here -- the subscription's recently watched is not this. */
    data object Recents : WorldChoice

    data class Country(val code: String) : WorldChoice

    data class Tag(val name: String) : WorldChoice
}

/** One line of the world's rail: a country or a tag, as the viewer reads it, with how many stations carry it. */
@Immutable
data class WorldRailEntry(val key: String, val label: String, val count: Int, val choice: WorldChoice)

/** What a list of the rail is: on its way, there, there but kept from an earlier visit, or not to be had. */
sealed interface RailLoad {
    data object Loading : RailLoad

    /** [fromSavedList] is true when the servers could not be reached and the list is the one kept from before. */
    data class Ready(val entries: List<WorldRailEntry>, val fromSavedList: Boolean) : RailLoad

    data class Unavailable(val reason: DirectoryFailure) : RailLoad
}

/** The rail's content: countries, or tags when the viewer asked for them, narrowed by what was typed in its field. */
@Immutable
data class WorldRailState(
    val tags: Boolean = false,
    val filter: String = "",
    val load: RailLoad = RailLoad.Loading,
)

/** The favourites or the recents, with which of them rest -- failed lately, or the directory says do not play -- to be shown dimmed. */
@Immutable
data class SavedStations(val stations: List<WorldStation>, val dimmed: Set<String>)

/**
 * A country's name for the reader: the one their language gives its code, and the directory's English name where the platform has none.
 *
 * The directory sends its own English name for every country; a reader of Arabic or French is not asked to read it.
 */
internal fun countryNameOf(code: String, fallback: String, locale: Locale): String {
    val named = runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(locale) }.getOrNull()
    // The platform answers with the code itself for a region it does not know.
    return named?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: fallback
}

/**
 * The rail's countries: named in [locale], in its alphabetical order, with the viewer's own country first -- a listener in Morocco opens
 * the world's radio to Morocco's stations, not after two hundred others.
 */
internal fun countryEntries(countries: List<WorldCountry>, locale: Locale): List<WorldRailEntry> {
    val collator = Collator.getInstance(locale)
    val own = locale.country
    return countries
        .map { WorldRailEntry("c:${it.code}", countryNameOf(it.code, it.name, locale), it.stationCount, WorldChoice.Country(it.code)) }
        .sortedWith(compareBy<WorldRailEntry> { !(own.isNotEmpty() && it.key == "c:${own.uppercase(Locale.ROOT)}") }.thenComparator { a, b -> collator.compare(a.label, b.label) })
}

/** The rail's tags, most stations first as the directory sent them, each as a word a reader opens a list by. */
internal fun tagEntries(tags: List<WorldTag>, locale: Locale): List<WorldRailEntry> =
    tags.map { WorldRailEntry("t:${it.name}", it.name.replaceFirstChar { c -> c.titlecase(locale) }, it.stationCount, WorldChoice.Tag(it.name)) }

/** The entries whose name -- or whose country code -- holds [filter], in any case; all of them for a field with nothing in it. */
internal fun List<WorldRailEntry>.matching(filter: String): List<WorldRailEntry> {
    val needle = filter.trim()
    if (needle.isEmpty()) return this
    return filter { it.label.contains(needle, ignoreCase = true) || (it.choice as? WorldChoice.Country)?.code.equals(needle, ignoreCase = true) }
}

/** A directory answer as the rail's content. */
internal fun DirectoryResult<List<WorldRailEntry>>.asRail(): RailLoad = when (this) {
    is DirectoryResult.Fresh -> RailLoad.Ready(value, fromSavedList = false)
    is DirectoryResult.Stale -> RailLoad.Ready(value, fromSavedList = true)
    is DirectoryResult.Unavailable -> RailLoad.Unavailable(reason)
}

/**
 * The line under a station's name: its country where the list mixes countries, otherwise what it plays at -- `128 kbps · MP3` -- and
 * only what the directory said. A station it said nothing of has no line.
 */
internal fun worldStationCaption(station: WorldStation, countryLabel: String?, bitrateLabel: String?): String? {
    val format = listOfNotNull(
        bitrateLabel,
        station.codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT),
    ).joinToString(" · ")
    return listOfNotNull(countryLabel?.takeIf { it.isNotBlank() }, format.takeIf { it.isNotEmpty() }).joinToString(" · ").ifEmpty { null }
}
