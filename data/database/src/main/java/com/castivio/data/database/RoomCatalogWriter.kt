package com.castivio.data.database

import android.os.Debug
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.tracing.trace
import com.castivio.core.common.UpdatePacer
import com.castivio.core.platform.CastivioTrace
import com.castivio.core.platform.DatabaseStep
import com.castivio.core.platform.DevDiagnostics
import com.castivio.core.platform.PerformanceLog
import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import com.castivio.domain.MovieDetails
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
 *  - **A larger page cache during import only.** The ids are hashes, so the primary key's index -- and the
 *    category's, when the provider does not keep a category's rows together -- is written in no order, and
 *    SQLite's default cache (about 2 MB) is smaller than the trees those inserts touch. 8 MiB for the load,
 *    the default after it, a fixed size whatever the library: see [ImportPageCache].
 *  - **The search index once, at the end.** Not row by row beside the rows, and not
 *    rebuilt whole either: the rows this import wrote, in two statements, after the
 *    last of them. See `indexWrittenRows`.
 *  - **The sort-title index once, at the end -- of a subscription's first load only.**
 *    Kept up row by row it was the costliest index of the six, and nothing that is read
 *    while a first load runs uses it. It is taken out before the first row, built over
 *    the finished rows, and put back by `finish`, by `abort`, and by the next load's first
 *    step if the process died in between. Never while rows of the subscription are already
 *    here: a refresh is read by name while it runs. See [SortTitleIndex].
 *
 *  - **Commits are not slowed by the screens.** Every batch is committed as soon as it is
 *    written; what is paced is only the *telling* -- the observers are notified about once
 *    a second during a load (see [UpdatePacer]), because the rail, the cards and the list
 *    re-read on every notification and nobody reads a count rising seven times a second.
 *    The first rows and the first counted category with rows are told at once, and the end of the
 *    load always is.
 *
 * Blocking, like the interface it implements. Call it on an IO dispatcher.
 *
 * @param notifyEveryMs the least time between two notifications that are not urgent.
 * @param monotonicMs a clock that never moves backwards, for the pacing.
 * @param tell notifies the observers that the tables changed. Raw statements bypass Room's
 *   write path, so nothing else would.
 * @param defersSortTitleIndex whether a subscription's first load leaves the sort-title index
 *   to be built after its rows -- see [SortTitleIndex]. On in the app. Off only where a caller
 *   times or tests this writer with the index in place, as the removed index-cost lab did (commit
 *   a98a7219) and a test does. Before [clock], which stays last so a trailing lambda is its.
 */
