package com.castivio.data.parsing

import com.castivio.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamUrlsTest {

    /** Users paste whatever their provider emailed them. All of it has to work. */
    @Test
    fun `the base url is normalised whatever the user pasted`() {
        val expected = "http://host.example.com:8080"
        for (input in listOf(
            "http://host.example.com:8080",
            "http://host.example.com:8080/",
            "host.example.com:8080",
            "  http://host.example.com:8080/player_api.php?username=a&password=b  ",
            "http://host.example.com:8080/get.php",
        )) {
            assertEquals("input '$input'", expected, XtreamUrls.normaliseBase(input))
        }
        assertEquals("https://secure.example.com", XtreamUrls.normaliseBase("https://secure.example.com/"))
    }

    @Test
    fun `api urls carry credentials and the action`() {
        val url = XtreamUrls.api("host:8080", "user", "pass", "get_live_streams", mapOf("category_id" to "12"))

        assertEquals(
            "http://host:8080/player_api.php?username=user&password=pass" +
                "&action=get_live_streams&category_id=12",
            url,
        )
    }

    /**
     * Xtream passwords routinely contain `@`, `+` and `&`. Unencoded, those change
     * what the URL means and the login fails for reasons no user can diagnose.
     */
    @Test
    fun `credentials are percent-encoded`() {
        val url = XtreamUrls.api("host", "user@mail.com", "p+ss&w/rd", "get_live_categories")

        assertEquals(
            "http://host/player_api.php?username=user%40mail.com&password=p%2Bss%26w%2Frd" +
                "&action=get_live_categories",
            url,
        )
    }

    @Test
    fun `spaces encode as percent-20, not plus`() {
        // URLEncoder would produce '+', which is correct in a form body and wrong
        // in a path — and Xtream credentials go in the path.
        assertEquals("a%20b", XtreamUrls.encode("a b"))
    }

    @Test
    fun `non-latin credentials survive encoding`() {
        assertEquals("%D9%85%D8%B1%D8%AD%D8%A8%D8%A7", XtreamUrls.encode("مرحبا"))
    }

    @Test
    fun `stream paths match what the classifier expects`() {
        assertEquals(
            "http://host:8080/live/u/p/1234.ts",
            XtreamUrls.stream("host:8080", "u", "p", MediaKind.LIVE, "1234"),
        )
        assertEquals(
            "http://host:8080/live/u/p/1234.ts",
            XtreamUrls.stream("host:8080", "u", "p", MediaKind.RADIO, "1234"),
        )
        assertEquals(
            "http://host:8080/movie/u/p/99.mkv",
            XtreamUrls.stream("host:8080", "u", "p", MediaKind.MOVIE, "99", "mkv"),
        )
        assertEquals(
            "http://host:8080/series/u/p/7.mp4",
            XtreamUrls.stream("host:8080", "u", "p", MediaKind.SERIES, "7"),
        )
        // A leading dot on the extension is the provider's habit, not an error.
        assertEquals(
            "http://host/movie/u/p/9.avi",
            XtreamUrls.stream("http://host", "u", "p", MediaKind.MOVIE, "9", ".avi"),
        )
    }

    @Test
    fun `the alternate live format is available for playback fallback`() {
        assertEquals(
            "http://host/live/u/p/1.m3u8",
            XtreamUrls.alternateLiveFormat("http://host/live/u/p/1.ts"),
        )
        assertEquals(
            "http://host/live/u/p/1.ts",
            XtreamUrls.alternateLiveFormat("http://host/live/u/p/1.m3u8"),
        )
        assertNull(XtreamUrls.alternateLiveFormat("http://host/movie/u/p/1.mkv"))
    }

    @Test
    fun `the xmltv endpoint is built from the same credentials`() {
        assertEquals(
            "http://host:8080/xmltv.php?username=u&password=p",
            XtreamUrls.xmltv("host:8080/", "u", "p"),
        )
    }
}
