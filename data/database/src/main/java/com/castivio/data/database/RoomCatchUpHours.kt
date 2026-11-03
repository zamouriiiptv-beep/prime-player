package com.castivio.data.database

import com.castivio.data.database.dao.MediaDao
import com.castivio.domain.CatchUpHours
import com.castivio.domain.CatchUpHoursWriter
import java.util.concurrent.Callable

/**
 * Gives rows that are already in the catalogue the archive a provider says their channel keeps.
 *
 * **One transaction, and only the rows that need it.** Every update is a lookup by primary key through a statement Room
 * prepared once; nothing is read back, nothing is rewritten, and what the import keeps beside a row -- the search entry,
 * the group counts, the favourites, the viewer's order, the sort-title index -- is not touched. The transaction is
 * opened after the list has been downloaded and parsed, never across the download, so it holds the database's one
 * writer for the milliseconds the updates take and not for as long as a panel takes to answer.
 *
 * Blocking, as SQLite is: the caller is on an IO dispatcher.
 */
class RoomCatchUpHours(
    private val dao: MediaDao,
    private val database: CastivioDatabase,
) : CatchUpHoursWriter {

    override fun apply(sourceId: String, updates: List<CatchUpHours>): Int {
        if (updates.isEmpty()) return 0
        return database.runInTransaction(
            Callable {
                var found = 0
                for (update in updates) found += dao.setCatchUpHours(update.itemId, sourceId, update.hours)
                found
            },
        )
    }
}
