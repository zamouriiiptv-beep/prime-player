package com.castivio.data.database

import android.content.Context
import androidx.room.Room
import com.castivio.domain.EpgProgramme
import com.castivio.domain.EpgSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Writing the guide leaves the journal in WAL, the mode in which the screens read while something writes.
 *
 * The guide writer set `journal_mode = MEMORY` for every store, as the catalogue writer did until a device
 * showed that a rollback journal locks the readers out while a writer works. Out of WAL, a write waits for the
 * reads in progress and the reads for the write -- and the writer ran each time a channel without a stored
 * guide was played from the board.
 *
 * A file, not the in-memory database the other tests use: an in-memory database has no journal to be in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpgJournalTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var database: CastivioDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(NAME)
        database = Room.databaseBuilder(context, CastivioDatabase::class.java, NAME)
            .allowMainThreadQueries()
            .build()
        assumeTrue("the SQLite under test runs WAL", journalMode() == "wal")
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(NAME)
    }

    @Test
    fun `the journal stays WAL while the guide is written, and after`() {
        val writer = RoomEpgWriter(database) { NOW }

        writer.begin("src")
        assertEquals("while the store is open", "wal", journalMode())
        writer.writeProgrammes(listOf(programme()))
        writer.commit()
        assertEquals("between two batches", "wal", journalMode())
        writer.finish(summary())

        assertEquals("wal", journalMode())
    }

    @Test
    fun `a store that fails leaves the journal in WAL`() {
        val writer = RoomEpgWriter(database) { NOW }
        writer.begin("src")

        writer.abort(IllegalStateException("the provider went away"))

        assertEquals("wal", journalMode())
    }

    private fun journalMode(): String =
        database.openHelper.writableDatabase.query("PRAGMA journal_mode").use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0).lowercase()
        }

    private fun programme() = EpgProgramme(
        channelId = "ch0",
        title = "News",
        description = null,
        startMs = NOW,
        stopMs = NOW + 30 * 60_000L,
    )

    private fun summary() = EpgSummary(
        sourceId = "src",
        programmes = 1,
        channels = 1,
        skipped = 0,
        outsideWindow = 0,
        durationMs = 0,
    )

    private companion object {
        const val NAME = "epg-journal-test.db"
        const val NOW = 1_784_980_800_000L
    }
}
