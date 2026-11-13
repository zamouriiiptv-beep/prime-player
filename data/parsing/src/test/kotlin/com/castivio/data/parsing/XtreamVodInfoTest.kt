package com.castivio.data.parsing

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamVodInfoTest {

    @Test
    fun `a reply's rating, story, people, genres, year, running time and added day are read`() {
        val reply = """
            {"info":{"rating":"8.1","plot":"A young man and a woman meet on a train.",
              "director":"Richard Linklater","cast":"Ethan Hawke, Julie Delpy",
              "genre":"Romance, Drama","releasedate":"1995-07-21","duration_secs":6060},
             "movie_data":{"stream_id":42,"added":"1729771200"}}
        """.trimIndent()

        val info = XtreamParser.parseVodInfo(StringReader(reply))!!

        assertEquals(8.1, info.rating!!, 0.001)
        assertEquals("A young man and a woman meet on a train.", info.plot)
        assertEquals("Richard Linklater", info.director)
        assertEquals(listOf("Ethan Hawke", "Julie Delpy"), info.cast)
        assertEquals(listOf("Romance", "Drama"), info.genres)
        assertEquals(1995, info.releaseYear)
        assertEquals(6060, info.durationSeconds)
        assertEquals(1729771200L, info.addedEpochSeconds)
    }

    @Test
    fun `a field the provider left out or blank is absent, and the rest still reads`() {
        val reply = """{"info":{"rating":"","plot":"  ","cast":"","director":"Richard Linklater"},"movie_data":{"added":"0"}}"""

        val info = XtreamParser.parseVodInfo(StringReader(reply))!!

        assertEquals("Richard Linklater", info.director)
        assertNull(info.rating)
        assertNull(info.plot)
        assertEquals(emptyList<String>(), info.cast)
        assertNull(info.releaseYear)
        assertNull(info.addedEpochSeconds)
    }

    /**
     * A poster and the page it opens read a rating by one rule -- see `XtreamParser.ratingOf` -- so they cannot
     * disagree about whether a film has one.
     */
    @Test
    fun `a rating that is not a finite number above zero is no rating, as it is on the list`() {
        for (raw in listOf("Infinity", "NaN", "-1", "0", "0.0", "n/a", "")) {
            val reply = """{"info":{"rating":"$raw","plot":"A story."}}"""

            assertNull("'$raw' is not a rating", XtreamParser.parseVodInfo(StringReader(reply))!!.rating)
        }
        val real = XtreamParser.parseVodInfo(StringReader("""{"info":{"rating":7.5,"plot":"A story."}}"""))!!
        assertEquals(7.5, real.rating!!, 0.0001)
    }

    @Test
    fun `the running time as a clock wins over a duration_secs that holds minutes`() {
        val reply = """{"info":{"duration":"01:58:00","duration_secs":"118"}}"""

        assertEquals(7080, XtreamParser.parseVodInfo(StringReader(reply))!!.durationSeconds)
    }

    @Test
    fun `duration_secs is read when there is no clock`() {
        val reply = """{"info":{"duration_secs":"6060"}}"""

        assertEquals(6060, XtreamParser.parseVodInfo(StringReader(reply))!!.durationSeconds)
    }

    @Test
    fun `a reply of another shape is no details at all`() {
        assertNull(XtreamParser.parseVodInfo(StringReader("""{"info":[]}""")))
    }

    @Test
    fun `a reply whose every field is blank is no details at all`() {
        val reply = """{"info":{"rating":"","plot":"  ","cast":""},"movie_data":{"added":"0"}}"""
        assertNull(XtreamParser.parseVodInfo(StringReader(reply)))
    }
}
