package com.castivio.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.castivio.domain.EpgProgramme
import com.castivio.domain.EpgRetention
import com.castivio.domain.EpgSummary
import com.castivio.domain.EpgWriter

/**
 * Writes the guide, on the same terms as [RoomCatalogWriter]: one prepared
 * statement for the whole import, a transaction per batch, fast pragmas while it
 * runs.
 *
 * `INSERT OR REPLACE` keyed on `(channel_id, start_ms)` makes a refresh
 * idempotent — a guide that overlaps yesterday's download updates rows rather
 * than duplicating the schedule.
 *
 * Retention runs at [finish]: the import already refused out-of-window
 * programmes, so this only removes what aged out since the last refresh. That is
 * a range delete over the `stop_ms` index and takes milliseconds, which is why
 * the guide never grows without bound even if the app runs for months.
 *
 * Blocking. Call it on an IO dispatcher.
 */
class RoomEpgWriter(
    private val database: CastivioDatabase,
    private val retention: EpgRetention = EpgRetention.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
) : EpgWriter {

    private var sourceId: String? = null
    private var inTransaction = false
    private var statement: SupportSQLiteStatement? = null

    override fun begin(sourceId: String) {
        val db = database.openHelper.writableDatabase
        this.sourceId = sourceId
        db.applyImportPragmas()
        statement = db.compileStatement(INSERT_PROGRAMME)
        db.begin()
    }

    override fun writeProgrammes(programmes: List<EpgProgramme>) {
        val source = sourceId ?: error("writeProgrammes before begin")
        val bound = statement ?: error("writeProgrammes before begin")
        for (programme in programmes) {
            bound.clearBindings()
            bound.bindString(1, programme.channelId)
            bound.bindLong(2, programme.startMs)
            bound.bindLong(3, programme.stopMs)
            bound.bindString(4, programme.title)
            // Held in a local: a property from another module cannot be smart cast.
            val description = programme.description
            if (description == null) bound.bindNull(5) else bound.bindString(5, description)
            bound.bindString(6, source)
            bound.executeInsert()
        }
    }

    override fun commit() {
        if (!inTransaction) return
        val db = database.openHelper.writableDatabase
        db.setTransactionSuccessful()
        db.endTransaction()
        inTransaction = false
        // Raw statements bypass Room's write path, so the observers have to be
        // told the table changed.
        database.invalidationTracker.refreshVersionsAsync()
        db.begin()
    }

    override fun finish(summary: EpgSummary) {
        if (sourceId == null) return
        val db = database.openHelper.writableDatabase
        commit()
        db.endTransactionQuietly()

        val now = clock()
        db.transaction {
            execSQL("DELETE FROM programme WHERE stop_ms < ?", arrayOf(now - retention.pastMs))
            execSQL("DELETE FROM programme WHERE start_ms > ?", arrayOf(now + retention.futureMs))
        }

        db.restoreNormalPragmas()
        database.invalidationTracker.refreshVersionsAsync()
        release()
    }

    override fun abort(cause: Throwable?) {
        val db = runCatching { database.openHelper.writableDatabase }.getOrNull()
        if (db != null) {
            // Committed batches stay: a partial guide still shows now/next for the
            // channels it reached.
            db.endTransactionQuietly()
            db.restoreNormalPragmas()
            database.invalidationTracker.refreshVersionsAsync()
        }
        inTransaction = false
        release()
    }

    private fun release() {
        runCatching { statement?.close() }
        statement = null
        sourceId = null
    }

    private fun SupportSQLiteDatabase.begin() {
        beginTransaction()
        inTransaction = true
    }

    private fun SupportSQLiteDatabase.endTransactionQuietly() {
        if (!inTransaction) return
        runCatching { endTransaction() }
        inTransaction = false
    }

    private inline fun SupportSQLiteDatabase.transaction(body: SupportSQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            body()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun SupportSQLiteDatabase.applyImportPragmas() {
        runCatching { execSQL("PRAGMA synchronous = OFF") }
        runCatching { execSQL("PRAGMA journal_mode = MEMORY") }
    }

    private fun SupportSQLiteDatabase.restoreNormalPragmas() {
        runCatching { execSQL("PRAGMA synchronous = NORMAL") }
        runCatching { execSQL("PRAGMA journal_mode = WAL") }
    }

    private companion object {
        const val INSERT_PROGRAMME = """
            INSERT OR REPLACE INTO programme (
                channel_id, start_ms, stop_ms, title, description, source_id
            ) VALUES (?, ?, ?, ?, ?, ?)
        """
    }
}
