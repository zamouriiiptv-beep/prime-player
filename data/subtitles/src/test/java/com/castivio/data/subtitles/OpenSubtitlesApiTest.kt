package com.castivio.data.subtitles

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        val result = api.search(hash = null, query = SubtitleQuery.parse("film.mkv"), languages = listOf("ar"))

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

        val result = api.search(
            hash = 1L,
            query = SubtitleQuery.parse("The.Road.2009.1080p.mkv"),
            languages = listOf("ar", "en"),
        )

        val sent = server.takeRequest()
        assertEquals("castivio-key", sent.getHeader("Api-Key"))
        assertEquals(OpenSubtitlesApi.USER_AGENT, sent.getHeader("User-Agent"))
        assertEquals("0000000000000001", sent.requestUrl?.queryParameter("moviehash"))
        assertEquals("ar,en", sent.requestUrl?.queryParameter("languages"))
        assertEquals(
            "the release noise was sent as part of the name",
            "The Road",
            sent.requestUrl?.queryParameter("query"),
        )
        assertEquals("2009", sent.requestUrl?.queryParameter("year"))

        val offers = result.found()
        assertEquals(2, offers.size)
    }

    /**
     * An episode is asked for as a name and two numbers, not as a sentence.
     *
     * `query=Friends S05E02` asks the server to find those characters in a title, which is a
     * different question from the one it has fields for and a much worse one. The numbers go
     * where the numbers go.
     */
    @Test
    fun `an episode is asked for by season and episode number`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))

        api(WORKING).search(null, SubtitleQuery.parse("Friends.S05E02.720p.HDTV.mkv"), emptyList())

        val sent = server.takeRequest().requestUrl
        assertEquals("Friends", sent?.queryParameter("query"))
        assertEquals("5", sent?.queryParameter("season_number"))
        assertEquals("2", sent?.queryParameter("episode_number"))
    }

    /* ------------------------------------------------------- results for something else */

    /**
     * The defect this filter was written for, as a test.
     *
     * The player used to search with the last path segment of the stream URL, so an IPTV
     * address ending in `/502` produced a search for "502" — and OpenSubtitles answered it
     * correctly, with episode 502 of five unrelated series. Those rows were real subtitles
     * and every one of them was wrong.
     *
     * The query is right now, and this asserts the second half: that a result which is not
     * about the query does not reach the viewer even when the server returns it.
     */
    @Test
    fun `results for another programme are not shown`() = runTest {
        // Twice: the query carries a year, so it is asked again without one before it gives
        // up. Every rung is answered with the same wrong results and every rung rejects them.
        server.enqueue(MockResponse().setBody(OTHER_PROGRAMMES))
        server.enqueue(MockResponse().setBody(OTHER_PROGRAMMES))

        val offers = api(WORKING).search(null, SubtitleQuery.parse("The Matrix 1999"), emptyList()).found()

        assertEquals("a subtitle for another programme reached the viewer", emptyList<SubtitleOffer>(), offers)
    }

    /** The right series, the wrong episode, which is the same mistake one step smaller. */
    @Test
    fun `another episode of the right series is not shown`() = runTest {
        server.enqueue(MockResponse().setBody(OTHER_PROGRAMMES))

        val offers = api(WORKING).search(null, SubtitleQuery.parse("Friends S05E01"), emptyList()).found()

        assertEquals(emptyList<SubtitleOffer>(), offers)
    }

    /** And the one that is actually asked for comes through, with what it is for read off. */
    @Test
    fun `the episode that was asked for is kept`() = runTest {
        server.enqueue(MockResponse().setBody(OTHER_PROGRAMMES))

        val offers = api(WORKING).search(null, SubtitleQuery.parse("Friends S05E02"), emptyList()).found()

        assertEquals(1, offers.size)
        assertEquals("Friends", offers[0].parentTitle)
        assertEquals(5, offers[0].season)
        assertEquals(2, offers[0].episode)
    }

    /* ------------------------------------------------------------------------ the ladder */

    /**
     * Asked again without the year rather than given up on.
     *
     * A provider dates a film by when it put the file up and a catalogue dates it by when it
     * was made, and they disagree often enough that insisting on the first is how a film with
     * subtitles gets reported as having none. So the year is dropped and the question asked
     * again — and dropped from the acceptance too, because a year that found nothing is not a
     * year worth rejecting answers over.
     *
     * The fixture is exactly that disagreement: one subtitle for the right film, dated four
     * years off. The first rung asks with the year and rejects it; the second finds it.
     */
    @Test
    fun `a year that finds nothing is dropped and the search runs again`() = runTest {
        server.enqueue(MockResponse().setBody(DATED_DIFFERENTLY))
        server.enqueue(MockResponse().setBody(DATED_DIFFERENTLY))

        val offers = api(WORKING).search(null, SubtitleQuery.parse("The.Matrix.1999.mkv"), emptyList()).found()

        assertEquals(2, server.requestCount)
        assertEquals("1999", server.takeRequest().requestUrl?.queryParameter("year"))
        assertNull(
            "the year was sent again after it had already found nothing",
            server.takeRequest().requestUrl?.queryParameter("year"),
        )
        assertEquals(1, offers.size)
        assertEquals(51L, offers[0].fileId)
    }

    /**
     * And the subtitle after the colon is dropped last of all.
     *
     * A work listed under its short name is rarer than one dated differently, so it is the
     * last thing tried — but it is tried, because "no subtitles available" has to mean the
     * question was asked every way there was before it is put in front of anybody.
     */
    @Test
    fun `the subtitle is dropped before the search gives up`() = runTest {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"data":[]}""")) }
        server.enqueue(MockResponse().setBody(SHORT_NAME))

        val offers = api(WORKING)
            .search(null, SubtitleQuery.parse("Blade Runner: The Final Cut 2007"), emptyList())
            .found()

        assertEquals(3, server.requestCount)
        assertEquals("Blade Runner: The Final Cut", server.takeRequest().requestUrl?.queryParameter("query"))
        assertEquals("Blade Runner: The Final Cut", server.takeRequest().requestUrl?.queryParameter("query"))
        assertEquals("Blade Runner", server.takeRequest().requestUrl?.queryParameter("query"))
        assertEquals(1, offers.size)
    }

    /** A found rung ends it: nothing is asked twice once something has answered. */
    @Test
    fun `a rung that finds something is the last one`() = runTest {
        server.enqueue(MockResponse().setBody(TWO_RESULTS))

        api(WORKING).search(null, SubtitleQuery.parse("The.Road.2009.mkv"), emptyList())

        assertEquals(1, server.requestCount)
    }

    /** And a refusal ends it too. Three ways of being told the key is wrong is not three answers. */
    @Test
    fun `a refusal is not retried down the ladder`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = api(WORKING).search(null, SubtitleQuery.parse("The.Matrix.1999.mkv"), emptyList())

        assertEquals(SubtitleResult.Refused(SubtitleFailure.REFUSED), result)
        assertEquals(1, server.requestCount)
    }

    /**
     * A hash match is never filtered out, whatever it is catalogued as.
     *
     * The escape hatch for every film held under one name and catalogued under another. The
     * hash is evidence about these exact bytes; a title is evidence about somebody's
     * spelling, and it must not be allowed to overrule the bytes.
     */
    @Test
    fun `a hash match survives a name that does not match at all`() = runTest {
        server.enqueue(MockResponse().setBody(HASHED_UNDER_ANOTHER_NAME))

        val offers = api(WORKING).search(1L, SubtitleQuery.parse("المصفوفة"), emptyList()).found()

        assertEquals(1, offers.size)
        assertTrue(offers[0].matchesThisFile)
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

        val offers = api.search(1L, SubtitleQuery.parse("The Road 2009"), listOf("ar")).found()

        assertTrue("the popular one came first", offers[0].matchesThisFile)
        assertEquals(11L, offers[0].fileId)
        assertEquals("ar", offers[0].language)
        assertEquals(99_999, offers[1].downloads)
    }

    /** A search with nothing for this film is an empty list, not a failure. */
    @Test
    fun `no results is an answer`() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))

        val result = api(WORKING).search(null, SubtitleQuery.parse("obscure.mkv"), emptyList())

        assertEquals(emptyList<SubtitleOffer>(), result.found())
    }

    /** A wrong key is reported as refused, which is a thing the user can act on. */
    @Test
    fun `a rejected key is reported as refused`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = api(WORKING).search(null, SubtitleQuery.parse("film.mkv"), emptyList())

        assertEquals(SubtitleResult.Refused(SubtitleFailure.REFUSED), result)
    }

    /** And an answer this client cannot read does not take the player down with it. */
    @Test
    fun `a body that is not JSON is a reason, not a crash`() = runTest {
        server.enqueue(MockResponse().setBody("<html>maintenance</html>"))

        val result = api(WORKING).search(null, SubtitleQuery.parse("film.mkv"), emptyList())

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

        /**
         * What a bad query used to return: three real subtitles, none of them the film.
         *
         * Two are episode 502 of series nobody asked about; the third is the right series
         * and the wrong episode. Between them they cover both ways a result can be wrong,
         * and the one correct row is here so that a filter which simply returned nothing
         * would fail as loudly as one that returned everything.
         */
        val OTHER_PROGRAMMES = """
            {"data":[
              {"attributes":{"language":"en","download_count":900,"moviehash_match":false,
                "release":"Friends.S05E02.DVDRip",
                "feature_details":{"title":"The One With All the Kissing","parent_title":"Friends",
                  "season_number":5,"episode_number":2,"year":1998},
                "files":[{"file_id":31,"file_name":"friends-502.srt"}]}},
              {"attributes":{"language":"en","download_count":800,"moviehash_match":false,
                "release":"The.Office.S05E02.HDTV",
                "feature_details":{"title":"Business Ethics","parent_title":"The Office",
                  "season_number":5,"episode_number":2,"year":2008},
                "files":[{"file_id":32,"file_name":"office-502.srt"}]}},
              {"attributes":{"language":"en","download_count":700,"moviehash_match":false,
                "release":"Friends.S05E09.DVDRip",
                "feature_details":{"title":"The One With Ross's Sandwich","parent_title":"Friends",
                  "season_number":5,"episode_number":9,"year":1998},
                "files":[{"file_id":33,"file_name":"friends-509.srt"}]}}
            ]}
        """.trimIndent()

        /** The right film, dated four years off — the disagreement the ladder exists for. */
        val DATED_DIFFERENTLY = """
            {"data":[
              {"attributes":{"language":"en","download_count":500,"moviehash_match":false,
                "release":"The.Matrix.BluRay",
                "feature_details":{"title":"The Matrix","year":2003},
                "files":[{"file_id":51,"file_name":"matrix.srt"}]}}
            ]}
        """.trimIndent()

        /** The same work, listed without the subtitle the provider's file name carried. */
        val SHORT_NAME = """
            {"data":[
              {"attributes":{"language":"en","download_count":300,"moviehash_match":false,
                "release":"Blade.Runner.1982",
                "feature_details":{"title":"Blade Runner","year":1982},
                "files":[{"file_id":61,"file_name":"blade.srt"}]}}
            ]}
        """.trimIndent()

        /** One result, catalogued in English, matched by hash against an Arabic-named file. */
        val HASHED_UNDER_ANOTHER_NAME = """
            {"data":[
              {"attributes":{"language":"ar","download_count":40,"moviehash_match":true,
                "release":"The.Matrix.1999.BluRay",
                "feature_details":{"title":"The Matrix","year":1999},
                "files":[{"file_id":41,"file_name":"matrix-ar.srt"}]}}
            ]}
        """.trimIndent()
    }
}
