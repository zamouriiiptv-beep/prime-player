package com.castivio.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What an import asks of SQLite for its page cache, and that a SQLite which refuses it is not an import that fails.
 *
 * The database here is a stand-in that records what it is asked. What a cache of this size does to the time of a load
 * is not a thing a unit test can hold, and is measured where it can be -- see [ImportPageCache]. What a test can hold
 * is the request: the right pragma, in the right unit, in the right order, and nothing thrown when it is refused. The
 * load itself, against real SQLite, is `ImportPageCacheLoadTest`.
 */
class ImportPageCacheTest {

    @Test
    fun `a load asks for 8 MiB, in KiB, and for the default back`() {
        val asked = mutableListOf<String>()
        val db = database(asked)

        ImportPageCache.raise(db)
        ImportPageCache.restore(db)

        // A negative size is KiB. A positive one would be pages, and 8,192 pages is 32 MiB at a 4 KiB page.
        assertEquals(listOf("PRAGMA cache_size = -8192", "PRAGMA cache_size = -2000"), asked)
    }

    @Test
    fun `a SQLite that refuses the pragma does not fail the load, on either side of it`() {
        val asked = mutableListOf<String>()
        val refusing = database(asked, refuses = true)

        ImportPageCache.raise(refusing)
        ImportPageCache.restore(refusing)

        assertEquals(
            "the restore is asked for although the raise was refused: it does not know",
            listOf("PRAGMA cache_size = -8192", "PRAGMA cache_size = -2000"),
            asked,
        )
    }

    /** A database that answers `execSQL` and records the statement, and that records anything else it is asked as such. */
    private fun database(asked: MutableList<String>, refuses: Boolean = false): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            InvocationHandler { _, method, args ->
                if (method.name == "execSQL") {
                    asked += args?.firstOrNull() as String
                    if (refuses) throw IllegalStateException("refused")
                } else {
                    asked += "unexpected call: ${method.name}"
                }
                null
            },
        ) as SupportSQLiteDatabase
}
