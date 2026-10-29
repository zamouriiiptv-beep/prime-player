package com.castivio.tv.debug.lab

import android.content.Context
import android.database.Cursor
import android.os.Debug
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import com.castivio.core.platform.DatabaseStep
import com.castivio.core.platform.PerformanceLog
import com.castivio.data.database.CastivioDatabase
import com.castivio.data.database.RoomCatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.ImportSummary
import com.castivio.domain.MediaKind
import java.io.File

/**
 * Thrown between batches when the run must not go on: Stop was pressed, or a section began loading in the app.
 * The run in progress is discarded and its scratch database dropped on the way out.
 */
internal class LabCancelled : RuntimeException("interrupted")

/**
 * Writes one synthetic catalogue to one scratch database with the app's own [RoomCatalogWriter], and times it.
 *
 * ## Isolation
 *
 * Each run makes its own database -- a file named `dblab-...`, never [CastivioDatabase.NAME] -- from the same
 * Room class as the app's, so its tables, its six indices and its search table are exactly the app's, and
 * deletes it when the run ends, however it ends. Nothing here opens, reads or writes the app's database, its
 * subscriptions or its settings, or uses the network. The writer is the only production code that runs, and it
 * is handed the scratch database. The one thing shared with the app is `PerformanceLog`'s step counters, which
 * the writer feeds and the lab reads as differences; see [LabGuard] for how a load in the app is kept out.
 *
 * ## What is timed
 *
 * One clock brackets each phase of the writer's own sequence -- `begin`, `writeGroups`, the batches of
 * `writeItems`, each `commit`, the block that builds the indices the case left out, and `finish` -- and another
 * pair brackets the whole. The lab's own work inside the window (making each batch, reading page counts and the
 * native heap) is timed apart and taken out of the total. Checking the result happens after the total is taken.
 *
 * The rhythm is the importer's: groups first, batches of 1,000 rows, a commit after the first batch and then
 * whenever 2,000 rows are waiting, a last commit, then the end-of-import work. It leaves out two things the
 * importer does that depend on a clock or a count rather than on the rows -- the commit a half-second timer can
 * add, and the per-category counts made while a load runs -- so every case writes in the same rhythm.
 */
internal object LabRunner {

    const val PREFIX = "dblab-"

    private const val BATCH = 1_000
    private const val COMMIT_ROWS = 2_000
    private const val NANOS_PER_MS = 1_000_000.0

    /** Above this share of the total, time the phases do not account for is reported as a timing warning. */
    private const val OTHER_WARNING_PERCENT = 5.0

    /** Runs [case] on [data] once. Round 0 is the warm-up, which the caller keeps apart. */
    fun run(context: Context, case: LabCase, data: LabDataset, round: Int, interrupted: () -> Boolean): CaseResult {
        val name = "$PREFIX${case.code}-${data.spec.name}-r$round-${System.nanoTime()}.db"
        check(name != CastivioDatabase.NAME && name.startsWith(PREFIX)) { "the scratch database must not be the app's" }
        val file = context.getDatabasePath(name)
        context.deleteDatabase(name)
        var database: CastivioDatabase? = null
        val result: CaseResult
        try {
            database = Room.databaseBuilder(context, CastivioDatabase::class.java, name).build()
            result = measure(file, database, case, data, round, interrupted)
        } finally {
            runCatching { database?.close() }
            context.deleteDatabase(name)
        }
        if (file.exists() || File(file.path + "-wal").exists()) {
            result.warnings += "the scratch database could not be deleted; it is removed at the next start"
        }
        return result
    }

    /** Removes any scratch database a stopped or crashed run left behind, and says how many there were. */
    fun sweep(context: Context): Int {
        val names = context.databaseList()
            .filter { it.startsWith(PREFIX) }
            .map { it.removeSuffix("-wal").removeSuffix("-shm").removeSuffix("-journal") }
            .toSet()
        for (name in names) {
            if (name != CastivioDatabase.NAME) context.deleteDatabase(name)
        }
        return names.size
    }

