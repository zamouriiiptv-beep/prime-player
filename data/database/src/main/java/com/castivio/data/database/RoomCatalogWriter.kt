package com.castivio.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.tracing.trace
import com.castivio.core.platform.CastivioTrace
import com.castivio.core.platform.DatabaseStep
import com.castivio.core.platform.PerformanceLog
import com.castivio.domain.CatalogCheck
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
 *
 * Blocking, like the interface it implements. Call it on an IO dispatcher.
 */
class RoomCatalogWriter(
    private val database: CastivioDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : CatalogWriter {

    private var sourceId: String? = null
    private var mode = ImportMode.REPLACE
    private var generation = 1L
    private var now = 0L
    private var groupOrder = 0
    private var inTransaction = false
    private var itemStatement: SupportSQLiteStatement? = null
    private var groupStatement: SupportSQLiteStatement? = null
    private var ftsStatement: SupportSQLiteStatement? = null

    override fun begin(sourceId: String, mode: ImportMode) {
        val db = database.openHelper.writableDatabase
        this.sourceId = sourceId
        this.mode = mode
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
        db.applyImportPragmas()
        // Which way this import writes decides which steps it pays for -- a replacing write
        // prunes and rebuilds the search index at the end, an appending one indexes per row
        // -- so a reading of its steps means nothing without it.
        PerformanceLog.saveMode(mode.name)
        itemStatement = db.compileStatement(INSERT_ITEM)
        groupStatement = db.compileStatement(INSERT_GROUP)
        // Only APPEND maintains the index per row; REPLACE rebuilds it in two
        // statements at the end, which is far cheaper across 400,000 rows.
        ftsStatement = if (mode == ImportMode.APPEND) db.compileStatement(INSERT_FTS) else null
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
        insert(source, statement, items)
    }

    /**
     * The batch's rows, and their search entries where this import indexes per row.
     *
     * Timed in two halves, per row, and reported per batch: the row into `media` with its
     * six indices, and the entry into `media_fts`. Together they were one figure, and the
     * one question it could not answer was how much of it the search index was. Reading
     * the clock four times a row costs well under a millisecond a thousand rows.
     */
    private fun insert(source: String, statement: SupportSQLiteStatement, items: List<CatalogItem>) {
        var rowNanos = 0L
        var searchNanos = 0L
        try {
            insertTimed(source, statement, items) { row, search ->
                rowNanos += row
                searchNanos += search
            }
        } finally {
            PerformanceLog.addDatabaseStep(DatabaseStep.ROWS, rowNanos)
            if (ftsStatement != null) PerformanceLog.addDatabaseStep(DatabaseStep.SEARCH_ROWS, searchNanos)
        }
    }

    private inline fun insertTimed(
        source: String,
        statement: SupportSQLiteStatement,
        items: List<CatalogItem>,
        spent: (row: Long, search: Long) -> Unit,
    ) {
        for (item in items) {
            val rowStarted = System.nanoTime()
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
            val rowId = statement.executeInsert()
            val searchStarted = System.nanoTime()

            // The FTS row is keyed by the media row's own rowid, so an append that
            // touches a row already indexed replaces its entry rather than adding a
            // second one and returning the item twice from search.
            ftsStatement?.let { fts ->
                fts.clearBindings()
                fts.bindLong(1, rowId)
                fts.bindString(2, item.id)
                fts.bindString(3, searchText(item.title, item.seriesTitle))
                fts.executeInsert()
            }
            spent(searchStarted - rowStarted, if (ftsStatement != null) System.nanoTime() - searchStarted else 0L)
        }
    }

    /**
     * Counts of what is stored for [sourceId] and in the search index, read and never
     * written. A measurement for debuggable builds, taken before an import and after it.
     *
     * The index is counted through its own size table, one row per entry, rather than
     * through the full-text table, which would read every entry's text to count it; the
     * duplicated ids through its content table for the same reason. Each falls back to the
     * full-text table where SQLite names those differently. Null if any of it fails: a
     * measurement that cannot be taken is absent, never guessed.
     */
    override fun check(sourceId: String): CatalogCheck? = runCatching {
        val startedAt = System.nanoTime()
        val db = database.openHelper.writableDatabase
        val rows = LinkedHashMap<MediaKind, Int>()
        db.query("SELECT kind, COUNT(*) FROM media WHERE source_id = ? GROUP BY kind", arrayOf<Any?>(sourceId)).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = MediaKind.entries.firstOrNull { it.name == cursor.getString(0) } ?: continue
                rows[kind] = cursor.getInt(1)
            }
        }
        val searchRows = db.firstIntOf(
            "SELECT COUNT(*) FROM media_fts_docsize",
            "SELECT COUNT(*) FROM media_fts",
        )
        val duplicated = db.firstIntOf(
            "SELECT COUNT(*) FROM (SELECT c0media_id FROM media_fts_content GROUP BY c0media_id HAVING COUNT(*) > 1)",
            "SELECT COUNT(*) FROM (SELECT media_id FROM media_fts GROUP BY media_id HAVING COUNT(*) > 1)",
        )
        val match = db.sampleWord(sourceId)?.let(FtsQuery::build)
        var results: Int? = null
        var distinct: Int? = null
        if (match != null) {
            // The app's own search, with a word from one of this source's titles turned
            // into a MATCH the way a typed one is, and the same join -- so a result that
            // comes back twice here comes back twice on screen.
            db.query(
                """
                SELECT COUNT(*), COUNT(DISTINCT m.id) FROM media_fts f
                INNER JOIN media m ON m.id = f.media_id
                WHERE f.search_text MATCH ? AND m.source_id = ?
                """,
                arrayOf<Any?>(match, sourceId),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    results = cursor.getInt(0)
                    distinct = cursor.getInt(1)
                }
            }
        }
        CatalogCheck(
            rows = rows,
            allRows = db.firstIntOf("SELECT COUNT(*) FROM media"),
            searchRows = searchRows,
            duplicatedInSearch = duplicated,
            sampleResults = results,
            sampleRows = distinct,
            tookMs = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }.getOrNull()

    /** The first of [sql] that SQLite accepts, as one integer. */
    private fun SupportSQLiteDatabase.firstIntOf(vararg sql: String): Int {
        var failure: Throwable? = null
        for (statement in sql) {
            try {
                return query(statement).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IllegalArgumentException("no statement")
    }

    /**
     * A word to search for: the first of [SAMPLE_WORD_MIN] letters or digits in a row in
     * one of this source's titles, cut where [FtsQuery] cuts a typed one.
     */
    private fun SupportSQLiteDatabase.sampleWord(sourceId: String): String? {
        val text = query("SELECT search_text FROM media WHERE source_id = ? LIMIT 1", arrayOf<Any?>(sourceId))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: return null
        var start = -1
        for (i in 0..text.length) {
            val inWord = i < text.length && text[i].isLetterOrDigit()
            if (inWord && start < 0) start = i
            if (!inWord && start >= 0) {
                if (i - start >= SAMPLE_WORD_MIN) return text.substring(start, i)
                start = -1
            }
        }
        return null
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
                // already visible.
                timed(DatabaseStep.PRUNE) {
                    execSQL("DELETE FROM media WHERE source_id = ? AND generation != ?", arrayOf(source, generation))
                    execSQL("DELETE FROM media_group WHERE source_id = ? AND generation != ?", arrayOf(source, generation))
                }
            }
            timed(DatabaseStep.COUNTS) {
                execSQL(
                    """
                    UPDATE media_group SET item_count =
                        (SELECT COUNT(*) FROM media WHERE media.group_id = media_group.id)
                    WHERE source_id = ?
                    """,
                    arrayOf(source),
                )
            }
            // An append has already indexed its own rows; rebuilding here would be
            // seconds of work to add a season.
            if (mode == ImportMode.REPLACE) timed(DatabaseStep.SEARCH) { rebuildSearchIndex() }
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

    /**
     * One step of the end of an import, timed for the on-device panel.
     *
     * The three steps after the last commit were the part of a load nothing measured: the
     * commit figure stops before them and the full load includes them. Timed one by one,
     * because they scale differently -- the prune with what was replaced, the counts with
     * the groups, the index with every row -- and a single number would not say which.
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
        runCatching { ftsStatement?.close() }
        itemStatement = null
        groupStatement = null
        ftsStatement = null
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
        /** The shortest word [check] searches for: shorter ones match half the catalogue. */
        const val SAMPLE_WORD_MIN = 3

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
        /**
         * `docid` is FTS4's name for the row's rowid, bound to the media row's
         * rowid so an append can replace an item's index entry instead of adding a
         * duplicate one.
         */
        const val INSERT_FTS = """
            INSERT OR REPLACE INTO media_fts (docid, media_id, search_text) VALUES (?, ?, ?)
        """

        const val INSERT_GROUP = """
            INSERT OR REPLACE INTO media_group (
                id, source_id, name, kind, provider_order, item_count, generation
            ) VALUES (?, ?, ?, ?, ?, 0, ?)
        """
    }
}
