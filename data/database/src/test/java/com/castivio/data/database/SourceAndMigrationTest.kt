package com.castivio.data.database

import com.castivio.domain.ProviderSource
import com.castivio.domain.RefreshPolicy
import com.castivio.domain.SourceKind
import com.castivio.domain.SyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceAndMigrationTest {

    private val now = 1_784_980_800_000L
    private val hour = 60 * 60 * 1000L

    private lateinit var database: CastivioDatabase

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a source round-trips with its sync state`() = runBlocking {
        val repository = repository()
        repository.save(
            ProviderSource(
                id = "src",
                kind = SourceKind.XTREAM,
                label = "host · user",
                url = "http://host:8080",
                username = "user",
                password = "secret",
                epgUrl = "http://host:8080/xmltv.php",
                userAgent = "Castivio/1.0",
            ),
        )

        val stored = repository.get("src")!!
        assertEquals(SourceKind.XTREAM, stored.kind)
        assertEquals("http://host:8080", stored.url)
        assertEquals("user", stored.username)
        assertEquals("secret", stored.password)
        assertEquals("Castivio/1.0", stored.userAgent)
        assertTrue(stored.isActive)
        assertEquals(now, stored.createdAtMs)
    }

    /**
     * Re-entering the same provider — after an expiry, or to fix a typo — must not
     * reset sync state, or the next launch re-downloads a catalogue that is already
     * on disk.
     */
    @Test
    fun `saving again keeps what the last import learned`() = runBlocking {
        val repository = repository()
        repository.save(source("src"))
        repository.recordCatalogueImport(
            "src",
            SyncState(etag = "\"abc\"", contentHash = "crc32:1", lastImportAtMs = now, itemCount = 4_000),
        )

        repository.save(source("src", password = "corrected"))

        val stored = repository.get("src")!!
        assertEquals("corrected", stored.password)
        assertEquals("\"abc\"", stored.sync.etag)
        assertEquals(4_000, stored.sync.itemCount)
        assertEquals(now, stored.sync.lastImportAtMs)
    }

    @Test
    fun `exactly one source is active`() = runBlocking {
        val repository = repository()
        repository.save(source("a"))
        repository.save(source("b"))

        assertEquals("b", repository.active().first()?.id)
        assertEquals(1, database.sourceDao().all().first().count { it.isActive })

        repository.setActive("a")
        assertEquals("a", repository.activeNow()?.id)
        assertEquals(1, database.sourceDao().all().first().count { it.isActive })
    }

    @Test
    fun `import timestamps are recorded separately for catalogue and guide`() = runBlocking {
        val repository = repository()
        repository.save(source("src"))

        repository.recordCatalogueImport("src", SyncState(lastImportAtMs = now, itemCount = 10))
        repository.recordEpgImport("src", now + hour)

        val stored = repository.get("src")!!
        assertEquals(now, stored.sync.lastImportAtMs)
        assertEquals(now + hour, stored.sync.lastEpgImportAtMs)
    }

    @Test
    fun `deleting a source leaves the others alone`() = runBlocking {
        val repository = repository()
        repository.save(source("a"))
        repository.save(source("b"))

        repository.delete("a")

        assertNull(repository.get("a"))
        assertNotNull(repository.get("b"))
    }

    // --------------------------------------------------------------- refresh policy

    @Test
    fun `refresh policy skips work that is not needed`() {
        val fresh = source("src").copy(
            sync = SyncState(lastImportAtMs = now - hour, lastEpgImportAtMs = now - hour, itemCount = 100),
        )

        assertFalse(RefreshPolicy.catalogueIsStale(fresh, now))
        assertFalse(RefreshPolicy.epgIsStale(fresh, now))
        assertFalse(RefreshPolicy.needsFirstImport(fresh))

        // Twelve hours for a catalogue, six for a guide.
        assertTrue(RefreshPolicy.catalogueIsStale(fresh, now + 13 * hour))
        assertTrue(RefreshPolicy.epgIsStale(fresh, now + 7 * hour))
    }

    @Test
    fun `a playlist that has never imported always needs one`() {
        val untouched = playlist("src")
        assertTrue(RefreshPolicy.needsFirstImport(untouched))
        assertTrue(RefreshPolicy.catalogueIsStale(untouched, now))

        // Imported, but nothing came back: the app has nothing to show, so this is
        // still a first import rather than a refresh that can wait.
        val empty = untouched.copy(sync = SyncState(lastImportAtMs = now, itemCount = 0))
        assertTrue(RefreshPolicy.needsFirstImport(empty))
    }

    /**
     * A panel with no stored rows is not a panel that needs setting up again.
     *
     * This rule is consulted in exactly one place — the gate that decides whether the
     * app opens on Home or on the sign-in screen — and a panel is read a section at a
     * time, so having fetched nothing yet is the *normal* state right after signing in
     * successfully. Counting its rows here would bounce the user straight back to the
     * screen they had just completed.
     */
    @Test
    fun `a panel with nothing fetched yet does not need setting up again`() {
        val justSignedIn = source("src")

        assertEquals(0, justSignedIn.sync.itemCount)
        assertNull(justSignedIn.sync.lastImportAtMs)
        assertFalse(
            "signing in to a panel would have sent the user back to the sign-in screen",
            RefreshPolicy.needsFirstImport(justSignedIn),
        )
    }

    /**
     * TV boxes without a real-time clock come up in 1970 and jump forward once the
     * network is up. A backwards clock must not pin the catalogue as fresh forever.
     */
    @Test
    fun `a clock that moved backwards forces a refresh`() {
        val stamped = source("src").copy(sync = SyncState(lastImportAtMs = now, lastEpgImportAtMs = now))

        assertTrue(RefreshPolicy.catalogueIsStale(stamped, now - 5 * hour))
        assertTrue(RefreshPolicy.epgIsStale(stamped, now - 5 * hour))
    }

    // ------------------------------------------------------------------ migrations

    /**
     * The policy that matters: a catalogue schema change must not cost the user
     * their favourites, watch progress or credentials.
     */
    @Test
    fun `recreating the catalogue keeps user data`() = runBlocking {
        Fixtures.import(database, Fixtures.livePlaylist(5))
        val id = database.mediaDao().window("LIVE", 0, 1).single().id
        RoomFavoritesRepository(database.favoriteDao()) { now }.toggle(id)
        RoomProgressRepository(database.progressDao()) { now }.save(id, 60_000, 600_000)
        repository().save(source("src"))
        repository().recordCatalogueImport("src", SyncState(etag = "\"v1\"", lastImportAtMs = now, itemCount = 5))

        val migration = CastivioMigrations.recreateCatalogue(
            from = 1,
            to = 2,
            createStatements = listOf(
                // Stand-in for the real DDL, which comes from the exported schema
                // at the version that needs it. What is under test is the policy:
                // catalogue out, user data untouched.
                "CREATE TABLE IF NOT EXISTS `media` (`id` TEXT NOT NULL, PRIMARY KEY(`id`))",
            ),
            dropTables = listOf("media_fts", "media"),
        )
        migration.migrate(database.openHelper.writableDatabase)

        // Catalogue gone…
        assertEquals(0, countOf("media"))
        // …user data intact.
        assertEquals(1, countOf("favorite"))
        assertEquals(1, countOf("playback_progress"))
        assertEquals(1, countOf("source"))
        // …and the app no longer believes the catalogue is current.
        assertNull(repository().get("src")!!.sync.etag)
        assertNull(repository().get("src")!!.sync.lastImportAtMs)
        // Credentials survive; only the sync state is cleared.
        assertEquals("secret", repository().get("src")!!.password)
    }

    @Test
    fun `a migration cannot be pointed at user data by accident`() {
        val refused = runCatching {
            CastivioMigrations.recreateCatalogue(
                from = 1,
                to = 2,
                createStatements = emptyList(),
                dropTables = listOf("media", "favorite"),
            )
        }

        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
        assertTrue(refused.exceptionOrNull()!!.message!!.contains("favorite"))
    }

    @Test
    fun `forgetting sync state forces a re-import without losing credentials`() = runBlocking {
        repository().save(source("src"))
        repository().recordCatalogueImport("src", SyncState(etag = "\"v1\"", lastImportAtMs = now, itemCount = 9))

        CastivioMigrations.forgetSyncState(1, 2).migrate(database.openHelper.writableDatabase)

        val stored = repository().get("src")!!
        assertNull(stored.sync.etag)
        assertNull(stored.sync.lastImportAtMs)
        assertEquals(0, stored.sync.itemCount)
        assertEquals("secret", stored.password)
        // Asked of a playlist, which is where the answer means anything: a panel is
        // fetched a section at a time and never reports needing a first import.
        assertTrue(RefreshPolicy.needsFirstImport(stored.copy(kind = SourceKind.M3U_URL)))
    }

    // ------------------------------------------------------------------- fixtures

    /** A playlist, whose rows arrive in one file and so can genuinely be missing. */
    private fun playlist(id: String) = source(id).copy(kind = SourceKind.M3U_URL)

    private fun source(id: String, password: String = "secret") = ProviderSource(
        id = id,
        kind = SourceKind.XTREAM,
        label = "host · user",
        url = "http://host:8080",
        username = "user",
        password = password,
    )

    private fun repository() = RoomSourceRepository(database.sourceDao()) { now }

    private fun countOf(table: String): Int =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else -1
        }
}
