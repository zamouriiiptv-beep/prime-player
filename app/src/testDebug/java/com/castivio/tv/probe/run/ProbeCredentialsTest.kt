package com.castivio.tv.probe.run

import com.castivio.data.parsing.XtreamUrls
import com.castivio.tv.probe.core.Redaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeCredentialsTest {

    private val base = "http://panel.example:8080/"
    private val user = "alice9"
    private val password = "p@ss+w&rd"

    private fun credentials(address: String = base, name: String = user, secret: String = password) =
        ProbeCredentials(address, name, secret)

    @Test
    fun printing_it_says_nothing_about_the_account() {
        val text = credentials().toString()
        assertEquals("ProbeCredentials(redacted)", text)
        assertEquals("ProbeCredentials(redacted)", "${credentials()}")
        for (secret in listOf("panel.example", "8080", user, password)) assertFalse(text.contains(secret))
    }

    @Test
    fun the_addresses_are_the_importers_own_character_for_character() {
        val c = credentials()
        assertEquals(XtreamUrls.api(base, user, password, action = "get_vod_categories"), c.categoriesUrl())
        assertEquals(XtreamUrls.api(base, user, password, action = "get_vod_streams"), c.wholeReplyUrl())
        assertEquals(
            XtreamUrls.api(base, user, password, action = "get_vod_streams", parameters = mapOf("category_id" to "17")),
            c.categoryUrl("17"),
        )
    }

    @Test
    fun the_whole_reply_names_no_category_and_a_category_names_its_own() {
        assertFalse(credentials().wholeReplyUrl().contains("category_id"))
        assertTrue(credentials().categoryUrl("17").endsWith("&category_id=17"))
    }

    @Test
    fun characters_that_change_an_addresss_meaning_are_encoded() {
        val url = credentials().categoriesUrl()
        assertTrue(url, url.contains("password=p%40ss%2Bw%26rd"))
        assertFalse(url.contains(password))
        assertTrue(credentials().categoryUrl("a b&c").endsWith("&category_id=a%20b%26c"))
    }

    @Test
    fun the_scheme_is_http_or_https_whatever_the_owner_typed() {
        assertEquals("http", credentials("http://panel.example:8080").scheme)
        assertEquals("https", credentials("https://panel.example").scheme)
        assertEquals("https", credentials("HTTPS://Panel.Example").scheme)
        assertEquals("http", credentials("panel.example:8080").scheme)
    }

    @Test
    fun every_secret_is_listed_as_it_is_and_as_an_address_writes_it() {
        val secrets = credentials().secrets()
        val expected = listOf(
            "http://panel.example:8080/",
            "http://panel.example:8080",
            "panel.example",
            "panel.example:8080",
            user,
            password,
            "p%40ss%2Bw%26rd",
        )
        for (secret in expected) assertTrue("$secret in $secrets", secret in secrets)
    }

    @Test
    fun an_address_of_numbers_gives_its_host_and_its_authority() {
        val secrets = credentials("http://127.0.0.1:8123").secrets()
        assertTrue("127.0.0.1" in secrets)
        assertTrue("127.0.0.1:8123" in secrets)
    }

    @Test
    fun nothing_blank_is_listed() {
        assertTrue(credentials(name = "", secret = "").secrets().none { it.isBlank() })
    }

    @Test
    fun scrubbing_an_address_removes_the_whole_account_even_where_the_platform_would_write_it_differently() {
        // A space, a tilde and a star are written one way by the importer's function and another by the platform's
        // URL encoder: the list of secrets holds both, so that neither is left in a text that carried the address.
        val c = credentials(name = "al ice~9", secret = "pa ss*w~rd")
        val url = c.categoriesUrl()
        assertTrue(url, url.contains("username=al%20ice~9&password=pa%20ss%2Aw~rd"))
        val scrubbed = Redaction.scrub(url, c.secrets())
        for (leaked in listOf("al%20ice~9", "pa%20ss%2Aw~rd", "al ice~9", "pa ss*w~rd", "panel.example", "8080")) {
            assertFalse("$leaked left in $scrubbed", scrubbed.contains(leaked))
        }
        assertTrue(scrubbed, scrubbed.contains("[redacted]"))
    }
}
