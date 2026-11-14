package com.castivio.tv.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class RedactionAndFormatTest {

    // ---------------------------------------------------------------------------------- redaction

    @Test
    fun every_secret_is_removed_as_written_and_as_written_into_a_url() {
        val text = "GET http://panel.example:8080/player_api.php?username=alice9&password=p%40ss%2Bword for panel.example"
        val scrubbed = Redaction.scrub(
            text,
            listOf("http://panel.example:8080", "panel.example", "alice9", "p@ss+word"),
        )
        assertFalse(scrubbed.contains("panel.example"))
        assertFalse(scrubbed.contains("alice9"))
        assertFalse(scrubbed.contains("p%40ss%2Bword"))
        assertFalse(scrubbed.contains("p@ss+word"))
        assertTrue(scrubbed.contains("[redacted]"))
    }

    @Test
    fun the_longest_secret_goes_first_so_that_none_of_it_is_left_over() {
        val scrubbed = Redaction.scrub("see http://panel.example:8080 now", listOf("panel.example", "http://panel.example:8080"))
        assertEquals("see [redacted] now", scrubbed)
    }

    @Test
    fun a_secret_is_found_whatever_its_case() {
        assertEquals("[redacted] and [redacted]", Redaction.scrub("Alice9 and ALICE9", listOf("alice9")))
    }

    @Test
    fun a_secret_too_short_to_be_told_from_ordinary_text_is_left_alone() {
        assertEquals("run 12 of 20", Redaction.scrub("run 12 of 20", listOf("12", "ab")))
    }

    @Test
    fun text_with_no_secret_in_it_comes_back_unchanged() {
        val text = "K3 #1 · 41.20 s · 1.89 MB/s"
        assertEquals(text, Redaction.scrub(text, listOf("alice9", "panel.example")))
        assertEquals(text, Redaction.scrub(text, emptyList()))
    }

    // ------------------------------------------------------------------------------------ format

    @Test
    fun digits_are_western_whatever_the_default_language() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("64.2", Fmt.seconds(64_200L))
            assertEquals("64.20", Fmt.seconds2(64_200L))
            assertEquals("1.21", Fmt.decimal(1.2149, 2))
            assertEquals("201,345", Fmt.count(201_345))
            assertEquals("201,345", Fmt.count(201_345L))
            assertEquals("+35.0%", Fmt.signedPercent(0.35))
            assertEquals("-12.0%", Fmt.signedPercent(-0.12))
            assertEquals("25.0%", Fmt.percent(0.25))
            assertEquals("77.9", Fmt.megabytes(81_682_000L))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun a_megabyte_is_the_strips_megabyte() {
        assertEquals("78.0", Fmt.megabytes(78L * 1_048_576L))
        assertEquals("0.0", Fmt.megabytes(0L))
    }
}
