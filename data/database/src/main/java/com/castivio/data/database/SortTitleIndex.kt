package com.castivio.data.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * `idx_media_source_kind_sort_title`: the one index of `media` a subscription's first load may leave to be
 * built after its rows.
 *
 * ## Why this one, and why only then
 *
 * Its keys are the lower-cased titles, which arrive in the provider's order and not the alphabet's, so
 * every row inserted lands somewhere in the middle of the tree -- the costliest kind of index to keep up
 * while a library is written, and the one a first load needs least: it is read only by the lists the
 * viewer sorts by name, and the list a section opens with is the provider's order. Building it once over
 * the finished rows is a sort and a sequential write: 101 ms against the 1,255 ms keeping it up cost over
 * 55,801 rows, on the phone the index-cost lab ran on.
 *
 * The other four it could be confused with are not candidates. `idx_media_source_kind_order` and
 * `idx_media_source_kind_group_order` are read *during* the load -- by the counts every card draws and by
 * the first category's page -- and `RoomCatalogWriter.COUNT_GROUPS` records what a category's count costs
 * without them. The generation and series indices are cheap to keep up, their keys being constant or
 * increasing.
 *
 * ## What leaving it out can and cannot do
 *
 * An index changes how a query is answered, never what it answers. A list sorted by name while the index
 * is out is read by sorting the category in memory -- slower for a large one, never wrong -- and a viewer
 * who has not pressed the sort never meets it. What it must not do is stay out: a load that ends or is
 * cancelled puts it back, and so does the next load's first step -- see [restore] -- for the process that
 * died in between.
 *
 * ## Room, and why an index that is out is not harmless for ever
 *
 * Nothing here relies on Room noticing. It checks the schema it finds against the entities' after a
 * migration has run, so a migration over a database still missing this index would end in a database that
 * does not open -- the worst way for an update to end. [CastivioMigrations] therefore runs [restore]
 * before every migration from the version that introduced deferral on, through `DeferredIndexGuard`, and
 * a test holds the list to it.
 */
internal object SortTitleIndex {

    const val NAME = "idx_media_source_kind_sort_title"

    /** The columns the entity declares, in order -- what [isIntact] compares against. */
    private val COLUMNS = listOf("source_id", "kind", "sort_title")

    /** Room's own DDL for the entity's index, character for character: see `MediaEntity`. */
    const val CREATE = "CREATE INDEX IF NOT EXISTS `$NAME` ON `media` (`source_id`, `kind`, `sort_title`)"

    const val DROP = "DROP INDEX IF EXISTS `$NAME`"

    /**
     * Puts the index back if it is not as the entity declares it: absent, or present with other columns.
     *
     * Idempotent, and a read when nothing is wrong. The second state is not one this code makes -- a
     * `CREATE INDEX` is atomic, so a half-built index cannot survive a crash -- and is mended all the same,
     * because a name that means something else is as bad as a name that is missing.
     *
     * @return whether the index is as declared afterwards.
     */
    fun restore(db: SupportSQLiteDatabase): Boolean {
        if (isIntact(db)) return true
        db.execSQL(DROP)
        db.execSQL(CREATE)
        return isIntact(db)
    }

    /** The index exists and indexes exactly the columns the entity says, in its order. */
    fun isIntact(db: SupportSQLiteDatabase): Boolean = columnsOf(db) == COLUMNS

    /** The indexed columns in key order; empty when there is no such index. */
    fun columnsOf(db: SupportSQLiteDatabase): List<String> =
        db.query("PRAGMA index_info(`$NAME`)").use { cursor ->
            val place = cursor.getColumnIndexOrThrow("seqno")
            val name = cursor.getColumnIndexOrThrow("name")
            val columns = ArrayList<Pair<Int, String>>(COLUMNS.size)
            while (cursor.moveToNext()) columns += cursor.getInt(place) to cursor.getString(name)
            columns.sortedBy { it.first }.map { it.second }
        }
}
