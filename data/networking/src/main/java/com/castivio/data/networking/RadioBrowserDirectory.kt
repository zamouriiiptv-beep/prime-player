package com.castivio.data.networking

import com.castivio.data.parsing.DirectoryCachePolicy
import com.castivio.data.parsing.RadioBrowserParser
import com.castivio.data.parsing.RadioBrowserQuery
import com.castivio.domain.DirectoryCache
import com.castivio.domain.DirectoryFailure
import com.castivio.domain.DirectoryResult
import com.castivio.domain.WorldCountry
import com.castivio.domain.WorldListing
import com.castivio.domain.WorldPage
import com.castivio.domain.WorldRadioDirectory
import com.castivio.domain.WorldStation
import com.castivio.domain.WorldTag
import java.io.Reader
import java.io.StringReader

/**
 * The world radio directory, reached over HTTPS through whichever of its servers answers.
 *
 * What it keeps, and for how long, is [DirectoryCachePolicy]'s: the countries and the tags on the device for a day -- and for two weeks
 * more, as a stale answer, when no server can be reached -- and pages of stations in memory for minutes. What it never does is ask for
 * everything: there is no call here that is not a page, a list of a few hundred, or a handful of uuids.
 */
class RadioBrowserDirectory internal constructor(
    private val api: RadioBrowserApi,
    private val cache: DirectoryCache,
    private val clock: () -> Long = System::currentTimeMillis,
) : WorldRadioDirectory {

    private val pages = object : LinkedHashMap<PageKey, KeptPage>(PAGES_KEPT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PageKey, KeptPage>): Boolean = size > PAGES_KEPT
    }

    private data class PageKey(val listing: WorldListing, val offset: Int, val limit: Int)

    private class KeptPage(val page: WorldPage, val atMs: Long)

    override suspend fun countries(forceRefresh: Boolean): DirectoryResult<List<WorldCountry>> =
        kept(KEY_COUNTRIES, forceRefresh, RadioBrowserQuery.countries()) { reader ->
            ArrayList<WorldCountry>().also { RadioBrowserParser.parseCountries(reader) { c -> it += c } }
        }

    override suspend fun tags(forceRefresh: Boolean): DirectoryResult<List<WorldTag>> =
        kept(KEY_TAGS, forceRefresh, RadioBrowserQuery.tags()) { reader ->
            ArrayList<WorldTag>().also { RadioBrowserParser.parseTags(reader) { t -> it += t } }
        }

    /**
     * A list kept on the device: the copy if it is fresh, the servers' word if it is not, and the copy again -- said to be stale -- when the
     * servers cannot be reached.
     */
    private suspend fun <T> kept(
        key: String,
        forceRefresh: Boolean,
        path: String,
        parse: (Reader) -> List<T>,
    ): DirectoryResult<List<T>> {
        val copy = cache.read(key)
        val age = copy?.let { clock() - it.fetchedAtMs }
        val verdict = age?.let(DirectoryCachePolicy::verdict)
        val fromCopy: List<T>? = copy?.let { runCatching { parse(StringReader(it.body)) }.getOrNull() }?.takeIf { it.isNotEmpty() }

        if (!forceRefresh && verdict == DirectoryCachePolicy.Verdict.FRESH && fromCopy != null) return DirectoryResult.Fresh(fromCopy)

        return when (val answer = api.get(path) { it.readText() }) {
            is DirectoryAnswer.Ok -> {
                val parsed = runCatching { parse(StringReader(answer.value)) }.getOrNull()
                if (parsed.isNullOrEmpty()) {
                    staleOr(fromCopy, age, verdict, DirectoryFailure.BAD_ANSWER)
                } else {
                    cache.write(key, answer.value, clock())
                    DirectoryResult.Fresh(parsed)
                }
            }
            is DirectoryAnswer.Failed -> staleOr(fromCopy, age, verdict, answer.reason)
        }
    }

    private fun <T> staleOr(
        copy: List<T>?,
        age: Long?,
        verdict: DirectoryCachePolicy.Verdict?,
        reason: DirectoryFailure,
    ): DirectoryResult<List<T>> =
        if (copy != null && age != null && verdict != DirectoryCachePolicy.Verdict.EXPIRED) {
            DirectoryResult.Stale(copy, age)
        } else {
            DirectoryResult.Unavailable(reason)
        }

    override suspend fun page(listing: WorldListing, offset: Int, limit: Int): DirectoryResult<WorldPage> {
        val key = PageKey(listing, offset, limit)
        val now = clock()
        val kept = synchronized(pages) { pages[key] }
        if (kept != null && now - kept.atMs in 0 until DirectoryCachePolicy.pageFreshMs(listing)) return DirectoryResult.Fresh(kept.page)

        val answer = api.get(RadioBrowserQuery.stations(listing, offset, limit)) { reader ->
            val stations = ArrayList<WorldStation>()
            val sent = RadioBrowserParser.parseStations(reader) { stations += it }
            WorldPage(stations = stations, offset = offset, limit = limit, rawCount = sent)
        }
        return when (answer) {
            is DirectoryAnswer.Ok -> {
                synchronized(pages) { pages[key] = KeptPage(answer.value, now) }
                DirectoryResult.Fresh(answer.value)
            }
            is DirectoryAnswer.Failed ->
                if (kept != null) DirectoryResult.Stale(kept.page, (now - kept.atMs).coerceAtLeast(0)) else DirectoryResult.Unavailable(answer.reason)
        }
    }

    override suspend fun stationsByUuid(uuids: List<String>): DirectoryResult<List<WorldStation>> {
        if (uuids.isEmpty()) return DirectoryResult.Fresh(emptyList())
        val found = ArrayList<WorldStation>()
        var failure: DirectoryFailure? = null
        var answered = false
        for (chunk in uuids.distinct().chunked(UUIDS_PER_REQUEST)) {
            when (val answer = api.get(RadioBrowserQuery.byUuids(chunk)) { reader ->
                ArrayList<WorldStation>().also { RadioBrowserParser.parseStations(reader) { s -> it += s } }
            }) {
                is DirectoryAnswer.Ok -> {
                    answered = true
                    found += answer.value
                }
                is DirectoryAnswer.Failed -> failure = answer.reason
            }
        }
        // What arrived is what is known: a favourite the directory could not be asked about stays as it was kept.
        return if (answered || failure == null) DirectoryResult.Fresh(found) else DirectoryResult.Unavailable(failure)
    }

    override suspend fun registerListen(uuid: String): String? =
        when (val answer = api.get(RadioBrowserQuery.listen(uuid)) { RadioBrowserParser.parseListen(it) }) {
            is DirectoryAnswer.Ok -> answer.value?.takeIf { it.ok }?.url
            is DirectoryAnswer.Failed -> null
        }

    private companion object {
        const val KEY_COUNTRIES = "countries"
        const val KEY_TAGS = "tags"
        const val PAGES_KEPT = 40
        const val UUIDS_PER_REQUEST = 40
    }
}
