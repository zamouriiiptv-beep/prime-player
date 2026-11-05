package com.castivio.domain

/**
 * The world's radio: a directory of stations that is not the subscription's.
 *
 * **Nothing here is a [MediaItem] or a [Channel].** A station of the world has no place in the catalogue, in the subscription's
 * favourites or in its list of what was watched last, and the types say so: it is its own thing, held in its own store, and the
 * radio's session holds it in a slot of its own. See `WorldRadioDirectory` for where it comes from and `WorldRadioLibrary` for what
 * the viewer keeps of it.
 */
data class WorldCountry(
    /** ISO 3166-1 alpha-2, upper case: what the directory is asked for the country's stations by, and what its name is made from. */
    val code: String,
    /** The directory's own English name, kept as a fallback: the screen names the country from [code], in the reader's language. */
    val name: String,
    val stationCount: Int,
)

/** A tag the directory files stations under -- a genre, a language, a format -- with how many carry it. */
data class WorldTag(val name: String, val stationCount: Int)

/**
 * A station of the directory, identified by [uuid] and nothing else.
 *
 * The directory's own `stationuuid` is the one key that does not change when somebody edits the station, which is why it is the key here:
 * what a viewer keeps of a station -- a favourite, a listen, a record of whether it plays -- is kept under it.
 */
data class WorldStation(
    val uuid: String,
    val name: String,
    /** The address the station was submitted with. */
    val url: String,
    /** The address the directory resolved it to -- a playlist followed to its stream -- or null when it did not. */
    val urlResolved: String?,
    val favicon: String?,
    /** ISO 3166-1 alpha-2, upper case, or null for a station of no country. */
    val countryCode: String?,
    val country: String?,
    val tags: List<String>,
    val codec: String?,
    /** Kilobits per second, or 0 when the directory does not know. */
    val bitrate: Int,
    val hls: Boolean,
    /** What the directory's last check of the stream said, or null when it said nothing. It may be old: see [serverCheckedAtMs]. */
    val serverCheckedOk: Boolean?,
    /** When the directory last checked the stream, in epoch milliseconds, or null. */
    val serverCheckedAtMs: Long?,
    val votes: Int,
    val clickCount: Int,
    val sslError: Boolean,
) {
    /** The address the player is asked for first: the resolved one where there is one. */
    val streamUrl: String get() = urlResolved?.takeIf { it.isNotBlank() } ?: url

    /** The other address, to try once when the first fails: null when the station has only one. */
    val alternateUrl: String? get() = listOfNotNull(url, urlResolved).firstOrNull { it.isNotBlank() && it != streamUrl }
}

/** Which list of stations is asked for. */
sealed interface WorldListing {
    /** The stations listened to most, in the whole world. */
    data object Top : WorldListing

    data class OfCountry(val code: String) : WorldListing

    data class OfTag(val tag: String) : WorldListing

    /** A search of the whole directory by name. */
    data class Search(val text: String) : WorldListing
}

/**
 * One page of a listing, as the directory sent it.
 *
 * [rawCount] is how many stations the directory sent, before anything was screened out of them: it, and not [stations]'s size, says
 * whether there is a next page, because a page the screening shortened is not the last.
 */
data class WorldPage(
    val stations: List<WorldStation>,
    val offset: Int,
    val limit: Int,
    val rawCount: Int = stations.size,
) {
    val endReached: Boolean get() = rawCount < limit
    val nextOffset: Int get() = offset + rawCount
}

/** Why the directory could not answer. */
enum class DirectoryFailure {
    /** No server could be reached at all: the device has no network, or none the directory's names resolve on. */
    OFFLINE,

    /** Servers were reached and answered with an error, or timed out, one after another. */
    SERVICE_DOWN,

    /** A server answered and what it sent could not be read as the directory's. */
    BAD_ANSWER,
}

/**
 * What the directory could say: fresh, old but better than nothing, or nothing.
 *
 * [Stale] is the answer for a list that is kept on the device -- the countries, the tags -- when the servers cannot be reached: the screen
 * draws it and says how old it is, instead of an error over a list it already has.
 */
sealed interface DirectoryResult<out T> {
    data class Fresh<out T>(val value: T) : DirectoryResult<T>

    data class Stale<out T>(val value: T, val ageMs: Long) : DirectoryResult<T>

    data class Unavailable(val reason: DirectoryFailure) : DirectoryResult<Nothing>
}

/** The value of a result that has one, fresh or stale, and null for one that has none. */
val <T> DirectoryResult<T>.valueOrNull: T?
    get() = when (this) {
        is DirectoryResult.Fresh -> value
        is DirectoryResult.Stale -> value
        is DirectoryResult.Unavailable -> null
    }

/**
 * The world radio's directory: where the stations come from.
 *
 * An interface because the directory is an implementation and not an assumption -- the first is a public one, reached over HTTP by whatever
 * servers it names -- and the screens know only this. Every call answers with a [DirectoryResult]; none of them throws for the network.
 */
interface WorldRadioDirectory {
    /** Every country with the stations it has, in the directory's order. Kept on the device: see [DirectoryResult.Stale]. */
    suspend fun countries(forceRefresh: Boolean = false): DirectoryResult<List<WorldCountry>>

    /** The tags with the most stations, most first. Kept on the device like the countries. */
    suspend fun tags(forceRefresh: Boolean = false): DirectoryResult<List<WorldTag>>

    /** A page of [listing], [limit] stations from [offset]. Never the whole list: there is no way to ask for it. */
    suspend fun page(listing: WorldListing, offset: Int, limit: Int): DirectoryResult<WorldPage>

    /** The stations with these uuids, as the directory has them now: what a favourite is refreshed with. Absent ones are absent. */
    suspend fun stationsByUuid(uuids: List<String>): DirectoryResult<List<WorldStation>>

    /**
     * Tells the directory a station is being listened to, as it asks to be told, and returns the address the station has now -- or null
     * when it could not be asked, in which case the address the viewer already has is the one to play. Nothing waits on it.
     */
    suspend fun registerListen(uuid: String): String?
}

/**
 * Bodies the directory keeps between runs: the countries and the tags, which change slowly and are asked for on every visit.
 *
 * Raw text, so the directory's own reader is the only code that knows its shape; and a table of its own in the world radio's store, so
 * clearing it touches nothing of the subscription's.
 */
interface DirectoryCache {
    suspend fun read(key: String): CachedBody?

    suspend fun write(key: String, body: String, fetchedAtMs: Long)
}

data class CachedBody(val body: String, val fetchedAtMs: Long)
