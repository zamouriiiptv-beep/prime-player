package com.castivio.tv.probe.run

import com.castivio.tv.probe.support.FakePanel
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProbeClientTest {

    private val events = ProbeEvents { System.nanoTime() }

    @Test
    fun it_has_no_cache_so_that_a_category_is_never_answered_from_the_disk() {
        assertNull(ProbeClient.create(events.factory).cache)
    }

    @Test
    fun it_does_not_retry_by_itself() {
        assertFalse(ProbeClient.create(events.factory).retryOnConnectionFailure)
    }

    @Test
    fun its_timeouts_are_the_applications() {
        val client = ProbeClient.create(events.factory)
        assertEquals(12_000, client.connectTimeoutMillis)
        assertEquals(20_000, client.readTimeoutMillis)
        assertEquals(20_000, client.writeTimeoutMillis)
    }

    @Test
    fun it_tells_the_listener_it_was_given() {
        assertSame(events.factory, ProbeClient.create(events.factory).eventListenerFactory)
    }

    @Test
    fun every_client_has_its_own_pool_and_its_own_dispatcher() {
        val first = ProbeClient.create(events.factory)
        val second = ProbeClient.create(events.factory)
        assertNotSame(first.connectionPool, second.connectionPool)
        assertNotSame(first.dispatcher, second.dispatcher)
    }

    @Test
    fun it_introduces_itself_as_the_application_does() {
        assertEquals("Castivio/1.0 (Android TV)", ProbeClient.USER_AGENT)
        FakePanel(listOf("1"), emptyList()).use { panel ->
            val client = ProbeClient.create(events.factory)
            val request = Request.Builder()
                .url("http://127.0.0.1:${panel.port}/player_api.php?action=get_vod_categories")
                .build()
            client.newCall(request).execute().use { response -> response.body?.string() }
            assertEquals(listOf(ProbeClient.USER_AGENT), panel.userAgents.toList())
        }
    }
}
