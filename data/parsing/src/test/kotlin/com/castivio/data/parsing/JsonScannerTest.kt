package com.castivio.data.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class JsonScannerTest {

    @Test
    fun `reads a flat array of objects`() {
        val json = """[{"id":"1","name":"Sports"},{"id":"2","name":"News"}]"""
        val rows = mutableListOf<Pair<String?, String?>>()

        val scanner = JsonScanner(StringReader(json))
        scanner.readArray {
            var id: String? = null
            var name: String? = null
            scanner.readObject { field ->
                when (field) {
                    "id" -> id = scanner.string()
                    "name" -> name = scanner.string()
                }
            }
            rows.add(id to name)
        }

        assertEquals(listOf("1" to "Sports", "2" to "News"), rows)
    }

    /**
     * The property that makes this scanner hard to misuse: a value the caller does
     * not read is skipped for it, so a provider adding a field — or a nested object
     * — cannot desynchronise the scan.
     */
    @Test
    fun `unread fields are skipped, including nested ones`() {
        val json = """
            [{"id":"1","extra":{"a":[1,2,{"b":"c"}],"d":null},"tags":["x","y"],"name":"Kept"}]
        """.trimIndent()
        val names = mutableListOf<String?>()

        val scanner = JsonScanner(StringReader(json))
        scanner.readArray {
            scanner.readObject { field ->
                if (field == "name") names.add(scanner.string())
            }
        }

        assertEquals(listOf("Kept"), names)
    }

    @Test
    fun `numbers and strings are interchangeable, as providers send them`() {
        val json = """{"a":1234,"b":"5678","c":12.7,"d":null,"e":""}"""
        val values = mutableMapOf<String, String?>()

        val scanner = JsonScanner(StringReader(json))
        scanner.readObject { field -> values[field] = scanner.string() }

        assertEquals("1234", values["a"])
        assertEquals("5678", values["b"])
        assertEquals("12.7", values["c"])
        assertNull(values["d"])
        // An empty string in an Xtream response means "absent".
        assertNull(values["e"])
    }

    @Test
    fun `long and int coerce through strings and decimals`() {
        val scanner = JsonScanner(StringReader("""{"a":"42","b":42,"c":"42.9","d":"","e":"abc"}"""))
        val values = mutableMapOf<String, Long>()
        scanner.readObject { field -> values[field] = scanner.long() }

        assertEquals(42L, values["a"])
        assertEquals(42L, values["b"])
        assertEquals(42L, values["c"])
        assertEquals(0L, values["d"])
        assertEquals(0L, values["e"])
    }

    @Test
    fun `booleans accept the shapes providers actually use`() {
        val scanner = JsonScanner(StringReader("""{"a":true,"b":"1",_c:0,"d":"0","e":false,"f":null}"""
            .replace("_c", "\"c\"")))
        val values = mutableMapOf<String, Boolean>()
        scanner.readObject { field -> values[field] = scanner.boolean() }

        assertTrue(values.getValue("a"))
        assertTrue(values.getValue("b"))
        assertFalse(values.getValue("c"))
        assertFalse(values.getValue("d"))
        assertFalse(values.getValue("e"))
        assertFalse(values.getValue("f"))
    }

    @Test
    fun `nested objects and arrays can be read explicitly`() {
        val json = """{"info":{"title":"Show","seasons":[1,2,3]},"id":"7"}"""
        var title: String? = null
        var seasons = 0
        var id: String? = null

        val scanner = JsonScanner(StringReader(json))
        scanner.readObject { field ->
            when (field) {
                "info" -> scanner.readObject { infoField ->
                    when (infoField) {
                        "title" -> title = scanner.string()
                        "seasons" -> scanner.readArray { seasons++; scanner.skip() }
                    }
                }
                "id" -> id = scanner.string()
            }
        }

        assertEquals("Show", title)
        assertEquals(3, seasons)
        assertEquals("7", id)
    }

    @Test
    fun `an object keyed by number is iterable, which is how series episodes arrive`() {
        val json = """{"1":[{"t":"a"},{"t":"b"}],"2":[{"t":"c"}]}"""
        val bySeason = mutableMapOf<String, MutableList<String?>>()

        val scanner = JsonScanner(StringReader(json))
        scanner.readObject { season ->
            val titles = bySeason.getOrPut(season) { mutableListOf() }
            scanner.readArray {
                scanner.readObject { field -> if (field == "t") titles.add(scanner.string()) }
            }
        }

        assertEquals(mapOf("1" to listOf("a", "b"), "2" to listOf("c")), bySeason.mapValues { it.value.toList() })
    }

    @Test
    fun `escapes are resolved`() {
        val json = """{"a":"line\nbreak","b":"quote\"inside","c":"slash\/x","d":"مرحبا"}"""
        val values = mutableMapOf<String, String?>()

        JsonScanner(StringReader(json)).let { scanner ->
            scanner.readObject { field -> values[field] = scanner.string() }
        }

        assertEquals("line\nbreak", values["a"])
        assertEquals("quote\"inside", values["b"])
        assertEquals("slash/x", values["c"])
        assertEquals("مرحبا", values["d"])
    }

    @Test
    fun `empty containers are handled`() {
        val scanner = JsonScanner(StringReader("""{"a":[],"b":{},"c":"x"}"""))
        var arrays = 0
        var value: String? = null
        scanner.readObject { field ->
            when (field) {
                "a" -> scanner.readArray { arrays++ }
                "b" -> scanner.readObject { }
                "c" -> value = scanner.string()
            }
        }
        assertEquals(0, arrays)
        assertEquals("x", value)
    }

    @Test
    fun `an empty array document reads as nothing`() {
        var elements = 0
        JsonScanner(StringReader("[]")).readArray { elements++ }
        assertEquals(0, elements)
    }

    /** Providers do send `null` where an array or object is documented. */
    @Test
    fun `null in place of a container is not an error`() {
        var elements = 0
        JsonScanner(StringReader("null")).readArray { elements++ }
        assertEquals(0, elements)

        var fields = 0
        JsonScanner(StringReader("null")).readObject { fields++ }
        assertEquals(0, fields)
    }

    @Test
    fun `malformed json fails loudly rather than silently`() {
        val truncated = runCatching {
            val scanner = JsonScanner(StringReader("""[{"a":"unterminated"""))
            scanner.readArray { scanner.readObject { scanner.skip() } }
        }
        assertTrue(truncated.exceptionOrNull() is JsonFormatException)

        val wrongDelimiter = runCatching {
            val scanner = JsonScanner(StringReader("""{"a":1;"b":2}"""))
            scanner.readObject { scanner.skip() }
        }
        assertTrue(wrongDelimiter.exceptionOrNull() is JsonFormatException)
    }

    @Test
    fun `documents larger than the buffer stream correctly`() {
        // Forces many refills, which is where an off-by-one in buffer handling shows.
        val entries = 5_000
        val json = buildString {
            append('[')
            for (i in 0 until entries) {
                if (i > 0) append(',')
                append("""{"id":"$i","name":"Channel $i with a deliberately long name to span buffers"}""")
            }
            append(']')
        }
        var count = 0
        var lastName: String? = null

        val scanner = JsonScanner(StringReader(json))
        scanner.readArray {
            scanner.readObject { field ->
                if (field == "name") lastName = scanner.string()
            }
            count++
        }

        assertEquals(entries, count)
        assertEquals("Channel 4999 with a deliberately long name to span buffers", lastName)
    }
}