class RoomCatalogWriter(
    private val database: CastivioDatabase,
    private val notifyEveryMs: Long = NOTIFY_INTERVAL_MS,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val tell: () -> Unit = { database.invalidationTracker.refreshVersionsAsync() },
    private val defersSortTitleIndex: Boolean = true,
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
    private var wroteGroups = false

    /** This load is its subscription's first: the sort-title index may be taken out. */
    private var deferSortTitle = false

    /** The drop has been issued and the index not yet put back: what `finish` and `abort` undo. */
    private var sortTitleDeferred = false
    private var itemStatement: SupportSQLiteStatement? = null
    private var groupStatement: SupportSQLiteStatement? = null
    private var countStatement: SupportSQLiteStatement? = null
    private var detailStatement: SupportSQLiteStatement? = null

    // ------------------------------------------------------ telling the observers
    private var notifyInterval = notifyEveryMs
    private var pacer = UpdatePacer(notifyInterval)
    @Volatile private var timer: ScheduledFuture<*>? = null
    private var rowsSinceCommit = false
    private var countedSinceCommit = false
    private var toldRows = false
    private var toldCounts = false

    // ------------------------------------------------------ measuring (development builds only)
    private var measuring = false
    private var figures = Figures()

    /** A read of the index the rows are looked up by: one row's worth, whatever the size of the section. */
    override fun holdsRows(sourceId: String, kind: MediaKind): Boolean =
        database.openHelper.writableDatabase.longOf(
            "SELECT EXISTS (SELECT 1 FROM media WHERE source_id = ? AND kind = ?)",
            sourceId,
            kind.name,
        ) == 1L

    /**
     * A refresh tells the screens every [REFRESH_NOTIFY_INTERVAL_MS], and at its end, instead of every
     * [notifyEveryMs]: the rows it writes replace rows the screens already show, and each telling makes every screen read
     * the tables again -- seconds of processor time on a phone with the films in it, which was half of the application's
     * during a refresh and went to the readers instead of the writer. The first rows and the first counted category are
     * still told at once, as for any load.
     */
    override fun tellRarely() {
        notifyInterval = REFRESH_NOTIFY_INTERVAL_MS
        pacer = UpdatePacer(notifyInterval)
    }

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
        wroteGroups = false
        pacer.reset()
        rowsSinceCommit = false
        countedSinceCommit = false
        toldRows = false
        toldCounts = false
        measuring = DevDiagnostics.enabled
        figures = Figures()
        db.applyImportPragmas()
        deferSortTitle = false
        sortTitleDeferred = false
        if (defersSortTitleIndex) {
            // First, whatever this load is: an index a load left out and never put back -- the process
            // was killed, the power went -- is put back before another write is made over its absence.
            // A read when there is nothing to mend. Not fatal when it cannot be: a list sorted by name
            // is slower without it, and the next load tries again.
            runCatching { SortTitleIndex.restore(db) }
            // Only a subscription's first load. With rows of it already here, a refresh or a second
            // section, a list sorted by name may be on screen while this one runs.
            deferSortTitle = db.longOf("SELECT EXISTS (SELECT 1 FROM media WHERE source_id = ?)", sourceId) == 0L
        }
        // Which way this import writes decides which steps it pays for -- a replacing write
        // prunes the old generation of what it replaces at the end, an appending one does
        // not -- so a reading of its steps means nothing without it.
        PerformanceLog.saveMode(mode.name)
        itemStatement = db.compileStatement(INSERT_ITEM)
        groupStatement = db.compileStatement(INSERT_GROUP)
        countStatement = db.compileStatement(COUNT_GROUP)
        detailStatement = db.compileStatement(INSERT_DETAIL)
        // Last, so that nothing above can end `begin` with the cache raised and no `finish` or `abort` to follow it.
        ImportPageCache.raise(db)
    }

    override fun writeGroups(groups: List<MediaGroup>) {
        val source = requireStarted()
        val statement = groupStatement ?: error("writeGroups before begin")
        if (groups.isEmpty()) return
        wroteGroups = true
        openTransaction()
        for (group in groups) {
            statement.clearBindings()
            statement.bindString(1, group.id)
            statement.bindString(2, source)
            statement.bindString(3, group.name)
            statement.bindString(4, group.kind.name)
            statement.bindLong(5, (groupOrder++).toLong())
            // Bound twice: the statement keeps the count a category already has, so a
            // section loaded again keeps its rail while it arrives -- see `INSERT_GROUP`.
            statement.bindString(6, group.id)
            statement.bindLong(7, generation)
            statement.executeInsert()
        }
    }

    /**
     * Each category's count, one indexed range at a time, in the open transaction: the
     * rail sees them with the next commit. Hundreds of categories at most over a whole
     * load, each counted as it completes -- the same count `finish` makes of all of them,
     * spread over the load instead of waiting for its end.
     */
    override fun countGroups(groupIds: Collection<String>) {
        requireStarted()
        val statement = countStatement ?: error("countGroups before begin")
        if (groupIds.isEmpty()) return
        openTransaction()
        timed(DatabaseStep.COUNTS) {
            for (id in groupIds) {
                statement.clearBindings()
                statement.bindString(1, id)
                statement.executeUpdateDelete()
            }
        }
        // Until the first category with rows is counted: an empty one lists nothing, so it
        // must not spend the one notice that is told at once. A few lookups per load at most.
        if (!toldCounts && !countedSinceCommit) countedSinceCommit = groupIds.any(::hasRows)
    }

    /**
     * One batch of rows into `media`, with its six indices -- and nothing else: the search
     * index takes them at the end of the import. Timed per batch, never per row.
     */
    override fun writeItems(items: List<CatalogItem>) {
        val source = requireStarted()
        val statement = itemStatement ?: error("writeItems before begin")
        if (items.isEmpty()) return
        openTransaction()
        leaveSortTitleForAfter()
        wroteRows = true
        rowsSinceCommit = true
        // Development builds only: the thread's own CPU time over the same stretch [timed] covers, to say how much
        // of the rows' wall time the processor was working on them. Read twice per batch, never per row.
        val cpuAt = if (measuring) Debug.threadCpuTimeNanos() else 0L
        val wallAt = if (measuring) System.nanoTime() else 0L
        timed(DatabaseStep.ROWS) {
            for (item in items) insert(source, statement, item)
        }
        if (measuring) {
            figures.rowsCpuNanos += Debug.threadCpuTimeNanos() - cpuAt
            figures.rowsWallNanos += System.nanoTime() - wallAt
        }
    }

    /**
     * Takes the sort-title index out before the load's first row, once -- see [SortTitleIndex].
     *
     * Not in [begin]: a load that fails at its categories, the commonest way for one to fail, never
     * reaches a row and never touches the schema. Inside the transaction the first rows are written in,
     * so the drop commits with them -- and a load rolled back before that commit takes the drop with it.
     */
    private fun leaveSortTitleForAfter() {
        if (!deferSortTitle || sortTitleDeferred) return
        // Marked first: a drop that was started is a drop to undo, whether or not it finished.
        sortTitleDeferred = true
        database.openHelper.writableDatabase.execSQL(SortTitleIndex.DROP)
    }

    /**
     * Builds the sort-title index over the rows that are there, when this load took it out, and
     * checks it is as the entity declares it.
     *
     * Outside the transaction that counts and indexes the search: an index that could not be built must
     * not undo the counts, which are right, and is mended by the next load's first step. Before the
     * observers are told, so the load that is announced is a complete one. Said in the run's notes,
     * with its time, because it is a cost the load has moved to its end and a reading of the load's
     * steps would otherwise not find it.
     */
    private fun putSortTitleIndexBack(db: SupportSQLiteDatabase) {
        if (!sortTitleDeferred) return
        val startedAt = System.nanoTime()
        val intact = runCatching { SortTitleIndex.restore(db) }.getOrDefault(false)
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        if (intact) sortTitleDeferred = false
        PerformanceLog.note(
            NOTE_SORT_INDEX,
            if (intact) "built after the rows in $tookMs ms" else "not rebuilt ($tookMs ms); the next load puts it back",
        )
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
        statement.bindNullable(21, item.catchUpHours)
        statement.executeInsert()
        val details = item.details
        val providerRef = item.providerRef
        if (details != null && providerRef != null) insertDetails(source, providerRef, details)
    }

    /**
     * A film's details, written beside its row in the same transaction: what a portal sends in its list,
     * so the film's page reads it from here and asks nothing of the network.
     */
    private fun insertDetails(source: String, providerRef: String, details: MovieDetails) {
        val statement = detailStatement ?: return
        statement.clearBindings()
        statement.bindString(1, source)
        statement.bindString(2, providerRef)
        details.rating?.let { statement.bindDouble(3, it) } ?: statement.bindNull(3)
        statement.bindNullable(4, details.plot)
        statement.bindNullable(5, details.director)
        statement.bindNullable(6, details.cast.joinToString(DETAIL_LIST_SEPARATOR).ifEmpty { null })
        statement.bindNullable(7, details.genres.joinToString(DETAIL_LIST_SEPARATOR).ifEmpty { null })
        statement.bindNullable(8, details.releaseYear)
        statement.bindNullable(9, details.durationSeconds)
        details.addedEpochSeconds?.let { statement.bindLong(10, it) } ?: statement.bindNull(10)
        statement.bindLong(11, now)
        statement.executeInsert()
    }

    /**
     * Ends one batch's transaction, inside a trace section. The next is opened by the next
     * write -- see [openTransaction].
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

    /**
     * Tells the observers now. Asked for the one commit a viewer is waiting on -- the category they opened while its
     * section was still arriving -- and for nothing else: every other commit is told at the pace [UpdatePacer] keeps,
     * which is what stops a load from making the screens re-read seven times a second. The extra telling is one
     * notification, made at the viewer's request.
     */
    override fun tellNow() {
        runCatching { told() }
    }

    /** Tells the readers, and counts it: how often they were told is a figure of the load. */
    private fun told() {
        figures.told++
        tell()
    }

    private fun commitNow() {
        if (!inTransaction) return
        val db = database.openHelper.writableDatabase
        val endingAt = if (measuring) System.nanoTime() else 0L
        db.setTransactionSuccessful()
        db.endTransaction()
        if (measuring) figures.committed(System.nanoTime() - endingAt)
        inTransaction = false
        // Raw statements bypass Room's write path, so nothing would tell the
        // observers that the tables changed. Without this the first import
        // finishes and the screen stays empty until something else writes.
        announceCommit()
    }

    /**
     * Tells the observers about this commit -- now if somebody is waiting on it, otherwise
     * when the interval since the last telling is up. The commit itself is already done and
     * is never delayed by this.
     *
     * Somebody is waiting on the first rows of a load and on its first counted category:
     * the first is the section's first content, the second is what Live TV opens with.
     */
    private fun announceCommit() {
        val firstRows = rowsSinceCommit && !toldRows
        val firstCounts = countedSinceCommit && !toldCounts
        toldRows = toldRows || rowsSinceCommit
        toldCounts = toldCounts || countedSinceCommit
        rowsSinceCommit = false
        countedSinceCommit = false

        val now = monotonicMs()
        val step = pacer.changed(now)
        // The timer is scheduled whatever else is told, or the pacer would wait for one that
        // does not exist.
        if (step is UpdatePacer.Step.Later) schedule(step.afterMs)
        val urgent = (firstRows || firstCounts) && pacer.urgent(now) == UpdatePacer.Step.Now
        if (step == UpdatePacer.Step.Now || urgent) told()
    }

    private fun schedule(afterMs: Long) {
        timer = TIMER.schedule(::timerFired, afterMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
    }

    /** The timer thread: the interval is up, and something changed since the last telling. */
    private fun timerFired() {
        // A database closed under a late timer is not an error worth a crash.
        runCatching {
            when (val step = pacer.due(monotonicMs())) {
                UpdatePacer.Step.Now -> told()
                is UpdatePacer.Step.Later -> schedule(step.afterMs)
                UpdatePacer.Step.Wait -> Unit
            }
        }
    }

    /** The end of a load: told whatever the interval, and no timer left behind. */
    private fun announceEnd() {
        timer?.cancel(false)
        timer = null
        pacer.reset()
        told()
    }

    override fun finish(summary: ImportSummary) {
        val source = sourceId ?: return
        val db = database.openHelper.writableDatabase
        if (measuring) figures.walAtFinish = probed { db.sizeOf(WAL_SUFFIX) }
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
            // Only an import that wrote categories has counts to settle: a show's episodes, written into the category
            // its row is in, change no count -- a category counts shows -- and recounting every category of the
            // subscription for them was a cost of every show opened.
            if (wroteGroups) timed(DatabaseStep.COUNTS) { execSQL(COUNT_GROUPS, arrayOf(source)) }
            timed(DatabaseStep.SEARCH) { indexWrittenRows() }
        }

        putSortTitleIndexBack(db)
        db.restoreNormalPragmas()
        announceEnd()
        if (measuring) noteFigures(db)
        release()
    }

    override fun abort(cause: Throwable?) {
        val db = runCatching { database.openHelper.writableDatabase }.getOrNull()
        if (db != null) {
            // Rolls back the batch in flight; everything already committed stays,
            // because a partial catalogue beats an empty one -- and is found by search
            // like a whole one, and listed like one: every category is counted, so the
            // rail lists each one that has rows, the one that was arriving included.
            // Quietly: an import that failed on the database itself may fail here again,
            // and that is no reason to lose the rows.
            db.endTransactionQuietly()
            val source = sourceId
            if (source != null && wroteRows) {
                runCatching {
                    db.transaction {
                        if (wroteGroups) timed(DatabaseStep.COUNTS) { execSQL(COUNT_GROUPS, arrayOf(source)) }
                        timed(DatabaseStep.SEARCH) { indexWrittenRows() }
                    }
                }
            }
            // Whether or not a row was written: a load that took the index out and failed before its
            // first commit has had the drop rolled back, and this finds it in place; one that failed
            // after has rows that are listed and searched, and are sorted by name like any others.
            putSortTitleIndexBack(db)
            db.restoreNormalPragmas()
            announceEnd()
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
        val deletingAt = if (measuring) System.nanoTime() else 0L
        execSQL("DELETE FROM media_fts WHERE docid NOT IN (SELECT rowid FROM media)")
        if (measuring) {
            figures.ftsDeleteNanos = System.nanoTime() - deletingAt
            figures.ftsDeleteRows = probed { changes() }
        }
        val insertingAt = if (measuring) System.nanoTime() else 0L
        execSQL(INDEX_WRITTEN, arrayOf(lastRowIdBefore))
        if (measuring) {
            figures.ftsInsertNanos = System.nanoTime() - insertingAt
            figures.ftsInsertRows = probed { changes() }
        }
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

    /**
     * What the end of a load cost beyond the three steps [timed] reports, counted for the run's notes.
     *
     * Development builds only: [measuring] is `DevDiagnostics.enabled`, and with it off nothing here is written
     * or read. Measurement only -- no statement, order or transaction depends on a figure. Written by the
     * writer's own thread.
     */
    private class Figures {
        /** The rows' wall time and the writer thread's CPU time over the same stretches: see [writeItems]. */
        var rowsWallNanos = 0L
        var rowsCpuNanos = 0L

        /** How many times the readers were told, the first rows and the end included. */
        var told = 0

        var opens = 0
        var openNanos = 0L
        var openMaxNanos = 0L
        var commits = 0
        var commitNanos = 0L
        var commitMaxNanos = 0L
        var lastCommitNanos = 0L
        var closingBeginNanos = 0L
        var closingEndNanos = 0L
        var ftsDeleteNanos = 0L
        var ftsDeleteRows = -1L
        var ftsInsertNanos = 0L
        var ftsInsertRows = -1L
        var syncPragmaNanos = 0L
        var journalPragmaNanos = 0L
        var walAtFinish = -1L

        /** The reads added to take these figures, and what they cost: see [noteFigures]. */
        var probes = 0
        var probeNanos = 0L

        fun opened(nanos: Long) {
            opens++
            openNanos += nanos
            if (nanos > openMaxNanos) openMaxNanos = nanos
        }

        fun committed(nanos: Long) {
            commits++
            commitNanos += nanos
            lastCommitNanos = nanos
            if (nanos > commitMaxNanos) commitMaxNanos = nanos
        }
    }

    /**
     * Says the end of a load in the run's notes, once, last in [finish] while the run is still going: where the
     * writer waited for a transaction and how its commits ended (the automatic checkpoint is inside them), what
     * the search index's two statements did, the pragmas, the files' sizes -- and what the reads added for this
     * cost. The report prints its notes to logcat when the run settles; nothing is drawn on a screen.
     */
    private fun noteFigures(db: SupportSQLiteDatabase) {
        val f = figures
        val walAtEnd = probed { db.sizeOf(WAL_SUFFIX) }
        val dbBytes = probed { db.sizeOf("") }
        PerformanceLog.note(
            NOTE_TOLD,
            "${f.told} times, at most one every ${notifyInterval / 1_000} s",
        )
        PerformanceLog.note(
            NOTE_TX_BEGIN,
            "${f.opens} opens, total ${tenthsOfMs(f.openNanos)}, max ${tenthsOfMs(f.openMaxNanos)}" +
                " · closing transaction ${tenthsOfMs(f.closingBeginNanos)}",
        )
        PerformanceLog.note(
            NOTE_TX_END,
            "${f.commits} commits, total ${tenthsOfMs(f.commitNanos)}, max ${tenthsOfMs(f.commitMaxNanos)}" +
                " · last ${tenthsOfMs(f.lastCommitNanos)} · closing transaction ${tenthsOfMs(f.closingEndNanos)}",
        )
        PerformanceLog.note(
            NOTE_FTS,
            "delete ${tenthsOfMs(f.ftsDeleteNanos)} (${f.ftsDeleteRows} rows)" +
                " · insert ${tenthsOfMs(f.ftsInsertNanos)} (${f.ftsInsertRows} rows)",
        )
        PerformanceLog.note(
            NOTE_PRAGMAS,
            "synchronous ${tenthsOfMs(f.syncPragmaNanos)} · journal_mode ${tenthsOfMs(f.journalPragmaNanos)}",
        )
        PerformanceLog.note(
            NOTE_DB_CONTEXT,
            "${mode.name} · sort index ${if (deferSortTitle) "deferred" else "kept"} · last rowid before $lastRowIdBefore" +
                " · db ${megabytesText(dbBytes)} · wal ${megabytesText(f.walAtFinish)} -> ${megabytesText(walAtEnd)}",
        )
        PerformanceLog.note(
            NOTE_PROBES,
            "${tenthsOfMs(f.probeNanos)} in ${f.probes} added reads (two changes() and the file sizes)",
        )
        // The one the strip draws: how much of the rows' wall time the writer's thread was on the processor.
        PerformanceLog.note(
            NOTE_WRITER_CPU,
            "rows cpu ${hundredthsOfSeconds(f.rowsCpuNanos)} of ${hundredthsOfSeconds(f.rowsWallNanos)}",
        )
    }

    /** [block], with its time counted as the cost of measuring: the reads this instrumentation adds. */
    private inline fun <T> probed(block: () -> T): T {
        val startedAt = System.nanoTime()
        try {
            return block()
        } finally {
            figures.probes++
            figures.probeNanos += System.nanoTime() - startedAt
        }
    }

    /** Rows the statement just run on this connection changed -- read inside the transaction it ran in. */
    private fun SupportSQLiteDatabase.changes(): Long = longOf("SELECT changes()")

    /** The size of the database file plus [suffix], or -1 when there is no such file. */
    private fun SupportSQLiteDatabase.sizeOf(suffix: String): Long {
        val file = File((path ?: return -1L) + suffix)
        return if (file.exists()) file.length() else -1L
    }

    private fun release() {
        timer?.cancel(false)
        timer = null
        // The next import is a first load until it says it is not.
        if (notifyInterval != notifyEveryMs) {
            notifyInterval = notifyEveryMs
            pacer = UpdatePacer(notifyInterval)
        }
        runCatching { itemStatement?.close() }
        runCatching { groupStatement?.close() }
        runCatching { countStatement?.close() }
        runCatching { detailStatement?.close() }
        itemStatement = null
        groupStatement = null
        countStatement = null
        detailStatement = null
        sourceId = null
        // Whatever is still owed is owed to the next load's first step, not to this writer.
        deferSortTitle = false
        sortTitleDeferred = false
    }

    private fun hasRows(groupId: String): Boolean =
        database.openHelper.writableDatabase
            .query("SELECT item_count FROM media_group WHERE id = ?", arrayOf<Any>(groupId))
            .use { it.moveToFirst() && it.getLong(0) > 0 }

    /**
     * Opens the transaction a write goes into, when none is open.
     *
     * Opened by the write rather than at the start and again after every commit: an open
     * transaction holds SQLite's one write connection, and every reader that needs it --
     * Room's notices, a list's first page -- waited for the next commit while the writer
     * sat waiting for the network with an empty transaction open.
     */
    private fun openTransaction() {
        if (!inTransaction) {
            val openingAt = if (measuring) System.nanoTime() else 0L
            database.openHelper.writableDatabase.begin()
            if (measuring) figures.opened(System.nanoTime() - openingAt)
        }
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
        val beginningAt = if (measuring) System.nanoTime() else 0L
        beginTransaction()
        if (measuring) figures.closingBeginNanos = System.nanoTime() - beginningAt
        try {
            body()
            setTransactionSuccessful()
        } finally {
            val endingAt = if (measuring) System.nanoTime() else 0L
            endTransaction()
            if (measuring) figures.closingEndNanos = System.nanoTime() - endingAt
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
     * Restores durability afterwards, and the page cache the load was given: see [ImportPageCache].
     *
     * The WAL statement is left in even though the import no longer leaves the journal
     * anywhere else: it is a repair for a connection some other writer moved, and it
     * costs one no-op statement per import.
     */
    private fun SupportSQLiteDatabase.restoreNormalPragmas() {
        val syncAt = if (measuring) System.nanoTime() else 0L
        runCatching { execSQL("PRAGMA synchronous = NORMAL") }
        val journalAt = if (measuring) System.nanoTime() else 0L
        runCatching { execSQL("PRAGMA journal_mode = WAL") }
        if (measuring) {
            figures.syncPragmaNanos = journalAt - syncAt
            figures.journalPragmaNanos = System.nanoTime() - journalAt
        }
        ImportPageCache.restore(this)
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
        /** The run's note on the sort-title index: whether a first load deferred it, and what building it cost. */
        const val NOTE_SORT_INDEX = "sort index"

        /** The run's notes on the end of a load, development builds only: see [noteFigures]. */
        const val NOTE_TOLD = "told"
        const val NOTE_TX_BEGIN = "tx begin"
        const val NOTE_TX_END = "tx end"
        const val NOTE_FTS = "fts"
        const val NOTE_PRAGMAS = "pragmas"
        const val NOTE_DB_CONTEXT = "db context"
        const val NOTE_PROBES = "probes"

        /** The writer's CPU against its wall time over the rows; read by the load strip, which names it the same. */
        const val NOTE_WRITER_CPU = "writer cpu"

        /** Nanoseconds as seconds to a hundredth, with a point whatever the device's language. */
        fun hundredthsOfSeconds(nanos: Long): String {
            val hundredths = nanos / 10_000_000
            return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
        }

        /** Appended to the database file's path: SQLite's write-ahead log. */
        const val WAL_SUFFIX = "-wal"

        /** Nanoseconds as milliseconds to a tenth, with a point whatever the device's language. */
        fun tenthsOfMs(nanos: Long): String {
            val tenths = nanos / 100_000
            return "${tenths / 10}.${tenths % 10} ms"
        }

        /** Bytes as megabytes to a tenth; `none` for a file that is not there. */
        fun megabytesText(bytes: Long): String {
            if (bytes < 0) return "none"
            val tenths = bytes * 10 / 1_048_576
            return "${tenths / 10}.${tenths % 10} MB"
        }

        /** One thread for every writer's timer: a timer only calls [tell]. */
        val TIMER = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "catalog-notify").apply { isDaemon = true }
        }

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
                episode_number, generation, added_at, catch_up_hours
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                COALESCE((SELECT added_at FROM media WHERE id = ?), ?), ?
            )
        """

        /** A film's details from its provider's list. `REPLACE`: a refresh writes the portal's latest. */
        const val INSERT_DETAIL = """
            INSERT OR REPLACE INTO vod_detail (
                source_id, provider_ref, rating, plot, director, cast_names, genres,
                release_year, duration_seconds, added_epoch, fetched_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        /**
         * `item_count` is filled in as each category completes -- see `countGroups` --
         * and for all of them at the end of the import.
         *
         * Until then a category keeps the count it already had: zero for a new one, the
         * last load's for one written again. The rail lists the categories that have a
         * count, so a section loaded a second time keeps its rail while it arrives rather
         * than emptying and refilling, and a new one appears when it is complete. It has
         * to be listed either way: a Kotlin default does not become a SQL default, so
         * omitting the NOT NULL column would fail every insert.
         */
        const val INSERT_GROUP = """
            INSERT OR REPLACE INTO media_group (
                id, source_id, name, kind, provider_order, item_count, generation
            ) VALUES (
                ?, ?, ?, ?, ?,
                COALESCE((SELECT item_count FROM media_group WHERE id = ?), 0),
                ?
            )
        """

        /**
         * What a category holds, counted: its rows, and for a category of shows the shows -- one for each series id,
         * whatever its episodes number. A show's episodes are written into its category when it is opened, and a count
         * of rows had a category read 394 shows before the first was opened and 564 after: the show's 170 episodes.
         *
         * A row with no series id is a show of its own, so it is counted as one. A category's rows are a range of the
         * index that leads with the subscription and the kind, so each branch is a range and not a scan; the shows are
         * counted by a distinct over that range, which is the dearer and is paid for only by a category of shows.
         */
        const val COUNT_OF_GROUP = """(
                CASE media_group.kind
                    WHEN 'SERIES' THEN (
                        SELECT COUNT(DISTINCT media.series_id) + COUNT(CASE WHEN media.series_id IS NULL THEN 1 END) FROM media
                        WHERE media.source_id = media_group.source_id
                          AND media.kind = media_group.kind
                          AND media.group_id = media_group.id
                    )
                    ELSE (
                        SELECT COUNT(*) FROM media
                        WHERE media.source_id = media_group.source_id
                          AND media.kind = media_group.kind
                          AND media.group_id = media_group.id
                    )
                END
            )"""

        /**
         * One category, counted: [COUNT_GROUPS] for a single id, through the same index.
         * Its subscription and kind come from the category's own row, which is what keeps
         * the count a range of the index rather than a scan.
         */
        const val COUNT_GROUP = """
            UPDATE media_group SET item_count = $COUNT_OF_GROUP
            WHERE id = ?
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
            UPDATE media_group SET item_count = $COUNT_OF_GROUP
            WHERE source_id = ?
        """

        /** `docid` is FTS4's name for an entry's rowid: here, always its row's. */
        const val INDEX_WRITTEN = """
            INSERT INTO media_fts (docid, media_id, search_text)
            SELECT rowid, id, search_text FROM media WHERE rowid > ?
        """
    }
}

/** About one notification a second during a load: see [RoomCatalogWriter]. */
const val NOTIFY_INTERVAL_MS = 1_000L

/** About one in ten seconds during a refresh, which has rows on the screen already: see [RoomCatalogWriter.tellRarely]. */
const val REFRESH_NOTIFY_INTERVAL_MS = 10_000L
