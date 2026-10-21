package com.castivio.playback.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What a log line or a copied report may say about the stream that was playing. */
class SourceRedactionTest {

    @Test
    fun `an Xtream channel keeps its host and file and loses the account in its path`() {
        val safe = SourceRedaction.safe("http://panel.example:8080/live/viewer/s3cret/1234.ts")

        assertEquals("http://panel.example:8080/…/1234.ts", safe)
        assertFalse(safe.contains("viewer"))
        assertFalse(safe.contains("s3cret"))
    }

    @Test
    fun `films, episodes and the short form lose the account the same way`() {
        assertEquals("http://h/…/77.mp4", SourceRedaction.safe("http://h/movie/viewer/s3cret/77.mp4"))
        assertEquals("http://h/…/9.mkv", SourceRedaction.safe("http://h/series/viewer/s3cret/9.mkv"))
        assertEquals("http://h:80/…/1234", SourceRedaction.safe("http://h:80/viewer/s3cret/1234"))
    }

    @Test
    fun `a query, a fragment and a name in the authority are dropped`() {
        assertEquals(
            "https://cdn.example/…/index.m3u8",
            SourceRedaction.safe("https://viewer:s3cret@cdn.example/hls/abc/index.m3u8?token=xyz#t=10"),
        )
    }

    @Test
    fun `a local file keeps only its own name`() {
        assertEquals("content://media/…/7", SourceRedaction.safe("content://media/external/video/media/7"))
        assertEquals("file:///…/film.mp4", SourceRedaction.safe("file:///storage/emulated/0/Movies/film.mp4"))
        assertEquals("http://h", SourceRedaction.safe("http://h"))
    }
}
