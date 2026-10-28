package com.castivio.data.networking

import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * A name server that drops one answer must not turn a provider that answered a moment ago into one that does not exist.
 */
class RememberingDnsTest {

    private val first = listOf(InetAddress.getByAddress("provider.example", byteArrayOf(10, 0, 0, 1)))
    private val second = listOf(InetAddress.getByAddress("provider.example", byteArrayOf(10, 0, 0, 2)))

    /** A name server that answers from a script: an address, or a failure. */
    private class Scripted(private val answers: ArrayDeque<Result<List<InetAddress>>>) : Dns {
        var asked = 0
        override fun lookup(hostname: String): List<InetAddress> {
            asked++
            return answers.removeFirst().getOrThrow()
        }
    }

    private fun failure() = Result.failure<List<InetAddress>>(UnknownHostException("no answer"))

    @Test
    fun `a lookup that works is passed through`() {
        val dns = RememberingDns(Scripted(ArrayDeque(listOf(Result.success(first)))))

        assertSame(first, dns.lookup("provider.example"))
    }

    @Test
    fun `a host that resolved before is answered from memory when the lookup fails`() {
        val server = Scripted(ArrayDeque(listOf(Result.success(first), failure(), failure())))
        val dns = RememberingDns(server)

        dns.lookup("provider.example")

        assertEquals(first, dns.lookup("provider.example"))
        assertEquals(first, dns.lookup("provider.example"))
        assertEquals("the name server is still asked every time", 3, server.asked)
    }

    @Test
    fun `a host that never resolved fails as it always did`() {
        val dns = RememberingDns(Scripted(ArrayDeque(listOf(failure()))))

        try {
            dns.lookup("typo.example")
            fail("a name nobody ever resolved was answered")
        } catch (e: UnknownHostException) {
            assertEquals("no answer", e.message)
        }
    }

    @Test
    fun `an answer replaces what was remembered, so a provider that moves is followed`() {
        val dns = RememberingDns(Scripted(ArrayDeque(listOf(Result.success(first), Result.success(second), failure()))))

        dns.lookup("provider.example")
        dns.lookup("provider.example")

        assertEquals("the failure after the move gets the new address", second, dns.lookup("provider.example"))
    }

    @Test
    fun `what is remembered is kept per host`() {
        val dns = RememberingDns(Scripted(ArrayDeque(listOf(Result.success(first), failure()))))
        dns.lookup("provider.example")

        try {
            dns.lookup("images.example")
            fail("one host was answered with another's address")
        } catch (e: UnknownHostException) {
            assertEquals("no answer", e.message)
        }
    }

    /** The provider's own name must outlast a long list of poster hosts, which are looked up and never again. */
    @Test
    fun `the host that is used again outlasts hosts that are not`() {
        val answers = ArrayDeque<Result<List<InetAddress>>>()
        val nameServer = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = answers.removeFirst().getOrThrow()
        }
        val dns = RememberingDns(nameServer, capacity = 3)
        answers.add(Result.success(first))
        dns.lookup("provider.example")
        for (n in 1..5) {
            answers.add(Result.success(listOf(InetAddress.getByAddress("poster$n.example", byteArrayOf(10, 1, 0, n.toByte())))))
            dns.lookup("poster$n.example")
            // The provider is used between the posters, as it is in a session.
            answers.add(failure())
            dns.lookup("provider.example")
        }

        answers.add(failure())
        assertEquals(first, dns.lookup("provider.example"))
    }

    @Test
    fun `a capacity of nothing is refused`() {
        try {
            RememberingDns(capacity = 0)
            fail("a memory of nothing was accepted")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("capacity"))
        }
    }

    /**
     * The whole promise, against a real client: the provider is reached by name, the name server fails once the name has
     * been resolved, and the request still goes through -- where it used to fail as an unknown host.
     */
    @Test
    fun `a client reaches a provider whose name the name server stops answering`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("one"))
            server.enqueue(MockResponse().setBody("two"))
            var answering = true
            val nameServer = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (answering) listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1))) else throw UnknownHostException(hostname)
            }
            val client = HttpClientProvider.create(dns = RememberingDns(nameServer))
            val url = "http://provider.example:${server.port}/"

            val before = client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body!!.string() }
            answering = false
            // A connection that is not reused, so that the name is looked up again.
            client.connectionPool.evictAll()
            val after = client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body!!.string() }

            assertEquals("one", before)
            assertEquals("two", after)
        } finally {
            server.shutdown()
        }
    }
}
