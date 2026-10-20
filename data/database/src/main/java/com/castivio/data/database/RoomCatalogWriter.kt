package com.castivio.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.tracing.trace
import com.castivio.core.platform.CastivioTrace
import com.castivio.core.platform.DatabaseStep
import com.castivio.core.platform.PerformanceLog
import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind

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
 *  - **Fast pragmas during import only.** `synchronous = OFF` is worth 3–5x on cheap
 *    flash. The risk is losing an *in-flight import* to a power cut, which costs a
 *    re-download and nothing else, so the trade is clearly worth it — and it is
 *    restored afterwards. The journal deliberately stays WAL throughout: see
 *    `applyImportPragmas` for why that one line decided time-to-first-content.
 *  - **The search index once, at the end.** Not row by row beside the rows, and not
 *    rebuilt whole either: the rows this import wrote, in two statements, after the
 *    last of them. See `indexWrittenRows`.
 *
 * Blocking, like the interface it implements. Call it on an IO dispatcher.
 */
class RoomCatalogWriter(
    private val database: CastivioDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : CatalogWriter {

    private var sourceId: String? = null
    private var mode = ImportMode.REPLACE
    private var sections: Set<MediaKind> = MediaKind.entries.toSet()
    private var generation = 1L
    private var now = 0L
    private var groupOrder = 0
    private var inTransaction = false
    private var lastRowIdBefore = 0L
    private var wroteRows = false
    private var itemStatement: SupportSQLiteStatement? = null
    private var groupStatement: SupportSQLiteStatement? = null

    override fun begin(sourceId: String, mode: ImportMode, sections: Set<MediaKind>) {
        require(sections.isNotEmpty()) { "an import of no section" }
        val db = database.openHelper.writableDatabase
        this.sourceId = sourceId
        this.mode = mode
        this.sections = sections
        now = clock()
        groupOrder = 0
        generation = when (mode) {
            // A new generation, so the old one can be dropped at the end.
            ImportMode.REPLACE -> db.longOf(
                "SELECT IFNULL(MAX(generation), 0) + 1 FROM media WHERE source_id = ?",
                sourceId,
            )
            // The generation already in use: appended rows belong to the catalogue
            // that is there, and must not be pruned as leftovers by the next
            // refresh's swap.
            ImportMode.APPEND -> db.longOf(
                "SELECT IFNULL(MAX(generation), 1) FROM media WHERE source_id = ?",
                sourceId,
            )
        }
        // Every row this import writes lands above it, a row written again included:
        // `INSERT OR REPLACE` allocates the new rowid before it deletes the old row. That
        // is how the index knows, at the end, which rows are this import's.
        lastRowIdBefore = db.longOf("SELECT IFNULL(MAX(rowid), 0) FROM media")
        wroteRows = false
        db.applyImportPragmas()
        // Which way this import writes decides which steps it pays for -- a replacing write
        // prunes the old generation of what it replaces at the end, an appending one does
        // not -- so a reading of its steps means nothing without it.
        PerformanceLog.saveMode(mode.name)
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

    /**
     * One batch of rows into `media`, with its six indices -- and nothing else: the search
     * index takes them at the end of the import. Timed per batch, never per row.
     */
    override fun writeItems(items: List<CatalogItem>) {
        val source = requireStarted()
        val statement = itemStatement ?: error("writeItems before begin")
        if (items.isEmpty()) return
        wroteRows = true
        timed(DatabaseStep.ROWS) {
            for (item in items) insert(source, statement, item)
        }
    }

    private fun insert(source: String, statement: SupportSQLiteStatement, item: CatalogItem) {
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
        statement.bindNullable(11, item.providerRef)
        statement.bindLong(12, item.providerOrder.toLong())
        statement.bindNullable(13, item.durationSeconds)
        statement.bindNullable(14, item.seriesId)
        statement.bindNullable(15, item.seriesTitle)
        statement.bindNullable(16, item.seasonNumber)
        statement.bindNullable(17, item.episodeNumber)
        statement.bindLong(18, generation)
        // Bound twice: the statement keeps an existing row's added_at so a
        // nightly refresh does not make the whole library "recently added".
        statement.bindString(19, item.id)
        statement.bindLong(20, now)
        statement.executeInsert()
    }

    /**
     * Ends one batch's transaction and opens the next, inside a trace section.
     *
     * The section is where SQLite's share of an import lives. Phase A measured our own
     * parse-and-build half on the JVM and found it to be under a second for 400,000
     * rows; this is the half that measurement deliberately stubbed out, and the only
     * place a device can report it from.
     *
     * Once per batch -- never per row. A section inside the write loop would cost more
     * than the write and would fill the trace buffer before the import finished.
     */
    override fun commit() = trace(CastivioTrace.COMMIT) {
        // Timed as well as traced, and at the same instant, so the on-device panel and a
        // perfetto capture cannot disagree about what "database" cost. `System.nanoTime`
        // rather than the wall clock for the reason `PerformanceLog` states at length:
        // NTP corrects the wall clock during exactly the minute a first import runs in.
        val startedAt = System.nanoTime()
        try {
            commitNow()
        } finally {
            PerformanceLog.addDatabaseNanos(System.nanoTime() - startedAt)
        }
    }

    private fun commitNow() {
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
            if (mode == ImportMode.REPLACE) {
                // The generation swap. Everything the provider no longer lists
                // disappears here, in one statement, after the new catalogue is
                // already visible -- in the sections this import replaces, and only
                // there: a section read on its own from a kept playlist leaves the
                // others' rows, of whatever generation, where they are.
                timed(DatabaseStep.PRUNE) {
                    val kinds = sections.joinToString(", ") { "?" }
                    val args = (listOf<Any>(source, generation) + sections.map { it.name }).toTypedArray()
                    execSQL("DELETE FROM media WHERE source_id = ? AND generation != ? AND kind IN ($kinds)", args)
                    execSQL("DELETE FROM media_group WHERE source_id = ? AND generation != ? AND kind IN ($kinds)", args)
                }
            }
            timed(DatabaseStep.COUNTS) { execSQL(COUNT_GROUPS, arrayOf(source)) }
            timed(DatabaseStep.SEARCH) { indexWrittenRows() }
        }

        db.restoreNormalPragmas()
        database.invalidationTracker.refreshVersionsAsync()
        release()
    }

    override fun abort(cause: Throwable?) {
        val db = runCatching { database.openHelper.writableDatabase }.getOrNull()
        if (db != null) {
            // Rolls back the batch in flight; everything already committed stays,
            // because a partial catalogue beats an empty one -- and is found by search
            // like a whole one. Quietly: an import that failed on the database itself
            // may fail here again, and that is no reason to lose the rows.
            db.endTransactionQuietly()
            if (sourceId != null && wroteRows) {
                runCatching { db.transaction { timed(DatabaseStep.SEARCH) { indexWrittenRows() } } }
            }
            db.restoreNormalPragmas()
            database.invalidationTracker.refreshVersionsAsync()
        }
        inTransaction = false
        release()
    }

    /**
     * The search index for what this import wrote, in two statements after its last row.
     *
     * Every entry's `docid` is its row's rowid, and `INSERT OR REPLACE` gives a row it
     * writes again a new one. So the entries this import made stale -- a row written
     * again, or the old generation a replacement pruned -- are the ones whose docid is no
     * longer a row, and the rows it wrote are the ones above [lastRowIdBefore]. Nothing
     * else writes the index, so a row above it has no entry yet.
     *
     * Kept row by row instead, the entry was a second statement per row on the hot path
     * and the stale one stayed behind: every reload added another copy of the section, and
     * search returned each channel once per load. Rebuilt whole instead, it cost every row
     * of every section and subscription, three hundred stations or not.
     *
     * Search is complete when the import completes -- the rows of a load still running
     * are found once it ends.
     */
    private fun SupportSQLiteDatabase.indexWrittenRows() {
        execSQL("DELETE FROM media_fts WHERE docid NOT IN (SELECT rowid FROM media)")
        execSQL(INDEX_WRITTEN, arrayOf(lastRowIdBefore))
    }

    /**
     * One step of an import's database work, timed for the log: the rows, batch by batch,
     * and the three steps after the last commit.
     *
     * Those three were the part of a load nothing measured: the commit figure stops before
     * them and the full load includes them. Timed one by one, because they scale
     * differently -- the prune with what was replaced, the counts with the groups, the
     * index with the rows written -- and a single number would not say which.
     */
    private inline fun timed(step: DatabaseStep, block: () -> Unit) {
        val startedAt = System.nanoTime()
        try {
            block()
        } finally {
            PerformanceLog.addDatabaseStep(step, System.nanoTime() - startedAt)
        }
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
     *
     * ## The journal stays WAL, and that is the whole of "first content"
     *
     * This used to set `journal_mode = MEMORY` as well, and that one line is what made
     * time-to-first-content equal time-to-full-import. WAL is the mode in which a
     * reader and a writer can hold the database at the same time; in *any* rollback
     * journal — MEMORY included — the writer takes an exclusive lock and readers wait.
     * This writer keeps a transaction open continuously, reopening it inside `commit`,
     * so for the whole import there is effectively always a writer holding that lock.
     *
     * The rows were being committed all along. Nothing could read them. `count()` — the
     * query the section header and the empty-state check both wait on — blocked until
     * the import ended, so the screen showed its "fetching" state for the full duration
     * and first content landed at 175s on a real provider instead of at the first
     * category.
     *
     * `synchronous = OFF` stays: it is the half of the tuning that actually bought the
     * 3–5x on cheap flash, and it costs an in-flight import in a power cut, which is a
     * re-download and nothing else. The journal mode bought far less and cost the
     * feature this whole phase exists to deliver.
     */
    private fun SupportSQLiteDatabase.applyImportPragmas() {
        runCatching { execSQL("PRAGMA synchronous = OFF") }
    }

    /**
     * Restores durability afterwards.
     *
     * The WAL statement is left in even though the import no longer leaves the journal
     * anywhere else: it is a repair for a connection some other writer moved, and it
     * costs one no-op statement per import.
     */
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
                artwork_url, group_id, epg_channel_id, provider_ref, provider_order,
                duration_seconds, series_id, series_title, season_number,
                episode_number, generation, added_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
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

        /**
         * Every category of the subscription, counted.
         *
         * Through the index that leads with the subscription and the kind: a category's
         * rows carry its subscription and its kind -- its id is made from both -- so the
         * two conditions change no figure and make each count a range of the index. On
         * the category id alone, which no index leads with, it was a scan of every row of
         * every subscription once per category: 4.8 s at the end of a load of the channels
         * on the subscription it was measured on, and as long after three hundred stations.
         */
        const val COUNT_GROUPS = """
            UPDATE media_group SET item_count = (
                SELECT COUNT(*) FROM media
                WHERE media.source_id = media_group.source_id
                  AND media.kind = media_group.kind
                  AND media.group_id = media_group.id
            )
            WHERE source_id = ?
        """

        /** `docid` is FTS4's name for an entry's rowid: here, always its row's. */
        const val INDEX_WRITTEN = """
            INSERT INTO media_fts (docid, media_id, search_text)
            SELECT rowid, id, search_text FROM media WHERE rowid > ?
        """
    }
}
