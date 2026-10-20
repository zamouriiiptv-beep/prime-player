package com.castivio.domain

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        /** The last answer filed under [sourceId], read back the way the store reads it. */
        override fun of(sourceId: String): Flow<Recorded?> = MutableStateFlow(
            recorded.lastOrNull { it.first == sourceId }?.let { (_, status) ->
                Recorded(usable = status.usable, expiresAtMs = status.expiresAtMs, label = status.statusLabel, atMs = 0)
            },
        )

        override suspend fun forget(sourceId: String) = Unit
    }

    /** Records every id whose kept playlist was discarded. */
    private class Copies : PlaylistCopies {
        val discarded = mutableListOf<String>()
        override suspend fun discard(sourceId: String) {
            discarded += sourceId
        }
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

    /** A `get.php` link that names a panel account, so it is read, and asked, through the panel. */
    private fun panelPlaylist() = ProviderSource(
        id = "playlist-1",
        kind = SourceKind.M3U_URL,
        label = "Playlist 1",
        url = "http://line.example.com:8080/get.php?username=sam&password=hunter2&type=m3u_plus&output=ts",
    )

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

    /**
     * A playlist's kept copy goes with the marks: the next section opened has to come from
     * the provider, and the copy would answer it from the device instead. Only the active
     * subscription's, and only when the provider answered.
     */
    @Test
    fun `a successful refresh discards only the active playlist's kept copy`() = runBlocking {
        val first = xtream("first", "http://one.test")
        val second = xtream("second", "http://two.test")
        val sources = Sources(first, second)
        val copies = Copies()
        val refresher = RefreshProvider(sources, Validator(), Statuses(), Sections(), copies)

        sources.setActive(second.id)
        refresher.refresh(now)

        assertEquals(listOf("second"), copies.discarded)
    }

    @Test
    fun `a failed refresh keeps the kept copy`() = runBlocking {
        val copies = Copies()
        val refresher = RefreshProvider(
            Sources(xtream("first", "http://one.test")),
            Validator { Outcome.Failure(AppError.TIMEOUT) },
            Statuses(),
            Sections(),
            copies,
        )

        refresher.refresh(now)

        assertTrue(copies.discarded.isEmpty())
    }

    // ------------------------------------------------- asked once, by Home itself

    /**
     * Adding a playlist asks nothing, so a playlist read through its panel had no expiry
     * to show until refresh was pressed. Home asks it once instead -- the same question --
     * and the sections stay exactly as they were.
     */
    @Test
    fun `a subscription never asked is asked once, and its sections are left alone`() = runBlocking {
        val playlist = panelPlaylist()
        val validator = Validator { Outcome.Success(ProviderStatus(usable = true, expiresAtMs = now + 1, statusLabel = "Active")) }
        val statuses = Statuses()
        val marks = Sections()
        val copies = Copies()
        val refresher = RefreshProvider(Sources(playlist), validator, statuses, marks, copies)

        val outcome = refresher.askIfNeverAsked(now)

        assertTrue(outcome is Refreshed.Updated)
        assertEquals("asked through the panel its link names", "sam", (validator.asked.single() as PlaylistSource.M3u).xtream?.username)
        assertEquals(listOf(playlist.id), statuses.recorded.map { it.first })
        assertEquals(now + 1, statuses.recorded.single().second.expiresAtMs)
        assertTrue("no section was let go stale", marks.forgotten.isEmpty())
        assertTrue("no kept playlist was discarded", copies.discarded.isEmpty())
    }

    @Test
    fun `a subscription already answered is not asked again`() = runBlocking {
        val validator = Validator()
        val refresher = RefreshProvider(Sources(xtream("first", "http://one.test")), validator, Statuses())

        refresher.askIfNeverAsked(now)
        val second = refresher.askIfNeverAsked(now)

        assertNull(second)
        assertEquals(1, validator.asked.size)
    }

    /** A playlist with no panel behind it, and a file, state no date: there is nothing to ask. */
    @Test
    fun `a source with no account behind it is not asked`() = runBlocking {
        val validator = Validator()
        val plain = ProviderSource(id = "plain", kind = SourceKind.M3U_URL, label = "plain", url = "http://cdn.example.com/lists/family.m3u")
        val file = ProviderSource(id = "file", kind = SourceKind.LOCAL_FILE, label = "file", url = "content://downloads/list.m3u")

        assertNull(RefreshProvider(Sources(plain), validator, Statuses()).askIfNeverAsked(now))
        assertNull(RefreshProvider(Sources(file), validator, Statuses()).askIfNeverAsked(now))
        assertTrue(validator.asked.isEmpty())
    }

    @Test
    fun `an ask that fails records nothing, so the next one asks again`() = runBlocking {
        val statuses = Statuses()
        val validator = Validator { Outcome.Failure(AppError.TIMEOUT) }
        val refresher = RefreshProvider(Sources(xtream("first", "http://one.test")), validator, statuses)

        val first = refresher.askIfNeverAsked(now)
        refresher.askIfNeverAsked(now)

        assertEquals(Refreshed.Unreachable(AppError.TIMEOUT), first)
        assertTrue(statuses.recorded.isEmpty())
        assertEquals(2, validator.asked.size)
    }

    @Test
    fun `with nothing active there is nobody to ask`() = runBlocking {
        assertNull(RefreshProvider(Sources(), Validator(), Statuses()).askIfNeverAsked(now))
    }

    /** Nothing active is not a failure, and it must not look like one to the screen. */
    @Test
    fun `no active subscription is reported as such rather than as an error`() = runBlocking {
        val refresher = RefreshProvider(Sources(), Validator(), Statuses(), Sections())

        assertEquals(Refreshed.NoProvider, refresher.refresh(now))
    }
}