    private fun measure(
        file: File,
        database: CastivioDatabase,
        case: LabCase,
        data: LabDataset,
        round: Int,
        interrupted: () -> Boolean,
    ): CaseResult {
        // Setup: not timed, and the same for every case. Room creates the schema when it first opens.
        val db = database.openHelper.writableDatabase
        val ddl = db.indexDdl()
        check(LabIndex.ALL.all { it in ddl }) { "the scratch database lacks an index Room should have created" }
        for (index in case.dropped) db.execSQL("DROP INDEX IF EXISTS `$index`")
        if (case.cacheKib > 0) db.execSQL("PRAGMA cache_size = -${case.cacheKib}")
        val pragmas = linkedMapOf(
            "sqlite_version" to db.text("SELECT sqlite_version()"),
            "journal_mode" to db.text("PRAGMA journal_mode"),
            "page_size" to db.text("PRAGMA page_size"),
            "cache_size" to db.text("PRAGMA cache_size"),
            "wal_autocheckpoint" to db.text("PRAGMA wal_autocheckpoint"),
            // The writer's `begin()` sets it to OFF for the import and `finish()` to NORMAL afterwards.
            "synchronous_during_import" to "OFF",
        )

        val timings = Timings()
        val builds = LinkedHashMap<String, Double>()
        val pages = LinkedHashMap<String, Long>()
        val sizes = LinkedHashMap<String, Long>()
        // The writer is told nothing about observers: the scratch database has none, and a refresh posted to
        // Room's executor would race the close at the end of the run.
        val writer = RoomCatalogWriter(database = database, tell = {}, clock = { LabDataset.NOW })

        Runtime.getRuntime().gc()
        val nativeBefore = Debug.getNativeHeapAllocatedSize()
        val pulseBefore = PerformanceLog.pulse()
        var nativeAfterRows = nativeBefore
        val start = System.nanoTime()
        var end: Long
        var finished = false
        try {
            var t = System.nanoTime()
            writer.begin(data.sourceId, ImportMode.REPLACE, setOf(MediaKind.LIVE))
            timings.begin += System.nanoTime() - t

            t = System.nanoTime()
            writer.writeGroups(data.groups)
            timings.groups += System.nanoTime() - t

            var position = 0
            var pending = 0
            var committedOnce = false
            while (position < data.rows) {
                if (interrupted()) throw LabCancelled()
                val last = minOf(position + BATCH, data.rows)
                t = System.nanoTime()
                val batch = data.items(position, last)
                timings.overhead += System.nanoTime() - t

                t = System.nanoTime()
                writer.writeItems(batch)
                timings.rows += System.nanoTime() - t
                pending += batch.size
                position = last
                if ((!committedOnce && pending > 0) || pending >= COMMIT_ROWS) {
                    t = System.nanoTime()
                    writer.commit()
                    timings.commit += System.nanoTime() - t
                    committedOnce = true
                    pending = 0
                }
            }
            if (pending > 0) {
                t = System.nanoTime()
                writer.commit()
                timings.commit += System.nanoTime() - t
            }
            t = System.nanoTime()
            nativeAfterRows = Debug.getNativeHeapAllocatedSize()
            timings.overhead += System.nanoTime() - t

            if (case.rebuilt.isNotEmpty()) {
                if (interrupted()) throw LabCancelled()
                // After the last commit and before `finish`, in one transaction, as a deferred build would be. The
                // page counts read around each build are the lab's own and are kept out of the build's time.
                val blockStart = System.nanoTime()
                var probes = 0L
                db.beginTransaction()
                try {
                    for (index in case.rebuilt) {
                        var p = System.nanoTime()
                        val pagesBefore = db.long("PRAGMA page_count")
                        probes += System.nanoTime() - p
                        val s = System.nanoTime()
                        db.execSQL(ddl.getValue(index))
                        builds[index] = (System.nanoTime() - s) / NANOS_PER_MS
                        p = System.nanoTime()
                        pages[index] = db.long("PRAGMA page_count") - pagesBefore
                        probes += System.nanoTime() - p
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                timings.build += System.nanoTime() - blockStart - probes
                timings.overhead += probes
            }

            if (interrupted()) throw LabCancelled()
            t = System.nanoTime()
            writer.finish(
                ImportSummary(
                    sourceId = data.sourceId,
                    items = data.rows,
                    groups = data.spec.groups,
                    skipped = 0,
                    byKind = mapOf(MediaKind.LIVE to data.rows),
                    durationMs = 0L,
                ),
            )
            timings.finish += System.nanoTime() - t
            end = System.nanoTime()
            finished = true
        } finally {
            if (!finished) runCatching { writer.abort(null) }
        }

        // The writer's own counters for the same run: the split of `finish`, and a cross-check of the lab's timers.
        // They are shared with the app, so a difference below zero means a load in the app reset them meanwhile.
        val pulseAfter = PerformanceLog.pulse()
        fun step(s: DatabaseStep): Double =
            ((pulseAfter.databaseSteps[s] ?: 0L) - (pulseBefore.databaseSteps[s] ?: 0L)).toDouble()
        val prune = step(DatabaseStep.PRUNE)
        val counts = step(DatabaseStep.COUNTS)
        val search = step(DatabaseStep.SEARCH)
        val writerRows = step(DatabaseStep.ROWS)
        val writerCommits = (pulseAfter.databaseMs - pulseBefore.databaseMs).toDouble()
        val finishMs = timings.finish / NANOS_PER_MS
        val finishSteps = linkedMapOf(
            "prune" to prune,
            "counts" to counts,
            "search index (FTS)" to search,
            "rest (last commit, pragmas, bookkeeping)" to maxOf(0.0, finishMs - prune - counts - search),
        )
        val crossChecks = linkedMapOf("writer ROWS step" to writerRows, "writer commits" to writerCommits)

        // ------------------------------------------------------------ verification: after the total is taken
        if (case.cacheKib > 0) pragmas["cache_size_after_run"] = db.text("PRAGMA cache_size")
        sizes["wal_bytes_after_finish"] = File(file.path + "-wal").let { if (it.exists()) it.length() else 0L }
        sizes["native_heap_before_bytes"] = nativeBefore
        sizes["native_heap_after_rows_bytes"] = nativeAfterRows
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        sizes["db_bytes_after_checkpoint"] = file.length()

        val checks = LinkedHashMap<String, Boolean>()
        checks["row count"] = db.long("SELECT COUNT(*) FROM media") == data.rows.toLong()
        checks["row content (digest of the lab's own columns)"] = digestOf(db) == data.expectedDigest
        val present = db.indexNames()
        checks["only the expected indices"] = present.filter { !it.startsWith("sqlite_autoindex") }.toSet() == case.expectedIndices()
        checks["primary-key index present"] = present.any { it.startsWith("sqlite_autoindex") }
        checks["search index row count"] = db.long("SELECT COUNT(*) FROM media_fts") == data.rows.toLong()
        checks["category counts"] = categoryCountsMatch(db, data)
        checks["integrity_check"] = db.text("PRAGMA integrity_check") == "ok"

        val total = timings.total(start, end) / NANOS_PER_MS
        val other = timings.other(start, end) / NANOS_PER_MS
        val rowsMs = timings.rows / NANOS_PER_MS
        val commitMs = timings.commit / NANOS_PER_MS
        val warnings = ArrayList<String>()
        if (total > 0 && 100.0 * other / total > OTHER_WARNING_PERCENT) {
            warnings += String.format(java.util.Locale.ROOT, "%.1f%% of the total fell between the phases", 100.0 * other / total)
        }
        if (writerRows >= 0 && Math.abs(rowsMs - writerRows) > maxOf(5.0, 0.05 * rowsMs)) {
            warnings += String.format(java.util.Locale.ROOT, "rows: lab %.0f ms against the writer's %.0f ms", rowsMs, writerRows)
        }
        if (case.cacheKib > 0 && pragmas["cache_size_after_run"] != "-${case.cacheKib}") {
            warnings += "the page cache did not hold its size (${pragmas["cache_size_after_run"]})"
        }

        val result = CaseResult(
            case = case,
            dataset = data.spec.name,
            round = round,
            rowCount = data.rows,
            beginMs = timings.begin / NANOS_PER_MS,
            groupsMs = timings.groups / NANOS_PER_MS,
            rowsMs = rowsMs,
            commitMs = commitMs,
            buildMs = timings.build / NANOS_PER_MS,
            builds = builds,
            finishMs = finishMs,
            finishSteps = finishSteps,
            totalMs = total,
            otherMs = other,
            pages = pages,
            sizes = sizes,
            pragmas = pragmas,
            crossChecks = crossChecks,
            derivedDigest = derivedDigest(db),
            checks = checks,
            warnings = warnings,
        )
        if (listOf(prune, counts, search, writerRows, writerCommits).any { it < 0 }) {
            result.excluded = "the app's step counters were reset during the run: a section load ran in the app"
        }
        return result
    }

    /** The phases of a run, in nanoseconds. */
    private class Timings {
        var begin = 0L
        var groups = 0L
        var rows = 0L
        var commit = 0L
        var build = 0L
        var finish = 0L

        /** The lab's own work inside the window: making the batches, reading page counts and the native heap. */
        var overhead = 0L

        /** From the first phase to the last, less the lab's own work: what an import would have spent. */
        fun total(start: Long, end: Long): Long = (end - start) - overhead

        /** What of the total fell between the phases. */
        fun other(start: Long, end: Long): Long = total(start, end) - (begin + groups + rows + commit + build + finish)
    }

    // -------------------------------------------------------------------------- reading the scratch database

    /** The DDL Room wrote for each index of `media`, by name: what a deferred build executes. */
    private fun SupportSQLiteDatabase.indexDdl(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        query("SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 'media' AND sql IS NOT NULL").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                map[name] = c.getString(1) ?: continue
            }
        }
        return map
    }

