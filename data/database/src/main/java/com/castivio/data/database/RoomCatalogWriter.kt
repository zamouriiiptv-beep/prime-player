package com.castivio.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup

/**
 * Writes the catalogue to SQLite as fast as the storage allows.
 *
 * Deliberately not a DAO. Room's generated `@Insert` builds a `ContentValues`
 * and a fresh statement per call; at 400,000 rows that overhead is the import.
 * This holds one prepared statement for the whole run and re-binds it per row,
 * which is the single biggest win available on the write path.
 *
 * Three more things make it survivable on a Fire TV Stick:
 *
 *  - **Transactions of one batch.** Committing per batch lets the UI show
 *    content while the rest imports. One transaction around 400,000 rows would
 *    be marginally faster and show nothing for twenty seconds.
 *  - **Generations.** Rows are written under a new generation and the previous
 *    one is deleted at the end, so a refresh never shows a half-empty library.
 *  - **Fast pragmas during import only.** `synchronous = OFF` and an in-memory
 *    journal are worth 3–5x on cheap flash. The risk is losing an *in-flight
 *    import* to a power cut, which costs a re-download and nothing else, so the
 *    trade is clearly worth it — and both are restored afterwards.
 *
 * Blocking, like the interface it implements. Call it on an IO dispatcher.
 */
