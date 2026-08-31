package com.castivio.data.subtitles

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The OpenSubtitles client, against a real server on localhost.
 *
 * Real HTTP for the reason `:data:networking` gives about its own tests: status handling,
 * headers and a JSON body are not things to verify by inspection, and a mocked `OkHttpClient`
 * would be a test of the mock. Nothing here reaches the internet and nothing here needs a
 * credential — the values are literals, which is the entire point of taking
 * [OpenSubtitlesCredentials] as a parameter rather than reading `BuildConfig`.
 *
 * Robolectric, because the client reads its responses with `org.json` and logs a failure
 * with `android.util.Log` — both of which are stubs that throw in a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
class OpenSubtitlesApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /* ------------------------------------------------------------ not set up at all */

    /**
     * With no credentials, nothing is sent anywhere.
     *
     * The claim that matters is the second one. A client that asked and got a 401 would be
     * telling OpenSubtitles about every viewer who opened the sheet on an unconfigured
     * build, and would report a network failure where the truth is a missing key.
     */
    @Test
    fun `an unconfigured client refuses without asking`() = runTest {
        val api = api(OpenSubtitlesCredentials.NONE)

        val result = api.search(hash = null, fileName = "film.mkv", languages = listOf("ar"))

        assertEquals(SubtitleResult.Refused(SubtitleFailure.NOT_CONFIGURED), result)
        assertEquals("a request left the device with no credentials to send", 0, server.requestCount)
    }

    /* --------------------------------------------------------------------- searching */

    /**
     * A search carries the key, the hash and the languages, and reads the results back.
     *
     * The request is asserted as well as the response: sending the hash as a decimal, or
     * forgetting the `Api-Key` header, produces a call that succeeds and matches nothing.
     */
    @Test
    fun `a search sends what it must and reads what comes back`() = runTest {
        server.enqueue(MockResponse().setBody(TWO_RESULTS))
        val api = api(WORKING)

        val result = api.search(hash = 1L, fileName = "The.Road.2009.1080p.mkv", languages = listOf("ar", "en"))

        val sent = server.takeRequest()
        assertEquals("castivio-key", sent.getHeader("Api-Key"))
        assertEquals(OpenSubtitlesApi.USER_AGENT, sent.getHeader("User-Agent"))
        assertEquals("0000000000000001", sent.requestUrl?.queryParameter("moviehash"))
        assertEquals("ar,en", sent.requestUrl?.queryParameter("languages"))
        assertEquals(
            "the extension was sent as part of the title",
            "The.Road.2009.1080p",
            sent.requestUrl?.queryParameter("query"),
        )

        val offers = result.found()
        assertEquals(2, offers.size)
    }

    /**
     * The one that fits this file is first.
     *
     * The whole recommendation. A viewer takes the top row, so the top row has to be the
     * subtitle somebody has already watched against these exact bytes rather than the most
     * downloaded subtitle for some other copy of the film.
     */
    @Test
    fun `a hash match outranks a more popular name match`() = runTest {
        server.enqueue(MockResponse().setBody(TWO_RESULTS))
        val api = api(WORKING)

        val offers = api.search(1L, "film.mkv", listOf("ar")).found()

        assertTrue("the popular one came first", offers[0].matchesThisFile)
        assertEquals(11L, offers[0].fileId)
        assertEquals("ar", offers[0].language)
        assertEquals(99_999, offers[1].downloads)
    }

    /** A search with nothing for this film is an empty list, not a failure. */
    @Test
    fun `no results is an answer`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))

        val result = api(WORKING).search(null, "obscure.mkv", emptyList())

        assertEquals(emptyList<SubtitleOffer>(), result.found())
    }

    /** A wrong key is reported as refused, which is a thing the user can act on. */
    @Test
    fun `a rejected key is reported as refused`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = api(WORKING).search(null, "film.mkv", emptyList())

        assertEquals(SubtitleResult.Refused(SubtitleFailure.REFUSED), result)
    }

    /** And an answer this client cannot read does not take the player down with it. */
    @Test
    fun `a body that is not JSON is a reason, not a crash`() = runTest {
        server.enqueue(MockResponse().setBody("<html>maintenance</html>"))

        val result = api(WORKING).search(null, "film.mkv", emptyList())

        assertTrue(result is SubtitleResult.Refused)
    }

    /* ------------------------------------------------------------------- downloading */

    /**
     * A download logs in first, and sends the token on the second call.
     *
     * Two requests, in order, and the password only on the first. That ordering is the
     * feature: a viewer who never opens the subtitle search never has their password sent
     * anywhere, because the login does not happen until a download does.
     */
    @Test
    fun `a download logs in and then asks with the token`() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"session-abc"}"""))
        server.enqueue(MockResponse().setBody("""{"link":"https://example.test/one.srt"}"""))

        val result = api(WORKING).link(fileId = 11)

        val login = server.takeRequest()
        assertTrue(login.path.orEmpty().endsWith("/login"))
        assertTrue("the password was not sent to the login", login.body.readUtf8().contains("hunter2"))

        val download = server.takeRequest()
        assertTrue(download.path.orEmpty().endsWith("/download"))
        assertEquals("Bearer session-abc", download.getHeader("Authorization"))
        assertTrue("the file id never reached the download call", download.body.readUtf8().contains("11"))

        assertEquals("https://example.test/one.srt", result.found())
    }

    /** The session is kept, so a second download in the same sitting does not log in again. */
    @Test
    fun `a second download reuses the session`() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"session-abc"}"""))
        server.enqueue(MockResponse().setBody("""{"link":"https://example.test/one.srt"}"""))
        server.enqueue(MockResponse().setBody("""{"link":"https://example.test/two.srt"}"""))
        val api = api(WORKING)

        api.link(11)
        api.link(12)

        assertEquals("the password was sent twice in one sitting", 3, server.requestCount)
    }

    /**
     * A token that has expired is worth exactly one retry.
     *
     * A session lasts about a day, so the first download of the morning meeting a 401 is the
     * ordinary course of events rather than a fault. A second refusal is a password that is
     * wrong, and retrying that forever would lock the account.
     */
    @Test
    fun `an expired session logs in again, once`() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"stale"}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"token":"fresh"}"""))
        server.enqueue(MockResponse().setBody("""{"link":"https://example.test/one.srt"}"""))

        val result = api(WORKING).link(11)

        assertEquals("https://example.test/one.srt", result.found())
        assertEquals(4, server.requestCount)
    }

    /**
     * A spent daily allowance is its own reason.
     *
     * The only failure here that is neither a mistake nor a broken network, and the only one
     * whose answer is "tomorrow". Reporting it as a generic error would send a viewer to
     * check their password over and over.
     */
    @Test
    fun `a spent allowance is reported as its own reason`() = runTest {
        server.enqueue(MockResponse().setBody("""{"token":"session-abc"}"""))
        server.enqueue(MockResponse().setResponseCode(406))

        val result = api(WORKING).link(11)

        assertEquals(SubtitleResult.Refused(SubtitleFailure.OUT_OF_DOWNLOADS), result)
    }

    /* ----------------------------------------------------------------- the file itself */

    @Test
    fun `a fetched file is parsed into cues`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "1\n00:00:01,000 --> 00:00:02,000\nمرحبًا\n\n2\n00:00:03,000 --> 00:00:04,000\nوداعًا\n",
            ),
        )

        val result = api(WORKING).fetch(server.url("/one.srt").toString())

        val track = result.found()
        assertEquals(2, track.cues.size)
        assertNotNull(track.at(1_500))
    }

    /**
     * A file with nothing in it is refused rather than applied.
     *
     * Applying an empty track would switch the viewer's subtitles to a source that shows
     * nothing, silently, with the sheet saying it had worked — indistinguishable from a film
     * with no dialogue.
     */
    @Test
    fun `a file that parses to nothing is refused`() = runTest {
        server.enqueue(MockResponse().setBody("not a subtitle file at all"))

        val result = api(WORKING).fetch(server.url("/one.srt").toString())

        assertEquals(SubtitleResult.Refused(SubtitleFailure.UNREADABLE), result)
    }

    /** The value, or a failure whose reason is in the message rather than a class cast. */
    private fun <T> SubtitleResult<T>.found(): T = when (this) {
        is SubtitleResult.Found -> value
        is SubtitleResult.Refused -> throw AssertionError("expected a result, got $reason")
    }

    private fun api(credentials: OpenSubtitlesCredentials) = OpenSubtitlesApi(
        client = OkHttpClient(),
        credentials = credentials,
        base = server.url("/api/v1/"),
    )

    private companion object {
        val WORKING = OpenSubtitlesCredentials(
            apiKey = "castivio-key",
            username = "sami",
            password = "hunter2",
        )

        /**
         * One hash match with few downloads, one name match with a great many.
         *
         * Built this way round on purpose: sorted by popularity alone the wrong one wins,
         * so the fixture fails the test if the ordering rule is ever dropped.
         */
        val TWO_RESULTS = """
            {"data":[
              {"attributes":{"language":"en","download_count":99999,"moviehash_match":false,
                "release":"The.Road.2009.BluRay","files":[{"file_id":22,"file_name":"road-en.srt"}]}},
              {"attributes":{"language":"ar","download_count":12,"moviehash_match":true,
                "release":"The.Road.2009.WEB","files":[{"file_id":11,"file_name":"road-ar.srt"}]}}
            ]}
        """.trimIndent()
    }
}
