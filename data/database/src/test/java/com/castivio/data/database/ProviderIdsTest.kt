package com.castivio.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.castivio.domain.MovieDetails
import com.castivio.domain.SeriesDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The ids a provider gives a film or a show at TMDB and IMDb, kept beside the details they came with.
 *
 * Version 12 adds `vod_detail.tmdb_id`, `imdb_id` and `ids_read`, and `series_detail.tmdb_id` and `imdb_id`. What these hold is the
 * three places it can go wrong without a screen noticing: the cache that writes and reads them, the flag that tells a film kept
 * before the ids were read from one whose provider gave none, and the migration that adds the columns to a database that is already
 * here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderIdsTest {

    private lateinit var database: CastivioDatabase
    private lateinit var films: RoomMovieDetailsCache
    private lateinit var shows: RoomSeriesDetailsStore

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
        films = RoomMovieDetailsCache(database.vodDetailDao())
        shows = RoomSeriesDetailsStore(database.seriesDetailDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------------ a film's ids, kept and read

    @Test
    fun `a film's ids are kept with its details and read back`() = runBlocking {
        films.put("src", "42", MovieDetails(plot = "A story.", tmdbId = 603L, imdbId = "tt0133093"), now = 1L)

        val read = films.get("src", "42")!!

        assertEquals(603L, read.tmdbId)
        assertEquals("tt0133093", read.imdbId)
        assertEquals("A story.", read.plot)
    }

    @Test
    fun `a film whose provider gave no id has none, and is not mistaken for one that was never read`() = runBlocking {
        films.put("src", "42", MovieDetails(plot = "A story."), now = 1L)

        val read = films.get("src", "42")!!

        assertNull(read.tmdbId)
        assertNull(read.imdbId)
        assertTrue("it was read, and said nothing", read.idsRead)
    }

    @Test
    fun `a film put from a reply read now is marked as read for its ids`() = runBlocking {
        films.put("src", "42", MovieDetails(tmdbId = 603L), now = 1L)

        assertTrue(films.get("src", "42")!!.idsRead)
        assertEquals(1, countOf(database.openHelper.writableDatabase, "SELECT COUNT(*) FROM `vod_detail` WHERE `ids_read` = 1"))
    }

    @Test
    fun `a row kept before the ids were read, or written by a portal's list, is not marked as read`() = runBlocking {
        // The columns a version 11 row has, written the way a portal's list writes it: `ids_read` is left to its default.
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO `vod_detail` (`source_id`, `provider_ref`, `rating`, `plot`, `director`, `cast_names`, " +
                "`genres`, `release_year`, `duration_seconds`, `added_epoch`, `fetched_at`) " +
                "VALUES ('src', '7', 7.5, 'Kept before.', NULL, NULL, NULL, 1999, 6000, NULL, 1)",
        )

        val read = films.get("src", "7")!!
        assertFalse("it was kept before the ids were read", read.idsRead)
        assertEquals("Kept before.", read.plot)
        assertNull("and has no id to give", read.tmdbId)
    }

    @Test
    fun `a film nothing was kept for has no details at all`() = runBlocking {
        assertNull(films.get("src", "nobody"))
    }

    @Test
    fun `asking again replaces the row, and the reply read now marks it as read`() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO `vod_detail` (`source_id`, `provider_ref`, `plot`, `fetched_at`) VALUES ('src', '7', 'Kept before.', 1)",
        )
        assertFalse(films.get("src", "7")!!.idsRead)

        films.put("src", "7", MovieDetails(plot = "Fresh.", tmdbId = 11L), now = 2L)

        assertTrue(films.get("src", "7")!!.idsRead)
        assertEquals(11L, films.get("src", "7")!!.tmdbId)
        assertEquals("Fresh.", films.get("src", "7")!!.plot)
    }

    @Test
    fun `two films of one source keep their own ids`() = runBlocking {
        films.put("src", "1", MovieDetails(tmdbId = 100L), now = 1L)
        films.put("src", "2", MovieDetails(tmdbId = 200L, imdbId = "tt0000200"), now = 1L)

        assertEquals(100L, films.get("src", "1")!!.tmdbId)
        assertNull(films.get("src", "1")!!.imdbId)
        assertEquals(200L, films.get("src", "2")!!.tmdbId)
        assertEquals("tt0000200", films.get("src", "2")!!.imdbId)
    }

    // ------------------------------------------------------------------ a show's ids, kept and read

    @Test
    fun `a show's ids are kept with its details and read back`() = runBlocking {
        shows.put("show", SeriesDetails(plot = "A story.", tmdbId = 1396L, imdbId = "tt0903747"), emptyMap(), now = 1L)

        val read = shows.details("show").first()!!

        assertEquals(1396L, read.tmdbId)
        assertEquals("tt0903747", read.imdbId)
        assertEquals("A story.", read.plot)
    }

    @Test
    fun `a show whose provider gave no id has none`() = runBlocking {
        shows.put("show", SeriesDetails(plot = "A story."), emptyMap(), now = 1L)

        val read = shows.details("show").first()!!

        assertNull(read.tmdbId)
        assertNull(read.imdbId)
    }

    // ------------------------------------------------------------------ the migration

    /**
     * Run the way the existing migrations are: on the database Room made, with the two tables put back as version 11 had them,
     * because Room's own migration test needs a device and an exported schema this repository does not keep. What a device adds,
     * and this does not, is Room's check of the result against the entities; the comparison of each new column's description with
     * the one Room made from the entity is the nearest to it -- and the one place the flag's default is held.
     */
    @Test
    fun `11 to 12 adds the columns as the entities declare them, and leaves the rows that are there as they were`() {
        val db = database.openHelper.writableDatabase
        val declared = mapOf(
            "vod_detail" to listOf("tmdb_id", "imdb_id", "ids_read").associateWith { columnOf(db, "vod_detail", it) },
            "series_detail" to listOf("tmdb_id", "imdb_id").associateWith { columnOf(db, "series_detail", it) },
        )
        assertEquals(Column("INTEGER", notNull = 0, default = null), declared.getValue("vod_detail").getValue("tmdb_id"))
        assertEquals(Column("TEXT", notNull = 0, default = null), declared.getValue("vod_detail").getValue("imdb_id"))
        assertEquals(
            "the flag is an INTEGER that cannot be null and is 0 unless said",
            Column("INTEGER", notNull = 1, default = "0"),
            declared.getValue("vod_detail").getValue("ids_read"),
        )
        assertEquals(Column("INTEGER", notNull = 0, default = null), declared.getValue("series_detail").getValue("tmdb_id"))
        assertEquals(Column("TEXT", notNull = 0, default = null), declared.getValue("series_detail").getValue("imdb_id"))

        toVersionEleven(db)
        db.execSQL(
            "INSERT INTO `vod_detail` (`source_id`, `provider_ref`, `rating`, `plot`, `fetched_at`) " +
                "VALUES ('src', '7', 8.1, 'Kept before.', 1000)",
        )
        db.execSQL(
            "INSERT INTO `series_detail` (`series_id`, `rating`, `plot`, `fetched_at`) VALUES ('show', 8.0, 'A show.', 2000)",
        )
        assertNull("a version 11 table has no such column", columnOf(db, "vod_detail", "ids_read"))
        assertNull(columnOf(db, "series_detail", "tmdb_id"))

        CastivioMigrations.ALL.single { it.startVersion == 11 }.migrate(db)

        for ((table, columns) in declared) {
            for ((name, column) in columns) assertEquals("$table.$name as Room checks for it", column, columnOf(db, table, name))
        }
        assertEquals(
            "the film is as it was",
            1,
            countOf(db, "SELECT COUNT(*) FROM `vod_detail` WHERE `provider_ref` = '7' AND `plot` = 'Kept before.' AND `fetched_at` = 1000"),
        )
        assertEquals("with no id, and not read for its ids", 1, countOf(db, "SELECT COUNT(*) FROM `vod_detail` WHERE `tmdb_id` IS NULL AND `ids_read` = 0"))
        assertEquals(
            "the show is as it was, with no id",
            1,
            countOf(db, "SELECT COUNT(*) FROM `series_detail` WHERE `series_id` = 'show' AND `plot` = 'A show.' AND `tmdb_id` IS NULL"),
        )
    }

    @Test
    fun `a film kept before the migration is told from one kept after it`() = runBlocking {
        val db = database.openHelper.writableDatabase
        toVersionEleven(db)
        db.execSQL("INSERT INTO `vod_detail` (`source_id`, `provider_ref`, `plot`, `fetched_at`) VALUES ('src', 'before', 'Old.', 1)")
        CastivioMigrations.ALL.single { it.startVersion == 11 }.migrate(db)

        films.put("src", "after", MovieDetails(plot = "New."), now = 2L)

        assertFalse("kept before: to be asked of its provider once more", films.get("src", "before")!!.idsRead)
        assertTrue("kept after: its reply was read for ids", films.get("src", "after")!!.idsRead)
    }

    /** That the chain is unbroken to the database's version is `MigrationGuardTest`'s; this says the step to 12 is there, and last. */
    @Test
    fun `the migration to version 12 is the last of the chain, and the database is at 12`() {
        val last = CastivioMigrations.ALL.maxByOrNull { it.startVersion }!!

        assertEquals(11, last.startVersion)
        assertEquals(12, last.endVersion)
        assertEquals(12, database.openHelper.readableDatabase.version)
    }

    // ------------------------------------------------------------------ helpers

    private data class Column(val type: String, val notNull: Int, val default: String?)

    /** What `PRAGMA table_info` says of one column of [table] -- the three things Room compares -- or null when there is none. */
    private fun columnOf(db: SupportSQLiteDatabase, table: String, name: String): Column? =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val columnName = cursor.getColumnIndexOrThrow("name")
            val type = cursor.getColumnIndexOrThrow("type")
            val notNull = cursor.getColumnIndexOrThrow("notnull")
            val default = cursor.getColumnIndexOrThrow("dflt_value")
            var found: Column? = null
            while (found == null && cursor.moveToNext()) {
                if (cursor.getString(columnName) == name) {
                    found = Column(cursor.getString(type), cursor.getInt(notNull), cursor.getString(default))
                }
            }
            found
        }

    /**
     * `vod_detail` and `series_detail` as a version 11 database has them: the tables Room makes less the columns this
     * migration adds, under the same names. The current ones are dropped and made again, so nothing here depends on a SQLite
     * that can drop a column.
     */
    private fun toVersionEleven(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE `vod_detail`")
        db.execSQL(
            "CREATE TABLE `vod_detail` (`source_id` TEXT NOT NULL, `provider_ref` TEXT NOT NULL, " +
                "`rating` REAL, `plot` TEXT, `director` TEXT, `cast_names` TEXT, `genres` TEXT, " +
                "`release_year` INTEGER, `duration_seconds` INTEGER, `added_epoch` INTEGER, " +
                "`fetched_at` INTEGER NOT NULL, PRIMARY KEY(`source_id`, `provider_ref`))",
        )
        db.execSQL("DROP TABLE `series_detail`")
        db.execSQL(
            "CREATE TABLE `series_detail` (`series_id` TEXT NOT NULL, `rating` REAL, `plot` TEXT, " +
                "`cast_names` TEXT, `genres` TEXT, `release_year` INTEGER, `backdrop_url` TEXT, " +
                "`fetched_at` INTEGER NOT NULL, PRIMARY KEY(`series_id`))",
        )
    }

    private fun countOf(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 }
}
