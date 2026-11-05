package com.castivio.data.parsing

import com.castivio.domain.WorldListing
import java.net.URLEncoder

/**
 * The paths the world radio directory is asked for, and nothing else: pure, so what is asked is asserted without a server.
 *
 * Every list is asked for with `hidebroken=true` -- a first filter, and only that: see `StationHealth` for what is read again here --
 * and in pages, with an offset and a limit. There is no path for "all of it".
 */
object RadioBrowserQuery {

    /** Pages of this size, as everywhere in the application. */
    const val PAGE_SIZE = 60

    /** The most tags asked for: a long tail of one-station tags is not a list anyone browses. */
    const val TAG_LIMIT = 200

    fun servers(): String = "/json/servers"

    fun countries(): String = "/json/countries?hidebroken=true&order=name"

    fun tags(): String = "/json/tags?hidebroken=true&order=stationcount&reverse=true&limit=$TAG_LIMIT"

    fun stations(listing: WorldListing, offset: Int, limit: Int): String {
        require(offset >= 0) { "offset $offset" }
        require(limit in 1..MAX_LIMIT) { "limit $limit" }
        val filter = when (listing) {
            WorldListing.Top -> ""
            is WorldListing.OfCountry -> "&countrycode=${encode(listing.code.uppercase())}"
            is WorldListing.OfTag -> "&tag=${encode(listing.tag)}&tagExact=true"
            is WorldListing.Search -> "&name=${encode(listing.text.trim())}"
        }
        return "/json/stations/search?hidebroken=true&order=clickcount&reverse=true$filter&offset=$offset&limit=$limit"
    }

    /** The stations with these uuids, in one request. */
    fun byUuids(uuids: List<String>): String =
        "/json/stations/byuuid?uuids=${encode(uuids.joinToString(","))}"

    /** Tells the directory a station is listened to, and answers with the address it has now. */
    fun listen(uuid: String): String = "/json/url/${encode(uuid)}"

    /** Percent-encoded as a query value or a path segment: a space is `%20`, never `+`. */
    internal fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private const val MAX_LIMIT = 500
}
