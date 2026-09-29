package com.castivio.domain

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Refresh asks the subscription that is active *now*.
 *
 * This is the test the feature was missing and the one its correctness rests on. A
 * box with two playlists switches between them from Home's menu, and the bug that
 * would never be noticed in a screenshot is refresh asking the previous one: the
 * request goes out, an answer comes back, it is filed against the wrong subscription,
 * and the header shows playlist 1's expiry while playlist 2 is on screen. Nothing
 * about that is visible until a user acts on a date that belongs to something else.
 *
 * So the assertions are about *identity* rather than about the answer: which source
 * the validator was handed, and which id the answer was recorded under.
 */
class RefreshProviderTest {

    private val now = 1_800_000_000_000L

    // ------------------------------------------------------------------ fakes

    /** Only what [RefreshProvider] touches. The rest throws rather than pretending. */
    private class Sources(vararg initial: ProviderSource) : SourceRepository {
        private val rows = initial.toMutableList()
        private val active = MutableStateFlow(initial.firstOrNull()?.id)

        override suspend fun register(source: PlaylistSource, label: String?): ProviderSource =
            throw UnsupportedOperationException()

        override fun sources(): Flow<List<ProviderSource>> = MutableStateFlow(rows.toList())

        override fun active(): Flow<ProviderSource?> =
            active.map { id -> rows.firstOrNull { it.id == id } }

        override suspend fun activeNow(): ProviderSource? =
            rows.firstOrNull { it.id == active.value }

        override suspend fun get(id: String): ProviderSource? = rows.firstOrNull { it.id == id }

        override suspend fun save(source: ProviderSource) {
            rows.removeAll { it.id == source.id }
            rows += source
        }

        override suspend fun setActive(id: String) {
            active.value = id
        }

        override suspend fun recordCatalogueImport(id: String, sync: SyncState) = Unit
        override suspend fun recordEpgImport(id: String, atMs: Long) = Unit
        override suspend fun delete(id: String) {
            rows.removeAll { it.id == id }
        }
    }

    /** Records every source it was asked about, in order. */
    private class Validator(
        private val answer: (PlaylistSource) -> Outcome<ProviderStatus> = {
            Outcome.Success(ProviderStatus(usable = true))
        },
    ) : ProviderValidator {
        val asked = mutableListOf<PlaylistSource>()
        override suspend fun validate(source: PlaylistSource): Outcome<ProviderStatus> {
            asked += source
            return answer(source)
        }
    }

    /** Records every id an answer was filed under, in order. */
    private class Statuses : ProviderStatusCatalogue {
        val recorded = mutableListOf<Pair<String, ProviderStatus>>()
        override suspend fun record(sourceId: String, status: ProviderStatus, atMs: Long) {
            recorded += sourceId to status
        }

        override fun of(sourceId: String): Flow<Recorded?> = MutableStateFlow(null)
        override suspend fun forget(sourceId: String) = Unit
    }

    /** Records every id whose section marks were cleared. */
    private class Sections : SectionCatalogue {
        val forgotten = mutableListOf<String>()
        override suspend fun loadedAt(sourceId: String, kind: MediaKind): Long? = null
        override fun loaded(sourceId: String): Flow<Map<MediaKind, Long>> = MutableStateFlow(emptyMap())
        override suspend fun markLoaded(sourceId: String, kind: MediaKind, atMs: Long) = Unit
        override suspend fun forget(sourceId: String) {
            forgotten += sourceId
        }
    }

    private fun xtream(id: String, host: String) = ProviderSource(
        id = id,
        kind = SourceKind.XTREAM,
        label = id,
        url = host,
        username = "user-$id",
        password = "secret",
    )

    // ------------------------------------------------- the switch, end to end

