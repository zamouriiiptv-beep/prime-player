package com.castivio.data.database

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The page cache SQLite is given while a catalogue is written, and the default it is put back to afterwards.
 *
 * ## Why a load wants more than the default
 *
 * Two indices of `media` are written in no order. A row's id is a hash (`StableIds`), so the primary key's index takes
 * every row at a place nothing predicts, and the category index does the same when the provider's list does not keep a
 * category's rows together. Each such insert needs a page somewhere in a tree that is larger than SQLite's default
 * cache -- about 2 MB -- so pages are read again and again from the file, and written to the log when they had changed.
 * With 8 MiB the pages those indices are written through stay in memory for the whole load.
 *
 * ## What it was measured to be worth
 *
 * Not on a device -- there was none to hand -- but on a desktop SQLite, with this writer's table, indices, statement,
 * hashed ids and commit every 2,000 rows, and the cache size the only thing changed. 54,174 rows took 8 to 21% less time
 * to insert at 8 MiB than at the default (the larger figures when the categories' rows arrive mixed, the smaller when they
 * arrive together or when the log is checkpointed every 100 pages instead of every 1,000), 400,000 rows took 18% less, and
 * 32 MiB was scarcely better than 8. A device's share will be smaller: a row there also pays for twenty-one bindings across JNI,
 * which that figure does not include. What the load strip's "writer rows" says on the device is what counts.
 *
 * ## Bounded, and put back to the default
 *
 * A fixed size that does not grow with the library: memory the writer holds is memory the player's decoders do not have on
 * a box with a gigabyte. A negative size is in KiB, a positive one in pages, which would make the size depend on the
 * database's page size.
 *
 * It is put back to SQLite's own default when the load ends or is abandoned -- not to a value read beforehand, because
 * nothing in the application sets another, and a pragma read is answered by whichever connection the platform hands the
 * query, which may not be the writer's. So a raise that no `finish` or `abort` ever followed is put back by the end of
 * the next load, and an import that ends while another still runs puts it back for both: the other goes on at the
 * default, slower and never wrong.
 *
 * Every statement is wrapped, as the writer's other import pragmas are: a SQLite that refuses one imports at the
 * default speed; it does not fail the import.
 */
internal object ImportPageCache {

    /** What an import is given, in KiB: 8 MiB. */
    const val IMPORT_KIB = 8_192

    /** SQLite's own default, in KiB, which [restore] sets. */
    const val NORMAL_KIB = 2_000

    /** The cache for the load about to run. Last in `begin`, so that nothing after it can end `begin` with it raised. */
    fun raise(db: SupportSQLiteDatabase) = set(db, IMPORT_KIB)

    /** The default, once the load has ended or been abandoned. */
    fun restore(db: SupportSQLiteDatabase) = set(db, NORMAL_KIB)

    private fun set(db: SupportSQLiteDatabase, kib: Int) {
        runCatching { db.execSQL("PRAGMA cache_size = -$kib") }
    }
}
