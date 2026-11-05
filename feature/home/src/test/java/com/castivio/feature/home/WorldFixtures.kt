package com.castivio.feature.home

import com.castivio.core.common.PlayOutcome
import com.castivio.domain.DirectoryFailure
import com.castivio.domain.DirectoryResult
import com.castivio.domain.StationHealth
import com.castivio.domain.StationRecord
import com.castivio.domain.WorldCountry
import com.castivio.domain.WorldListing
import com.castivio.domain.WorldPage
import com.castivio.domain.WorldRadioDirectory
import com.castivio.domain.WorldRadioLibrary
import com.castivio.domain.WorldStation
import com.castivio.domain.WorldTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** A station of the directory as the tests need it: a uuid, a name, and the addresses it plays at. */
internal fun worldStation(
    uuid: String,
    name: String = "Station $uuid",
    url: String = "http://a.invalid/$uuid",
    resolved: String? = null,
    countryCode: String? = "MA",
    country: String? = "Morocco",
    tags: List<String> = emptyList(),
    codec: String? = "MP3",
    bitrate: Int = 128,
    checkedOk: Boolean? = true,
    checkedAtMs: Long? = 1_000_000L,
    clicks: Int = 10,
) = WorldStation(
    uuid = uuid,
    name = name,
    url = url,
    urlResolved = resolved,
    favicon = null,
    countryCode = countryCode,
    country = country,
    tags = tags,
    codec = codec,
    bitrate = bitrate,
    hls = false,
    serverCheckedOk = checkedOk,
    serverCheckedAtMs = checkedAtMs,
    votes = 0,
    clickCount = clicks,
    sslError = false,
)

/** A directory that answers from lists the test gives it, and remembers what it was asked. */
internal class FakeWorldDirectory(
    var stationsOf: (WorldListing) -> List<WorldStation> = { emptyList() },
    var countries: DirectoryResult<List<WorldCountry>> = DirectoryResult.Fresh(emptyList()),
    var tags: DirectoryResult<List<WorldTag>> = DirectoryResult.Fresh(emptyList()),
    var pageFailure: DirectoryFailure? = null,
) : WorldRadioDirectory {
    val pageAsks = mutableListOf<Triple<WorldListing, Int, Int>>()
    val countryAsks = mutableListOf<Boolean>()
    val uuidAsks = mutableListOf<List<String>>()
    val listens = mutableListOf<String>()
    var byUuid: List<WorldStation> = emptyList()

    override suspend fun countries(forceRefresh: Boolean): DirectoryResult<List<WorldCountry>> {
        countryAsks += forceRefresh
        return countries
    }

    override suspend fun tags(forceRefresh: Boolean): DirectoryResult<List<WorldTag>> = tags

    override suspend fun page(listing: WorldListing, offset: Int, limit: Int): DirectoryResult<WorldPage> {
        pageAsks += Triple(listing, offset, limit)
        pageFailure?.let { return DirectoryResult.Unavailable(it) }
        val all = stationsOf(listing)
        val slice = all.drop(offset).take(limit)
        return DirectoryResult.Fresh(WorldPage(stations = slice, offset = offset, limit = limit, rawCount = slice.size))
    }

    override suspend fun stationsByUuid(uuids: List<String>): DirectoryResult<List<WorldStation>> {
        uuidAsks += uuids
        return DirectoryResult.Fresh(byUuid)
    }

    override suspend fun registerListen(uuid: String): String? {
        listens += uuid
        return null
    }
}

/** The world radio's store, in memory: what the viewer keeps, and what listening said, as the real one holds them. */
internal class MemoryWorldLibrary : WorldRadioLibrary {
    private val saved = MutableStateFlow<List<WorldStation>>(emptyList())
    private val heard = MutableStateFlow<List<WorldStation>>(emptyList())
    val outcomes = mutableListOf<Pair<String, PlayOutcome>>()
    val refreshed = mutableListOf<String>()
    val recordsByUuid = HashMap<String, StationRecord>()

    override fun favorites(): Flow<List<WorldStation>> = saved
    override fun favoriteIds(): Flow<Set<String>> = saved.map { list -> list.mapTo(HashSet()) { it.uuid } }
    override fun recents(limit: Int): Flow<List<WorldStation>> = heard.map { it.take(limit) }

    override suspend fun setFavorite(station: WorldStation, favorite: Boolean) {
        saved.value = if (favorite) listOf(station) + saved.value.filterNot { it.uuid == station.uuid } else saved.value.filterNot { it.uuid == station.uuid }
    }

    override suspend fun recordListened(station: WorldStation, nowMs: Long) {
        heard.value = listOf(station) + heard.value.filterNot { it.uuid == station.uuid }
    }

    override suspend fun records(uuids: Collection<String>): Map<String, StationRecord> =
        uuids.mapNotNull { id -> recordsByUuid[id]?.let { id to it } }.toMap()

    override suspend fun recordOutcome(uuid: String, outcome: PlayOutcome, nowMs: Long) {
        outcomes += uuid to outcome
        StationHealth.after(recordsByUuid[uuid], uuid, outcome, nowMs)?.let { recordsByUuid[uuid] = it }
    }

    override suspend fun refreshSaved(station: WorldStation) {
        refreshed += station.uuid
    }

    /** The uuids kept as favourites, and the uuids heard, the latest first. */
    val keptUuids: List<String> get() = saved.value.map { it.uuid }
    val listenedUuids: List<String> get() = heard.value.map { it.uuid }

    fun keep(vararg stations: WorldStation) {
        saved.value = stations.toList()
    }
}
