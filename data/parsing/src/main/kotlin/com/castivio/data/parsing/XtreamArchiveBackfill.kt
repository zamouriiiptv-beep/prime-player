package com.castivio.data.parsing

import com.castivio.domain.CatchUpHours
import java.io.Reader

/**
 * An Xtream panel's channel list, turned into the writes that give an existing catalogue its `catch_up_hours`.
 *
 * Pure: a reader in, a list out. The id of a row is the one the import gave it -- [StableIds.item] over the source and
 * `stream:<id>` -- and is made here and nowhere else, so a channel found in the list is found in the catalogue by its
 * key and not by a scan; that derivation is private to this module, which is why the step lives in it.
 *
 * **Only the channels that keep an archive are returned.** A catalogue's rows are null in the column until this runs,
 * and null is what a channel with no archive is, so there is nothing to write for the rest. The list is held as long as
 * the channels that keep an archive are many -- ids and numbers, a few megabytes at the very worst -- which is what lets
 * the write be a single transaction that does not wait on the network, instead of a transaction held open across a
 * download.
 */
object XtreamArchiveBackfill {

    fun updates(sourceId: String, reader: Reader): List<CatchUpHours> {
        val out = ArrayList<CatchUpHours>()
        XtreamParser.parseArchiveFlags(reader) { streamId, hours ->
            out.add(CatchUpHours(itemId = StableIds.item(sourceId, "stream:$streamId"), hours = hours))
        }
        return out
    }
}
