package com.castivio.data.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * Which rows of an existing catalogue get an archive, and under which key.
 *
 * The keys are the point: a write that names a row by anything but the id the import gave it finds nothing.
 */
class XtreamArchiveBackfillTest {

    private val list = """[
        {"num":1,"name":"Nova","stream_id":11,"tv_archive":1,"tv_archive_duration":7,"category_id":"1"},
        {"num":2,"name":"Atlas","stream_id":"12","tv_archive":"0","tv_archive_duration":"7"},
        {"num":3,"name":"Orion","stream_id":13,"tv_archive":1,"tv_archive_duration":3,"epg_channel_id":"x"},
        {"num":4,"name":"No archive at all","stream_id":14},
        {"num":5,"name":"Marked, no days","stream_id":15,"tv_archive":1,"tv_archive_duration":0},
        {"name":"no id","tv_archive":1,"tv_archive_duration":7}
    ]"""

    @Test
    fun `only the channels that keep an archive are written, in hours`() {
        val updates = XtreamArchiveBackfill.updates("src", StringReader(list))

        assertEquals(listOf(7 * 24, 3 * 24), updates.map { it.hours })
    }

    @Test
    fun `a row is named by the id the import gives it`() {
        val updates = XtreamArchiveBackfill.updates("src", StringReader(list))

        assertEquals(StableIds.item("src", "stream:11"), updates[0].itemId)
        assertEquals(StableIds.item("src", "stream:13"), updates[1].itemId)
    }

    @Test
    fun `the same stream under another subscription is another row`() {
        val here = XtreamArchiveBackfill.updates("one", StringReader(list)).first().itemId
        val there = XtreamArchiveBackfill.updates("two", StringReader(list)).first().itemId

        assertTrue(here != there)
    }

    @Test
    fun `the rule for an archive is the one the import uses`() {
        // The same list, read by the import's parser, offers catch-up for exactly the channels the backfill writes.
        val fromImport = ArrayList<String>()
        XtreamParser.parseStreams(StringReader(list)) { if (it.catchUpHours != null) fromImport.add(it.streamId) }
        val fromBackfill = ArrayList<String>()
        XtreamParser.parseArchiveFlags(StringReader(list)) { id, _ -> fromBackfill.add(id) }

        assertEquals(fromImport, fromBackfill)
    }

    @Test
    fun `an empty list is no writes`() {
        assertTrue(XtreamArchiveBackfill.updates("src", StringReader("[]")).isEmpty())
    }
}
