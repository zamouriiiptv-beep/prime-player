package com.castivio.data.parsing

import com.castivio.domain.WorldCountry
import com.castivio.domain.WorldStation
import com.castivio.domain.WorldTag
import java.io.Reader
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * The world radio directory's JSON, read an element at a time like every other list in the application.
 *
 * Tolerant on purpose: a directory is a community's, written by many hands, and a field that is absent, empty, null, a number where a
 * string was expected or the other way about must cost that field and not the list. An element without what it cannot be used without --
 * a station with no uuid, no name or no address -- is skipped; everything else is read as far as it goes.
 *
 * Nothing here knows a server, a path or a network: it is given a reader.
 */
object RadioBrowserParser {

    /** `/json/countries`: the code, the English name and how many stations. */
    fun parseCountries(reader: Reader, onCountry: (WorldCountry) -> Unit): Int {
        var count = 0
        val scanner = JsonScanner(reader)
        scanner.readArray {
            var name: String? = null
            var code: String? = null
            var stations = 0
            scanner.readObject { field ->
                when (field) {
                    "name" -> name = scanner.string()
                    "iso_3166_1" -> code = scanner.string()
                    "stationcount" -> stations = scanner.int()
                }
            }
            val iso = code?.trim()?.uppercase()
            val label = name?.trim()
            if (iso != null && iso.length == 2 && iso.all { it in 'A'..'Z' } && label != null && stations > 0) {
                onCountry(WorldCountry(code = iso, name = label, stationCount = stations))
                count++
            }
        }
        return count
    }

    /** `/json/tags`: a tag and how many stations carry it. Tags that are empty, or carry nothing, are left out. */
    fun parseTags(reader: Reader, onTag: (WorldTag) -> Unit): Int {
        var count = 0
        val scanner = JsonScanner(reader)
        scanner.readArray {
            var name: String? = null
            var stations = 0
            scanner.readObject { field ->
                when (field) {
                    "name" -> name = scanner.string()
                    "stationcount" -> stations = scanner.int()
                }
            }
            val tag = name?.trim()
            if (tag != null && tag.isNotEmpty() && stations > 0) {
                onTag(WorldTag(name = tag, stationCount = stations))
                count++
            }
        }
        return count
    }

    /** `/json/stations/...`: a list of stations. Returns how many were sent, before nothing was screened out -- see `WorldPage.rawCount`. */
    fun parseStations(reader: Reader, onStation: (WorldStation) -> Unit): Int {
        var sent = 0
        val scanner = JsonScanner(reader)
        scanner.readArray {
            sent++
            var uuid: String? = null
            var name: String? = null
            var url: String? = null
            var resolved: String? = null
            var favicon: String? = null
            var tags: String? = null
            var countryCode: String? = null
            var country: String? = null
            var codec: String? = null
            var bitrate = 0
            var hls = false
            var checkedOk: Boolean? = null
            var checkedIso: String? = null
            var checkedPlain: String? = null
            var votes = 0
            var clicks = 0
            var sslError = false
            scanner.readObject { field ->
                when (field) {
                    "stationuuid" -> uuid = scanner.string()
                    "name" -> name = scanner.string()
                    "url" -> url = scanner.string()
                    "url_resolved" -> resolved = scanner.string()
                    "favicon" -> favicon = scanner.string()
                    "tags" -> tags = scanner.string()
                    "countrycode" -> countryCode = scanner.string()
                    "country" -> country = scanner.string()
                    "codec" -> codec = scanner.string()
                    "bitrate" -> bitrate = scanner.int()
                    "hls" -> hls = scanner.boolean()
                    "lastcheckok" -> checkedOk = scanner.boolean()
                    "lastchecktime_iso8601" -> checkedIso = scanner.string()
                    "lastchecktime" -> checkedPlain = scanner.string()
                    "votes" -> votes = scanner.int()
                    "clickcount" -> clicks = scanner.int()
                    "ssl_error" -> sslError = scanner.boolean()
                }
            }
            val id = uuid?.trim()
            val title = name?.trim()
            val address = url?.trim()
            if (!id.isNullOrEmpty() && !title.isNullOrEmpty() && !address.isNullOrEmpty()) {
                onStation(
                    WorldStation(
                        uuid = id,
                        name = title,
                        url = address,
                        urlResolved = resolved?.trim()?.takeIf { it.isNotEmpty() },
                        favicon = favicon?.trim()?.takeIf { it.isNotEmpty() },
                        countryCode = countryCode?.trim()?.uppercase()?.takeIf { it.length == 2 },
                        country = country?.trim()?.takeIf { it.isNotEmpty() },
                        tags = tags.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                        codec = codec?.trim()?.takeIf { it.isNotEmpty() },
                        bitrate = bitrate.coerceAtLeast(0),
                        hls = hls,
                        serverCheckedOk = checkedOk,
                        serverCheckedAtMs = checkTimeOf(checkedIso, checkedPlain),
                        votes = votes,
                        clickCount = clicks,
                        sslError = sslError,
                    ),
                )
            }
        }
        return sent
    }

    /** `/json/servers`: the servers' names, as they are to be reached by. */
    fun parseServers(reader: Reader, onHost: (String) -> Unit): Int {
        var count = 0
        val scanner = JsonScanner(reader)
        scanner.readArray {
            var name: String? = null
            scanner.readObject { field -> if (field == "name") name = scanner.string() }
            val host = name?.trim()?.lowercase()
            if (!host.isNullOrEmpty()) {
                onHost(host)
                count++
            }
        }
        return count
    }

    /** What `/json/url/{uuid}` answers: whether it counted the listen, and the address the station has now. */
    data class ListenAnswer(val ok: Boolean, val url: String?)

    fun parseListen(reader: Reader): ListenAnswer? {
        var ok = false
        var url: String? = null
        var sawAny = false
        val scanner = JsonScanner(reader)
        scanner.readObject { field ->
            sawAny = true
            when (field) {
                "ok" -> ok = scanner.boolean()
                "url" -> url = scanner.string()
            }
        }
        return if (sawAny) ListenAnswer(ok = ok, url = url?.trim()?.takeIf { it.isNotEmpty() }) else null
    }

    /**
     * When the directory last checked the stream, from whichever of its two spellings was sent: ISO 8601 with an offset, or
     * `yyyy-MM-dd HH:mm:ss` in UTC. Null when neither reads as a time.
     */
    internal fun checkTimeOf(iso: String?, plain: String?): Long? {
        if (!iso.isNullOrBlank()) {
            try {
                return OffsetDateTime.parse(iso.trim()).toInstant().toEpochMilli()
            } catch (ignored: DateTimeParseException) {
                // fall through to the other spelling
            }
        }
        if (!plain.isNullOrBlank()) {
            try {
                return LocalDateTime.parse(plain.trim().replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (ignored: DateTimeParseException) {
                return null
            }
        }
        return null
    }
}