    private fun SupportSQLiteDatabase.text(sql: String): String =
        query(sql).use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }

    private fun SupportSQLiteDatabase.long(sql: String): Long =
        query(sql).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun SupportSQLiteDatabase.indexNames(): Set<String> {
        val names = LinkedHashSet<String>()
        query("PRAGMA index_list('media')").use { c ->
            val column = c.getColumnIndex("name")
            while (c.moveToNext()) {
                val name = c.getString(column) ?: continue
                names.add(name)
            }
        }
        return names
    }

    /** Which of the 17 columns hold integers; the rest are text. In the order of [INTRINSIC_COLUMNS]. */
    private val INTEGER_COLUMN = booleanArrayOf(
        false, false, false, false, false, false, false, false, false, true, true, false, false, true, true, true, true,
    )

    private const val INTRINSIC_COLUMNS = "id, source_id, kind, title, stream_url, artwork_url, group_id, epg_channel_id, " +
        "provider_ref, provider_order, duration_seconds, series_id, series_title, season_number, episode_number, " +
        "generation, added_at"

    /** The digest of the columns the lab writes itself, read back in insertion order: [LabDataset.expectedDigest]'s other half. */
    private fun digestOf(db: SupportSQLiteDatabase): String {
        val digest = LabDigest()
        db.query("SELECT $INTRINSIC_COLUMNS FROM media ORDER BY rowid").use { c ->
            while (c.moveToNext()) {
                for (i in INTEGER_COLUMN.indices) digest.field(columnText(c, i, INTEGER_COLUMN[i]))
                digest.endRow()
            }
        }
        return digest.hex()
    }

    private fun columnText(c: Cursor, index: Int, integer: Boolean): String? = when {
        c.isNull(index) -> null
        integer -> c.getLong(index).toString()
        else -> c.getString(index)
    }

    /** The two columns the writer folds from the title, which only the writer can produce: compared across cases. */
    private fun derivedDigest(db: SupportSQLiteDatabase): String {
        val digest = LabDigest()
        db.query("SELECT sort_title, search_text FROM media ORDER BY rowid").use { c ->
            while (c.moveToNext()) {
                digest.field(c.getString(0))
                digest.field(c.getString(1))
                digest.endRow()
            }
        }
        return digest.hex()
    }

    private fun categoryCountsMatch(db: SupportSQLiteDatabase, data: LabDataset): Boolean {
        val found = HashMap<String, Int>()
        db.query("SELECT id, item_count FROM media_group").use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                found[id] = c.getInt(1)
            }
        }
        val expected = data.expectedGroupCounts
        return expected.all { (id, count) -> found[id] == count } && found.values.sum() == data.rows
    }
}