    /**
     * **The test the owner asked for.** Two subscriptions, refresh the first, switch,
     * refresh again — and the second refresh must be about the second subscription in
     * both directions: what was asked, and where the answer went.
     */
    @Test
    fun `after switching, refresh asks the new subscription and files the answer under it`() =
        runBlocking {
            val first = xtream("first", "http://one.test")
            val second = xtream("second", "http://two.test")
            val sources = Sources(first, second)
            val validator = Validator()
            val statuses = Statuses()
            val refresher = RefreshProvider(sources, validator, statuses)

            refresher.refresh(now)

            sources.setActive(second.id)
            refresher.refresh(now)

            assertEquals(2, validator.asked.size)
            // Asked about the right host both times — the credentials, not just the id.
            assertEquals("http://one.test", (validator.asked[0] as PlaylistSource.Xtream).host)
            assertEquals("http://two.test", (validator.asked[1] as PlaylistSource.Xtream).host)

            assertEquals(listOf("first", "second"), statuses.recorded.map { it.first })
        }

    /**
     * The same guarantee stated the other way: the previous subscription is not
     * touched by a refresh that happens after a switch. A second answer filed against
     * `first` would show playlist 1's date under playlist 2's name.
     */
    @Test
    fun `refreshing after a switch leaves the previous subscription alone`() = runBlocking {
        val first = xtream("first", "http://one.test")
        val second = xtream("second", "http://two.test")
        val sources = Sources(first, second)
        val statuses = Statuses()
        val refresher = RefreshProvider(sources, Validator(), statuses)

        sources.setActive(second.id)
        refresher.refresh(now)

        assertEquals(listOf("second"), statuses.recorded.map { it.first })
        assertTrue(statuses.recorded.none { it.first == "first" })
    }

    /**
     * A subscription added and made active is asked about on the very next press.
     * Nothing is cached in the use case: `activeNow()` is a query run inside `refresh`,
     * so a source that did not exist when this object was built is still the one asked.
     */
    @Test
    fun `a subscription added after this was built is the one refreshed`() = runBlocking {
        val sources = Sources()
        val validator = Validator()
        val statuses = Statuses()
        val refresher = RefreshProvider(sources, validator, statuses)

        assertEquals(Refreshed.NoProvider, refresher.refresh(now))

        val added = xtream("added", "http://new.test")
        sources.save(added)
        sources.setActive(added.id)
        refresher.refresh(now)

        assertEquals(1, validator.asked.size)
        assertEquals("http://new.test", (validator.asked.single() as PlaylistSource.Xtream).host)
        assertEquals(listOf("added"), statuses.recorded.map { it.first })
    }

    // --------------------------------------------------------- the section marks

    /**
     * A successful refresh clears the active source's section marks, so the next visit
     * to Movies, Series or Radio fetches instead of drawing what was imported weeks
     * ago. It clears *only* the active one: another playlist's catalogue is still
     * perfectly good and re-fetching it would be work nobody asked for.
     */
    @Test
    fun `a successful refresh lets only the active source's sections go stale`() = runBlocking {
        val first = xtream("first", "http://one.test")
        val second = xtream("second", "http://two.test")
        val sources = Sources(first, second)
        val marks = Sections()
        val refresher = RefreshProvider(sources, Validator(), Statuses(), marks)

        sources.setActive(second.id)
        refresher.refresh(now)

        assertEquals(listOf("second"), marks.forgotten)
    }

    /**
     * **A failure throws nothing away.** An unreachable provider has told us nothing
     * about whether its catalogue moved, and discarding a working offline catalogue on
     * the strength of a request that never arrived is the opposite of useful — the user
     * would press refresh on a train and lose the library they were about to watch.
     */
    @Test
    fun `a failed refresh records nothing and keeps the sections`() = runBlocking {
        val sources = Sources(xtream("first", "http://one.test"))
        val statuses = Statuses()
        val marks = Sections()
        val refresher = RefreshProvider(
            sources,
            Validator { Outcome.Failure(AppError.TIMEOUT) },
            statuses,
            marks,
        )

        val outcome = refresher.refresh(now)

        assertTrue(outcome is Refreshed.Unreachable)
        assertEquals(AppError.TIMEOUT, (outcome as Refreshed.Unreachable).error)
        assertTrue(statuses.recorded.isEmpty())
        assertTrue(marks.forgotten.isEmpty())
    }

    /** Nothing active is not a failure, and it must not look like one to the screen. */
    @Test
    fun `no active subscription is reported as such rather than as an error`() = runBlocking {
        val refresher = RefreshProvider(Sources(), Validator(), Statuses(), Sections())

        assertEquals(Refreshed.NoProvider, refresher.refresh(now))
    }
}
