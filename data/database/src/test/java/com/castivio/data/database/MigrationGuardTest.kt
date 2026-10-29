package com.castivio.data.database

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Version 9: `idx_media_epg` removed, and every migration from 8 on run over a database that a first load
 * may have left without its sort-title index.
 *
 * Migrations run here the way the existing ones are tested -- on the database Room made, with the state
 * the migration starts from put back by hand -- because Room's own migration test needs a device and an
 * exported schema this repository does not keep. What a device adds, and this does not, is Room's check of
 * the result against the entities; the second test below holds the entity's side of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationGuardTest {

    private lateinit var database: CastivioDatabase

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------------ the sixth index

    @Test
    fun `a database made at this version has the five indices and not the sixth`() {
        assertEquals(EXPECTED_INDICES, mediaIndices())
        assertFalse(EPG_INDEX in mediaIndices())
    }

    @Test
    fun `8 to 9 drops the epg index and nothing else`() {
        Fixtures.import(database, Fixtures.livePlaylist(6), sourceId = "src")
        database.openHelper.writableDatabase.execSQL("CREATE INDEX IF NOT EXISTS `$EPG_INDEX` ON `media` (`epg_channel_id`)")
        assertEquals("the state a version 8 database is in", EXPECTED_INDICES + EPG_INDEX, mediaIndices())
        val rows = countOf("SELECT COUNT(*) FROM media")
        val sources = countOf("SELECT COUNT(*) FROM source")
        val groups = countOf("SELECT COUNT(*) FROM media_group")

        migrate()

        assertEquals(EXPECTED_INDICES, mediaIndices())
        assertEquals("no row of the catalogue changed", rows, countOf("SELECT COUNT(*) FROM media"))
        assertEquals("nor of the subscriptions", sources, countOf("SELECT COUNT(*) FROM source"))
        assertEquals(groups, countOf("SELECT COUNT(*) FROM media_group"))
        assertEquals("the search index is as it was", rows, countOf("SELECT COUNT(*) FROM media_fts"))
    }

    @Test
    fun `8 to 9 over a database that never had the index, or has lost it, is the same`() {
        migrate()
        migrate()

        assertEquals(EXPECTED_INDICES, mediaIndices())
    }

    // ------------------------------------------------------------------ the rule for every migration after

    @Test
    fun `8 to 9 puts back a sort-title index a load left out`() {
        Fixtures.import(database, Fixtures.livePlaylist(4), sourceId = "src")
        val db = database.openHelper.writableDatabase
        db.execSQL(SortTitleIndex.DROP)
        db.execSQL("CREATE INDEX IF NOT EXISTS `$EPG_INDEX` ON `media` (`epg_channel_id`)")
        assertFalse(SortTitleIndex.isIntact(db))

        migrate()

        assertEquals("Room would find five, as declared", EXPECTED_INDICES, mediaIndices())
        assertTrue(SortTitleIndex.isIntact(db))
    }

    /**
     * The rule is held here and not by anybody remembering it: a migration added after deferral
     * began that is not run through `guarded` would fail Room's check of its result on a database
     * whose first load was cut short -- a database that does not open.
     */
    @Test
    fun `every migration from the version deferral began is guarded`() {
        val fromThen = CastivioMigrations.ALL.filter { it.startVersion >= CastivioMigrations.DEFERRAL_FROM_VERSION }

        assertTrue("there is at least the one that removes the sixth index", fromThen.isNotEmpty())
        val unguarded = fromThen.filter { it !is DeferredIndexGuard }.map { "${it.startVersion} -> ${it.endVersion}" }
        assertEquals("migrations that are not run through CastivioMigrations.guarded", emptyList<String>(), unguarded)
    }

    /** A version bump with no migration is an app that cannot open on update: see `CastivioMigrations.ALL`. */
    @Test
    fun `the migrations are one unbroken chain to the version of the database`() {
        val chain = CastivioMigrations.ALL.sortedBy { it.startVersion }

        assertEquals(1, chain.first().startVersion)
        chain.zipWithNext().forEach { (before, after) -> assertEquals(before.endVersion, after.startVersion) }
        assertEquals(database.openHelper.readableDatabase.version, chain.last().endVersion)
    }

    // ------------------------------------------------------------------ the reads that must not lose their index

    /**
     * Held beside the deferral because it is what the deferral must not touch: the counts of a
     * category and the first category's page are read while a load runs, and are answered by
     * these two indices. A plan that stops naming them is a first load that scans.
     */
    @Test
    fun `the category count and the category page are answered by the group index`() {
        assertTrue(planOf("SELECT COUNT(*) FROM media WHERE source_id = 's' AND kind = 'LIVE' AND group_id = 'g'").contains(GROUP_INDEX))
        assertTrue(
            planOf("SELECT * FROM media WHERE source_id = 's' AND kind = 'LIVE' AND group_id = 'g' ORDER BY provider_order LIMIT 60")
                .contains(GROUP_INDEX),
        )
    }

    @Test
    fun `the count of a section is answered by an index that leads with the subscription and the kind`() {
        val plan = planOf("SELECT COUNT(*) FROM media WHERE source_id = 's' AND kind = 'LIVE'")

        assertTrue(plan, plan.contains("idx_media_source_kind"))
    }

    // ------------------------------------------------------------------ helpers

    private fun migrate() =
        CastivioMigrations.ALL.single { it.startVersion == 8 }.migrate(database.openHelper.writableDatabase)

    /** The names of `media`'s indices, the primary key's own left out. */
    private fun mediaIndices(): Set<String> =
        database.openHelper.writableDatabase.query("PRAGMA index_list(`media`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) {
                    val index = cursor.getString(name)
                    if (!index.startsWith("sqlite_autoindex")) add(index)
                }
            }
        }

    private fun planOf(sql: String): String =
        database.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            val detail = cursor.getColumnIndexOrThrow("detail")
            buildString { while (cursor.moveToNext()) appendLine(cursor.getString(detail)) }
        }

    private fun countOf(sql: String): Int =
        database.openHelper.writableDatabase.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 }

    private companion object {
        const val EPG_INDEX = "idx_media_epg"
        const val GROUP_INDEX = "idx_media_source_kind_group_order"

        /** The indices `MediaEntity` declares, and a version 9 database has. */
        val EXPECTED_INDICES = setOf(
            "idx_media_source_kind_order",
            GROUP_INDEX,
            "idx_media_source_kind_sort_title",
            "idx_media_series",
            "idx_media_source_generation",
        )
    }
}
