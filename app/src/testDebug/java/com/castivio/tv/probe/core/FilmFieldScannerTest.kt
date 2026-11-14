package com.castivio.tv.probe.core

import com.castivio.data.parsing.JsonFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class FilmFieldScannerTest {

    private class Scanned(val entries: FilmEntries, val counts: FilmFieldScanner.Counts)

    private fun scan(json: String, requested: String? = null): Scanned {
        val entries = FilmEntries()
        val counts = FilmFieldScanner.scan(StringReader(json), requested, StringPool(), entries)
        return Scanned(entries, counts)
    }

    private fun ids(entries: FilmEntries): List<Long> = (0 until entries.size).map { entries.id(it) }

    @Test
    fun a_number_and_a_string_are_the_same_id_and_the_same_category() {
        val result = scan("""[{"stream_id":1,"category_id":"7","name":"A"},{"stream_id":"2","category_id":7,"name":"B"}]""")
        assertEquals(listOf(1L, 2L), ids(result.entries))
        assertEquals("7", result.entries.declared(0))
        assertEquals("7", result.entries.declared(1))
        assertEquals(2, result.counts.films)
        assertEquals(0, result.counts.withoutId)
    }

    @Test
    fun for_the_one_reply_the_asked_and_the_named_category_are_the_same() {
        val result = scan("""[{"stream_id":1,"category_id":"7"}]""")
        assertEquals("7", result.entries.requested(0))
        assertEquals("7", result.entries.declared(0))
    }

    @Test
    fun the_category_asked_is_kept_apart_from_the_one_the_film_names() {
        val result = scan("""[{"stream_id":1,"category_id":"7"}]""", requested = "9")
        assertEquals("9", result.entries.requested(0))
        assertEquals("7", result.entries.declared(0))
    }

    @Test
    fun rows_without_an_id_are_counted_and_left_out() {
        val result = scan(
            """[{"name":"x","category_id":"1"},{"stream_id":"","category_id":"1"},{"stream_id":null},{"stream_id":5,"category_id":"1"}]""",
        )
        assertEquals(listOf(5L), ids(result.entries))
        assertEquals(1, result.counts.films)
        assertEquals(3, result.counts.withoutId)
    }

    @Test
    fun a_film_without_a_category_is_kept_with_none() {
        val result = scan("""[{"stream_id":5}]""")
        assertEquals(listOf(5L), ids(result.entries))
        assertNull(result.entries.declared(0))
        assertNull(result.entries.requested(0))
    }

    @Test
    fun every_other_member_is_stepped_over_whole() {
        val json = """[{"stream_id":5,"name":"he said \"stream_id\": 99 and category_id","category_id":"3",""" +
            """"tags":[{"stream_id":77,"category_id":"x"}],"info":{"category_id":"y","stream_id":88}}]"""
        val result = scan(json)
        assertEquals(listOf(5L), ids(result.entries))
        assertEquals("3", result.entries.declared(0))
        assertEquals(1, result.counts.films)
    }

    @Test
    fun an_empty_list_and_a_null_are_no_films() {
        assertEquals(0, scan("[]").counts.films)
        assertEquals(0, scan("null").counts.films)
    }

    @Test
    fun a_reply_that_is_not_a_list_is_refused() {
        assertThrows(JsonFormatException::class.java) { scan("""{"user_info":{"auth":0}}""") }
        assertThrows(JsonFormatException::class.java) { scan("<html>busy</html>") }
    }

    @Test
    fun an_id_larger_than_an_int_is_kept_whole() {
        val result = scan("""[{"stream_id":12345678901234,"category_id":"1"}]""")
        assertEquals(listOf(12345678901234L), ids(result.entries))
    }

    @Test
    fun a_number_and_a_string_of_it_make_one_key() {
        assertEquals(42L, StreamKey.of("42"))
        assertEquals(42L, StreamKey.of(" 42 "))
        assertEquals(0L, StreamKey.of("0"))
    }

    @Test
    fun an_id_that_is_not_a_whole_number_never_meets_a_number() {
        val odd = StreamKey.of("abc")
        assertTrue(odd < 0L)
        assertEquals(odd, StreamKey.of("abc"))
        assertNotEquals(odd, StreamKey.of("abd"))
        assertTrue(StreamKey.of("-5") < 0L)
        assertNotEquals(-5L, StreamKey.of("-5"))
        assertTrue(StreamKey.of("1.5") < 0L)
    }

    @Test
    fun the_pool_makes_one_string_of_every_repeat() {
        val pool = StringPool()
        val first = pool.of(String(charArrayOf('1', '2')))
        val second = pool.of("12")
        assertSame(first, second)
        assertNull(pool.of(null))
    }

    @Test
    fun sorted_ids_are_distinct_and_can_be_searched() {
        val sorted = SortedIds.of(longArrayOf(5L, 1L, 5L, 3L, 1L, 9L))
        assertEquals(4, sorted.size)
        assertEquals(listOf(1L, 3L, 5L, 9L), (0 until sorted.size).map { sorted.at(it) })
        assertTrue(3L in sorted)
        assertTrue(4L !in sorted)
        assertTrue(sorted.indexOf(9L) >= 0)
        assertTrue(sorted.indexOf(2L) < 0)
        assertEquals(0, SortedIds.of(LongArray(0)).size)
    }

    @Test
    fun the_entries_grow_past_their_first_capacity_and_can_be_joined() {
        val first = FilmEntries(initialCapacity = 4)
        for (id in 1L..5_000L) first.add(id, "a", "a")
        assertEquals(5_000, first.size)
        assertEquals(5_000L, first.id(4_999))
        val second = FilmEntries()
        second.add(7L, "b", "c")
        first.addAll(second)
        assertEquals(5_001, first.size)
        assertEquals("b", first.requested(5_000))
        assertEquals("c", first.declared(5_000))
    }
}
