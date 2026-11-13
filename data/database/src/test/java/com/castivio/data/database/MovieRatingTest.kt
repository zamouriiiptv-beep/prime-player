package com.castivio.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import com.castivio.domain.CatalogItem
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaKind
import com.castivio.domain.Movie
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The rating a provider's list gives a film, on its way from the import to the poster.
 *
 * Version 11 adds `media.rating`. What these hold is the three places it can go wrong without a screen noticing: the
 * statement that writes it (twenty-two positional binds, one of which is the `added_at` that must survive a refresh),
 * the migration that adds the column to a catalogue that is already here, and the mapping that hands it to the wall
 * -- on whichever query the wall happens to be read by.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MovieRatingTest {

    private lateinit var database: CastivioDatabase

    @Before
    fun setUp() {
        database = CastivioDatabase.inMemory(RuntimeEnvironment.getApplication())
        Fixtures.register(database, "src")
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ------------------------------------------------------------------ the write

    @Test
    fun `a film's rating is written with its row and read back on the film`() = runBlocking {
        write(film("dune", "Dune", rating = 8.1), film("cats", "Cats"))

        val films = database.mediaDao().window("MOVIE", 0, 10).map { it.toDomain() as Movie }

        assertEquals(8.1, films.single { it.title == "Dune" }.rating!!, 0.0001)
        assertNull("a film the list gave no rating reads as none", films.single { it.title == "Cats" }.rating)
    }

    /**
     * The rating is the last of twenty-two binds, after `catch_up_hours` and after the two that make up `added_at`'s
     * "keep the old day" subquery. A film is given both columns here so that a bind that slipped one place would put a
     * figure where a number of hours belongs.
     */
    @Test
    fun `the rating and the archive hours are written to their own columns, and the first day survives a refresh`() = runBlocking {
        write(film("dune", "Dune", rating = 8.1).copy(catchUpHours = 72), now = 1_000L)
        write(film("dune", "Dune", rating = 7.9).copy(catchUpHours = 72), now = 9_000L)

        val row = database.mediaDao().window("MOVIE", 0, 10).single()

        assertEquals("the refresh writes the list's latest figure", 7.9, row.rating!!, 0.0001)
        assertEquals(72, row.catchUpHours)
        assertEquals("a refresh must not make the whole library look recently added", 1_000L, row.addedAt)
    }

    @Test
    fun `a film the list no longer gives a rating loses the one it had`() = runBlocking {
        write(film("dune", "Dune", rating = 8.1), now = 1_000L)
        write(film("dune", "Dune"), now = 9_000L)

        assertNull(database.mediaDao().window("MOVIE", 0, 10).single().rating)
    }

    @Test
    fun `a rating of nothing that reaches the table is read as none`() = runBlocking {
        write(film("zero", "Zero", rating = 0.0), film("below", "Below", rating = -1.0))

        val films = database.mediaDao().window("MOVIE", 0, 10).map { it.toDomain() as Movie }

        assertEquals(2, films.size)
        assertNull("zero is no rating, and is not drawn as one", films.single { it.title == "Zero" }.rating)
        assertNull(films.single { it.title == "Below" }.rating)
    }

    // ------------------------------------------------------------------ the read, by every way the wall is read

    @Test
    fun `the rating is on the film whether the wall is paged or searched`() = runBlocking {
        write(film("dune", "Dune", rating = 8.1))

        val paged = database.mediaDao().pageByProvider("MOVIE").firstPage().single().toDomain() as Movie
        val found = repository().search("dune", MediaKind.MOVIE, limit = 10).single() as Movie

        assertEquals(8.1, paged.rating!!, 0.0001)
        assertEquals(8.1, found.rating!!, 0.0001)
    }

    // ------------------------------------------------------------------ the migration

    /**
     * Run the way the existing migrations are: on the database Room made, with the table put back as the version
     * before had it, because Room's own migration test needs a device and an exported schema this repository does
     * not keep. What a device adds, and this does not, is Room's check of the result against the entity; the
     * comparison of the new column's own description with the one Room made from the entity is the nearest to it.
     */
    @Test
    fun `10 to 11 adds the column as the entity declares it, and leaves the rows that are there as they were`() {
        val db = database.openHelper.writableDatabase
        val declared = columnOf(db, "rating")
        assertEquals("the entity declares a nullable REAL with no default", Column("REAL", notNull = 0, default = null), declared)

        toVersionTen(db)
        db.execSQL(
            "INSERT INTO `media` (`id`, `source_id`, `kind`, `title`, `sort_title`, `search_text`, `stream_url`, " +
                "`provider_order`, `generation`, `added_at`, `catch_up_hours`) " +
                "VALUES ('old', 'src', 'MOVIE', 'Dune', 'dune', 'dune', 'http://host/old.mkv', 0, 1, 1000, 72)",
        )
        assertNull("a version 10 table has no such column", columnOf(db, "rating"))

        CastivioMigrations.ALL.single { it.startVersion == 10 }.migrate(db)

        assertEquals("the column Room checks for", declared, columnOf(db, "rating"))
        assertEquals(
            "the row is as it was",
            1,
            countOf(db, "SELECT COUNT(*) FROM `media` WHERE `id` = 'old' AND `title` = 'Dune' AND `added_at` = 1000 AND `catch_up_hours` = 72"),
        )
        assertEquals("and has no rating until its list is read again", 1, countOf(db, "SELECT COUNT(*) FROM `media` WHERE `rating` IS NULL"))
    }

    @Test
    fun `the migration to version 11 is the last of the chain, and the database is at 11`() {
        val last = CastivioMigrations.ALL.maxByOrNull { it.startVersion }!!

        assertEquals(10, last.startVersion)
        assertEquals(11, last.endVersion)
        assertEquals(11, database.openHelper.readableDatabase.version)
    }

    // ------------------------------------------------------------------ helpers

    private data class Column(val type: String, val notNull: Int, val default: String?)

    /** What `PRAGMA table_info` says of one column of `media` -- the three things Room compares -- or null when there is none. */
    private fun columnOf(db: SupportSQLiteDatabase, name: String): Column? =
        db.query("PRAGMA table_info(`media`)").use { cursor ->
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
     * `media` as a version 10 database has it: the table Room makes less the column this migration adds, under the same
     * name. The current one is moved aside rather than altered, so nothing here depends on a SQLite that can drop a
     * column.
     */
    private fun toVersionTen(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `media` RENAME TO `media_current`")
        db.execSQL(
            "CREATE TABLE `media` (`id` TEXT NOT NULL, `source_id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `sort_title` TEXT NOT NULL, `search_text` TEXT NOT NULL, " +
                "`stream_url` TEXT NOT NULL, `artwork_url` TEXT, `group_id` TEXT, `epg_channel_id` TEXT, " +
                "`provider_ref` TEXT, `provider_order` INTEGER NOT NULL, `duration_seconds` INTEGER, " +
                "`series_id` TEXT, `series_title` TEXT, `season_number` INTEGER, `episode_number` INTEGER, " +
                "`generation` INTEGER NOT NULL, `added_at` INTEGER NOT NULL, `catch_up_hours` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
    }

    private fun countOf(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else -1 }

    private fun film(id: String, title: String, rating: Double? = null) = CatalogItem(
        id = id,
        sourceId = "src",
        kind = MediaKind.MOVIE,
        title = title,
        streamUrl = "http://host/movie/u/p/$id.mkv",
        rating = rating,
    )

    /** One import of the films, the way a refresh writes them: the section replaced, the rows given. */
    private fun write(vararg items: CatalogItem, now: Long = 1_000L) {
        val writer = RoomCatalogWriter(database) { now }
        writer.begin("src", ImportMode.REPLACE, setOf(MediaKind.MOVIE))
        writer.writeItems(items.toList())
        writer.finish(
            ImportSummary(
                sourceId = "src",
                items = items.size,
                groups = 0,
                skipped = 0,
                byKind = mapOf(MediaKind.MOVIE to items.size),
                durationMs = 1,
            ),
        )
    }

    private fun repository() = RoomCatalogRepository(
        mediaDao = database.mediaDao(),
        groupDao = database.groupDao(),
        favoriteDao = database.favoriteDao(),
        progressDao = database.progressDao(),
        database = database,
    )
}