class RoomCatalogWriter(
    private val database: CastivioDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : CatalogWriter {

    private var sourceId: String? = null
    private var generation = 1L
    private var now = 0L
    private var groupOrder = 0
    private var inTransaction = false
    private var itemStatement: SupportSQLiteStatement? = null
    private var groupStatement: SupportSQLiteStatement? = null

    override fun begin(sourceId: String) {
        val db = database.openHelper.writableDatabase
        this.sourceId = sourceId
        now = clock()
        groupOrder = 0
        generation = db.longOf(
            "SELECT IFNULL(MAX(generation), 0) + 1 FROM media WHERE source_id = ?",
            sourceId,
        )
        db.applyImportPragmas()
        itemStatement = db.compileStatement(INSERT_ITEM)
        groupStatement = db.compileStatement(INSERT_GROUP)
        db.begin()
    }

    override fun writeGroups(groups: List<MediaGroup>) {
        val source = requireStarted()
        val statement = groupStatement ?: error("writeGroups before begin")
        for (group in groups) {
            statement.clearBindings()
            statement.bindString(1, group.id)
            statement.bindString(2, source)
            statement.bindString(3, group.name)
            statement.bindString(4, group.kind.name)
            statement.bindLong(5, (groupOrder++).toLong())
            statement.bindLong(6, generation)
            statement.executeInsert()
        }
    }

    override fun writeItems(items: List<CatalogItem>) {
        val source = requireStarted()
        val statement = itemStatement ?: error("writeItems before begin")
        for (item in items) {
            statement.clearBindings()
            statement.bindString(1, item.id)
            statement.bindString(2, source)
            statement.bindString(3, item.kind.name)
            statement.bindString(4, item.title)
            statement.bindString(5, sortTitle(item.title))
            statement.bindString(6, searchText(item.title, item.seriesTitle))
            statement.bindString(7, item.streamUrl)
            statement.bindNullable(8, item.artworkUrl)
            statement.bindNullable(9, item.groupId)
            statement.bindNullable(10, item.epgChannelId)
            statement.bindLong(11, item.providerOrder.toLong())
            statement.bindNullable(12, item.durationSeconds)
            statement.bindNullable(13, item.seriesId)
            statement.bindNullable(14, item.seriesTitle)
            statement.bindNullable(15, item.seasonNumber)
            statement.bindNullable(16, item.episodeNumber)
            statement.bindLong(17, generation)
            // Bound twice: the statement keeps an existing row's added_at so a
            // nightly refresh does not make the whole library "recently added".
            statement.bindString(18, item.id)
            statement.bindLong(19, now)
            statement.executeInsert()
        }
    }

    override fun commit() {
        if (!inTransaction) return
        val db = database.openHelper.writableDatabase
        db.setTransactionSuccessful()
        db.endTransaction()
        inTransaction = false
        // Raw statements bypass Room's write path, so nothing would tell the
        // observers that the tables changed. Without this the first import
        // finishes and the screen stays empty until something else writes.
        database.invalidationTracker.refreshVersionsAsync()
        db.begin()
    }

    override fun finish(summary: ImportSummary) {
        val source = sourceId ?: return
        val db = database.openHelper.writableDatabase
        commit()
        db.endTransactionQuietly()

        db.transaction {
            // The generation swap. Everything the provider no longer lists
            // disappears here, in one statement, after the new catalogue is
            // already visible.
            execSQL("DELETE FROM media WHERE source_id = ? AND generation != ?", arrayOf(source, generation))
            execSQL("DELETE FROM media_group WHERE source_id = ? AND generation != ?", arrayOf(source, generation))
            execSQL(
                """
                UPDATE media_group SET item_count =
                    (SELECT COUNT(*) FROM media WHERE media.group_id = media_group.id)
                WHERE source_id = ?
                """,
                arrayOf(source),
            )
            rebuildSearchIndex()
        }

        db.restoreNormalPragmas()
        database.invalidationTracker.refreshVersionsAsync()
        release()
    }

    override fun abort(cause: Throwable?) {
        val db = runCatching { database.openHelper.writableDatabase }.getOrNull()
        if (db != null) {
            // Rolls back the batch in flight; everything already committed stays,
            // because a partial catalogue beats an empty one.
            db.endTransactionQuietly()
            db.restoreNormalPragmas()
            database.invalidationTracker.refreshVersionsAsync()
        }
        inTransaction = false
        release()
    }

    /**
     * Rebuilds the whole FTS index in two statements.
     *
     * Cheaper *and* simpler than maintaining it per row: inserting into the
     * index during import would add a second write per row on the hot path, and
     * keeping a standalone index in sync through `INSERT OR REPLACE` (which
     * assigns a new rowid) is exactly the kind of bookkeeping that rots into
     * stale search results.
     *
     * Search is therefore complete when the import completes — which is also the
     * only point at which searching the catalogue is a meaningful thing to do.
     */
    private fun SupportSQLiteDatabase.rebuildSearchIndex() {
        execSQL("DELETE FROM media_fts")
        execSQL(
            """
            INSERT INTO media_fts (media_id, search_text)
            SELECT id, search_text FROM media
            """,
        )
    }

    private fun release() {
        runCatching { itemStatement?.close() }
        runCatching { groupStatement?.close() }
        itemStatement = null
        groupStatement = null
        sourceId = null
    }

    private fun requireStarted(): String = sourceId ?: error("write before begin")

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

    /**
     * Import-only tuning. Every statement is wrapped: a device whose SQLite
     * refuses one of these should import slowly, not fail.
     */
    private fun SupportSQLiteDatabase.applyImportPragmas() {
        runCatching { execSQL("PRAGMA synchronous = OFF") }
        runCatching { execSQL("PRAGMA journal_mode = MEMORY") }
    }

    private fun SupportSQLiteDatabase.restoreNormalPragmas() {
        runCatching { execSQL("PRAGMA synchronous = NORMAL") }
        runCatching { execSQL("PRAGMA journal_mode = WAL") }
    }

    private fun SupportSQLiteDatabase.longOf(sql: String, vararg args: Any): Long =
        query(sql, args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 1L
        }

    private fun SupportSQLiteStatement.bindNullable(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }

    private fun SupportSQLiteStatement.bindNullable(index: Int, value: Int?) {
        if (value == null) bindNull(index) else bindLong(index, value.toLong())
    }

    private companion object {
        /**
         * One statement for the whole import.
         *
         * `INSERT OR REPLACE` rather than `INSERT`: ids are stable, so a refresh
         * updates rows in place instead of needing the old catalogue deleted
         * first — which is what lets the library stay readable throughout.
         */
        const val INSERT_ITEM = """
            INSERT OR REPLACE INTO media (
                id, source_id, kind, title, sort_title, search_text, stream_url,
                artwork_url, group_id, epg_channel_id, provider_order,
                duration_seconds, series_id, series_title, season_number,
                episode_number, generation, added_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                COALESCE((SELECT added_at FROM media WHERE id = ?), ?)
            )
        """

        /**
         * `item_count` is written as 0 and filled in at the end of the import.
         * It has to be listed: a Kotlin default does not become a SQL default, so
         * omitting the NOT NULL column would fail every insert.
         */
        const val INSERT_GROUP = """
            INSERT OR REPLACE INTO media_group (
                id, source_id, name, kind, provider_order, item_count, generation
            ) VALUES (?, ?, ?, ?, ?, 0, ?)
        """
    }
}
