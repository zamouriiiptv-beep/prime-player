package com.castivio.data.networking

import com.castivio.data.parsing.ReplySpool
import com.castivio.data.parsing.WholeSectionUnsupported
import com.castivio.domain.MediaKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The one reply taken to a file first, as the panel actually receives the question and as the parser actually reads
 * the answer.
 *
 * What is pinned is the HTTP half of what a refresh promises: the question is the one the reply that arrives asks, a
 * "no" is a refusal and not a failure, a connection that breaks leaves no file, the reader is handed exactly the text a
 * reader of the connection would be, and the meters say what the download and what the parser did.
 */
class XtreamDownloadedReplyHttpTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var directory: File

    private val json = "[" + """{"stream_id":"1","name":"Channel","category_id":"3"},""".repeat(3_000) + "{}]"

    @Before
    fun setUp() {
        CallMetrics.reset()
        server = MockWebServer()
        server.start()
        directory = folder.newFolder("replies")
    }

    @After
    fun tearDown() {
        server.shutdown()
        CallMetrics.reset()
    }

    private fun spool(freeBytes: Long = Long.MAX_VALUE) = ReplySpool(directory, freeBytes = { freeBytes })

    private fun api(spool: ReplySpool?, metered: Boolean = false) = XtreamHttpApi(
        client = HttpClientProvider.create(eventListenerFactory = CallMetricsListener.FACTORY),
        base = server.url("/").toString().trimEnd('/'),
        username = "sam",
        password = "hunter2",
        metered = { metered },
        spool = spool,
    )

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    private fun left(): List<String> = directory.list().orEmpty().sorted()

    private fun tally() = CallMetrics.snapshot().single { it.action == "get_live_streams" }

    @Test
    fun `the download asks the question the reply that arrives asks`() {
        server.enqueue(MockResponse().setBody(json))

        api(spool()).allStreamsDownloaded(MediaKind.LIVE)!!.use { it.readText() }

        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.contains("action=get_live_streams"))
        assertFalse("a category was asked for: $path", path.contains("category_id"))
    }

    @Test
    fun `the parser is handed the same text as from the connection, and the file goes with the reader`() {
        server.enqueue(MockResponse().setBody(json))

        val reader = api(spool()).allStreamsDownloaded(MediaKind.LIVE)!!
        assertEquals("the reply is in a file by the time it is returned", 1, left().size)
        val text = reader.use { it.readText() }

        assertEquals(json, text)
        assertEquals("closing the reader takes the file", emptyList<String>(), left())
    }

    @Test
    fun `with no spool the reply is read as it arrives and no file is made`() {
        server.enqueue(MockResponse().setBody(json))

        val text = api(spool = null).allStreamsDownloaded(MediaKind.LIVE)!!.use { it.readText() }

        assertEquals(json, text)
        assertEquals(emptyList<String>(), left())
    }

    @Test
    fun `with no room for a file the reply is read as it arrives`() {
        server.enqueue(MockResponse().setBody(json))

        val reader = api(spool(freeBytes = 0)).allStreamsDownloaded(MediaKind.LIVE)!!
        assertEquals("nothing was written", emptyList<String>(), left())
        val text = reader.use { it.readText() }

        assertEquals(json, text)
    }

    /** A "no" is a refusal after one attempt, for the engine to tell from a failure: the same as the reply that arrives. */
    @Test
    fun `a refusal comes back as one, after one attempt, with no file`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("[]"))

        try {
            api(spool()).allStreamsDownloaded(MediaKind.LIVE)
            fail("a 404 was read as an answer")
        } catch (e: WholeSectionUnsupported) {
            assertEquals("HTTP 404", e.message)
            assertEquals(404, e.status)
        }

        assertEquals("the refusal was retried", 1, server.requestCount)
        assertEquals(emptyList<String>(), left())
    }

    /** A connection that breaks mid-reply is thrown as the network's failure, and what arrived of it is not kept. */
    @Test
    fun `a connection that breaks leaves no file and is thrown as it was`() {
        server.enqueue(MockResponse().setBody(json).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))

        try {
            api(spool()).allStreamsDownloaded(MediaKind.LIVE)
            fail("half a reply was taken for a reply")
        } catch (e: IOException) {
            assertFalse("it is the network's failure, not the disk's", e is com.castivio.data.parsing.SpoolWriteException)
        }

        assertEquals(1, server.requestCount)
        assertEquals(emptyList<String>(), left())
    }

    @Test
    fun `a download for an import that was left stops, and leaves no file`() {
        server.enqueue(MockResponse().setBody(json))

        try {
            api(spool()).allStreamsDownloaded(MediaKind.LIVE) { true }
            fail("a cancelled download was handed over")
        } catch (e: com.castivio.data.parsing.SpoolCancelled) {
            // the import was left
        }

        assertEquals(emptyList<String>(), left())
    }

    @Test
    fun `gzip with no header is unpacked from the file, and said to be`() {
        val packed = gzip(json)
        server.enqueue(MockResponse().setBody(Buffer().write(packed)))

        val text = api(spool(), metered = true).allStreamsDownloaded(MediaKind.LIVE)!!.use { it.readText() }

        assertEquals(json, text)
        val wire = tally().wireFigures()!!
        assertEquals(1, wire.sniffedGzipCalls)
        assertEquals(json.toByteArray().size.toLong(), wire.decodedBytes)
        assertEquals("what the wire carried is the packed size", packed.size.toLong(), tally().byteCount)
    }

    /**
     * The meters are where they were: the wire's on the connection, which is read to its end by the download, and the
     * decoded count on what the parser is handed, which is read from the file.
     */
    @Test
    fun `the meters say what the download carried and what the parser was handed`() {
        server.enqueue(MockResponse().setBody(json))

        api(spool(), metered = true).allStreamsDownloaded(MediaKind.LIVE)!!.use { it.readText() }

        val tally = tally()
        val wire = tally.wireFigures()!!
        assertEquals(1, wire.metered)
        assertEquals(json.toByteArray().size.toLong(), tally.byteCount)
        assertEquals(json.toByteArray().size.toLong(), wire.decodedBytes)
        assertEquals(0, wire.encodedCalls)
    }

    @Test
    fun `an unmetered download claims no decoded size`() {
        server.enqueue(MockResponse().setBody(json))

        api(spool(), metered = false).allStreamsDownloaded(MediaKind.LIVE)!!.use { it.readText() }

        val wire = tally().wireFigures()!!
        assertEquals(0, wire.metered)
        assertEquals(0L, wire.decodedBytes)
    }

    /** Radio asks the live question, taken to a file or not. */
    @Test
    fun `radio's download asks the live question`() {
        server.enqueue(MockResponse().setBody("[]"))

        api(spool()).allStreamsDownloaded(MediaKind.RADIO)!!.close()

        assertTrue(server.takeRequest().path.orEmpty().contains("action=get_live_streams"))
    }
}
