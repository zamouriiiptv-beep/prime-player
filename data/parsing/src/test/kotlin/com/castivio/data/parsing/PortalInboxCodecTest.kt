package com.castivio.data.parsing

import com.castivio.domain.portal.SentSource
import com.castivio.domain.portal.SentSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader

/**
 * The portal's JSON in both directions.
 *
 * The reading half is held to what the portal actually sends and to what it will send once
 * it is ahead of the app; the writing half is held to the one rule that matters for a body
 * built by hand, which is that anything a person can type survives the trip.
 */
class PortalInboxCodecTest {

    private fun parse(json: String) = PortalInboxCodec.parseWaiting(StringReader(json))

    // ------------------------------------------------------------------- reading

    @Test
    fun `both kinds are read with everything the device needs`() {
        val sent = parse(
            """
            {"playlists":[
              {"id":"pl_1","version":"abc123","name":"Home","sourceType":"xtream",
               "serverUrl":"panel.example:8080","username":"bob","password":"hunter2"},
              {"id":"pl_2","version":"def456","name":"Sports","sourceType":"m3u_url",
               "m3uUrl":"https://list.example.com/a.m3u8"}
            ]}
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                SentSubscription("pl_1", "abc123", "Home", SentSource.Xtream("panel.example:8080", "bob", "hunter2")),
                SentSubscription("pl_2", "def456", "Sports", SentSource.Playlist("https://list.example.com/a.m3u8")),
            ),
            sent,
        )
    }

    @Test
    fun `an empty answer is nothing sent, whichever way it is empty`() {
        assertTrue(parse("""{"playlists":[]}""").isEmpty())
        assertTrue(parse("""{"playlists":null}""").isEmpty())
        assertTrue(parse("""{}""").isEmpty())
        assertTrue(parse("""{"other":1}""").isEmpty())
    }

    @Test
    fun `a kind this build has no form for is left out, and the others are still read`() {
        val sent = parse(
            """
            {"playlists":[
              {"id":"a","version":"1","name":"Panel","sourceType":"stalker","serverUrl":"http://p.example/c/"},
              {"id":"b","version":"1","name":"File","sourceType":"m3u_file","fileName":"a.m3u"},
              {"id":"d","version":"1","name":"Future","sourceType":"xspf","m3uUrl":"https://list.example.com/d.xspf"},
              {"id":"c","version":"1","name":"Kept","sourceType":"m3u_url","m3uUrl":"https://list.example.com/c.m3u8"}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("c"), sent.map { it.id })
    }

    @Test
    fun `one with a field its kind cannot do without is left out`() {
        val sent = parse(
            """
            {"playlists":[
              {"id":"a","version":"1","sourceType":"xtream","serverUrl":"h:80","username":"u"},
              {"id":"b","version":"1","sourceType":"xtream","username":"u","password":"p"},
              {"id":"c","version":"1","sourceType":"m3u_url"},
              {"id":"d","sourceType":"m3u_url","m3uUrl":"https://x.example/d"},
              {"version":"1","sourceType":"m3u_url","m3uUrl":"https://x.example/e"},
              {"id":"f","version":"1","sourceType":"m3u_url","m3uUrl":"https://x.example/f"}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("f"), sent.map { it.id })
    }

    @Test
    fun `a missing name is blank and fields the device does not know are skipped however nested`() {
        val sent = parse(
            """
            {"extra":{"a":[1,2,{"b":"}"}]},
             "playlists":[
              {"id":"a","version":"1","sourceType":"m3u_url","m3uUrl":"https://x.example/a",
               "later":{"deep":["]","}"]},"createdAt":"2026-11-11T00:00:00Z"}
            ]}
            """.trimIndent(),
        )

        assertEquals(1, sent.size)
        assertEquals("", sent.single().name)
    }

    @Test
    fun `a password that looks like a number or has an escape is kept exactly`() {
        val sent = parse(
            """
            {"playlists":[
              {"id":"a","version":"1","sourceType":"xtream","serverUrl":"h:80","username":"u","password":"007"},
              {"id":"b","version":"1","sourceType":"xtream","serverUrl":"h:80","username":"u","password":"p\"q\\ré"}
            ]}
            """.trimIndent(),
        )

        assertEquals("007", (sent[0].source as SentSource.Xtream).password)
        assertEquals("p\"q\\ré", (sent[1].source as SentSource.Xtream).password)
    }

    @Test
    fun `a name in any script is read as written`() {
        val sent = parse(
            """{"playlists":[{"id":"a","version":"1","name":"  مرحبا 📺  ","sourceType":"m3u_url","m3uUrl":"https://x.example/a"}]}""",
        )

        assertEquals("مرحبا 📺", sent.single().name)
    }

    @Test
    fun `a page that is not JSON is a failure and not an empty answer`() {
        try {
            parse("<!DOCTYPE html><html><body>Bad gateway</body></html>")
            fail("a web page must not read as nothing waiting")
        } catch (expected: RuntimeException) {
            // reaches the caller as a failure
        }
    }

    // ------------------------------------------------------------------- writing

    @Test
    fun `quoting follows the rules JSON has`() {
        assertEquals("\"plain\"", PortalInboxCodec.quote("plain"))
        assertEquals("\"a\\\"b\"", PortalInboxCodec.quote("a\"b"))
        assertEquals("\"a\\\\b\"", PortalInboxCodec.quote("a\\b"))
        assertEquals("\"line\\nbreak\\r\\ttab\"", PortalInboxCodec.quote("line\nbreak\r\ttab"))
        assertEquals("\"\\u0001\\u001f\"", PortalInboxCodec.quote("\u0001\u001f"))
        assertEquals("\"é مرحبا\"", PortalInboxCodec.quote("é مرحبا"))
    }

    @Test
    fun `anything that can be typed survives being written and read back`() {
        val awkward = listOf(
            "hunter2",
            "p\"q",
            "back\\slash",
            "{\"nested\":[1,2]}",
            "tab\there",
            "new\nline",
            "emoji 📺",
            "مرحبا",
            "ends with backslash\\",
            "'single' and \"double\"",
        )
        for (text in awkward) {
            val back = JsonScanner(StringReader(PortalInboxCodec.quote(text))).string()
            assertEquals(text, back)
        }
    }

    @Test
    fun `the register body carries what the device reports and leaves out what it does not`() {
        val full = PortalInboxCodec.registerBody("AA:BB:CC:00:11:22", "482731", "NVIDIA SHIELD", "Android TV")
        assertEquals(
            """{"macAddress":"AA:BB:CC:00:11:22","deviceKey":"482731","modelName":"NVIDIA SHIELD","platform":"Android TV"}""",
            full,
        )

        val bare = PortalInboxCodec.registerBody("AA:BB:CC:00:11:22", "482731", null, "  ")
        assertEquals("""{"macAddress":"AA:BB:CC:00:11:22","deviceKey":"482731"}""", bare)
    }

    @Test
    fun `the inbox body is the address and the key`() {
        assertEquals(
            """{"macAddress":"AA:BB:CC:00:11:22","deviceKey":"482731"}""",
            PortalInboxCodec.inboxBody("AA:BB:CC:00:11:22", "482731"),
        )
    }

    @Test
    fun `the acknowledgement names the exact versions that were added`() {
        val taken = listOf(
            SentSubscription("pl_1", "v1", "A", SentSource.Playlist("https://x.example/a")),
            SentSubscription("pl_2", "v\"2", "B", SentSource.Playlist("https://x.example/b")),
        )

        assertEquals(
            """{"macAddress":"AA:BB:CC:00:11:22","deviceKey":"482731","delivered":[{"id":"pl_1","version":"v1"},{"id":"pl_2","version":"v\"2"}]}""",
            PortalInboxCodec.acknowledgeBody("AA:BB:CC:00:11:22", "482731", taken),
        )
        assertEquals(
            """{"macAddress":"AA:BB:CC:00:11:22","deviceKey":"482731","delivered":[]}""",
            PortalInboxCodec.acknowledgeBody("AA:BB:CC:00:11:22", "482731", emptyList()),
        )
    }

    @Test
    fun `what the device writes is what the portal reads`() {
        // The acknowledgement round-trips through the same scanner the rest of the app uses.
        val body = PortalInboxCodec.acknowledgeBody("AA:BB:CC:00:11:22", "48\"2731", listOf(
            SentSubscription("pl_1", "v1", "A", SentSource.Playlist("https://x.example/a")),
        ))
        var key: String? = null
        var delivered = 0
        val scanner = JsonScanner(StringReader(body))
        scanner.readObject { field ->
            when (field) {
                "deviceKey" -> key = scanner.string()
                "delivered" -> scanner.readArray { scanner.skip(); delivered++ }
            }
        }

        assertEquals("48\"2731", key)
        assertEquals(1, delivered)
    }
}
