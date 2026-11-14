package com.castivio.tv.probe.run

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/** The small parts the runner is made of, each on its own. */
class RunSupportTest {

    // ----------------------------------------------------------------------------- counting stream

    @Test
    fun the_counting_stream_counts_every_byte_and_tells_the_listener_each_chunk() {
        val chunks = ArrayList<Int>()
        val stream = CountingStream(ByteArrayInputStream(ByteArray(10))) { chunks += it }
        val buffer = ByteArray(4)
        assertEquals(4, stream.read(buffer, 0, 4))
        assertEquals(4, stream.read(buffer, 0, 4))
        assertEquals(2, stream.read(buffer, 0, 4))
        assertEquals(-1, stream.read(buffer, 0, 4))
        assertEquals(10L, stream.total)
        assertEquals(listOf(4, 4, 2), chunks)
    }

    @Test
    fun a_single_byte_counts_as_one_and_the_end_counts_as_nothing() {
        val chunks = ArrayList<Int>()
        val stream = CountingStream(ByteArrayInputStream(byteArrayOf(1, 2))) { chunks += it }
        assertEquals(1, stream.read())
        assertEquals(2, stream.read())
        assertEquals(-1, stream.read())
        assertEquals(2L, stream.total)
        assertEquals(listOf(1, 1), chunks)
    }

    @Test
    fun the_listener_can_end_a_read_and_the_bytes_it_was_told_of_stay_counted() {
        val stream = CountingStream(ByteArrayInputStream(ByteArray(10))) { throw CancelledException() }
        try {
            stream.read(ByteArray(4), 0, 4)
            fail("the read should have been ended by the listener")
        } catch (expected: CancelledException) {
            // The four bytes did come off the wire, whatever happened to the read after them.
        }
        assertEquals(4L, stream.total)
    }

    @Test
    fun available_and_close_go_to_the_stream_underneath() {
        val delegate = object : InputStream() {
            var closed = false

            override fun read(): Int = -1

            override fun available(): Int = 7

            override fun close() {
                closed = true
            }
        }
        val stream = CountingStream(delegate) { }
        assertEquals(7, stream.available())
        stream.close()
        assertTrue(delegate.closed)
    }

    // ------------------------------------------------------------------------------ named threads

    @Test
    fun the_threads_are_numbered_from_one_and_never_keep_the_process_alive() {
        val factory = NamedThreads("probe-test")
        val first = factory.newThread(Runnable { })
        val second = factory.newThread(Runnable { })
        assertEquals("probe-test-1", first.name)
        assertEquals("probe-test-2", second.name)
        assertTrue(first.isDaemon && second.isDaemon)
    }

    // ----------------------------------------------------------------------------------- the trace

    @Test
    fun the_first_byte_time_is_from_the_request_sent_to_the_answer_beginning() {
        val trace = CallTrace()
        assertNull(trace.firstByteMs())
        trace.requestEndNs = 1_000_000L
        assertNull(trace.firstByteMs())
        trace.headersStartNs = 251_000_000L
        assertEquals(250L, trace.firstByteMs())
    }

    @Test
    fun an_answer_that_seems_to_come_before_the_request_is_not_a_time() {
        val trace = CallTrace()
        trace.requestEndNs = 5_000_000L
        trace.headersStartNs = 4_000_000L
        assertNull(trace.firstByteMs())
    }

    // ---------------------------------------------------------------------------------- the events

    private fun newCall(): Call = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:1/").build())

    private fun connection(protocol: Protocol): Connection = object : Connection {
        override fun route(): Route = throw UnsupportedOperationException()

        override fun socket(): Socket = throw UnsupportedOperationException()

        override fun handshake(): Handshake? = null

        override fun protocol(): Protocol = protocol
    }

    @Test
    fun each_call_has_its_own_trace_and_a_call_nobody_told_of_has_an_empty_one() {
        val events = ProbeEvents { 5_000_000L }
        val known = newCall()
        events.factory.create(known).requestHeadersEnd(known, known.request())
        assertEquals(5_000_000L, events.trace(known).requestEndNs)
        assertEquals(-1L, events.trace(newCall()).requestEndNs)
        assertEquals(-1, events.trace(newCall()).connection)
    }

    @Test
    fun the_first_byte_is_timed_by_the_clock_the_listener_was_given() {
        val now = AtomicLong(1_000_000L)
        val events = ProbeEvents { now.get() }
        val call = newCall()
        val listener = events.factory.create(call)
        listener.requestHeadersEnd(call, call.request())
        now.set(301_000_000L)
        listener.responseHeadersStart(call)
        assertEquals(300L, events.trace(call).firstByteMs())
    }

    @Test
    fun sockets_are_counted_every_time_and_remote_ends_once_each() {
        val events = ProbeEvents { 0L }
        val call = newCall()
        val listener = events.factory.create(call)
        listener.connectStart(call, InetSocketAddress("127.0.0.1", 1111), Proxy.NO_PROXY)
        listener.connectStart(call, InetSocketAddress("127.0.0.1", 1111), Proxy.NO_PROXY)
        listener.connectStart(call, InetSocketAddress("127.0.0.1", 2222), Proxy.NO_PROXY)
        assertEquals(3, events.connects.get())
        assertEquals(2, events.remoteEndpoints)
    }

    @Test
    fun connections_are_numbered_in_the_order_they_are_first_seen_and_carry_their_protocol() {
        val events = ProbeEvents { 0L }
        val plain = connection(Protocol.HTTP_1_1)
        val multiplexed = connection(Protocol.HTTP_2)
        val first = newCall()
        val second = newCall()
        val third = newCall()
        events.factory.create(first).connectionAcquired(first, plain)
        events.factory.create(second).connectionAcquired(second, multiplexed)
        events.factory.create(third).connectionAcquired(third, plain)
        assertEquals(1, events.trace(first).connection)
        assertEquals("http/1.1", events.trace(first).protocol)
        assertEquals(2, events.trace(second).connection)
        assertEquals("h2", events.trace(second).protocol)
        // The same connection, seen again, keeps its number.
        assertEquals(1, events.trace(third).connection)
    }
}
