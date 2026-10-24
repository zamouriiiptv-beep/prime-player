package com.castivio.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The film id an Xtream address carries, which is what a playlist row is asked about by. */
class MovieDetailsAddressTest {

    @Test
    fun `a film's id is read from its panel address`() {
        assertEquals("12345", xtreamVodId("http://host:8080/movie/user/secret/12345.mkv"))
        assertEquals("12345", xtreamVodId("https://host/movie/user/secret/12345.mp4?token=abc"))
        assertEquals("12345", xtreamVodId("http://host/movie/user/secret/12345"))
    }

    @Test
    fun `an address of another shape has no film id`() {
        assertNull(xtreamVodId("http://host/live/user/secret/12345.ts"))
        assertNull(xtreamVodId("http://cdn.example/films/dune.mkv"))
        assertNull(xtreamVodId("http://host/movie/user/secret/dune.mkv"))
    }
}
